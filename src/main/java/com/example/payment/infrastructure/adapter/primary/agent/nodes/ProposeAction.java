package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Cause;
import com.example.payment.infrastructure.adapter.primary.agent.Evidence;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.Map;

/**
 * Code picks the action, by the runbook's rule for the cause; the model only writes the message when it escalates.
 *
 * Until bonus 16 the model picked the action from the allowed set. The evals showed a small model naming the cause
 * right every time and picking the action right less than half the time, with the rule spelled out in its prompt.
 * Once the cause is known, the rule is four lines; a model could only copy them, less reliably. So the model is left
 * the part that needs words, and an injected reference can change at most the wording of an escalation, which the
 * approver reads and nothing sends.
 */
public class ProposeAction implements NodeAction<InvestigationState> {

	/** PAYMENT_SYSTEM_NOT_ANSWERING for this long: every scheduled run has asked already, so ask its owners. */
	static final Duration ASKED_ENOUGH = Duration.ofMinutes(30);

	// Long enough for an escalation e-mail's body, short enough that a runaway answer can't fill the view.
	static final int MAX_ESCALATION_LENGTH = 2000;

	private static final Logger log = LoggerFactory.getLogger(ProposeAction.class);

	private final ChatClient chatClient;

	public ProposeAction(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	/** The model's answer: the message only. */
	record Answer(String escalationText) {
	}

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		Evidence evidence = state.evidence();
		Action action = choose(state.diagnosis().orElseThrow().cause(), evidence);
		// The policy still has the last word: code that chose outside the set is a bug, and proposes nothing.
		if (!state.allowedActions().contains(action)) {
			return Map.of(InvestigationState.PROPOSAL,
					new ProposedAction(Action.NO_ACTION, null, "chose " + action + ", which is not allowed here"));
		}
		ProposedAction proposal = new ProposedAction(action, null, null);
		if (!proposal.escalates()) {
			return Map.of(InvestigationState.PROPOSAL, proposal);
		}
		return Map.of(InvestigationState.PROPOSAL, new ProposedAction(action, escalationText(state, action), null));
	}

	/** The runbook's rule (PaymentsStuckInProcessing, check 3), once the cause is known. */
	static Action choose(Cause cause, Evidence evidence) {
		return switch (cause) {
			case RECONCILIATION_NOT_RUNNING -> Action.ESCALATE_TO_ON_CALL;
			case PAYMENT_SYSTEM_NOT_ANSWERING -> evidence.age().compareTo(ASKED_ENOUGH) < 0
					? Action.RECONCILE_NOW : Action.ESCALATE_TO_PAYMENT_SYSTEM;
			case WITHIN_THRESHOLD, NOT_STUCK, UNCLEAR -> Action.NO_ACTION;
		};
	}

	// The model's text if it gives one, else a plain one from the facts: an escalation always says what it is about.
	private String escalationText(InvestigationState state, Action action) {
		Evidence evidence = state.evidence();
		String text = ask(state, action);
		if (text == null || text.isBlank()) {
			return "Payment %s has been %s for %d min. %s".formatted(evidence.paymentId(), evidence.status(),
					evidence.age().toMinutes(), action == Action.ESCALATE_TO_ON_CALL
							? "Reconciliation has not started a run for more than twice its schedule."
							: "Reconciliation keeps asking and the payment system has no answer for it.");
		}
		text = text.strip();
		return text.length() > MAX_ESCALATION_LENGTH ? text.substring(0, MAX_ESCALATION_LENGTH) : text;
	}

	private String ask(InvestigationState state, Action action) {
		String to = action == Action.ESCALATE_TO_ON_CALL
				? "our on-call engineers: reconciliation isn't running, so stuck payments aren't being settled"
				: "the payment system's owners: our reconciliation keeps asking about this payment and gets no answer";
		try {
			Answer answer = chatClient.prompt()
					.system(Prompts.SYSTEM)
					.user("""
							Write a short message to %s. The payment is %s. Cite its id and the facts that matter,
							with their values. Plain sentences; no greeting or signature. Answer as escalationText.

							""".formatted(to, state.evidence().paymentId()) + Prompts.dataBlock(state.evidence()))
					.call()
					.entity(Answer.class);
			return answer == null ? null : answer.escalationText();
		}
		catch (RuntimeException e) {
			log.warn("Escalation text for payment {} could not be read: {}", state.paymentId(), e.getClass().getSimpleName());
			return null;
		}
	}
}
