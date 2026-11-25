package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;

import java.time.Instant;
import java.util.List;

/**
 * An investigation as an operator reads it: what was found, what it means, what to do, and what people decided. All
 * of it comes from the investigation table, never from the graph's checkpoints.
 *
 * @param status PROPOSED until a second operator approves or rejects it; then APPROVED, and EXECUTED or
 *        NO_LONGER_NEEDED once it ran, or REJECTED
 * @param result what running it found; null until it ran
 */
public record InvestigationView(String id, String paymentId, String startedBy, Instant startedAt,
		List<Evidence.Item> evidence, Diagnosis diagnosis, List<Action> allowedActions, ProposedAction proposal,
		String status, String decidedBy, Instant decidedAt, String result) {

	static InvestigationView of(Investigation record) {
		Findings findings = Findings.fromJson(record.findings());
		return new InvestigationView(record.id(), record.paymentId(), record.startedBy(), record.startedAt(),
				findings.evidence(), findings.diagnosis(), findings.allowedActions(), findings.proposal(),
				record.status().name(), record.decidedBy(), record.decidedAt(), record.result());
	}
}
