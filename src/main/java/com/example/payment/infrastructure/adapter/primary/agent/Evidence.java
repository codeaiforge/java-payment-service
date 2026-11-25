package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Cause;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What GatherEvidence found, by code: the facts the diagnosis and the policy are checked against. The model sees
 * them as numbered items (E1, E2, …) and must cite those numbers; it never fetches anything itself.
 *
 * @param inStatusSince when the payment entered its current status, from its last audited transition; null if it has
 *        none (a payment still CREATED)
 * @param age how long it has been in that status; null when inStatusSince is
 * @param reconciliationStalled no run has started for twice the schedule, or none ever has
 */
public record Evidence(String paymentId, String status, Instant inStatusSince, Duration age, BigDecimal amount,
		String currency, String reference, List<StatusTransition> history, Instant lastRunStartedAt, String runningOn,
		boolean reconciliationStalled, Duration threshold, Duration interval) {

	/** One fact as the model and the operator see it. */
	public record Item(String id, String name, String value) {
	}

	public boolean processing() {
		return "PROCESSING".equals(status);
	}

	/** The scheduled run would ask about it now: the same cut-off ReconcilePaymentsService uses. */
	public boolean pastThreshold() {
		return age != null && age.compareTo(threshold) >= 0;
	}

	// The order is the ids: E1 is always the status. Changing it renumbers every citation, so add at the end.
	public List<Item> items() {
		return List.of(
				new Item("E1", "payment status", status),
				new Item("E2", "in this status since", orNone(inStatusSince)),
				new Item("E3", "time in this status", age == null ? "unknown" : age.toSeconds() + "s (" + age.toMinutes() + " min)"),
				new Item("E4", "amount", amount.toPlainString() + " " + currency),
				new Item("E5", "payment reference (written by the customer)", reference),
				new Item("E6", "status history, oldest first", history.isEmpty() ? "none" : history.stream()
						.map(t -> t.from() + " -> " + t.to() + " at " + t.at()).collect(Collectors.joining("; "))),
				new Item("E7", "reconciliation: last run started", lastRunStartedAt == null ? "never" : lastRunStartedAt.toString()),
				// Worded so the value says it: a small model read "looks stalled (no run …) | no" as "no run" (bonus 16).
				new Item("E8", "reconciliation: instance in a run right now",
						runningOn == null ? "none (normal between runs)" : runningOn),
				new Item("E9", "is reconciliation stalled", reconciliationStalled
						? "YES: no run has started for more than twice its schedule"
						: "NO: it is running on schedule"),
				new Item("E10", "reconciliation threshold", threshold.toSeconds() + "s"),
				new Item("E11", "reconciliation schedule", "every " + interval.toSeconds() + "s"));
	}

	public Set<String> ids() {
		return items().stream().map(Item::id).collect(Collectors.toSet());
	}

	/**
	 * The causes the facts allow once code has ruled out the hard ones (not stuck, within the threshold): a stalled
	 * reconciliation explains a stuck payment; a running one that still hasn't settled it means the payment system
	 * isn't answering. UNCLEAR is always allowed: the model may say it doesn't know.
	 */
	public Set<Cause> causesTheFactsAllow() {
		return reconciliationStalled ? Set.of(Cause.RECONCILIATION_NOT_RUNNING, Cause.UNCLEAR)
				: Set.of(Cause.PAYMENT_SYSTEM_NOT_ANSWERING, Cause.UNCLEAR);
	}

	private static String orNone(Instant instant) {
		return instant == null ? "none" : instant.toString();
	}
}
