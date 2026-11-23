package com.example.payment.application.usecase.command;

import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;

/**
 * What reconciling one payment found and did.
 *
 * @param answer what the payment system said
 * @param status the payment's stored status afterwards, re-read when another run stored first
 * @param stored whether this call changed the payment
 */
public record ReconcilePaymentResult(PaymentId paymentId, Answer answer, PaymentStatus status, boolean stored) {

	/** The payment system's answer, as the application reports it: callers never see the outbound port's type. */
	public enum Answer {
		EXECUTED, REJECTED, NOT_RECEIVED, UNKNOWN
	}
}
