package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.domain.entity.WebhookSubscription;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A registered endpoint. The secret is only in the answer to its registration:
 * the customer stores it then, and no later request can read it back.
 */
public record WebhookResponse(String webhookId, String url, String status,
		@JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

	static WebhookResponse registered(WebhookSubscription subscription) {
		return new WebhookResponse(subscription.id().toString(), subscription.url().toString(),
				subscription.status().name(), subscription.secret());
	}

	static WebhookResponse from(WebhookSubscription subscription) {
		return new WebhookResponse(subscription.id().toString(), subscription.url().toString(),
				subscription.status().name(), null);
	}
}
