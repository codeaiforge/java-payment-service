package com.example.payment.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

public record WebhookId(UUID value) {

	public WebhookId {
		Objects.requireNonNull(value, "Webhook id is required");
	}

	public static WebhookId of(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Webhook id is required");
		}
		try {
			return new WebhookId(UUID.fromString(value.strip()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid webhook id: " + value);
		}
	}

	public static WebhookId newId() {
		return new WebhookId(UUID.randomUUID());
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
