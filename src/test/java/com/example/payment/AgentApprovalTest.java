package com.example.payment;

import com.example.payment.infrastructure.adapter.primary.agent.ScriptedChatModel;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Four-eyes approval of the agent's proposals (docs/episodes/bonus-15), across instances and a restart: two
 * instances with the agent profile, as in MultipleInstancesTest, sharing one PostgreSQL, Kafka and set of external
 * systems, each with its own scripted model. Requests go over real HTTP with tokens signed with the local key.
 *
 * Each test's payment had its payment-order answer lost (TIMEOUT) and is backdated five minutes, so the agent
 * proposes RECONCILE_NOW. The scheduled reconciliation waits an hour, so only an approval settles it, and the lock row
 * says it ran a moment ago, so it doesn't look stalled. Asked again, WireMock's payment system answers EXECUTED.
 */
class AgentApprovalTest {

	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
	static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.1");
	static final GenericContainer<?> schemaRegistry = new TestcontainersConfiguration().schemaRegistry();

	private static final String DIAGNOSIS = """
			{"cause": "PAYMENT_SYSTEM_NOT_ANSWERING", "evidenceIds": ["E1", "E3", "E7"], "confidence": "MEDIUM",
			 "summary": "Reconciliation ran, and the payment has been PROCESSING for 5 minutes."}""";

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

	@BeforeEach
	void reconciliationRanJustNow() {
		jdbc().sql("""
				INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES ('reconciliation', now(), now(), 'test')
				ON CONFLICT (name) DO UPDATE SET lock_until = excluded.lock_until, locked_at = excluded.locked_at""").update();
	}

	@Test
	void aProposalStartedOnOneInstanceIsApprovedOnTheOtherAndSettlesThePayment() throws Exception {
		String paymentId = stuckPayment();
		String investigation = start(first, paymentId);

		HttpResponse<String> approved = post(second, "/operations/investigations/" + investigation + "/approve", operator("OPS-0002"));

		assertThat(approved.statusCode()).isEqualTo(200);
		assertThat((String) JsonPath.read(approved.body(), "$.status")).isEqualTo("EXECUTED");
		assertThat((String) JsonPath.read(approved.body(), "$.decidedBy")).isEqualTo("OPS-0002");
		assertThat((String) JsonPath.read(approved.body(), "$.result")).contains("EXECUTED").contains("COMPLETED");
		assertThat(status(paymentId)).isEqualTo("COMPLETED");
		externalSystems.verify(1, getRequestedFor(urlEqualTo("/payment-orders/" + paymentId)));
		// The change is in the payment's own history, like any reconciliation's.
		HttpResponse<String> history = get(first, "/payments/" + paymentId + "/history", customer());
		assertThat((List<String>) JsonPath.read(history.body(), "$[-1:].to")).containsExactly("COMPLETED");
		// The instance that took the approval timed the wait and counted the end (bonus 16).
		MeterRegistry meters = second.getBean(MeterRegistry.class);
		assertThat(meters.find("agent.approval.latency").tag("decision", "APPROVED").timer().count()).isPositive();
		assertThat(meters.find("agent.investigations").tags("cause", "PAYMENT_SYSTEM_NOT_ANSWERING", "action",
				"RECONCILE_NOW", "outcome", "EXECUTED").counter().count()).isPositive();
	}

	// One approval on each instance, released together: the investigation row lets one through, and the payment
	// system is asked once.
	@Test
	void twoApprovalsAtOnceRunTheActionOnce() throws Exception {
		String paymentId = stuckPayment();
		String investigation = start(first, paymentId);
		CountDownLatch go = new CountDownLatch(1);

		CompletableFuture<Integer> one = CompletableFuture.supplyAsync(() -> approveWhen(go, first, investigation, "OPS-0002"));
		CompletableFuture<Integer> other = CompletableFuture.supplyAsync(() -> approveWhen(go, second, investigation, "OPS-0003"));
		go.countDown();

		assertThat(List.of(one.get(), other.get())).containsExactlyInAnyOrder(200, 409);
		externalSystems.verify(1, getRequestedFor(urlEqualTo("/payment-orders/" + paymentId)));
		assertThat(investigationStatus(investigation)).isEqualTo("EXECUTED");
	}

	@Test
	void theOperatorWhoStartedItMayNotApproveIt() throws Exception {
		String investigation = start(first, stuckPayment());

		assertThat(post(second, "/operations/investigations/" + investigation + "/approve", operator("OPS-0001")).statusCode())
				.isEqualTo(403);
		assertThat(investigationStatus(investigation)).isEqualTo("PROPOSED");
	}

	// Another operator reconciled it by hand while the proposal waited: the approval runs nothing.
	@Test
	void aPaymentSettledWhileTheProposalWaitedIsNoLongerNeeded() throws Exception {
		String paymentId = stuckPayment();
		String investigation = start(first, paymentId);
		assertThat(post(second, "/operations/payments/" + paymentId + "/reconcile", operator("OPS-0003")).statusCode())
				.isEqualTo(200);

		HttpResponse<String> approved = post(second, "/operations/investigations/" + investigation + "/approve", operator("OPS-0002"));

		assertThat((String) JsonPath.read(approved.body(), "$.status")).isEqualTo("NO_LONGER_NEEDED");
		externalSystems.verify(1, getRequestedFor(urlEqualTo("/payment-orders/" + paymentId)));
	}

	@Test
	void aRejectedProposalNeverRuns() throws Exception {
		String paymentId = stuckPayment();
		String investigation = start(first, paymentId);

		HttpResponse<String> rejected = post(second, "/operations/investigations/" + investigation + "/reject", operator("OPS-0002"));
		assertThat((String) JsonPath.read(rejected.body(), "$.status")).isEqualTo("REJECTED");

		assertThat(post(first, "/operations/investigations/" + investigation + "/approve", operator("OPS-0003")).statusCode())
				.isEqualTo(409);
		assertThat(status(paymentId)).isEqualTo("PROCESSING");
		externalSystems.verify(0, getRequestedFor(urlEqualTo("/payment-orders/" + paymentId)));
	}

	// The instance that paused the run is gone; a new one, with nothing in memory, resumes it from PostgreSQL.
	@Test
	void aPausedInvestigationSurvivesARestart() throws Exception {
		String paymentId = stuckPayment();
		String investigation = start(first, paymentId);
		// An instance runs reconciliation as it starts; with the lock free, the new one would settle the payment before
		// the approval (it did, on CI). Held, as after another instance's run, only the approval settles it.
		jdbc().sql("UPDATE shedlock SET lock_until = now() + interval '1 hour' WHERE name = 'reconciliation'").update();
		first.close();
		first = instance();

		HttpResponse<String> approved = post(first, "/operations/investigations/" + investigation + "/approve", operator("OPS-0002"));

		assertThat((String) JsonPath.read(approved.body(), "$.status")).isEqualTo("EXECUTED");
		assertThat((String) JsonPath.read(approved.body(), "$.diagnosis.cause")).isEqualTo("PAYMENT_SYSTEM_NOT_ANSWERING");
		assertThat(status(paymentId)).isEqualTo("COMPLETED");
	}

	// Command-line arguments: they override application.properties, which SpringApplicationBuilder.properties() don't.
	private static ConfigurableApplicationContext instance() {
		return new SpringApplicationBuilder(PaymentApplication.class, OperationsAgentEndToEndTest.Model.class)
				.profiles("local", "agent").run(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.ai.model.chat=none",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
				"--spring.kafka.properties.apicurio.registry.url=" + TestcontainersConfiguration.registryUrl(schemaRegistry),
				"--account-system.url=" + externalSystems.baseUrl(),
				"--authorization-system.url=" + externalSystems.baseUrl(),
				"--limit-system.url=" + externalSystems.baseUrl(),
				"--payment-system.url=" + externalSystems.baseUrl(),
				"--spring.cloud.openfeign.client.config.payment-system.read-timeout=300",
				"--payment.reconciliation.interval=1h",
				"--management.tracing.export.otlp.enabled=false",
				"--management.opentelemetry.logging.export.otlp.endpoint=http://localhost:9/otlp/v1/logs");
	}

	private static String start(ConfigurableApplicationContext instance, String paymentId) throws Exception {
		// The diagnosis only: code chooses the action (bonus 16), and an unread answer would be the next test's diagnosis.
		instance.getBean(ScriptedChatModel.class).answering(DIAGNOSIS);
		HttpResponse<String> started = post(instance, "/operations/investigations", operator("OPS-0001"),
				"{\"paymentId\": \"" + paymentId + "\"}");
		assertThat(started.statusCode()).isEqualTo(201);
		assertThat((String) JsonPath.read(started.body(), "$.status")).isEqualTo("PROPOSED");
		assertThat((String) JsonPath.read(started.body(), "$.proposal.action")).isEqualTo("RECONCILE_NOW");
		return JsonPath.read(started.body(), "$.id");
	}

	private static int approveWhen(CountDownLatch go, ConfigurableApplicationContext instance, String investigation,
			String operatorId) {
		try {
			go.await();
			return post(instance, "/operations/investigations/" + investigation + "/approve", operator(operatorId)).statusCode();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// PROCESSING with its answer lost, then five minutes older, in the trail and the row alike.
	private static String stuckPayment() throws Exception {
		HttpResponse<String> created = post(first, "/payments", customer(), """
				{"sourceAccountId": "ACC-10001", "destinationAccountId": "ACC-20001", "amount": 250.00,
				 "currency": "EUR", "reference": "TIMEOUT approved by a second operator"}""");
		assertThat(created.statusCode()).isEqualTo(201);
		String paymentId = JsonPath.read(created.body(), "$.paymentId");
		assertThat(post(first, "/payments/" + paymentId + "/execute", customer()).statusCode()).isEqualTo(202);
		await().until(() -> "PROCESSING".equals(status(paymentId)));
		jdbc().sql("UPDATE payment_audit SET occurred_at = occurred_at - interval '5 minutes' WHERE payment_id = ?::uuid")
				.param(paymentId).update();
		jdbc().sql("UPDATE payment SET status_changed_at = status_changed_at - interval '5 minutes' WHERE id = ?::uuid")
				.param(paymentId).update();
		return paymentId;
	}

	private static String status(String paymentId) {
		return jdbc().sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single();
	}

	private static String investigationStatus(String investigation) {
		return jdbc().sql("SELECT status FROM investigation WHERE id = ?::uuid").param(investigation).query(String.class).single();
	}

	private static JdbcClient jdbc() {
		return second.getBean(JdbcClient.class);
	}

	private static HttpResponse<String> post(ConfigurableApplicationContext instance, String path, String token)
			throws Exception {
		return send(instance, path, token, HttpRequest.BodyPublishers.noBody(), "POST");
	}

	private static HttpResponse<String> post(ConfigurableApplicationContext instance, String path, String token,
			String body) throws Exception {
		return send(instance, path, token, HttpRequest.BodyPublishers.ofString(body), "POST");
	}

	private static HttpResponse<String> get(ConfigurableApplicationContext instance, String path, String token)
			throws Exception {
		return send(instance, path, token, HttpRequest.BodyPublishers.noBody(), "GET");
	}

	private static HttpResponse<String> send(ConfigurableApplicationContext instance, String path, String token,
			HttpRequest.BodyPublisher body, String method) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%s%s"
						.formatted(instance.getEnvironment().getProperty("local.server.port"), path)))
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.header("Content-Type", "application/json")
				.method(method, body)
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	private static String customer() throws Exception {
		return signed(new JWTClaimsSet.Builder().subject("CUST-1001").build());
	}

	private static String operator(String operatorId) throws Exception {
		return signed(new JWTClaimsSet.Builder().subject(operatorId).claim("scope", "payments.operate").build());
	}

	// What an identity provider would issue, signed with the local key both instances accept.
	private static String signed(JWTClaimsSet claims) throws Exception {
		SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		token.sign(new MACSigner(first.getEnvironment().getProperty("payment.security.local-jwt-secret")
				.getBytes(StandardCharsets.UTF_8)));
		return token.serialize();
	}
}
