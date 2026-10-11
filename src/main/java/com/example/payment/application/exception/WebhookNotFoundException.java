package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.WebhookId;

/** No endpoint has the requested id, or not one of the caller's. */
public class WebhookNotFoundException extends RuntimeException {

	public WebhookNotFoundException(WebhookId webhookId) {
		super("Webhook " + webhookId + " does not exist");
	}
}
