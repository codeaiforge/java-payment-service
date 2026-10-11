package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.WebhookStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSubscriptionTest {

	@Test
	void anHttpsUrlIsRegisteredActive() {
		WebhookSubscription subscription = register("https://merchant.example.com/hooks");

		assertThat(subscription.status()).isEqualTo(WebhookStatus.ACTIVE);
		assertThat(subscription.consecutiveFailures()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://merchant.example.com/hooks", "ftp://merchant.example.com/", "merchant.example.com/hooks"})
	void onlyHttpsIsAccepted(String url) {
		assertThatThrownBy(() -> register(url)).isInstanceOf(IllegalArgumentException.class).hasMessage("Webhook URL must be https");
	}

	@Test
	void aUrlWithAUserOrPasswordIsRefused() {
		assertThatThrownBy(() -> register("https://user:password@merchant.example.com/hooks"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void isDisabledAfterFiveFailedDeliveriesInARow() {
		WebhookSubscription subscription = register("https://merchant.example.com/hooks");

		failTimes(subscription, WebhookSubscription.MAX_CONSECUTIVE_FAILURES - 1);
		assertThat(subscription.isActive()).isTrue();
		subscription.recordFailedDelivery();

		assertThat(subscription.status()).isEqualTo(WebhookStatus.DISABLED);
	}

	@Test
	void aDeliveryResetsTheCount() {
		WebhookSubscription subscription = register("https://merchant.example.com/hooks");

		failTimes(subscription, WebhookSubscription.MAX_CONSECUTIVE_FAILURES - 1);
		subscription.recordDelivered();
		failTimes(subscription, WebhookSubscription.MAX_CONSECUTIVE_FAILURES - 1);

		assertThat(subscription.isActive()).isTrue();
		assertThat(subscription.consecutiveFailures()).isEqualTo(WebhookSubscription.MAX_CONSECUTIVE_FAILURES - 1);
	}

	@Test
	void toStringShowsNeitherTheSecretNorTheUrlNorTheCustomer() {
		assertThat(register("https://merchant.example.com/hooks/abc").toString())
				.doesNotContain("whsec_", "merchant.example.com", "CUST-1001");
	}

	private static WebhookSubscription register(String url) {
		return WebhookSubscription.register("CUST-1001", URI.create(url), "whsec_test-secret");
	}

	private static void failTimes(WebhookSubscription subscription, int times) {
		for (int i = 0; i < times; i++) {
			subscription.recordFailedDelivery();
		}
	}
}
