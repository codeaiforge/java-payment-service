package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the graph carries from node to node. Each node returns only the keys it sets, and a later value replaces an
 * earlier one; the accessors keep the keys and casts in one place.
 */
public class InvestigationState extends AgentState {

	public static final String PAYMENT_ID = "paymentId";
	public static final String STARTED_BY = "startedBy";
	public static final String EVIDENCE = "evidence";
	public static final String DIAGNOSIS = "diagnosis";
	public static final String DIAGNOSE_ATTEMPTS = "diagnoseAttempts";
	public static final String ALLOWED_ACTIONS = "allowedActions";
	public static final String PROPOSAL = "proposal";

	public InvestigationState(Map<String, Object> data) {
		super(data);
	}

	public String paymentId() {
		return this.<String>value(PAYMENT_ID).orElseThrow();
	}

	/** The operator who started it: the reads are logged under this id. */
	public String startedBy() {
		return this.<String>value(STARTED_BY).orElseThrow();
	}

	public Evidence evidence() {
		return this.<Evidence>value(EVIDENCE).orElseThrow();
	}

	public Optional<Diagnosis> diagnosis() {
		return value(DIAGNOSIS);
	}

	public int diagnoseAttempts() {
		return this.<Integer>value(DIAGNOSE_ATTEMPTS).orElse(0);
	}

	public List<Action> allowedActions() {
		return this.<List<Action>>value(ALLOWED_ACTIONS).orElseThrow();
	}

	public Optional<ProposedAction> proposal() {
		return value(PROPOSAL);
	}
}
