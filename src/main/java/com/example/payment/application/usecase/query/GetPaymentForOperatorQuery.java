package com.example.payment.application.usecase.query;

/** @param operatorId the authenticated operator, for the log: who looked at this payment */
public record GetPaymentForOperatorQuery(String paymentId, String operatorId) {
}
