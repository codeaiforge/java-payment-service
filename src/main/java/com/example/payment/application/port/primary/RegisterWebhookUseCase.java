package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.RegisterWebhookCommand;
import com.example.payment.domain.entity.WebhookSubscription;

public interface RegisterWebhookUseCase {

	/** Registers the caller's endpoint and returns it with its new secret, the only time the secret is shown. */
	WebhookSubscription register(RegisterWebhookCommand command);
}
