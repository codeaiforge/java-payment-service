package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.command.ReconcilePaymentResult;

/** @param answer what the payment system said: EXECUTED, REJECTED, NOT_RECEIVED or UNKNOWN */
public record ReconcileResponse(String paymentId, String answer, String status, boolean stored) {

	static ReconcileResponse from(ReconcilePaymentResult result) {
		return new ReconcileResponse(result.paymentId().toString(), result.answer().name(), result.status().name(),
				result.stored());
	}
}
