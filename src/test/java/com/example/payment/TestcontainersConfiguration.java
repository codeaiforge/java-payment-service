package com.example.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL, a real Kafka and a real schema registry in Docker.
 * {@code @ServiceConnection} points the datasource and the Kafka clients at the
 * first two; the registry has no service connection, so its URL is set here.
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

	// As in docker-compose.yml, with the same compatibility rule (docs/episodes/bonus-10).
	@Bean
	GenericContainer<?> schemaRegistry() {
		return new GenericContainer<>("apicurio/apicurio-registry:3.3.3")
				.withEnv("APICURIO_RULES_GLOBAL_COMPATIBILITY", "FULL_TRANSITIVE")
				.withExposedPorts(8080)
				.waitingFor(Wait.forHttp("/apis/registry/v3/system/info"));
	}

	@Bean
	DynamicPropertyRegistrar schemaRegistryUrl(GenericContainer<?> schemaRegistry) {
		return registry -> registry.add("spring.kafka.properties.apicurio.registry.url", () -> registryUrl(schemaRegistry));
	}

	public static String registryUrl(GenericContainer<?> schemaRegistry) {
		return "http://%s:%d/apis/registry/v3".formatted(schemaRegistry.getHost(), schemaRegistry.getMappedPort(8080));
	}
}
