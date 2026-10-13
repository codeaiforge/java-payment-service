package com.example.payment;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What Kubernetes relies on (docs/episodes/bonus-13): a pod that is told to stop finishes what it has started, and its
 * probes say what the kubelet and the Service need to know. Each test starts its own instance, as a pod would, on its
 * own containers: the last test stops PostgreSQL.
 *
 * Closing the context is what the JVM's shutdown hook does on SIGTERM, which is how the kubelet stops a container.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PodLifecycleTest {

	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
	static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.1");
	static final GenericContainer<?> schemaRegistry = new TestcontainersConfiguration().schemaRegistry();

	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
			.build();

	static ConfigurableApplicationContext instance;

	@BeforeAll
	static void startContainers() {
		postgres.start();
		kafka.start();
		schemaRegistry.start();
	}

	@AfterAll
	static void stopInstance() {
		if (instance != null) {
			instance.close();
		}
	}

	// The account system answers every call 1.5s late (within its 2s deadline), so creating a payment takes seconds.
	// While it is in hand, shutdown starts: new connections are refused at once, and the request still gets its 201.
	@Test
	@Order(1)
	void aRequestInHandFinishesAfterShutdownStartsAndANewOneIsRefused() throws Exception {
		instance = start();
		int port = port(instance);
		String token = signedToken(instance.getEnvironment().getProperty("payment.security.local-jwt-secret"));
		externalSystems.setGlobalFixedDelay(1500);

		CompletableFuture<Integer> inHand = CompletableFuture.supplyAsync(() -> createPayment(port, token));
		await().until(() -> !externalSystems.getAllServeEvents().isEmpty());
		ConfigurableApplicationContext stopping = instance;
		instance = null;
		CompletableFuture<Void> shutdown = CompletableFuture.runAsync(stopping::close);

		await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
				.until(() -> refused(port));
		assertThat(inHand).isNotDone();
		assertThat(inHand.get(15, TimeUnit.SECONDS)).isEqualTo(201);
		shutdown.get(30, TimeUnit.SECONDS);
		externalSystems.setGlobalFixedDelay(0);
	}

	// Liveness never looks at the database: if it did, a database outage would restart every pod, and each one would
	// come back to the same outage. Readiness doesn't either (docs/episodes/bonus-13). Full health does, for people.
	@Test
	@Order(2)
	void bothProbesStayUpWithTheDatabaseDown() throws Exception {
		instance = start();
		int port = port(instance);
		assertThat(get(port, "/actuator/health/liveness")).isEqualTo(200);
		assertThat(get(port, "/actuator/health/readiness")).isEqualTo(200);

		postgres.stop();

		await().atMost(Duration.ofSeconds(10)).until(() -> get(port, "/actuator/health") == 503);
		assertThat(get(port, "/actuator/health/liveness")).isEqualTo(200);
		assertThat(get(port, "/actuator/health/readiness")).isEqualTo(200);
	}

	// Command-line arguments: they override application.properties, which SpringApplicationBuilder.properties() don't.
	private static ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(PaymentApplication.class).profiles("local").run(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				// Health asks for a connection; with the database gone it waits this long (30s by default) for one.
				"--spring.datasource.hikari.connection-timeout=1000",
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
				"--spring.kafka.properties.apicurio.registry.url=" + TestcontainersConfiguration.registryUrl(schemaRegistry),
				"--account-system.url=" + externalSystems.baseUrl(),
				"--authorization-system.url=" + externalSystems.baseUrl(),
				"--limit-system.url=" + externalSystems.baseUrl(),
				"--payment-system.url=" + externalSystems.baseUrl(),
				"--management.tracing.export.otlp.enabled=false",
				// Nothing listens on port 9: Loki is down, as in PaymentEndToEndTest.
				"--management.opentelemetry.logging.export.otlp.endpoint=http://localhost:9/otlp/v1/logs");
	}

	private static int port(ConfigurableApplicationContext context) {
		return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
	}

	private static Integer createPayment(int port, String token) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%d/payments".formatted(port)))
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"sourceAccountId": "ACC-10001", "destinationAccountId": "ACC-20001", "amount": 250.00,
						 "currency": "EUR", "reference": "In hand at shutdown"}
						"""))
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	// A new client each time, so the request needs a new connection rather than reusing one.
	private static boolean refused(int port) throws Exception {
		try {
			get(port, "/actuator/health/liveness");
			return false;
		} catch (ConnectException e) {
			return true;
		}
	}

	private static int get(int port, String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
				.timeout(Duration.ofSeconds(5)).build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
	}

	private static String signedToken(String key) throws Exception {
		SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
				new JWTClaimsSet.Builder().subject("CUST-1001").build());
		token.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
		return token.serialize();
	}
}
