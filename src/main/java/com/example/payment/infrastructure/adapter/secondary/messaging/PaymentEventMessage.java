package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.domain.event.PaymentEvent;

import java.math.RoundingMode;

/**
 * A PaymentEvent on the wire: the Avro record generated from
 * src/main/avro/PaymentEvent.avsc (docs/episodes/bonus-10). The schema is the
 * contract with the consumers, checked in the registry and by the build; the
 * domain event can change without breaking it.
 */
final class PaymentEventMessage {

	/** Keyed by payment id: every event of one payment lands on the same partition, in order. */
	static final String TOPIC = "payment-events";

	private PaymentEventMessage() {
	}

	static com.example.payment.events.PaymentEvent from(PaymentEvent event) {
		return com.example.payment.events.PaymentEvent.newBuilder()
				.setEventId(event.eventId().toString())
				.setType(com.example.payment.events.PaymentEventType.valueOf(event.type().name()))
				.setPaymentId(event.paymentId().value().toString())
				.setRefundId(event.refundId() == null ? null : event.refundId().toString())
				.setSourceAccountId(event.sourceAccountId().value())
				.setDestinationAccountId(event.destinationAccountId().value())
				// The schema's scale is 2, and Avro writes a decimal only at exactly its scale. Money has at most 2 places.
				.setAmount(event.amount().amount().setScale(2, RoundingMode.UNNECESSARY))
				.setCurrency(event.amount().currency().name())
				.setOccurredAt(event.occurredAt())
				.setReference(event.reference() == null ? null : event.reference().value())
				.build();
	}
}
