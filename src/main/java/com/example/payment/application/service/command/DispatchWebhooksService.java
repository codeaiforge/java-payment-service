package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.DispatchWebhooksUseCase;
import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.application.port.secondary.WebhookRepository.Delivery;
import com.example.payment.application.port.secondary.WebhookSender;
import com.example.payment.application.port.secondary.WebhookSender.Attempt;
import com.example.payment.domain.entity.WebhookSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Sends due webhook deliveries and decides what each answer means
 * (docs/episodes/bonus-11).
 *
 * A 2xx is delivered. Anything else is tried again later, waiting twice as long
 * each time from {@link #FIRST_RETRY} up to {@link #LONGEST_WAIT}, each wait
 * shortened by a random part of its half (jitter), so endpoints that failed
 * together don't all come back at the same moment. A delivery still failing
 * {@link #MAX_AGE} after it was queued is given up: FAILED, and one more failure
 * on the subscription's count, which may disable it.
 *
 * Deliveries are claimed with a lease, not held in a transaction: sending can
 * take seconds per endpoint, and a transaction open that long holds a database
 * connection for nothing. A claimed delivery is invisible to every other
 * instance until its lease ends; an instance that dies mid-send leaves it to be
 * claimed again then. The endpoint may get it twice, which receivers must
 * expect anyway (Webhook-Id).
 */
@Service
public class DispatchWebhooksService implements DispatchWebhooksUseCase {

	public static final Duration FIRST_RETRY = Duration.ofSeconds(30);
	public static final Duration LONGEST_WAIT = Duration.ofHours(1);
	public static final Duration MAX_AGE = Duration.ofHours(24);

	private static final Logger log = LoggerFactory.getLogger(DispatchWebhooksService.class);
	private static final int BATCH_SIZE = 20;
	// Longer than a batch can take: 20 sends, each within the sender's deadlines (3s to connect, 10s to answer).
	private static final Duration LEASE = Duration.ofMinutes(5);

	private final WebhookRepository webhookRepository;
	private final WebhookSender webhookSender;
	private final TransactionOperations transactions;
	private final Clock clock;

	public DispatchWebhooksService(WebhookRepository webhookRepository, WebhookSender webhookSender,
			TransactionOperations transactions, Clock clock) {
		this.webhookRepository = webhookRepository;
		this.webhookSender = webhookSender;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Override
	public void dispatchDue() {
		Instant now = clock.instant();
		for (Delivery delivery : webhookRepository.claimDueDeliveries(now, now.plus(LEASE), BATCH_SIZE)) {
			// One delivery's trouble (the database blinking) doesn't stop the others; its lease ends and it is due again.
			try {
				dispatch(delivery);
			}
			catch (RuntimeException e) {
				log.warn("Webhook delivery {} not attempted: {}", delivery.id(), e.getClass().getSimpleName());
			}
		}
	}

	private void dispatch(Delivery delivery) {
		Optional<WebhookSubscription> subscription = webhookRepository.findSubscription(delivery.subscriptionId())
				.filter(WebhookSubscription::isActive);
		// Disabled since it was queued: nothing more is sent to it. Not counted, it has already stopped.
		if (subscription.isEmpty()) {
			webhookRepository.markFailed(delivery.id(), delivery.attempts(), 0);
			return;
		}
		Attempt attempt = webhookSender.send(subscription.get(), delivery.event(), clock.instant());
		int attempts = delivery.attempts() + 1;
		Instant now = clock.instant();
		if (attempt.delivered()) {
			transactions.executeWithoutResult(tx -> {
				webhookRepository.markDelivered(delivery.id(), attempts, attempt.status());
				updateSubscription(delivery, WebhookSubscription::recordDelivered);
			});
		}
		else if (now.isBefore(delivery.createdAt().plus(MAX_AGE))) {
			Instant next = now.plus(waitAfter(attempts));
			webhookRepository.retryAt(delivery.id(), attempts, attempt.status(), next);
			log.warn("Webhook delivery {} attempt {} failed (status {}), next attempt at {}", delivery.id(), attempts,
					attempt.status(), next);
		}
		else {
			transactions.executeWithoutResult(tx -> {
				webhookRepository.markFailed(delivery.id(), attempts, attempt.status());
				updateSubscription(delivery, WebhookSubscription::recordFailedDelivery);
			});
			log.warn("Webhook delivery {} given up after {} attempts", delivery.id(), attempts);
		}
	}

	// Locked while it changes: deliveries to one endpoint can finish on two instances at once, and each counts.
	private void updateSubscription(Delivery delivery, Consumer<WebhookSubscription> change) {
		webhookRepository.findSubscriptionForUpdate(delivery.subscriptionId()).ifPresent(subscription -> {
			boolean wasActive = subscription.isActive();
			change.accept(subscription);
			webhookRepository.save(subscription);
			if (wasActive && !subscription.isActive()) {
				log.warn("Webhook {} disabled after {} failed deliveries in a row", subscription.id(),
						subscription.consecutiveFailures());
			}
		});
	}

	/** How long to wait after this many failed attempts: doubling from FIRST_RETRY, at most LONGEST_WAIT, minus jitter. */
	static Duration waitAfter(int attempts) {
		long seconds = Math.min(FIRST_RETRY.toSeconds() << Math.min(attempts - 1, 20), LONGEST_WAIT.toSeconds());
		return Duration.ofSeconds(seconds - ThreadLocalRandom.current().nextLong(seconds / 2 + 1));
	}
}
