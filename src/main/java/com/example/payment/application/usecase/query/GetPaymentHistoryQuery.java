package com.example.payment.application.usecase.query;

/** @param customerId the authenticated caller; only the holder of the payment's source account may read its history */
public record GetPaymentHistoryQuery(String paymentId, String customerId) {
}
