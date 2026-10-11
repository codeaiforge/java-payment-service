package com.example.payment.application.usecase.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A page of an account's statement, newest first. */
public record GetStatementResult(List<Line> lines, int page, int size, long totalLines) {

	/**
	 * @param reference the payment's reference; null for a payment whose events were published before events carried it
	 * @param refundedAmount what has been refunded so far, in the payment's currency; 0.00 if nothing
	 */
	public record Line(String paymentId, String counterpartyAccountId, BigDecimal amount, String currency,
			String reference, String status, BigDecimal refundedAmount, Instant settledAt) {
	}
}
