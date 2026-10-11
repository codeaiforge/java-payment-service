package com.example.payment.infrastructure.adapter.primary.scheduling;

import com.example.payment.application.port.primary.DispatchWebhooksUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A clock as a caller, like ReconciliationScheduler. No lock: every instance
 * dispatches, and each delivery is claimed by one of them
 * (DispatchWebhooksService), so more instances send more at once.
 */
@Component
class WebhookDispatchScheduler {

	private final DispatchWebhooksUseCase dispatchWebhooksUseCase;

	WebhookDispatchScheduler(DispatchWebhooksUseCase dispatchWebhooksUseCase) {
		this.dispatchWebhooksUseCase = dispatchWebhooksUseCase;
	}

	@Scheduled(fixedDelayString = "${payment.webhooks.dispatch-interval}")
	void run() {
		dispatchWebhooksUseCase.dispatchDue();
	}
}
