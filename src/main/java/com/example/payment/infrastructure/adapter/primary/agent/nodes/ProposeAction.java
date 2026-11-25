package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The model picks one action from the allowed set, and writes the message if it escalates. Anything else it says,
 * an action outside the set, one that doesn't exist, or no readable answer, becomes NO_ACTION, and the view records
 * what it said instead. When NO_ACTION is the only choice the model isn't asked.
 */
public class ProposeAction implements NodeAction<InvestigationState> {

	// Long enough for an escalation e-mail's body, short enough that a runaway answer can't fill the view.
	static final int MAX_ESCALATION_LENGTH = 2000;

	private static final Logger log = LoggerFactory.getLogger(ProposeAction.class);

	private final ChatClient chatClient;

	public ProposeAction(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	/** The model's answer as text, so an action that doesn't exist can still be recorded as what it proposed. */
	record Answer(String action, String escalationText) {
	}

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		List<Action> allowed = state.allowedActions();
		if (allowed.equals(List.of(Action.NO_ACTION))) {
			return Map.of(InvestigationState.PROPOSAL, ProposedAction.noAction());
		}
		return Map.of(InvestigationState.PROPOSAL, check(ask(state, allowed), allowed));
	}

	static ProposedAction check(Answer answer, List<Action> allowed) {
		if (answer == null || answer.action() == null) {
			return new ProposedAction(Action.NO_ACTION, null, "no readable answer");
		}
		Action action = allowed.stream().filter(a -> a.name().equals(answer.action().strip())).findFirst().orElse(null);
		if (action == null) {
			return new ProposedAction(Action.NO_ACTION, null, "proposed " + answer.action() + ", which is not allowed here");
		}
		ProposedAction proposal = new ProposedAction(action, null, null);
		if (!proposal.escalates() || answer.escalationText() == null) {
			return proposal;
		}
		String text = answer.escalationText().strip();
		return new ProposedAction(action, text.length() > MAX_ESCALATION_LENGTH ? text.substring(0, MAX_ESCALATION_LENGTH) : text, null);
	}

	private Answer ask(InvestigationState state, List<Action> allowed) {
		// The cause only, an enum: the diagnosis's summary is the model's own text, and could carry what it read.
		Diagnosis diagnosis = state.diagnosis().orElseThrow();
		try {
			return chatClient.prompt()
					.system(Prompts.SYSTEM)
					.user("""
							The diagnosis is %s. Choose exactly one action from: %s.
							What each action does, and when the runbook uses it:
							- RECONCILE_NOW asks the payment system again now. For PAYMENT_SYSTEM_NOT_ANSWERING when the
							  payment has been in this status less than 30 minutes (E3).
							- ESCALATE_TO_PAYMENT_SYSTEM asks the payment system's owners about this payment by id. For
							  PAYMENT_SYSTEM_NOT_ANSWERING at 30 minutes or more: every scheduled run has asked already.
							- ESCALATE_TO_ON_CALL tells our on-call that reconciliation isn't running. For
							  RECONCILIATION_NOT_RUNNING.
							- NO_ACTION leaves it to the scheduled run.
							If you escalate, write escalationText: a short message for the people you escalate to,
							citing the payment id and the facts. Otherwise leave it null.

							""".formatted(diagnosis.cause(),
							allowed.stream().map(Action::name).collect(Collectors.joining(", ")))
							+ Prompts.dataBlock(state.evidence()))
					.call()
					.entity(Answer.class);
		}
		catch (RuntimeException e) {
			log.warn("Proposal for payment {} could not be read: {}", state.paymentId(), e.getClass().getSimpleName());
			return null;
		}
	}
}
