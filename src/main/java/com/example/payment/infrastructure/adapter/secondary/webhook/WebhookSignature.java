package com.example.payment.infrastructure.adapter.secondary.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * The Webhook-Signature header: HMAC-SHA256 over "id.timestamp.body", keyed
 * with the subscription's secret, as "v1=" and lowercase hex.
 *
 * The receiver computes the same and compares, in constant time
 * (MessageDigest.isEqual): a comparison that stops at the first wrong byte
 * tells an attacker, by how long it took, how many bytes they got right. The
 * timestamp is signed, so the receiver can refuse an old one (more than a few
 * minutes) and a recorded request can't be replayed later; the ID is signed,
 * so it can't be swapped to slip past the receiver's deduplication. The "v1="
 * leaves room for another scheme, or for two signatures while a secret is
 * rotated.
 */
final class WebhookSignature {

	private WebhookSignature() {
	}

	static String sign(String secret, String id, String timestamp, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
			return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
		}
	}
}
