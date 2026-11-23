package com.example.payment.application.usecase.command;

/**
 * An operator asking to settle one PROCESSING payment now, identified by its id as plain text.
 *
 * @param operatorId the authenticated operator, for the log: who forced this payment's outcome
 */
public record ReconcilePaymentCommand(String paymentId, String operatorId) {
}
