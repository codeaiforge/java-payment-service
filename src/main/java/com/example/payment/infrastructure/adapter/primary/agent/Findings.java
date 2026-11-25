package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * What an investigation found and proposed, as the operators saw it. Stored with the investigation (as JSON) when it
 * opens, so it stays readable after the run ends: the run's checkpoints exist to resume it, and are released then.
 */
public record Findings(List<Evidence.Item> evidence, Diagnosis diagnosis, List<Action> allowedActions,
		ProposedAction proposal) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	static Findings of(InvestigationState state) {
		return new Findings(state.evidence().items(), state.diagnosis().orElseThrow(), state.allowedActions(),
				state.proposal().orElseThrow());
	}

	String toJson() {
		return MAPPER.writeValueAsString(this);
	}

	static Findings fromJson(String json) {
		return MAPPER.readValue(json, Findings.class);
	}
}
