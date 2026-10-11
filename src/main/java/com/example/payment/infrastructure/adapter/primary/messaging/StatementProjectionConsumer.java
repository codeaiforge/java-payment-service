package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.application.port.primary.ProjectPaymentEventUseCase;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.RefundId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Feeds payment-events into the payment statement (docs/episodes/bonus-09). A
 * primary adapter, like PaymentProcessingConsumer: it maps the message and calls
 * a use case; what an event does to the statement is ProjectPaymentEventService's.
 *
 * Its own consumer group, so it reads every event whatever other groups do, and
 * resetting its offsets replays the topic into the statement alone (a rebuild).
 *
 * Messages are Avro (docs/episodes/bonus-10). Each names the schema it was
 * written with; the registry supplies it and Avro resolves it to the schema this
 * service was built with. An event written before events carried a reference
 * arrives with none: the field's default.
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
	// payment-processing stays JSON, so this listener alone swaps the JSON deserializer for Apicurio's Avro one, reading
	// into the generated class rather than a generic record.
	@KafkaListener(topics = TOPIC, groupId = GROUP, properties = {
			"spring.deserializer.value.delegate.class=io.apicurio.registry.serde.avro.AvroKafkaDeserializer",
			"apicurio.registry.use-specific-avro-reader=true"})
	void onMessage(com.example.payment.events.PaymentEvent message) {
		projectPaymentEventUseCase.project(toEvent(message));
	}

	/** Through the domain's constructors: a message with a field it can't read fails here. */
	static PaymentEvent toEvent(com.example.payment.events.PaymentEvent message) {
		return new PaymentEvent(UUID.fromString(message.getEventId()), PaymentEventType.valueOf(message.getType().name()),
				PaymentId.of(message.getPaymentId()),
				message.getRefundId() == null ? null : new RefundId(UUID.fromString(message.getRefundId())),
				new AccountId(message.getSourceAccountId()), new AccountId(message.getDestinationAccountId()),
				new Money(message.getAmount(), Currency.of(message.getCurrency())),
				message.getReference() == null ? null : new PaymentReference(message.getReference()),
				message.getOccurredAt());
	}
}
