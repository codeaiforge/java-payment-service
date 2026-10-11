package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetWebhooksQuery;
import com.example.payment.domain.entity.WebhookSubscription;

import java.util.List;

public interface GetWebhooksUseCase {

	/** The caller's endpoints, active and disabled. */
	List<WebhookSubscription> getWebhooks(GetWebhooksQuery query);
}
