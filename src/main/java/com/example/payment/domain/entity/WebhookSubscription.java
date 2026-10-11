package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.WebhookId;
import com.example.payment.domain.valueobject.WebhookStatus;

import java.net.URI;
import java.util.Objects;

/**
 * A customer's endpoint that is told about their payments (docs/episodes/bonus-11):
 * every event of a payment from an account they hold is POSTed to it, signed
 * with its secret.
 *
 * An endpoint that keeps failing is switched off: after
 * {@link #MAX_CONSECUTIVE_FAILURES} deliveries in a row were given up, it is
 * DISABLED and gets nothing more. One delivery that arrives resets the count.
 * ACTIVE -> DISABLED, never back: the customer registers the endpoint again.
 *
 * Whether the URL's host is one we may call at all (not our own network) only
 * DNS can tell, so that is checked outside the domain, at registration and on
 * every send.
 */
public class WebhookSubscription {

	public static final int MAX_CONSECUTIVE_FAILURES = 5;

	private final WebhookId id;
	private final String customerId;
	private final URI url;
	private final String secret;
	private WebhookStatus status;
	private int consecutiveFailures;

	private WebhookSubscription(WebhookId id, String customerId, URI url, String secret, WebhookStatus status,
			int consecutiveFailures) {
		this.id = Objects.requireNonNull(id, "Webhook id is required");
		this.customerId = Objects.requireNonNull(customerId, "Customer id is required");
		this.url = Objects.requireNonNull(url, "URL is required");
		this.secret = Objects.requireNonNull(secret, "Secret is required");
		this.status = Objects.requireNonNull(status, "Webhook status is required");
		this.consecutiveFailures = consecutiveFailures;

		if (customerId.isBlank() || secret.isBlank()) {
			throw new IllegalArgumentException("Customer id and secret must not be blank");
		}
		// Plain http would send the payment and its signature readable to anyone on the way.
		if (!"https".equalsIgnoreCase(url.getScheme())) {
			throw new IllegalArgumentException("Webhook URL must be https");
		}
		if (url.getHost() == null) {
			throw new IllegalArgumentException("Webhook URL must name a host");
		}
		// A password in the URL would be stored and shown back on every list.
		if (url.getUserInfo() != null) {
			throw new IllegalArgumentException("Webhook URL must not contain a user or password");
		}
		if (consecutiveFailures < 0) {
			throw new IllegalArgumentException("Consecutive failures must not be negative");
		}
	}

	public static WebhookSubscription register(String customerId, URI url, String secret) {
		return new WebhookSubscription(WebhookId.newId(), customerId, url, secret, WebhookStatus.ACTIVE, 0);
	}

	public static WebhookSubscription restore(WebhookId id, String customerId, URI url, String secret,
			WebhookStatus status, int consecutiveFailures) {
		return new WebhookSubscription(id, customerId, url, secret, status, consecutiveFailures);
	}

	public void recordDelivered() {
		consecutiveFailures = 0;
	}

	/** A delivery was given up. The {@value #MAX_CONSECUTIVE_FAILURES}th in a row disables the endpoint. */
	public void recordFailedDelivery() {
		if (status == WebhookStatus.DISABLED) {
			return;
		}
		consecutiveFailures++;
		if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
			status = WebhookStatus.DISABLED;
		}
	}

	public boolean isActive() {
		return status == WebhookStatus.ACTIVE;
	}

	public WebhookId id() {
		return id;
	}

	public String customerId() {
		return customerId;
	}

	public URI url() {
		return url;
	}

	public String secret() {
		return secret;
	}

	public WebhookStatus status() {
		return status;
	}

	public int consecutiveFailures() {
		return consecutiveFailures;
	}

	// Only the ID: the secret, the URL and the customer must never reach a log line by way of a toString.
	@Override
	public String toString() {
		return "WebhookSubscription[" + id + "]";
	}
}
