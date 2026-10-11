package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.WebhookId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A WebhookRepository for service tests, behaving as the SQL one does: one delivery per event per subscription, leases. */
public class InMemoryWebhookRepository implements WebhookRepository {

	private final Map<WebhookId, WebhookSubscription> subscriptions = new LinkedHashMap<>();
	private final Map<UUID, Row> deliveries = new LinkedHashMap<>();

	/** A stored delivery and where it stands. */
	public static final class Row {
		public final UUID id;
		public final WebhookId subscriptionId;
		public final PaymentEvent event;
		public final Instant createdAt;
		public String status = "PENDING";
		public int attempts;
		public int lastResponseStatus;
		public Instant nextAttemptAt;

		Row(WebhookId subscriptionId, PaymentEvent event, Instant now) {
			this.id = UUID.randomUUID();
			this.subscriptionId = subscriptionId;
			this.event = event;
			this.createdAt = now;
			this.nextAttemptAt = now;
		}
	}

	public List<Row> deliveries() {
		return new ArrayList<>(deliveries.values());
	}

	@Override
	public void save(WebhookSubscription subscription) {
		subscriptions.put(subscription.id(), subscription);
	}

	@Override
	public Optional<WebhookSubscription> findSubscription(WebhookId id) {
		return Optional.ofNullable(subscriptions.get(id));
	}

	@Override
	public Optional<WebhookSubscription> findSubscriptionForUpdate(WebhookId id) {
		return findSubscription(id);
	}

	@Override
	public List<WebhookSubscription> findSubscriptions(String customerId) {
		return subscriptions.values().stream().filter(s -> s.customerId().equals(customerId)).toList();
	}

	@Override
	public void delete(WebhookId id) {
		subscriptions.remove(id);
		deliveries.values().removeIf(row -> row.subscriptionId.equals(id));
	}

	@Override
	public void enqueue(WebhookId subscriptionId, PaymentEvent event, Instant now) {
		boolean queued = deliveries.values().stream()
				.anyMatch(row -> row.subscriptionId.equals(subscriptionId) && row.event.eventId().equals(event.eventId()));
		if (!queued) {
			Row row = new Row(subscriptionId, event, now);
			deliveries.put(row.id, row);
		}
	}

	@Override
	public List<Delivery> claimDueDeliveries(Instant now, Instant leaseUntil, int max) {
		return deliveries.values().stream()
				.filter(row -> row.status.equals("PENDING") && !row.nextAttemptAt.isAfter(now))
				.sorted(Comparator.comparing(row -> row.nextAttemptAt))
				.limit(max)
				.map(row -> {
					row.nextAttemptAt = leaseUntil;
					return new Delivery(row.id, row.subscriptionId, row.event, row.attempts, row.createdAt);
				})
				.toList();
	}

	@Override
	public void markDelivered(UUID deliveryId, int attempts, int responseStatus) {
		finish(deliveryId, "DELIVERED", attempts, responseStatus);
	}

	@Override
	public void retryAt(UUID deliveryId, int attempts, int responseStatus, Instant nextAttemptAt) {
		Row row = deliveries.get(deliveryId);
		row.attempts = attempts;
		row.lastResponseStatus = responseStatus;
		row.nextAttemptAt = nextAttemptAt;
	}

	@Override
	public void markFailed(UUID deliveryId, int attempts, int responseStatus) {
		finish(deliveryId, "FAILED", attempts, responseStatus);
	}

	private void finish(UUID deliveryId, String status, int attempts, int responseStatus) {
		Row row = deliveries.get(deliveryId);
		row.status = status;
		row.attempts = attempts;
		row.lastResponseStatus = responseStatus;
	}
}
