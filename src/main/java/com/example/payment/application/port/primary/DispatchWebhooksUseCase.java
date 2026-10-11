package com.example.payment.application.port.primary;

/** Sends the webhook deliveries that are due, and decides what each answer means (docs/episodes/bonus-11). */
public interface DispatchWebhooksUseCase {

	void dispatchDue();
}
