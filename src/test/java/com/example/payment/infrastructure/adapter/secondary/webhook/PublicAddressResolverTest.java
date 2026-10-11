package com.example.payment.infrastructure.adapter.secondary.webhook;

import com.example.payment.application.exception.WebhookValidationException;
import com.example.payment.application.service.command.InMemoryWebhookRepository;
import com.example.payment.application.service.command.RegisterWebhookService;
import com.example.payment.application.usecase.command.RegisterWebhookCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF (docs/episodes/bonus-11): a customer's URL never reaches our own network.
 * No DNS server is asked: addresses are literals, and the two hostnames are
 * answered by the test's own lookup.
 */
class PublicAddressResolverTest {

	private final PublicAddressResolver resolver = new PublicAddressResolver(PublicAddressResolverTest::lookup, List.of());

	private final InMemoryWebhookRepository repository = new InMemoryWebhookRepository();
	private final RegisterWebhookService registration = new RegisterWebhookService(repository,
			new HttpWebhookSender(resolver, defaultContext()));

	// intranet.example.com is a name the customer controls that points inside; mixed.example.com points both ways.
	private static InetAddress[] lookup(String host) throws UnknownHostException {
		return switch (host) {
			case "intranet.example.com" -> new InetAddress[] {InetAddress.ofLiteral("10.1.2.3")};
			case "merchant.example.com" -> new InetAddress[] {InetAddress.ofLiteral("93.184.215.14")};
			case "mixed.example.com" -> new InetAddress[] {InetAddress.ofLiteral("93.184.215.14"), InetAddress.ofLiteral("192.168.0.7")};
			default -> InetAddress.getAllByName(host);
		};
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://127.0.0.1/", "https://10.0.0.1/", "https://169.254.169.254/",
			"https://intranet.example.com/hooks", "https://mixed.example.com/hooks"})
	void registeringAnEndpointInsideOurNetworkIsRefused(String url) {
		assertThatThrownBy(() -> registration.register(new RegisterWebhookCommand("CUST-1001", url)))
				.isInstanceOf(WebhookValidationException.class)
				.hasMessage("Webhook URL's host must resolve, and only to public addresses");
		assertThat(repository.findSubscriptions("CUST-1001")).isEmpty();
	}

	@Test
	void aPublicEndpointIsRegistered() {
		registration.register(new RegisterWebhookCommand("CUST-1001", "https://merchant.example.com/hooks"));

		assertThat(repository.findSubscriptions("CUST-1001")).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0.0.0.0", "127.0.0.1", "127.8.9.10", "10.0.0.1", "172.16.5.4", "172.31.255.255",
			"192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "192.0.0.170", "198.18.0.1",
			"224.0.0.1", "240.0.0.1", "255.255.255.255",
			"::", "::1", "fe80::1", "fc00::1", "fd00:ec2::254", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
			"::127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1", "ff02::1"})
	void refusesEveryAddressThatIsNotPublic(String address) {
		assertThatThrownBy(() -> resolver.resolve(address)).isInstanceOf(UnknownHostException.class)
				.hasMessageNotContaining(address);
	}

	@ParameterizedTest
	@ValueSource(strings = {"93.184.215.14", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1",
			"2606:2800:21f:cb07:6820:80da:af6b:8b2c"})
	void resolvesAPublicAddress(String address) throws UnknownHostException {
		assertThat(resolver.resolve(address)).containsExactly(InetAddress.ofLiteral(address));
	}

	// For the tests' WireMock merchant only: the address named is let through, nothing else.
	@Test
	void anAllowedPrivateAddressIsLetThroughAndNoOther() throws UnknownHostException {
		PublicAddressResolver allowing = new PublicAddressResolver(PublicAddressResolverTest::lookup, List.of("127.0.0.1"));

		assertThat(allowing.resolve("127.0.0.1")).containsExactly(InetAddress.ofLiteral("127.0.0.1"));
		assertThatThrownBy(() -> allowing.resolve("127.0.0.2")).isInstanceOf(UnknownHostException.class);
		assertThatThrownBy(() -> allowing.resolve("10.0.0.1")).isInstanceOf(UnknownHostException.class);
	}

	private static SSLContext defaultContext() {
		try {
			return SSLContext.getDefault();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
