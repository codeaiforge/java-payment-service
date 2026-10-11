package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.DeleteWebhookCommand;

public interface DeleteWebhookUseCase {

	/** Deletes the caller's endpoint and every delivery still waiting for it. */
	void delete(DeleteWebhookCommand command);
}
