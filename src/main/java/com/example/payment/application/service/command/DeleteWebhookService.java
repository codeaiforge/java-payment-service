package com.example.payment.application.service.command;

import com.example.payment.application.exception.WebhookNotFoundException;
import com.example.payment.application.exception.WebhookValidationException;
import com.example.payment.application.port.primary.DeleteWebhookUseCase;
import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.application.usecase.command.DeleteWebhookCommand;
import com.example.payment.domain.valueobject.WebhookId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeleteWebhookService implements DeleteWebhookUseCase {

	private static final Logger log = LoggerFactory.getLogger(DeleteWebhookService.class);

	private final WebhookRepository webhookRepository;

	public DeleteWebhookService(WebhookRepository webhookRepository) {
		this.webhookRepository = webhookRepository;
	}

	@Override
	public void delete(DeleteWebhookCommand command) {
		WebhookId webhookId;
		try {
			webhookId = WebhookId.of(command.webhookId());
		}
		catch (IllegalArgumentException e) {
			throw new WebhookValidationException(e.getMessage(), e);
		}
		// Another customer's webhook is not found, as for payments: a 403 would confirm that the ID exists.
		webhookRepository.findSubscription(webhookId)
				.filter(subscription -> subscription.customerId().equals(command.customerId()))
				.orElseThrow(() -> new WebhookNotFoundException(webhookId));
		webhookRepository.delete(webhookId);
		log.info("Webhook {} deleted", webhookId);
	}
}
