package com.example.payment.application.usecase.command;

/**
 * Intent to be told about one's payments at an endpoint.
 *
 * @param customerId the authenticated caller; the endpoint receives the events of payments from accounts they hold
 */
public record RegisterWebhookCommand(String customerId, String url) {
}
