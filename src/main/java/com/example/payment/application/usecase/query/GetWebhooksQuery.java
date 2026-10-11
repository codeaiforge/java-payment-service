package com.example.payment.application.usecase.query;

/** @param customerId the authenticated caller, whose endpoints are listed */
public record GetWebhooksQuery(String customerId) {
}
