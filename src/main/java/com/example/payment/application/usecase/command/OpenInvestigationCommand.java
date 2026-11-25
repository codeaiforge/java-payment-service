package com.example.payment.application.usecase.command;

/**
 * @param escalationText the drafted message, for an escalation; null otherwise
 * @param findings what the agent found and proposed, as JSON; kept as the record of what the operators saw
 */
public record OpenInvestigationCommand(String investigationId, String paymentId, String startedBy,
		String proposedAction, String escalationText, String findings) {
}
