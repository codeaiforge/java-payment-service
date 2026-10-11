package com.example.payment.infrastructure.adapter.secondary.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** A fixed vector, so a receiver in any language can check their code against ours. */
class WebhookSignatureTest {

	// python3 -c "import hmac,hashlib; print(hmac.new(b'whsec_test-secret',
	//     b'0b4c3d2e-1f00-4a5b-9c8d-7e6f5a4b3c2d.1760000000.{\"hello\":\"world\"}', hashlib.sha256).hexdigest())"
	@Test
	void signsIdTimestampAndBody() {
		String signature = WebhookSignature.sign("whsec_test-secret", "0b4c3d2e-1f00-4a5b-9c8d-7e6f5a4b3c2d", "1760000000",
				"{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

		assertThat(signature).isEqualTo("v1=6a4dabd6b80b345b42fbb801bbd121bfb5ce8296e704c769108601a5f7f81b37");
	}
}
