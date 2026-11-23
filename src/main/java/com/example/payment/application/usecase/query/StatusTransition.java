package com.example.payment.application.usecase.query;

import com.example.payment.domain.valueobject.PaymentStatus;

import java.time.Instant;

/** One status change of a payment, as the audit trail recorded it. */
public record StatusTransition(PaymentStatus from, PaymentStatus to, Instant at) {
}
