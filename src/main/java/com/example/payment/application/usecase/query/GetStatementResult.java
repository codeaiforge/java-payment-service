package com.example.payment.application.usecase.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A page of an account's statement, newest first. No reference: payment events
 * don't carry one, and the statement is built from nothing else.
 */
public record GetStatementResult(List<Line> lines, int page, int size, long totalLines) {

	/** @param refundedAmount what has been refunded so far, in the payment's currency; 0.00 if nothing */
	public record Line(String paymentId, String counterpartyAccountId, BigDecimal amount, String currency, String status,
			BigDecimal refundedAmount, Instant settledAt) {
	}
}
