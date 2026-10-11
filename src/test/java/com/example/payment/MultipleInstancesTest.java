package com.example.payment;

import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.config.SerdeConfig;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two instances of the service, as behind a load balancer (docs/episodes/bonus-07): two application contexts, each
 * with its own relay and its own reconciliation scheduler, sharing one PostgreSQL, one Kafka and one set of external
 * systems. Nothing in either instance knows about the other; they only meet in the database.
 *
 * The relays run often with small batches, so they are usually both claiming at the same moment.
 */
class MultipleInstancesTest {

	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
	static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:4.1.1");
	static final GenericContainer<?> schemaRegistry = new TestcontainersConfiguration().schemaRegistry();

	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
			.build();

	static ConfigurableApplicationContext first;
	static ConfigurableApplicationContext second;

	@BeforeAll
	static void startTwoInstances() {
		postgres.start();
		kafka.start();
		schemaRegistry.start();
		first = instance();
		second = instance();
	}

	@AfterAll
	static void stopThem() {
		second.close();
		first.close();
	}

	// Command-line arguments: they override application.properties, which SpringApplicationBuilder.properties() don't.
	private static ConfigurableApplicationContext instance() {
		return new SpringApplicationBuilder(PaymentApplication.class).profiles("local").run(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
				"--spring.kafka.properties.apicurio.registry.url=" + TestcontainersConfiguration.registryUrl(schemaRegistry),
				"--account-system.url=" + externalSystems.baseUrl(),
				"--authorization-system.url=" + externalSystems.baseUrl(),
				"--limit-system.url=" + externalSystems.baseUrl(),
				"--payment-system.url=" + externalSystems.baseUrl(),
				"--payment.reconciliation.interval=100ms",
				// A run releases the lock as soon as it ends: what is tested is the lock, not the spacing between runs.
				"--payment.reconciliation.lock-at-least-for=0s",
				"--payment.events.relay-interval=20ms",
				"--payment.events.relay-batch-size=5",
				"--management.tracing.export.otlp.enabled=false",
				// Nothing listens on port 9: Loki is down, as in PaymentEndToEndTest.
				"--management.opentelemetry.logging.export.otlp.endpoint=http://localhost:9/otlp/v1/logs");
	}

	@Test
	void everyEventIsPublishedExactlyOnceWhileBothRelaysRun() {
		Map<String, List<UUID>> recorded = new HashMap<>();
		for (int i = 0; i < 40; i++) {
			Payment payment = storedPayment(PaymentStatus.COMPLETED);
			recorded.put(payment.id().toString(), record(payment, 1));
		}

		List<Map<String, String>> received = paymentEvents(recorded.keySet(), 40);

		assertThat(received).extracting(m -> UUID.fromString(m.get("eventId")))
				.containsExactlyInAnyOrderElementsOf(recorded.values().stream().flatMap(List::stream).toList());
	}

	// Each payment's two events are recorded together, so both relays see both at once. Without the oldest-per-payment
	// rule one relay could claim the first while the other claims the second, and the second could arrive first.
	@Test
	void aPaymentsEventsArriveInTheOrderTheyWereRecorded() {
		Map<String, List<UUID>> recorded = new HashMap<>();
		for (int i = 0; i < 30; i++) {
			Payment payment = storedPayment(PaymentStatus.COMPLETED);
			recorded.put(payment.id().toString(), record(payment, 2));
		}

		Map<String, List<UUID>> received = new HashMap<>();
		for (Map<String, String> event : paymentEvents(recorded.keySet(), 60)) {
			received.computeIfAbsent(event.get("paymentId"), id -> new ArrayList<>()).add(UUID.fromString(event.get("eventId")));
		}

		assertThat(received).isEqualTo(recorded);
	}

	// Both schedulers fire every 100ms; whichever holds the lock asks, the other skips its run. Each stuck payment
	// is asked about once, then it is COMPLETED and no later run looks at it again.
	@Test
	void eachStuckPaymentIsAskedAboutOnce() {
		List<PaymentId> stuck = IntStream.range(0, 10).mapToObj(i -> storedPayment(PaymentStatus.PROCESSING).id()).toList();
		JdbcClient jdbc = first.getBean(JdbcClient.class);
		// Stuck for longer than the threshold, without the test waiting for it.
		stuck.forEach(id -> jdbc.sql("UPDATE payment SET status_changed_at = now() - interval '1 hour' WHERE id = ?")
				.param(id.value()).update());

		await().untilAsserted(() -> assertThat(stuck).allSatisfy(id -> assertThat(jdbc
				.sql("SELECT status FROM payment WHERE id = ?").param(id.value()).query(String.class).single())
				.isEqualTo("COMPLETED")));

		await().during(Duration.ofSeconds(1)).untilAsserted(() -> stuck.forEach(id ->
				externalSystems.verify(1, getRequestedFor(urlEqualTo("/payment-orders/" + id)))));
	}

	private static Payment storedPayment(PaymentStatus status) {
		Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Two instances"), status);
		first.getBean(PaymentRepository.class).save(payment);
		return payment;
	}

	// Recorded through the first instance's port, in one transaction, as a service would. Event IDs in recording order.
	private static List<UUID> record(Payment payment, int events) {
		List<PaymentEvent> recorded = IntStream.range(0, events)
				.mapToObj(i -> PaymentEvent.finalStatusReached(payment, Instant.now())).toList();
		PaymentEventPort port = first.getBean(PaymentEventPort.class);
		first.getBean(TransactionOperations.class).executeWithoutResult(tx -> recorded.forEach(port::record));
		return recorded.stream().map(PaymentEvent::eventId).toList();
	}

	// These payments' events on payment-events, in offset order per partition, once exactly this many have arrived and
	// no more for a second: a duplicate sent late would still be counted.
	private static List<Map<String, String>> paymentEvents(Set<String> paymentIds, int expected) {
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "multiple-instances-test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				SerdeConfig.REGISTRY_URL, TestcontainersConfiguration.registryUrl(schemaRegistry));
		List<Map<String, String>> received = new ArrayList<>();
		AvroKafkaDeserializer<GenericRecord> avro = new AvroKafkaDeserializer<>();
		avro.configure(config, false);
		try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(config, new StringDeserializer(), avro)) {
			consumer.subscribe(List.of("payment-events"));
			await().atMost(Duration.ofSeconds(30)).during(Duration.ofSeconds(1)).untilAsserted(() -> {
				for (ConsumerRecord<String, GenericRecord> record : consumer.poll(Duration.ofMillis(100))) {
					if (!paymentIds.contains(record.key())) {
						continue;
					}
					received.add(Map.of("eventId", record.value().get("eventId").toString(),
							"paymentId", record.value().get("paymentId").toString()));
				}
				assertThat(received).hasSize(expected);
			});
		}
		return received;
	}
}
