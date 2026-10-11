package com.example.payment.infrastructure.adapter.secondary.webhook;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only DNS lookup a webhook makes, and the check that keeps a customer's
 * URL out of our own network (SSRF, docs/episodes/bonus-11). A customer could
 * register https://169.254.169.254/ (the cloud's metadata service, which hands
 * out credentials), https://10.0.0.5/ or a name of their own that resolves to
 * either; the service would then call it from inside, with our network's trust.
 *
 * A host is refused when any of its addresses is not public: loopback,
 * private, link-local (which holds the metadata service), carrier-grade NAT,
 * IPv6 unique local, multicast, reserved, and IPv6 forms that carry an IPv4
 * address. Refused before any connection is opened.
 *
 * It is the HTTP client's resolver, not a check before the call: the client
 * connects to exactly the addresses checked here. A separate check would look
 * the name up, then the client would look it up again, and a DNS server
 * answering a public address first and 127.0.0.1 second (DNS rebinding) would
 * pass the check and still reach us.
 */
@Component
public class PublicAddressResolver implements DnsResolver {

	private final Lookup lookup;
	private final Set<InetAddress> allowedPrivateAddresses;

	@Autowired
	PublicAddressResolver(@Value("${payment.webhooks.allowed-private-addresses}") List<String> allowedPrivateAddresses) {
		this(InetAddress::getAllByName, allowedPrivateAddresses);
	}

	PublicAddressResolver(Lookup lookup, List<String> allowedPrivateAddresses) {
		this.lookup = lookup;
		this.allowedPrivateAddresses = new HashSet<>();
		for (String address : allowedPrivateAddresses) {
			this.allowedPrivateAddresses.add(InetAddress.ofLiteral(address));
		}
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] addresses = lookup.lookup(host);
		for (InetAddress address : addresses) {
			if (!isPublic(address) && !allowedPrivateAddresses.contains(address)) {
				throw new RefusedAddressException();
			}
		}
		return addresses;
	}

	// Only asked for Kerberos, which webhooks don't use. Never a reverse lookup: another answer nobody checked.
	@Override
	public String resolveCanonicalHostname(String host) {
		return host;
	}

	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		int[] b = unsigned(address.getAddress());
		if (address instanceof Inet4Address) {
			return !(b[0] == 0                                  // 0.0.0.0/8, "this network"
					|| b[0] == 100 && (b[1] & 0xc0) == 64        // 100.64.0.0/10, carrier-grade NAT
					|| b[0] == 192 && b[1] == 0 && b[2] == 0     // 192.0.0.0/24, IETF protocol assignments
					|| b[0] == 198 && (b[1] & 0xfe) == 18        // 198.18.0.0/15, benchmarking
					|| b[0] >= 240);                             // 240.0.0.0/4 reserved, and the broadcast address
		}
		// Java already turns ::ffff:a.b.c.d into an Inet4Address. These carry one too, which the network may route.
		boolean ipv4Compatible = true;                       // ::a.b.c.d (deprecated)
		for (int i = 0; i < 12; i++) {
			ipv4Compatible &= b[i] == 0;
		}
		return !((b[0] & 0xfe) == 0xfc                       // fc00::/7, unique local (isSiteLocal only knows fec0::/10)
				|| b[0] == 0x20 && b[1] == 0x02               // 2002::/16, 6to4
				|| b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b   // 64:ff9b::/96, NAT64
				|| ipv4Compatible);
	}

	private static int[] unsigned(byte[] bytes) {
		int[] result = new int[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			result[i] = bytes[i] & 0xff;
		}
		return result;
	}

	interface Lookup {
		InetAddress[] lookup(String host) throws UnknownHostException;
	}

	/** An UnknownHostException, so the client stops as for a name that doesn't resolve. Names no host or address. */
	static class RefusedAddressException extends UnknownHostException {

		RefusedAddressException() {
			super("Host resolves to an address webhooks may not call");
		}
	}
}
