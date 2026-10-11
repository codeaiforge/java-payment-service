package com.example.payment.config;

import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import org.apache.avro.generic.GenericContainer;
import org.apache.avro.util.ClassSecurityValidator;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One producer, two formats (docs/episodes/bonus-10): payment-events is Avro,
 * its schema in the registry; payment-processing stays JSON. Each message's
 * value picks its serializer by type, so the relay, the payment-processing
 * publisher and the retry topics, which re-send whatever a listener failed on,
 * all share the producer Spring Boot configures.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

	// Avro creates the class an event's schema names, so it only creates classes it was told to trust (CVE-2024-47561).
	// The statement's consumer reads into the classes generated from src/main/avro: trust those, nothing wider.
	static {
		ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
				ClassSecurityValidator.builder()
						.add("com.example.payment.events.PaymentEvent")
						.add("com.example.payment.events.PaymentEventType")
						.build()));
	}

	// Checked in order, first match wins. Bytes are a message that couldn't be read, parked in a dead-letter topic as
	// they arrived. Avro records (generated or generic) go through the registry. Anything else is JSON, as before.
	// The producer factory configures each with the producer's properties (spring.kafka.producer.properties.*).
	@Bean
	@SuppressWarnings("unchecked")
	DefaultKafkaProducerFactoryCustomizer valueSerializerByType() {
		return factory -> ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializerSupplier(() -> {
			Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
			byType.put(byte[].class, new ByteArraySerializer());
			byType.put(GenericContainer.class, new AvroKafkaSerializer<>());
			byType.put(Object.class, new JacksonJsonSerializer<>());
			return new DelegatingByTypeSerializer(byType, true);
		});
	}
}
