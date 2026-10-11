package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.WebhookId;
import com.example.payment.domain.valueobject.WebhookStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements WebhookRepository in plain SQL rather than JPA: the delivery queue
 * is made of statements JPA has no words for (ON CONFLICT DO NOTHING, an UPDATE
 * that claims with SKIP LOCKED and returns what it claimed), and the
 * subscriptions are too small to need an entity of their own.
 */
@Component
public class WebhookPersistenceAdapter implements WebhookRepository {

	private static final String SUBSCRIPTION_COLUMNS = "id, customer_id, url, secret, status, consecutive_failures";

	private final JdbcClient jdbc;

	WebhookPersistenceAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void save(WebhookSubscription subscription) {
		jdbc.sql("""
				INSERT INTO webhook_subscription (id, customer_id, url, secret, status, consecutive_failures, created_at)
				VALUES (:id, :customerId, :url, :secret, :status, :failures, :now)
				ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, consecutive_failures = EXCLUDED.consecutive_failures
				""")
				.param("id", subscription.id().value())
				.param("customerId", subscription.customerId())
				.param("url", subscription.url().toString())
				.param("secret", subscription.secret())
				.param("status", subscription.status().name())
				.param("failures", subscription.consecutiveFailures())
				.param("now", Timestamp.from(Instant.now()))
				.update();
	}

	@Override
	public Optional<WebhookSubscription> findSubscription(WebhookId id) {
		return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + " FROM webhook_subscription WHERE id = ?")
				.param(id.value()).query(WebhookPersistenceAdapter::subscription).optional();
	}

	// MANDATORY: outside a transaction the lock would be released as soon as the query returns.
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<WebhookSubscription> findSubscriptionForUpdate(WebhookId id) {
		return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + " FROM webhook_subscription WHERE id = ? FOR UPDATE")
				.param(id.value()).query(WebhookPersistenceAdapter::subscription).optional();
	}

	@Override
	public List<WebhookSubscription> findSubscriptions(String customerId) {
		return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + " FROM webhook_subscription WHERE customer_id = ? ORDER BY created_at")
				.param(customerId).query(WebhookPersistenceAdapter::subscription).list();
	}

	@Override
	public void delete(WebhookId id) {
		jdbc.sql("DELETE FROM webhook_subscription WHERE id = ?").param(id.value()).update();
	}

	@Override
	public void enqueue(WebhookId subscriptionId, PaymentEvent event, Instant now) {
		jdbc.sql("""
				INSERT INTO webhook_delivery (id, subscription_id, event_id, event_type, payment_id, refund_id,
				    source_account_id, destination_account_id, amount, currency, reference, occurred_at,
				    status, attempts, next_attempt_at, created_at)
				VALUES (:id, :subscriptionId, :eventId, :type, :paymentId, :refundId, :source, :destination, :amount,
				    :currency, :reference, :occurredAt, 'PENDING', 0, :now, :now)
				ON CONFLICT (subscription_id, event_id) DO NOTHING
				""")
				.param("id", UUID.randomUUID())
				.param("subscriptionId", subscriptionId.value())
				.param("eventId", event.eventId())
				.param("type", event.type().name())
				.param("paymentId", event.paymentId().value())
				.param("refundId", event.refundId() == null ? null : event.refundId().value())
				.param("source", event.sourceAccountId().value())
				.param("destination", event.destinationAccountId().value())
				.param("amount", event.amount().amount())
				.param("currency", event.amount().currency().name())
				.param("reference", event.reference() == null ? null : event.reference().value())
				.param("occurredAt", Timestamp.from(event.occurredAt()))
				.param("now", Timestamp.from(now))
				.update();
	}

	// One statement, so no transaction to hold open: the rows are locked only while it runs, SKIP LOCKED passes over
	// rows another instance's statement is claiming, and the new next_attempt_at keeps them out of every later claim
	// until the lease ends (docs/episodes/bonus-07 for SKIP LOCKED).
	@Override
	public List<Delivery> claimDueDeliveries(Instant now, Instant leaseUntil, int max) {
		return jdbc.sql("""
				UPDATE webhook_delivery SET next_attempt_at = :leaseUntil
				WHERE id IN (SELECT id FROM webhook_delivery
				             WHERE status = 'PENDING' AND next_attempt_at <= :now
				             ORDER BY next_attempt_at
				             LIMIT :max
				             FOR UPDATE SKIP LOCKED)
				RETURNING *
				""")
				.param("now", Timestamp.from(now))
				.param("leaseUntil", Timestamp.from(leaseUntil))
				.param("max", max)
				.query(WebhookPersistenceAdapter::delivery).list();
	}

	@Override
	public void markDelivered(UUID deliveryId, int attempts, int responseStatus) {
		finish(deliveryId, "DELIVERED", attempts, responseStatus);
	}

	@Override
	public void retryAt(UUID deliveryId, int attempts, int responseStatus, Instant nextAttemptAt) {
		jdbc.sql("""
				UPDATE webhook_delivery SET attempts = ?, last_response_status = ?, next_attempt_at = ? WHERE id = ?
				""")
				.params(attempts, responseStatus, Timestamp.from(nextAttemptAt), deliveryId).update();
	}

	@Override
	public void markFailed(UUID deliveryId, int attempts, int responseStatus) {
		finish(deliveryId, "FAILED", attempts, responseStatus);
	}

	private void finish(UUID deliveryId, String status, int attempts, int responseStatus) {
		jdbc.sql("UPDATE webhook_delivery SET status = ?, attempts = ?, last_response_status = ? WHERE id = ?")
				.params(status, attempts, responseStatus, deliveryId).update();
	}

	private static WebhookSubscription subscription(ResultSet row, int rowNumber) throws SQLException {
		return WebhookSubscription.restore(new WebhookId(row.getObject("id", UUID.class)), row.getString("customer_id"),
				URI.create(row.getString("url")), row.getString("secret"), WebhookStatus.valueOf(row.getString("status")),
				row.getInt("consecutive_failures"));
	}

	private static Delivery delivery(ResultSet row, int rowNumber) throws SQLException {
		UUID refundId = row.getObject("refund_id", UUID.class);
		String reference = row.getString("reference");
		PaymentEvent event = new PaymentEvent(row.getObject("event_id", UUID.class),
				PaymentEventType.valueOf(row.getString("event_type")), new PaymentId(row.getObject("payment_id", UUID.class)),
				refundId == null ? null : new RefundId(refundId), new AccountId(row.getString("source_account_id")),
				new AccountId(row.getString("destination_account_id")),
				new Money(row.getBigDecimal("amount"), Currency.of(row.getString("currency"))),
				reference == null ? null : new PaymentReference(reference), row.getTimestamp("occurred_at").toInstant());
		return new Delivery(row.getObject("id", UUID.class), new WebhookId(row.getObject("subscription_id", UUID.class)),
				event, row.getInt("attempts"), row.getTimestamp("created_at").toInstant());
	}
}
