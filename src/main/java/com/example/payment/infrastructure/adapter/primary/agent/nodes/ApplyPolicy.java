package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.infrastructure.adapter.primary.agent.Evidence;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Code only: which actions this payment allows, from the facts and not from the diagnosis. The model picks from this
 * set and nothing else, so no answer it gives can reach an action the runbook doesn't offer for this state.
 */
public class ApplyPolicy implements NodeAction<InvestigationState> {

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		return Map.of(InvestigationState.ALLOWED_ACTIONS, allowed(state.evidence()));
	}

	static List<Action> allowed(Evidence evidence) {
		// Not stuck, too young for the scheduled run, or of unknown age: there is nothing to do yet.
		if (!evidence.processing() || !evidence.pastThreshold()) {
			return List.of(Action.NO_ACTION);
		}
		EnumSet<Action> allowed = EnumSet.of(Action.RECONCILE_NOW, Action.ESCALATE_TO_PAYMENT_SYSTEM, Action.NO_ACTION);
		if (evidence.reconciliationStalled()) {
			allowed.add(Action.ESCALATE_TO_ON_CALL);
		}
		return List.copyOf(allowed);
	}
}
