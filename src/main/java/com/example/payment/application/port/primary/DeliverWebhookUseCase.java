package com.example.payment.application.port.primary;

import com.example.payment.domain.event.PaymentEvent;

/** Queues a payment event for each active endpoint of the payment's customer (docs/episodes/bonus-11). */
public interface DeliverWebhookUseCase {

	/** Queues each delivery once: an event already queued for an endpoint is not queued again. */
	void deliver(PaymentEvent event);
}
