package com.example.payment.application.service.command;

import com.example.payment.application.exception.WebhookValidationException;
import com.example.payment.application.port.primary.RegisterWebhookUseCase;
import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.application.port.secondary.WebhookSender;
import com.example.payment.application.usecase.command.RegisterWebhookCommand;
import com.example.payment.domain.entity.WebhookSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Registers a customer's endpoint (docs/episodes/bonus-11). The domain refuses
 * a URL that isn't https; the sender refuses a host we may not call, such as
 * one inside our own network. Only the webhook ID is logged: never the URL,
 * the secret or the customer.
 */
@Service
public class RegisterWebhookService implements RegisterWebhookUseCase {

	private static final Logger log = LoggerFactory.getLogger(RegisterWebhookService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final WebhookRepository webhookRepository;
	private final WebhookSender webhookSender;

	public RegisterWebhookService(WebhookRepository webhookRepository, WebhookSender webhookSender) {
		this.webhookRepository = webhookRepository;
		this.webhookSender = webhookSender;
	}

	@Override
	public WebhookSubscription register(RegisterWebhookCommand command) {
		WebhookSubscription subscription;
		try {
			subscription = WebhookSubscription.register(command.customerId(), new URI(command.url().strip()), newSecret());
		}
		catch (URISyntaxException | IllegalArgumentException e) {
			throw new WebhookValidationException(e instanceof URISyntaxException ? "Webhook URL is not a URL" : e.getMessage(), e);
		}
		// Checked again on every send: DNS can answer differently later (docs/episodes/bonus-11, DNS rebinding).
		if (!webhookSender.isPublic(subscription.url())) {
			throw new WebhookValidationException("Webhook URL's host must resolve, and only to public addresses");
		}
		webhookRepository.save(subscription);
		log.info("Webhook {} registered", subscription.id());
		return subscription;
	}

	// 256 random bits: guessing it is out of reach, so a valid signature proves the sender knew it.
	private static String newSecret() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
