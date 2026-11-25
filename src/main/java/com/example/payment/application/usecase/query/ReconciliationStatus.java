package com.example.payment.application.usecase.query;

import java.time.Instant;

/**
 * Whether reconciliation runs, as its scheduler lock records it.
 *
 * @param lastRunStartedAt when an instance last took the lock to run; null if it never has. It moves when a run
 *        starts, not when one succeeds: a run that fails on every payment still moves it.
 * @param runningOn the instance holding the lock, null when none is. Held includes the short hold after a quick run
 *        (lock-at-least-for), so non-null means "running or just ran", not strictly "running".
 * @param runningUntil when that hold ends, or when a crashed holder's lock expires; null when none is held
 */
public record ReconciliationStatus(Instant lastRunStartedAt, String runningOn, Instant runningUntil) {

	public static final ReconciliationStatus NEVER_RUN = new ReconciliationStatus(null, null, null);
}
