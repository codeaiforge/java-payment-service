package com.example.payment.application.service.query;

import com.example.payment.application.port.primary.GetWebhooksUseCase;
import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.application.usecase.query.GetWebhooksQuery;
import com.example.payment.domain.entity.WebhookSubscription;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetWebhooksService implements GetWebhooksUseCase {

	private final WebhookRepository webhookRepository;

	public GetWebhooksService(WebhookRepository webhookRepository) {
		this.webhookRepository = webhookRepository;
	}

	@Override
	public List<WebhookSubscription> getWebhooks(GetWebhooksQuery query) {
		return webhookRepository.findSubscriptions(query.customerId());
	}
}
