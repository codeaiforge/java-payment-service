package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.DeleteWebhookUseCase;
import com.example.payment.application.port.primary.GetWebhooksUseCase;
import com.example.payment.application.port.primary.RegisterWebhookUseCase;
import com.example.payment.application.usecase.command.DeleteWebhookCommand;
import com.example.payment.application.usecase.command.RegisterWebhookCommand;
import com.example.payment.application.usecase.query.GetWebhooksQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * The caller's webhook endpoints (docs/episodes/bonus-11). Like every endpoint,
 * it needs a token, and the caller's customer ID comes from it: a customer only
 * ever sees and deletes their own.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

	private final RegisterWebhookUseCase registerWebhookUseCase;
	private final GetWebhooksUseCase getWebhooksUseCase;
	private final DeleteWebhookUseCase deleteWebhookUseCase;

	public WebhookController(RegisterWebhookUseCase registerWebhookUseCase, GetWebhooksUseCase getWebhooksUseCase,
			DeleteWebhookUseCase deleteWebhookUseCase) {
		this.registerWebhookUseCase = registerWebhookUseCase;
		this.getWebhooksUseCase = getWebhooksUseCase;
		this.deleteWebhookUseCase = deleteWebhookUseCase;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WebhookResponse register(@Valid @RequestBody WebhookRequest request, Principal caller) {
		return WebhookResponse.registered(
				registerWebhookUseCase.register(new RegisterWebhookCommand(caller.getName(), request.url())));
	}

	@GetMapping
	public List<WebhookResponse> list(Principal caller) {
		return getWebhooksUseCase.getWebhooks(new GetWebhooksQuery(caller.getName())).stream()
				.map(WebhookResponse::from).toList();
	}

	@DeleteMapping("/{webhookId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String webhookId, Principal caller) {
		deleteWebhookUseCase.delete(new DeleteWebhookCommand(webhookId, caller.getName()));
	}
}
