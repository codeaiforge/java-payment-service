package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;

import java.time.Instant;
import java.util.List;

/**
 * An investigation as an operator reads it: what was found, what it means, and what to do. Nothing in it has been
 * done.
 *
 * @param error why the investigation stopped before a proposal, such as the step limit; null when it finished. The
 *        other fields hold what it had found by then.
 */
public record InvestigationView(String id, String paymentId, String startedBy, Instant startedAt,
		List<Evidence.Item> evidence, Diagnosis diagnosis, List<Action> allowedActions, ProposedAction proposal,
		String error) {

	static InvestigationView of(String id, Instant startedAt, InvestigationState state) {
		return new InvestigationView(id, state.paymentId(), state.startedBy(), startedAt, state.evidence().items(),
				state.diagnosis().orElseThrow(), state.allowedActions(), state.proposal().orElseThrow(), null);
	}

	static InvestigationView failed(String id, String paymentId, String startedBy, Instant startedAt, String error) {
		return new InvestigationView(id, paymentId, startedBy, startedAt, List.of(), null, List.of(), null, error);
	}
}
