package com.example.payment.application.port.primary;

import com.example.payment.domain.event.PaymentEvent;

/** Applies a payment event to the payment statement (docs/episodes/bonus-09). */
public interface ProjectPaymentEventUseCase {

	/** Applies the event once: an event already applied changes nothing. */
	void project(PaymentEvent event);
}
