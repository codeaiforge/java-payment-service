package com.example.payment.infrastructure.adapter.primary.agent;

/**
 * One action from the set ApplyPolicy allowed. Nothing runs it in bonus 14; bonus 15 runs it after a second operator
 * approves.
 *
 * @param escalationText the message to send, for an escalation; null otherwise
 * @param replaced what the model proposed, when it wasn't allowed or couldn't be read and NO_ACTION was put in its
 *        place; null when the proposal stands
 */
public record ProposedAction(Action action, String escalationText, String replaced) {

	// SHORTCUT: adding an action here is a decision for the people who own the runbook, not a code change.
	public enum Action {
		RECONCILE_NOW, ESCALATE_TO_PAYMENT_SYSTEM, ESCALATE_TO_ON_CALL, NO_ACTION
	}

	public static ProposedAction noAction() {
		return new ProposedAction(Action.NO_ACTION, null, null);
	}

	public boolean escalates() {
		return action == Action.ESCALATE_TO_PAYMENT_SYSTEM || action == Action.ESCALATE_TO_ON_CALL;
	}
}
