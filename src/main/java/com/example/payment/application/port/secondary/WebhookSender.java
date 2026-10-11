package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;

import java.net.URI;
import java.time.Instant;

/**
 * Calls customers' endpoints (docs/episodes/bonus-11). The URL comes from the
 * customer, so the adapter treats it as untrusted: it calls only hosts that
 * resolve to public addresses, never our own network.
 */
public interface WebhookSender {

	/** Whether the URL's host resolves, and only to public addresses. Asked at registration; every send checks again. */
	boolean isPublic(URI url);

	/**
	 * POSTs the event to the subscription's URL, signed with its secret and stamped {@code now}. The endpoint failing
	 * is an answer, not an exception: an error status, no answer and a refused address all come back as not delivered.
	 */
	Attempt send(WebhookSubscription subscription, PaymentEvent event, Instant now);

	/** @param status the endpoint's HTTP status, 0 if there was no answer */
	record Attempt(boolean delivered, int status) {
	}
}
