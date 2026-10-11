package com.example.payment.domain.event;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.RefundId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Payment X completed": a business fact, told to whoever listens. It says
 * nothing about how it travels (Kafka, JSON); that is the messaging adapter's job.
 *
 * {@code eventId} is unique per fact. Delivery is at least once, so a consumer
 * that sees the same eventId twice ignores the second.
 *
 * A refund event names the refund too ({@code refundId}, null for a payment's
 * own events) and carries the refund's amount, not the payment's.
 *
 * Every event carries the payment's {@code reference}, so a consumer can show
 * it (docs/episodes/bonus-10). It is free text the payer wrote: fine in an
 * event, which exists to tell consumers what they need, but still never in a
 * log line, metric or span. Null on an event published before events had it.
 */
public record PaymentEvent(UUID eventId, PaymentEventType type, PaymentId paymentId, RefundId refundId,
		AccountId sourceAccountId, AccountId destinationAccountId, Money amount, PaymentReference reference,
		Instant occurredAt) {

	public PaymentEvent {
		Objects.requireNonNull(eventId, "Event id is required");
		Objects.requireNonNull(type, "Event type is required");
		Objects.requireNonNull(paymentId, "Payment id is required");
		Objects.requireNonNull(sourceAccountId, "Source account is required");
		Objects.requireNonNull(destinationAccountId, "Destination account is required");
		Objects.requireNonNull(amount, "Amount is required");
		Objects.requireNonNull(occurredAt, "Time of the event is required");
	}

	/** The event for a payment that has just reached a final status. The caller says when: the domain reads no clock. */
	public static PaymentEvent finalStatusReached(Payment payment, Instant occurredAt) {
		PaymentEventType type = switch (payment.status()) {
			case COMPLETED -> PaymentEventType.PAYMENT_COMPLETED;
			case FAILED -> PaymentEventType.PAYMENT_FAILED;
			case CANCELLED -> PaymentEventType.PAYMENT_CANCELLED;
			case CREATED, AUTHORIZED, PROCESSING -> throw new IllegalStateException(
					"Payment " + payment.id() + " is " + payment.status() + ", not final: no event");
		};
		return new PaymentEvent(UUID.randomUUID(), type, payment.id(), null, payment.sourceAccountId(),
				payment.destinationAccountId(), payment.amount(), payment.reference(), occurredAt);
	}

	/** The event for a refund of this payment that has just reached a final status. */
	public static PaymentEvent refundFinished(Payment payment, Refund refund, Instant occurredAt) {
		if (!refund.paymentId().equals(payment.id())) {
			throw new IllegalArgumentException("Refund " + refund.id() + " is not a refund of payment " + payment.id());
		}
		PaymentEventType type = switch (refund.status()) {
			case COMPLETED -> PaymentEventType.REFUND_COMPLETED;
			case FAILED -> PaymentEventType.REFUND_FAILED;
			case PROCESSING -> throw new IllegalStateException(
					"Refund " + refund.id() + " is PROCESSING, not final: no event");
		};
		return new PaymentEvent(UUID.randomUUID(), type, payment.id(), refund.id(), payment.sourceAccountId(),
				payment.destinationAccountId(), refund.amount(), payment.reference(), occurredAt);
	}
}
