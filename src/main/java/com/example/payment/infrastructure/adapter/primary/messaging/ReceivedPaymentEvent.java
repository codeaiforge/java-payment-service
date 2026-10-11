package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A message on payment-events, as the statement reads it (docs/episodes/bonus-09).
 * This service publishes the topic too, but reads it like any other consumer:
 * through its own copy of the JSON contract, not the publisher's class.
 */
public record ReceivedPaymentEvent(String eventId, String type, String paymentId, String refundId, String sourceAccountId,
		String destinationAccountId, BigDecimal amount, String currency, String occurredAt) {

	/** Through the domain's constructors: a message missing a field, or with one it can't read, fails here. */
	PaymentEvent toEvent() {
		return new PaymentEvent(UUID.fromString(eventId), PaymentEventType.valueOf(type), PaymentId.of(paymentId),
				refundId == null ? null : new RefundId(UUID.fromString(refundId)), new AccountId(sourceAccountId),
				new AccountId(destinationAccountId), new Money(amount, Currency.of(currency)), Instant.parse(occurredAt));
	}
}
