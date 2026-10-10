package com.example.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL and a real Kafka in Docker. {@code @ServiceConnection}
 * points the datasource and the Kafka clients at them; no URLs to configure.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:18-alpine");
	}

	// SHORTCUT: also started by the @DataJpaTest slices that don't need it; split the class if that start-up shows.
	@Bean
	@ServiceConnection
	KafkaContainer kafka() {
		return new KafkaContainer("apache/kafka-native:4.1.1");
	}
}
