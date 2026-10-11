package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.application.port.primary.ProjectPaymentEventUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds payment-events into the payment statement (docs/episodes/bonus-09). A
 * primary adapter, like PaymentProcessingConsumer: it maps the message and calls
 * a use case; what an event does to the statement is ProjectPaymentEventService's.
 *
 * Its own consumer group, so it reads every event whatever other groups do, and
 * resetting its offsets replays the topic into the statement alone (a rebuild).
 */
@Component
class StatementProjectionConsumer {

	static final String TOPIC = "payment-events";
	static final String GROUP = "payment-statement";

	private final ProjectPaymentEventUseCase projectPaymentEventUseCase;

	StatementProjectionConsumer(ProjectPaymentEventUseCase projectPaymentEventUseCase) {
		this.projectPaymentEventUseCase = projectPaymentEventUseCase;
	}

	// Anything thrown (the database down, a message it can't read) goes through the retry topics like a failed
	// payment-processing message, and after the last attempt is parked in payment-events-dlt. The statement then misses
	// that event until it is replayed. A retried event arrives after the payment's later ones; the projection allows it.
	@KafkaListener(topics = TOPIC, groupId = GROUP,
			properties = "spring.json.value.default.type=com.example.payment.infrastructure.adapter.primary.messaging.ReceivedPaymentEvent")
	void onMessage(ReceivedPaymentEvent message) {
		projectPaymentEventUseCase.project(message.toEvent());
	}
}
