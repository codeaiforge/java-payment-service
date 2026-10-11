package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Whose endpoints hear of a payment: the payer's active ones, nobody else's. */
class DeliverWebhookServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

	// ACC-1 is CUST-1's, ACC-2 is CUST-2's. ACC-9 doesn't exist.
	private final Map<String, String> holders = Map.of("ACC-1", "CUST-1", "ACC-2", "CUST-2");
	private final InMemoryWebhookRepository repository = new InMemoryWebhookRepository();
	private final DeliverWebhookService service = new DeliverWebhookService(
			accountId -> Optional.ofNullable(holders.get(accountId.value())).map(holder -> new Account(AccountStatus.ACTIVE, holder)),
			repository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void queuesTheEventForEachActiveEndpointOfThePayer() {
		WebhookSubscription first = subscribe("CUST-1");
		WebhookSubscription second = subscribe("CUST-1");
		subscribe("CUST-2");
		WebhookSubscription disabled = subscribe("CUST-1");
		for (int i = 0; i < WebhookSubscription.MAX_CONSECUTIVE_FAILURES; i++) {
			disabled.recordFailedDelivery();
		}

		service.deliver(completed("ACC-1", "ACC-2"));

		assertThat(repository.deliveries()).extracting(row -> row.subscriptionId)
				.containsExactlyInAnyOrder(first.id(), second.id());
		assertThat(repository.deliveries()).allSatisfy(row -> assertThat(row.nextAttemptAt).isEqualTo(NOW));
	}

	// The payee is told nothing: the event is about the payer's payment, and webhooks are the payer's.
	@Test
	void thePayeesEndpointsAreNotTold() {
		subscribe("CUST-2");

		service.deliver(completed("ACC-1", "ACC-2"));

		assertThat(repository.deliveries()).isEmpty();
	}

	@Test
	void anEventFromAnUnknownAccountGoesNowhere() {
		subscribe("CUST-1");

		service.deliver(completed("ACC-9", "ACC-2"));

		assertThat(repository.deliveries()).isEmpty();
	}

	@Test
	void theSameEventTwiceIsQueuedOnce() {
		subscribe("CUST-1");
		PaymentEvent event = completed("ACC-1", "ACC-2");

		service.deliver(event);
		service.deliver(event);

		assertThat(repository.deliveries()).hasSize(1);
	}

	private WebhookSubscription subscribe(String customerId) {
		WebhookSubscription subscription = WebhookSubscription.register(customerId,
				URI.create("https://merchant.example.com/hooks"), "whsec_test-secret");
		repository.save(subscription);
		return subscription;
	}

	private static PaymentEvent completed(String source, String destination) {
		Payment payment = Payment.create(new AccountId(source), new AccountId(destination),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
		payment.authorize();
		payment.startProcessing();
		payment.complete();
		return PaymentEvent.finalStatusReached(payment, NOW);
	}
}
