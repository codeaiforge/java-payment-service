package com.example.payment.application.usecase.command;

/** @param operatorId the authenticated operator approving or rejecting */
public record DecideInvestigationCommand(String investigationId, String operatorId) {
}
