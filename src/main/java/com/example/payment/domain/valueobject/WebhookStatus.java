package com.example.payment.domain.valueobject;

/**
 * Whether a webhook subscription is still sent events. Decided by
 * WebhookSubscription: ACTIVE -> DISABLED, never back.
 */
public enum WebhookStatus {
	ACTIVE,
	DISABLED
}
