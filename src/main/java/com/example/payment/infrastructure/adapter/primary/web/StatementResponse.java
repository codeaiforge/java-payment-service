package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.query.GetStatementResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record StatementResponse(List<Line> lines, int page, int size, long totalLines) {

	public record Line(String paymentId, String counterpartyAccountId, BigDecimal amount, String currency,
			String reference, String status, BigDecimal refundedAmount, Instant settledAt) {
	}

	static StatementResponse from(GetStatementResult result) {
		return new StatementResponse(result.lines().stream()
				.map(line -> new Line(line.paymentId(), line.counterpartyAccountId(), line.amount(), line.currency(),
						line.reference(), line.status(), line.refundedAmount(), line.settledAt()))
				.toList(), result.page(), result.size(), result.totalLines());
	}
}
