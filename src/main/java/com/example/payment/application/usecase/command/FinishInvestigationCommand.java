package com.example.payment.application.usecase.command;

import com.example.payment.application.usecase.query.Investigation;

/** @param outcome EXECUTED or NO_LONGER_NEEDED */
public record FinishInvestigationCommand(String investigationId, Investigation.Status outcome, String result) {
}
