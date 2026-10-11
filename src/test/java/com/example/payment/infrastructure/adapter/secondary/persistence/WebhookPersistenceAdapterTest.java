package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.port.secondary.WebhookRepository.Delivery;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.WebhookStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL with the Flyway schema: the queue's SQL, which no fake can check. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, WebhookPersistenceAdapter.class})
class WebhookPersistenceAdapterTest {

	private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

	@Autowired
	WebhookPersistenceAdapter webhooks;

	@Autowired
	JdbcClient jdbc;

	@Test
	void aSubscriptionComesBackAsItWasSavedAndItsChangesAreStored() {
		WebhookSubscription subscription = subscribe("CUST-P1");
		subscription.recordFailedDelivery();
		webhooks.save(subscription);

		WebhookSubscription found = webhooks.findSubscription(subscription.id()).orElseThrow();
		assertThat(found.customerId()).isEqualTo("CUST-P1");
		assertThat(found.url()).isEqualTo(URI.create("https://merchant.example.com/hooks?shop=1"));
		assertThat(found.secret()).isEqualTo("whsec_test-secret");
		assertThat(found.status()).isEqualTo(WebhookStatus.ACTIVE);
		assertThat(found.consecutiveFailures()).isEqualTo(1);
		assertThat(webhooks.findSubscriptions("CUST-P1")).extracting(WebhookSubscription::id).containsExactly(subscription.id());
		assertThat(webhooks.findSubscriptions("CUST-P2")).isEmpty();
	}

	@Test
	void anEventIsQueuedOncePerSubscriptionAndComesBackWhole() {
		WebhookSubscription subscription = subscribe("CUST-P3");
		PaymentEvent event = refundCompleted();

		webhooks.enqueue(subscription.id(), event, NOW);
		webhooks.enqueue(subscription.id(), event, NOW);

		Delivery delivery = webhooks.claimDueDeliveries(NOW, NOW.plusSeconds(300), 100).stream()
				.filter(d -> d.subscriptionId().equals(subscription.id())).findFirst().orElseThrow();
		assertThat(delivery.event()).isEqualTo(event);
		assertThat(delivery.attempts()).isZero();
		assertThat(delivery.createdAt()).isEqualTo(NOW);
		assertThat(count(subscription)).isEqualTo(1);
	}

	@Test
	void aClaimedDeliveryIsLeasedUntilItsLeaseEnds() {
		WebhookSubscription subscription = subscribe("CUST-P4");
		webhooks.enqueue(subscription.id(), refundCompleted(), NOW);
		Instant leaseUntil = NOW.plus(Duration.ofMinutes(5));

		assertThat(claimed(subscription, NOW, leaseUntil)).isTrue();
		assertThat(claimed(subscription, NOW.plusSeconds(1), leaseUntil.plusSeconds(1))).isFalse();
		assertThat(claimed(subscription, leaseUntil, leaseUntil.plusSeconds(300))).isTrue();
	}

	@Test
	void aRetryIsDueAtItsTimeAndAFinishedDeliveryNever() {
		WebhookSubscription subscription = subscribe("CUST-P5");
		webhooks.enqueue(subscription.id(), refundCompleted(), NOW);
		Delivery delivery = webhooks.claimDueDeliveries(NOW, NOW.plusSeconds(300), 100).stream()
				.filter(d -> d.subscriptionId().equals(subscription.id())).findFirst().orElseThrow();

		webhooks.retryAt(delivery.id(), 1, 500, NOW.plusSeconds(30));
		assertThat(claimed(subscription, NOW.plusSeconds(29), NOW.plusSeconds(400))).isFalse();
		assertThat(claimed(subscription, NOW.plusSeconds(30), NOW.plusSeconds(400))).isTrue();

		webhooks.markDelivered(delivery.id(), 2, 200);
		assertThat(claimed(subscription, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2)))).isFalse();
		assertThat(jdbc.sql("SELECT status || ' ' || attempts || ' ' || last_response_status FROM webhook_delivery WHERE id = ?")
				.param(delivery.id()).query(String.class).single()).isEqualTo("DELIVERED 2 200");
	}

	@Test
	void deletingASubscriptionDeletesItsDeliveries() {
		WebhookSubscription subscription = subscribe("CUST-P6");
		webhooks.enqueue(subscription.id(), refundCompleted(), NOW);

		webhooks.delete(subscription.id());

		assertThat(webhooks.findSubscription(subscription.id())).isEmpty();
		assertThat(count(subscription)).isZero();
	}

	private WebhookSubscription subscribe(String customerId) {
		WebhookSubscription subscription = WebhookSubscription.register(customerId,
				URI.create("https://merchant.example.com/hooks?shop=1"), "whsec_test-secret");
		webhooks.save(subscription);
		return subscription;
	}

	private boolean claimed(WebhookSubscription subscription, Instant now, Instant leaseUntil) {
		return webhooks.claimDueDeliveries(now, leaseUntil, 100).stream()
				.anyMatch(d -> d.subscriptionId().equals(subscription.id()));
	}

	private int count(WebhookSubscription subscription) {
		return jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?")
				.param(subscription.id().value()).query(Integer.class).single();
	}

	// Every field filled, the reference left out: an event from before events had one.
	private static PaymentEvent refundCompleted() {
		return new PaymentEvent(UUID.randomUUID(), PaymentEventType.REFUND_COMPLETED, PaymentId.newId(), RefundId.newId(),
				new AccountId("ACC-1"), new AccountId("ACC-2"), new Money(new BigDecimal("40.00"), Currency.EUR), null,
				Instant.parse("2026-10-11T09:30:00.123456Z"));
	}
}
