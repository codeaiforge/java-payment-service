package com.example.payment.infrastructure.adapter.secondary.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The payment-events topic, configured by its publisher rather than left to the
 * broker's defaults (docs/episodes/bonus-09). Spring's KafkaAdmin creates it at
 * start-up, or brings an existing one's configuration in line
 * (spring.kafka.admin.modify-topic-configs).
 */
@Configuration(proxyBeanMethods = false)
class PaymentEventsTopic {

	// Kept forever, not the broker's 7 days. The topic is the only full history of what happened: the statement is
	// rebuilt by replaying it from the start, which only works if the start is still there. Not compacted either:
	// compaction keeps only the latest event per key, and a payment's key carries its payment event and its refunds.
	// SHORTCUT: no snapshot of the statement, so the topic grows without bound. At volume, snapshot the read model and
	// let retention drop what the snapshot covers.
	@Bean
	NewTopic paymentEvents() {
		return TopicBuilder.name(PaymentEventMessage.TOPIC)
				.config(TopicConfig.RETENTION_MS_CONFIG, "-1")
				.build();
	}
}
