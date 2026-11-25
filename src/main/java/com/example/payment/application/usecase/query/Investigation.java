package com.example.payment.application.usecase.query;

import java.time.Instant;

/**
 * What the operations agent proposed for one payment, and what people decided. The application doesn't interpret
 * the proposed action: it records it, lets a second operator approve or reject it, and keeps the result. Running
 * it is the agent adapter's job, through primary ports.
 *
 * @param findings what the agent found and proposed, as the agent adapter wrote it (JSON); kept as is
 * @param decidedBy the operator who approved or rejected it; null while PROPOSED
 * @param result what running it found, such as the payment system's answer; null until finished
 */
public record Investigation(String id, String paymentId, String startedBy, Instant startedAt, String proposedAction,
		String escalationText, String findings, Status status, String decidedBy, Instant decidedAt, String result, Instant finishedAt) {

	public enum Status {
		PROPOSED, APPROVED, EXECUTED, NO_LONGER_NEEDED, REJECTED
	}
}
