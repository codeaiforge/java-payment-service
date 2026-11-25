package com.example.payment.infrastructure.adapter.primary.agent;

import java.util.List;

/**
 * Why a payment is where it is. Either code decided it from the facts (NOT_STUCK, WITHIN_THRESHOLD, and UNCLEAR when
 * the trail is missing), or the model did and Diagnose checked it against them.
 *
 * @param evidenceIds the Evidence items this rests on
 */
public record Diagnosis(Cause cause, List<String> evidenceIds, Confidence confidence, String summary) {

	public enum Cause {
		RECONCILIATION_NOT_RUNNING, PAYMENT_SYSTEM_NOT_ANSWERING, WITHIN_THRESHOLD, NOT_STUCK, UNCLEAR
	}

	public enum Confidence {
		LOW, MEDIUM, HIGH
	}
}
