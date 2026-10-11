package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.application.port.primary.DeliverWebhookUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds payment-events into webhook deliveries (docs/episodes/bonus-11). A
 * primary adapter, like StatementProjectionConsumer: it maps the Avro message
 * the same way and calls a use case; whose endpoints get the event is
 * DeliverWebhookService's.
 *
 * Its own consumer group, so webhooks and the statement each read every event
 * at their own pace, and a slow account system holds up only this one.
 */
@Component
class WebhookDeliveryConsumer {

	static final String GROUP = "payment-webhooks";

	private final DeliverWebhookUseCase deliverWebhookUseCase;

	WebhookDeliveryConsumer(DeliverWebhookUseCase deliverWebhookUseCase) {
		this.deliverWebhookUseCase = deliverWebhookUseCase;
	}

	// As in StatementProjectionConsumer: Avro into the generated class, retry topics and payment-events-dlt on failure.
	@KafkaListener(topics = StatementProjectionConsumer.TOPIC, groupId = GROUP, properties = {
			"spring.deserializer.value.delegate.class=io.apicurio.registry.serde.avro.AvroKafkaDeserializer",
			"apicurio.registry.use-specific-avro-reader=true"})
	void onMessage(com.example.payment.events.PaymentEvent message) {
		deliverWebhookUseCase.deliver(StatementProjectionConsumer.toEvent(message));
	}
}
