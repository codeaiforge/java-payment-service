package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.query.ReconciliationStatus;

import java.time.Instant;

/** See ReconciliationStatus: runningOn and runningUntil are null when no instance holds the lock. */
public record ReconciliationStatusResponse(Instant lastRunStartedAt, String runningOn, Instant runningUntil) {

	static ReconciliationStatusResponse from(ReconciliationStatus status) {
		return new ReconciliationStatusResponse(status.lastRunStartedAt(), status.runningOn(), status.runningUntil());
	}
}
