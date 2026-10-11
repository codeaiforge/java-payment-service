package com.example.payment.infrastructure.adapter.secondary.webhook;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;

/**
 * The body customers receive: a public contract, written for them, not the
 * Avro record on payment-events (docs/episodes/bonus-11). The two change for
 * different reasons and at different speeds. Internal consumers are our own
 * teams and move with the schema registry's rules; customers' code changes
 * when they choose, maybe never, and they can't read our registry. So this
 * contract only grows: a field is never renamed or removed, and the event
 * types are named here, so a new internal type is a decision to publish, not
 * a side effect.
 *
 * Every value is a string: amounts as written ("250.00"), so no client parses
 * them into a binary floating point number; times in ISO-8601 UTC.
 *
 * Ordering is not part of the contract: a retried delivery arrives after later
 * ones. A receiver that cares orders by occurredAt, and deduplicates by id,
 * which is also the Webhook-Id header.
 */
record WebhookPayload(String id, String type, String occurredAt, Data data) {

	/** refundId only on refund events; reference only if the payment has one. */
	record Data(String paymentId, String refundId, String sourceAccountId, String destinationAccountId, String amount,
			String currency, String reference) {
	}

	static WebhookPayload from(PaymentEvent event) {
		return new WebhookPayload(event.eventId().toString(), type(event.type()), event.occurredAt().toString(),
				new Data(event.paymentId().toString(), event.refundId() == null ? null : event.refundId().toString(),
						event.sourceAccountId().value(), event.destinationAccountId().value(),
						event.amount().amount().toPlainString(), event.amount().currency().name(),
						event.reference() == null ? null : event.reference().value()));
	}

	// No default: a new PaymentEventType doesn't compile until someone decides what customers call it.
	private static String type(PaymentEventType type) {
		return switch (type) {
			case PAYMENT_COMPLETED -> "payment.completed";
			case PAYMENT_FAILED -> "payment.failed";
			case PAYMENT_CANCELLED -> "payment.cancelled";
			case REFUND_COMPLETED -> "refund.completed";
			case REFUND_FAILED -> "refund.failed";
		};
	}
}
