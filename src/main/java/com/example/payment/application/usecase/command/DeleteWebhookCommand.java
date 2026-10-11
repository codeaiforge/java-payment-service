package com.example.payment.application.usecase.command;

/** @param customerId the authenticated caller; only the customer who registered the endpoint may delete it */
public record DeleteWebhookCommand(String webhookId, String customerId) {
}
