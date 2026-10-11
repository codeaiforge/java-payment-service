package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.WebhookId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook subscriptions, and the deliveries waiting for them
 * (docs/episodes/bonus-11). A delivery is durable work: it is stored when the
 * event arrives and stays until it is delivered or given up, whichever
 * instance sends it and however often instances restart.
 */
public interface WebhookRepository {

	void save(WebhookSubscription subscription);

	Optional<WebhookSubscription> findSubscription(WebhookId id);

	/** As findSubscription, and locks it until the caller's transaction ends: for a change that reads its count first. */
	Optional<WebhookSubscription> findSubscriptionForUpdate(WebhookId id);

	List<WebhookSubscription> findSubscriptions(String customerId);

	/** Deletes the subscription and its deliveries. */
	void delete(WebhookId id);

	/** Queues the event for the subscription, due now. Does nothing if it is already queued for it. */
	void enqueue(WebhookId subscriptionId, PaymentEvent event, Instant now);

	/**
	 * Up to {@code max} deliveries due at {@code now}, oldest due first. Each is leased until {@code leaseUntil}: no
	 * other caller gets it before then, and if nothing records its result by then it is due again.
	 */
	List<Delivery> claimDueDeliveries(Instant now, Instant leaseUntil, int max);

	/** @param responseStatus the endpoint's HTTP status, 0 for no answer */
	void markDelivered(UUID deliveryId, int attempts, int responseStatus);

	void retryAt(UUID deliveryId, int attempts, int responseStatus, Instant nextAttemptAt);

	void markFailed(UUID deliveryId, int attempts, int responseStatus);

	/**
	 * An event on its way to one subscription.
	 *
	 * @param attempts how many times it was sent so far
	 * @param createdAt when it was queued; it is given up a while after this
	 */
	record Delivery(UUID id, WebhookId subscriptionId, PaymentEvent event, int attempts, Instant createdAt) {
	}
}
