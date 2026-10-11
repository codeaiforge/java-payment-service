package com.example.payment;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.example.payment.infrastructure.adapter.primary.messaging.PaymentProcessingMessage;
import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service, from HTTP to the database, Kafka and the external systems:
 * real PostgreSQL and Kafka (Testcontainers), WireMock for the account,
 * authorization, limit and payment systems. Only the HTTP server is simulated (MockMvc).
 *
 * Every request carries a token for customer CUST-1001, who holds ACC-10001
 * (wiremock/mappings/accounts.json). jwt() stands in for a signed token; two
 * tests sign real ones with the local key.
 *
 * Metrics and tracing are on as in production (Boot propagates the
 * traceparent header only then), with spans kept in memory instead of sent
 * to Tempo.
 *
 * Log records take the production path, the batch processor and its OTLP
 * exporter, to a Loki that isn't there: every test here runs with Loki down.
 * The same batches also reach an in-memory exporter the tests read.
 *
 * The same WireMock also plays customers' webhook endpoints, over HTTPS with a
 * test certificate (src/test/resources/webhooks), on 127.0.0.1: the one private
 * address these tests let webhooks call.
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
class PaymentEndToEndTest {

	// The same fictional account, authorization, limit and payment systems docker-compose.yml serves locally.
	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock").dynamicHttpsPort()
					.keystorePath("src/test/resources/webhooks/merchant.p12").keystoreType("PKCS12")
					.keystorePassword("merchant-test").keyManagerPassword("merchant-test"))
			.build();

	@DynamicPropertySource
	static void externalSystemUrls(DynamicPropertyRegistry registry) {
		// HTTP: with an HTTPS port too, baseUrl() would be the HTTPS one, the merchant's.
		registry.add("account-system.url", () -> externalSystems.getRuntimeInfo().getHttpBaseUrl());
		registry.add("authorization-system.url", () -> externalSystems.getRuntimeInfo().getHttpBaseUrl());
		registry.add("limit-system.url", () -> externalSystems.getRuntimeInfo().getHttpBaseUrl());
		registry.add("payment-system.url", () -> externalSystems.getRuntimeInfo().getHttpBaseUrl());
		// The TIMEOUT mapping answers after 7s; no need to wait the real 5s to give up.
		registry.add("spring.cloud.openfeign.client.config.payment-system.read-timeout", () -> "300");
		// Reconciliation runs often here; with the 2m threshold it only finds payments a test has backdated.
		registry.add("payment.reconciliation.interval", () -> "200ms");
		// One instance here: a run that ends early needn't keep the lock, the next one 200ms later should run.
		registry.add("payment.reconciliation.lock-at-least-for", () -> "0s");
		registry.add("payment.events.relay-interval", () -> "100ms");
		registry.add("management.tracing.export.otlp.enabled", () -> "false");
		// Nothing listens on port 9: each export fails, as when Loki is down.
		registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> "http://localhost:9/otlp/v1/logs");
		registry.add("payment.webhooks.dispatch-interval", () -> "100ms");
		registry.add("payment.webhooks.allowed-private-addresses", () -> "127.0.0.1");
		registry.add("payment.webhooks.ssl-bundle", () -> "merchant");
		registry.add("spring.ssl.bundle.pem.merchant.truststore.certificate", () -> "classpath:webhooks/merchant.crt");
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	KafkaTemplate<String, PaymentProcessingMessage> kafkaTemplate;

	@Autowired
	KafkaTemplate<String, Object> events;

	@Autowired
	ConsumerFactory<?, ?> consumerFactory;

	@Autowired
	GenericContainer<?> schemaRegistry;

	@Value("${payment.security.local-jwt-secret}")
	String localJwtSecret;

	@Autowired
	InMemorySpanExporter spans;

	@Autowired
	InMemoryLogRecordExporter logRecords;

	@Autowired
	OpenTelemetry openTelemetry;

	@Autowired
	List<OtlpHttpLogRecordExporter> lokiExporters;

	// The appender sends to the last context that installed it; another test class's context may have since.
	@BeforeEach
	void sendLogsToThisContext() {
		OpenTelemetryAppender.install(openTelemetry);
	}

	// An endpoint hears of every payment of its customer that settles while it is registered: each test's are gone after
	// it, so they don't collect later tests' payments, or retry against stubs WireMock has since forgotten.
	// Every test starts with full rate-limit buckets: CUST-1001 creates payments in nearly every test, far more than
	// 10 a minute across the class.
	@BeforeEach
	void fillRateLimitBuckets() {
		jdbc.sql("DELETE FROM rate_limit_bucket").update();
	}

	@AfterEach
	void deleteWebhooks() {
		jdbc.sql("DELETE FROM webhook_subscription").update();
	}

	// Finished spans are kept in memory, as each one ends, instead of being sent to Tempo.
	@TestConfiguration
	static class Spans {

		@Bean
		InMemorySpanExporter spanExporter() {
			return InMemorySpanExporter.create();
		}

		@Bean
		SpanProcessor inMemorySpans(InMemorySpanExporter exporter) {
			return SimpleSpanProcessor.create(exporter);
		}

		// Boot's batch processor exports to every LogRecordExporter bean: Loki (OTLP) and this one.
		@Bean
		InMemoryLogRecordExporter logRecordExporter() {
			return InMemoryLogRecordExporter.create();
		}
	}

	@Test
	void noTokenIs401() throws Exception {
		mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content(paymentWithReference("No token")))
				.andExpect(status().isUnauthorized());

		assertThat(paymentsWithReference("No token")).isZero();
	}

	@Test
	void aTokenSignedWithTheLocalKeyIsAccepted() throws Exception {
		String paymentId = createPayment("Signed token");

		mockMvc.perform(get("/payments/{id}", paymentId).header("Authorization", "Bearer " + signedToken(localJwtSecret)))
				.andExpect(status().isOk());
	}

	@Test
	void aTokenSignedWithAnotherKeyIs401() throws Exception {
		String forged = signedToken("someone-elses-key-that-is-also-long-enough-0001");

		mockMvc.perform(get("/payments/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void healthMetricsAndDocumentationNeedNoToken() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	// ACC-30001 belongs to CUST-2002. Paying *to* it is fine (createdPaymentCanBeReadBack); paying from it is not.
	@Test
	void creatingFromAnAccountTheCallerDoesNotHoldIs403() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{
								  "sourceAccountId": "ACC-30001",
								  "destinationAccountId": "ACC-20001",
								  "amount": 250.00,
								  "currency": "GBP",
								  "reference": "Not my account"
								}
								""").with(customer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail").value("Account ACC-30001 is not held by the caller"));

		assertThat(paymentsWithReference("Not my account")).isZero();
	}

	// The same answer as for a payment that doesn't exist, so CUST-2002 can't tell whether this ID is in use.
	@Test
	void anotherCustomersPaymentIs404AndIsNotTouched() throws Exception {
		String paymentId = createPayment("Someone else's payment");
		String notFound = "Payment " + paymentId + " does not exist";

		mockMvc.perform(get("/payments/{id}", paymentId).with(otherCustomer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value(notFound));
		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(otherCustomer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value(notFound));
		mockMvc.perform(post("/payments/{id}/cancel", paymentId).with(otherCustomer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value(notFound));

		await().during(Duration.ofMillis(500)).untilAsserted(() -> assertThat(storedStatus(paymentId)).isEqualTo("CREATED"));
	}

	@Test
	void createsAndStoresPayment() throws Exception {
		String response = mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						""").with(customer()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.paymentId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("CREATED"))
				.andExpect(jsonPath("$.amount").value(250.00))
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andReturn().getResponse().getContentAsString();

		String paymentId = JsonPath.read(response, "$.paymentId");
		assertThat(jdbc.sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single())
				.isEqualTo("CREATED");
	}

	@Test
	void createdPaymentCanBeReadBack() throws Exception {
		String response = mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-30001",
						  "amount": 99.95,
						  "currency": "GBP",
						  "reference": "Rent October"
						}
						""").with(customer()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String paymentId = JsonPath.read(response, "$.paymentId");

		mockMvc.perform(get("/payments/{id}", paymentId).with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentId").value(paymentId))
				.andExpect(jsonPath("$.sourceAccountId").value("ACC-10001"))
				.andExpect(jsonPath("$.destinationAccountId").value("ACC-30001"))
				.andExpect(jsonPath("$.amount").value(99.95))
				.andExpect(jsonPath("$.currency").value("GBP"))
				.andExpect(jsonPath("$.reference").value("Rent October"))
				.andExpect(jsonPath("$.status").value("CREATED"));
	}

	@Test
	void publishesTheApiContract() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/payments'].post").exists())
				.andExpect(jsonPath("$.paths['/payments/{paymentId}'].get").exists())
				.andExpect(jsonPath("$.paths['/payments/{paymentId}/execute'].post").exists())
				.andExpect(jsonPath("$.paths['/payments/{paymentId}/cancel'].post").exists());
	}

	@Test
	void unknownPaymentIs404() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get("/payments/{id}", unknown).with(customer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Payment " + unknown + " does not exist"));
	}

	@Test
	void malformedPaymentIdIs400() throws Exception {
		mockMvc.perform(get("/payments/not-a-uuid").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Invalid payment id: not-a-uuid"));
	}

	@Test
	void rejectsBusinessRuleViolationWith400() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "JPY",
						  "reference": "Invoice 12345"
						}
						""").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Unsupported currency: JPY"));
	}

	@Test
	void rejectsInactiveAccountWith400() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-90001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						""").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Destination account ACC-90001 is not active"));
	}

	@Test
	void rejectsAccountTheAccountSystemDoesNotKnowWith400() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-99999",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						""").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Source account ACC-99999 does not exist"));
	}

	// 202 with the payment as it is now; the consumer moves it on after the response has gone.
	@Test
	void executesAPaymentToCompletedAndStoresIt() throws Exception {
		String paymentId = createPayment("Invoice 12345");

		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(customer()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("CREATED"));

		awaitStatus(paymentId, "COMPLETED");
		mockMvc.perform(get("/payments/{id}", paymentId).with(customer()))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void paymentThePaymentSystemRejectsEndsFailed() throws Exception {
		String paymentId = createPayment("REJECT insufficient funds");

		execute(paymentId);

		awaitStatus(paymentId, "FAILED");
	}

	@Test
	void paymentWhoseAnswerIsLostStaysProcessingAndCannotBeSentAgain() throws Exception {
		String paymentId = createPayment("TIMEOUT slow payment system");

		execute(paymentId);

		await().untilAsserted(() -> {
			externalSystems.verify(1, postRequestedFor(urlEqualTo("/payment-orders"))
					.withHeader("Idempotency-Key", equalTo(paymentId)));
			assertThat(storedStatus(paymentId)).isEqualTo("PROCESSING");
		});
		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(customer()))
				.andExpect(status().isConflict());
	}

	// The payment system settled it after we stopped listening. A run of the scheduler asks, and the payment
	// catches up with what really happened.
	@Test
	void reconciliationCompletesAPaymentWhoseAnswerWasLost() throws Exception {
		String paymentId = createPayment("TIMEOUT reconciled later");
		execute(paymentId);
		awaitStatus(paymentId, "PROCESSING");

		// Stuck for longer than the threshold, without the test waiting for it.
		jdbc.sql("UPDATE payment SET status_changed_at = now() - interval '1 hour' WHERE id = ?::uuid").param(paymentId).update();

		awaitStatus(paymentId, "COMPLETED");
		externalSystems.verify(getRequestedFor(urlEqualTo("/payment-orders/" + paymentId)));
		assertThat(jdbc.sql("SELECT count(*) FROM payment_audit WHERE payment_id = ?::uuid AND from_status = 'PROCESSING' AND to_status = 'COMPLETED'")
				.param(paymentId).query(Integer.class).single()).isEqualTo(1);
		// Asked, never sent again.
		externalSystems.verify(1, postRequestedFor(urlEqualTo("/payment-orders")).withHeader("Idempotency-Key", equalTo(paymentId)));
	}

	// What another service sees: one PAYMENT_COMPLETED on payment-events, keyed by the payment, sent by the relay
	// from the outbox row written with the status change.
	@Test
	void aCompletedPaymentIsPublishedOnceOnPaymentEvents() throws Exception {
		String paymentId = createPayment("Published event");
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");

		GenericRecord message = paymentEvents(paymentId, 1).getFirst();
		assertThat(message.get("type")).hasToString("PAYMENT_COMPLETED");
		assertThat(message.get("paymentId")).hasToString(paymentId);
		assertThat(message.get("eventId")).hasToString(jdbc.sql(
				"SELECT event_id::text FROM payment_outbox WHERE payment_id = ?::uuid").param(paymentId).query(String.class).single());
		assertThat(message.get("currency")).hasToString("EUR");
		// Read with v1, as a consumer that was never updated: the reference it doesn't know about is skipped.
		assertThat(message.getSchema().getField("reference")).isNull();
	}

	// The relay registered the schema it writes with (docs/episodes/bonus-10). A version that would break consumers, here
	// amount renamed, is refused by the registry's FULL_TRANSITIVE rule, as a second line behind PaymentEventSchemaTest.
	@Test
	void theRegistryHoldsTheEventSchemaAndRefusesABreakingVersion() throws Exception {
		paymentEvents(completedPayment("Registered schema"), 1);
		String versions = TestcontainersConfiguration.registryUrl(schemaRegistry)
				+ "/groups/default/artifacts/payment-events-value/versions";

		HttpResponse<String> latest = http(HttpRequest.newBuilder(URI.create(versions + "/branch=latest/content")));
		assertThat(latest.statusCode()).isEqualTo(200);
		assertThat(new Schema.Parser().parse(latest.body())).isEqualTo(com.example.payment.events.PaymentEvent.getClassSchema());

		String renamed = latest.body().replace("\"amount\"", "\"value\"");
		HttpResponse<String> refused = http(HttpRequest.newBuilder(URI.create(versions))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JsonMapper.shared().writeValueAsString(
						Map.of("content", Map.of("content", renamed, "contentType", "application/json"))))));
		assertThat(refused.body()).contains("RuleViolationException");
		assertThat(refused.statusCode()).isEqualTo(400);
	}

	// A full refund in parts: 100, then the 150 left. One cent more is refused, and nothing was sent for it.
	@Test
	void refundsACompletedPaymentFullyInParts() throws Exception {
		String paymentId = completedPayment("Refunded in parts");

		refund(paymentId, "100.00", customer())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.paymentId").value(paymentId))
				.andExpect(jsonPath("$.amount").value(100.00))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		refund(paymentId, "150.00", customer()).andExpect(status().isCreated());
		refund(paymentId, "0.01", customer())
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("Refund of 0.01 exceeds the 0.00 EUR left to refund on payment " + paymentId));

		mockMvc.perform(get("/payments/{id}/refunds", paymentId).with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].amount").value(100.00))
				.andExpect(jsonPath("$[1].amount").value(150.00));
		externalSystems.verify(2, postRequestedFor(urlEqualTo("/refund-orders"))
				.withRequestBody(matchingJsonPath("$.originalPaymentId", equalTo(paymentId))));
		// The payment itself is still COMPLETED: refunding is a new money movement, not a status of the payment.
		assertThat(storedStatus(paymentId)).isEqualTo("COMPLETED");
	}

	@Test
	void refundingAPaymentThatIsNotCompletedIs409() throws Exception {
		String paymentId = createPayment("Not yet completed");

		refund(paymentId, "50.00", customer()).andExpect(status().isConflict());

		assertThat(jdbc.sql("SELECT count(*) FROM refund WHERE payment_id = ?::uuid").param(paymentId)
				.query(Integer.class).single()).isZero();
	}

	@Test
	void anotherCustomersPaymentCannotBeRefundedOrItsRefundsRead() throws Exception {
		String paymentId = completedPayment("Someone else's refund");
		String notFound = "Payment " + paymentId + " does not exist";

		refund(paymentId, "50.00", otherCustomer())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value(notFound));
		mockMvc.perform(get("/payments/{id}/refunds", paymentId).with(otherCustomer()))
				.andExpect(status().isNotFound());

		externalSystems.verify(0, postRequestedFor(urlEqualTo("/refund-orders"))
				.withRequestBody(matchingJsonPath("$.originalPaymentId", equalTo(paymentId))));
	}

	// The payment's event first, then the refund's, both keyed by the payment, so a consumer sees them in order.
	@Test
	void aCompletedRefundIsPublishedOnPaymentEvents() throws Exception {
		String paymentId = completedPayment("Refund event");
		String refundId = JsonPath.read(refund(paymentId, "40.00", customer()).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.refundId");

		List<GenericRecord> messages = paymentEvents(paymentId, 2);

		assertThat(messages).extracting(m -> m.get("type").toString())
				.containsExactly("PAYMENT_COMPLETED", "REFUND_COMPLETED");
		GenericRecord refunded = messages.get(1);
		assertThat(refunded.get("refundId")).hasToString(refundId);
		assertThat((BigDecimal) refunded.get("amount")).isEqualByComparingTo("40.00");
	}

	// The 17.17 refund settled after we stopped listening (wiremock/mappings/refund-orders.json). A reconciliation run
	// asks about it by its refund ID, and the refund catches up: audited, and published like any finished refund.
	@Test
	void reconciliationCompletesARefundWhoseAnswerWasLost() throws Exception {
		String paymentId = completedPayment("Refund reconciled later");
		String refundId = JsonPath.read(refund(paymentId, "17.17", customer())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PROCESSING"))
				.andReturn().getResponse().getContentAsString(), "$.refundId");

		jdbc.sql("UPDATE refund SET status_changed_at = now() - interval '1 hour' WHERE id = ?::uuid").param(refundId).update();

		await().untilAsserted(() -> assertThat(jdbc.sql("SELECT status FROM refund WHERE id = ?::uuid").param(refundId)
				.query(String.class).single()).isEqualTo("COMPLETED"));
		externalSystems.verify(getRequestedFor(urlEqualTo("/refund-orders/" + refundId)));
		assertThat(jdbc.sql("SELECT count(*) FROM refund_audit WHERE refund_id = ?::uuid AND from_status = 'PROCESSING' AND to_status = 'COMPLETED'")
				.param(refundId).query(Integer.class).single()).isEqualTo(1);
		assertThat(paymentEvents(paymentId, 2)).extracting(m -> m.get("type").toString())
				.containsExactly("PAYMENT_COMPLETED", "REFUND_COMPLETED");
		// Asked, never sent again.
		externalSystems.verify(1, postRequestedFor(urlEqualTo("/refund-orders"))
				.withRequestBody(matchingJsonPath("$.originalPaymentId", equalTo(paymentId))));
	}

	// The statement (docs/episodes/bonus-09) is built from payment-events, after the fact: await it.
	@Test
	void aPartRefundShowsOnTheStatement() throws Exception {
		String paymentId = completedPayment("On the statement");
		refund(paymentId, "40.00", customer()).andExpect(status().isCreated());

		await().untilAsserted(() -> mockMvc.perform(get("/accounts/ACC-10001/statement?size=100").with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lines[?(@.paymentId == '%s')].refundedAmount".formatted(paymentId)).value(40.00))
				.andExpect(jsonPath("$.lines[?(@.paymentId == '%s')].amount".formatted(paymentId)).value(250.00))
				.andExpect(jsonPath("$.lines[?(@.paymentId == '%s')].counterpartyAccountId".formatted(paymentId)).value("ACC-20001"))
				.andExpect(jsonPath("$.lines[?(@.paymentId == '%s')].reference".formatted(paymentId)).value("On the statement"))
				.andExpect(jsonPath("$.lines[?(@.paymentId == '%s')].status".formatted(paymentId)).value("COMPLETED")));
	}

	// Events published straight to the topic, as the relay would, for ACC-70001: no test pays from it, and these are in
	// 2020, so nothing else is on that page. The refund arrives twice and counts once.
	@Test
	void theStatementShowsPublishedEventsAndARedeliveredOneCountsOnce() throws Exception {
		String paymentId = UUID.randomUUID().toString();
		publishEvent(paymentId, "PAYMENT_COMPLETED", null, "100.00", "2020-03-01T10:00:00Z");
		com.example.payment.events.PaymentEvent refund = event(paymentId, "REFUND_COMPLETED", UUID.randomUUID().toString(),
				"30.00", "2020-03-02T10:00:00Z");
		events.send("payment-events", paymentId, refund).join();
		events.send("payment-events", paymentId, refund).join();
		// Published after the duplicate, on the same partition: once it is applied, the duplicate has been read too.
		String last = publishEvent(paymentId, "REFUND_FAILED", UUID.randomUUID().toString(), "5.00", "2020-03-03T10:00:00Z");
		await().untilAsserted(() -> assertThat(jdbc.sql("SELECT last_event_id::text FROM statement_line WHERE payment_id = ?::uuid")
				.param(paymentId).query(String.class).optional()).contains(last));

		mockMvc.perform(get("/accounts/ACC-70001/statement?from=2020-03-01&to=2020-03-01").with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lines.length()").value(1))
				.andExpect(jsonPath("$.lines[0].paymentId").value(paymentId))
				.andExpect(jsonPath("$.lines[0].refundedAmount").value(30.00))
				.andExpect(jsonPath("$.lines[0].settledAt").value("2020-03-01T10:00:00Z"));
	}

	@Test
	void theStatementIsFilteredAndPaged() throws Exception {
		publishEvent(UUID.randomUUID().toString(), "PAYMENT_COMPLETED", null, "10.00", "2021-06-01T08:00:00Z");
		publishEvent(UUID.randomUUID().toString(), "PAYMENT_FAILED", null, "20.00", "2021-06-02T08:00:00Z");
		publishEvent(UUID.randomUUID().toString(), "PAYMENT_COMPLETED", null, "30.00", "2021-06-03T08:00:00Z");
		publishEvent(UUID.randomUUID().toString(), "PAYMENT_COMPLETED", null, "40.00", "2021-06-04T08:00:00Z");
		String june = "/accounts/ACC-70001/statement?from=2021-06-01&to=2021-06-03";
		await().untilAsserted(() -> mockMvc.perform(get(june).with(customer())).andExpect(jsonPath("$.totalLines").value(3)));

		mockMvc.perform(get(june + "&status=COMPLETED&size=1").with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalLines").value(2))
				.andExpect(jsonPath("$.lines.length()").value(1))
				.andExpect(jsonPath("$.lines[0].amount").value(30.00));
		mockMvc.perform(get(june + "&status=COMPLETED&size=1&page=1").with(customer()))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.lines[0].amount").value(10.00));
	}

	@Test
	void anotherCustomersStatementIs403() throws Exception {
		mockMvc.perform(get("/accounts/ACC-30001/statement").with(customer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail").value("Account ACC-30001 is not held by the caller"));
		mockMvc.perform(get("/accounts/ACC-UNKNOWN/statement").with(customer())).andExpect(status().isForbidden());
	}

	@Test
	void aStatementPageAboveTheCapOrABadFilterIs400() throws Exception {
		mockMvc.perform(get("/accounts/ACC-10001/statement?size=101").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("size must be between 1 and 100"));
		mockMvc.perform(get("/accounts/ACC-10001/statement?status=PROCESSING").with(customer()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/accounts/ACC-10001/statement?from=yesterday").with(customer()))
				.andExpect(status().isBadRequest());
	}

	/** @return the event's ID */
	private String publishEvent(String paymentId, String type, String refundId, String amount, String occurredAt) {
		com.example.payment.events.PaymentEvent event = event(paymentId, type, refundId, amount, occurredAt);
		events.send("payment-events", paymentId, event).join();
		return event.getEventId();
	}

	// Without a reference, as every event before docs/episodes/bonus-10.
	private static com.example.payment.events.PaymentEvent event(String paymentId, String type, String refundId,
			String amount, String occurredAt) {
		return com.example.payment.events.PaymentEvent.newBuilder().setEventId(UUID.randomUUID().toString())
				.setType(com.example.payment.events.PaymentEventType.valueOf(type)).setPaymentId(paymentId)
				.setRefundId(refundId).setSourceAccountId("ACC-70001").setDestinationAccountId("ACC-20001")
				.setAmount(new BigDecimal(amount)).setCurrency("EUR").setOccurredAt(Instant.parse(occurredAt)).build();
	}

	// Kafka delivers at least once, so the same message twice is normal. The second finds the payment past
	// AUTHORIZED and is dropped: the payment system is asked once.
	@Test
	void registersAWebhookAndShowsItsSecretOnlyOnce() throws Exception {
		String response = registerWebhook(merchantUrl("/merchant/once"), customer())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.url").value(merchantUrl("/merchant/once")))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.secret").value(startsWith("whsec_")))
				.andReturn().getResponse().getContentAsString();

		mockMvc.perform(get("/webhooks").with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].webhookId").value((String) JsonPath.read(response, "$.webhookId")))
				.andExpect(jsonPath("$[0].secret").doesNotExist());
	}

	// SSRF (docs/episodes/bonus-11): addresses inside our network are refused, whatever the customer's reason.
	@Test
	void aWebhookThatIsNotHttpsOrPointsIntoOurNetworkIs400() throws Exception {
		registerWebhook("http://merchant.example.com/hooks", customer())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Webhook URL must be https"));
		for (String url : List.of("https://10.0.0.1/hooks", "https://169.254.169.254/latest/meta-data/", "https://127.0.0.2/")) {
			registerWebhook(url, customer())
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail").value("Webhook URL's host must resolve, and only to public addresses"));
		}
		registerWebhook("not a url", customer()).andExpect(status().isBadRequest());

		assertThat(jdbc.sql("SELECT count(*) FROM webhook_subscription").query(Integer.class).single()).isZero();
	}

	@Test
	void aSettledPaymentIsPostedSignedToThePayersEndpoint() throws Exception {
		externalSystems.stubFor(WireMock.post("/merchant/signed").willReturn(aResponse().withStatus(200)));
		String secret = JsonPath.read(registerWebhook(merchantUrl("/merchant/signed"), customer())
				.andReturn().getResponse().getContentAsString(), "$.secret");

		String paymentId = completedPayment("Told by webhook");

		LoggedRequest request = await().until(() -> webhooksReceived("/merchant/signed", paymentId), r -> !r.isEmpty()).getFirst();
		assertThat(request.getHeader("Webhook-Signature")).isEqualTo(signature(secret, request));
		assertThat(request.getHeader("Webhook-Id")).isEqualTo(jdbc.sql(
				"SELECT event_id::text FROM payment_outbox WHERE payment_id = ?::uuid").param(paymentId).query(String.class).single());
		assertThat((String) JsonPath.read(request.getBodyAsString(), "$.type")).isEqualTo("payment.completed");
		assertThat((String) JsonPath.read(request.getBodyAsString(), "$.data.amount")).isEqualTo("250.00");
		assertThat((String) JsonPath.read(request.getBodyAsString(), "$.data.reference")).isEqualTo("Told by webhook");
		externalSystems.verify(postRequestedFor(urlEqualTo("/merchant/signed")).withHeader("traceparent", absent()));
	}

	// CUST-2002's endpoint isn't CUST-1001's to see or delete, and hears nothing of CUST-1001's payments.
	@Test
	void aCustomerSeesAndDeletesOnlyTheirOwnWebhooksAndHearsOnlyOfTheirOwnPayments() throws Exception {
		externalSystems.stubFor(WireMock.post(WireMock.urlPathMatching("/merchant/.*")).willReturn(aResponse().withStatus(200)));
		registerWebhook(merchantUrl("/merchant/mine"), customer()).andExpect(status().isCreated());
		String theirs = JsonPath.read(registerWebhook(merchantUrl("/merchant/theirs"), otherCustomer())
				.andReturn().getResponse().getContentAsString(), "$.webhookId");

		mockMvc.perform(get("/webhooks").with(customer()))
				.andExpect(jsonPath("$[?(@.webhookId == '%s')]".formatted(theirs)).isEmpty());
		mockMvc.perform(delete("/webhooks/{id}", theirs).with(customer())).andExpect(status().isNotFound());
		String paymentId = completedPayment("Only mine");

		await().until(() -> webhooksReceived("/merchant/mine", paymentId), r -> !r.isEmpty());
		// Every endpoint the event is for was queued at once, before any was sent: none was queued for theirs.
		assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?::uuid").param(theirs)
				.query(Integer.class).single()).isZero();
		assertThat(webhooksReceived("/merchant/theirs", paymentId)).isEmpty();
		mockMvc.perform(get("/webhooks").with(otherCustomer()))
				.andExpect(jsonPath("$[0].webhookId").value(theirs));
		mockMvc.perform(delete("/webhooks/{id}", theirs).with(otherCustomer())).andExpect(status().isNoContent());
	}

	// The URL's path can hold the customer's own token, and the secret signs as us: neither leaves the database, and nor
	// does the customer ID, on success or failure.
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void noWebhookSecretUrlPathOrCustomerIdInLogsSpansOrExportedLogRecords(CapturedOutput output) throws Exception {
		externalSystems.stubFor(WireMock.post("/merchant/path-token-7f3a").willReturn(aResponse().withStatus(200)));
		externalSystems.stubFor(WireMock.post("/merchant/path-token-9c1e").willReturn(aResponse().withStatus(500)));
		String secret = JsonPath.read(registerWebhook(merchantUrl("/merchant/path-token-7f3a"), customer())
				.andReturn().getResponse().getContentAsString(), "$.secret");
		String failingSecret = JsonPath.read(registerWebhook(merchantUrl("/merchant/path-token-9c1e"), customer())
				.andReturn().getResponse().getContentAsString(), "$.secret");

		String paymentId = completedPayment("Webhook not in any log");

		await().until(() -> jdbc.sql("SELECT string_agg(status || attempts, ',' ORDER BY status) FROM webhook_delivery WHERE payment_id = ?::uuid")
				.param(paymentId).query(String.class).single(), "DELIVERED1,PENDING1"::equals);
		await().until(() -> logRecords.getFinishedLogRecordItems().stream()
				.anyMatch(record -> String.valueOf(record.getBodyValue()).contains("attempt 1 failed (status 500)")));
		List<String> forbidden = List.of(secret, failingSecret, "path-token", "CUST-1001");
		assertThat(output.getOut()).doesNotContain(forbidden);
		assertThat(spans.getFinishedSpanItems()).allSatisfy(span ->
				assertThat(span.getName() + span.getAttributes() + span.getEvents()).doesNotContain(forbidden));
		assertThat(logRecords.getFinishedLogRecordItems()).allSatisfy(record ->
				assertThat(record.getBodyValue() + " " + record.getAttributes()).doesNotContain(forbidden));
	}

	@Test
	void aDuplicateMessageDoesNotSendThePaymentTwice() throws Exception {
		String paymentId = createPayment("Duplicate message");

		kafkaTemplate.send(PaymentProcessingMessage.TOPIC, paymentId, new PaymentProcessingMessage(paymentId));
		kafkaTemplate.send(PaymentProcessingMessage.TOPIC, paymentId, new PaymentProcessingMessage(paymentId));

		awaitStatus(paymentId, "COMPLETED");
		await().during(Duration.ofMillis(500)).untilAsserted(() ->
				externalSystems.verify(1, postRequestedFor(urlEqualTo("/payment-orders"))
						.withHeader("Idempotency-Key", equalTo(paymentId))));
		assertThat(jdbc.sql("SELECT count(*) FROM payment_audit WHERE payment_id = ?::uuid").param(paymentId)
				.query(Integer.class).single()).isEqualTo(3);
	}

	// The request's correlation ID travels in the message header, so the consumer's call is correlated too.
	@Test
	void correlationIdFollowsThePaymentThroughKafka() throws Exception {
		String paymentId = createPayment("Correlated execution");

		mockMvc.perform(post("/payments/{id}/execute", paymentId).header("X-Correlation-Id", "corr-episode-17").with(customer()))
				.andExpect(status().isAccepted());

		await().untilAsserted(() -> externalSystems.verify(postRequestedFor(urlEqualTo("/payment-orders"))
				.withHeader("Idempotency-Key", equalTo(paymentId))
				.withHeader("X-Correlation-Id", equalTo("corr-episode-17"))));
	}

	@Test
	void executingTwiceIs409() throws Exception {
		String paymentId = createPayment("Invoice 12345");
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");

		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(customer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Payment " + paymentId + " is COMPLETED and cannot become PROCESSING"));
	}

	// The decline happens after the 202, so the caller no longer gets a 422: the payment just stays CREATED.
	@Test
	void paymentTheAuthorizationSystemDeclinesStaysCreated() throws Exception {
		String paymentId = createPayment("ACC-70001", "250.00", "Declined by authorization");

		execute(paymentId);

		await().untilAsserted(() -> externalSystems.verify(postRequestedFor(urlEqualTo("/authorizations"))
				.withRequestBody(matchingJsonPath("$[?(@.creditorAccount == 'ACC-70001')]"))));
		assertThat(storedStatus(paymentId)).isEqualTo("CREATED");
	}

	// Creating it is fine: limits depend on what the account has spent by the time the money moves.
	@Test
	void paymentOverTheLimitStaysAuthorized() throws Exception {
		String paymentId = createPayment("ACC-20001", "10000.01", "Over the limit");

		execute(paymentId);

		await().untilAsserted(() -> externalSystems.verify(postRequestedFor(urlEqualTo("/limit-checks"))
				.withRequestBody(matchingJsonPath("$[?(@.amount > 10000)]"))));
		assertThat(storedStatus(paymentId)).isEqualTo("AUTHORIZED");
		externalSystems.verify(0, postRequestedFor(urlEqualTo("/payment-orders"))
				.withHeader("Idempotency-Key", equalTo(paymentId)));
	}

	@Test
	void paymentAtTheLimitCompletes() throws Exception {
		String paymentId = createPayment("ACC-20001", "10000.00", "At the limit");

		execute(paymentId);

		awaitStatus(paymentId, "COMPLETED");
	}

	@Test
	void executingAnUnknownPaymentIs404() throws Exception {
		mockMvc.perform(post("/payments/{id}/execute", UUID.randomUUID()).with(customer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void cancelsACreatedPaymentAndStoresIt() throws Exception {
		String paymentId = createPayment("Invoice 12345");

		mockMvc.perform(post("/payments/{id}/cancel", paymentId).with(customer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(get("/payments/{id}", paymentId).with(customer()))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void cancellingACompletedPaymentIs409() throws Exception {
		String paymentId = createPayment("Invoice 12345");
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");

		mockMvc.perform(post("/payments/{id}/cancel", paymentId).with(customer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Payment " + paymentId + " is COMPLETED and cannot become CANCELLED"));
		mockMvc.perform(get("/payments/{id}", paymentId).with(customer()))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void cancelledPaymentCannotBeExecuted() throws Exception {
		String paymentId = createPayment("Invoice 12345");
		mockMvc.perform(post("/payments/{id}/cancel", paymentId).with(customer())).andExpect(status().isOk());

		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(customer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Payment " + paymentId + " is CANCELLED and cannot become PROCESSING"));
	}

	@Test
	void cancellingAnUnknownPaymentIs404() throws Exception {
		mockMvc.perform(post("/payments/{id}/cancel", UUID.randomUUID()).with(customer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void retryWithTheSameIdempotencyKeyReturnsTheSamePayment() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = paymentWithReference("Idempotent retry");

		String first = createWithKey(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String retry = createWithKey(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertThat((String) JsonPath.read(retry, "$.paymentId")).isEqualTo(JsonPath.read(first, "$.paymentId"));
		assertThat(paymentsWithReference("Idempotent retry")).isEqualTo(1);
	}

	@Test
	void sameIdempotencyKeyWithADifferentRequestIs422() throws Exception {
		String key = UUID.randomUUID().toString();
		createWithKey(key, paymentWithReference("Original")).andExpect(status().isCreated());

		createWithKey(key, paymentWithReference("Something else"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("Idempotency-Key was already used for a different request"));
	}

	// The real race: the same request sent eight times at once. PostgreSQL's primary key on the
	// idempotency table lets exactly one through; every other answer is that payment or a 409.
	@Test
	void concurrentDuplicatesCreateOnePayment() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = paymentWithReference("Concurrent duplicate");

		List<Callable<MvcResult>> requests = Collections.nCopies(8, () -> createWithKey(key, body).andReturn());
		List<MvcResult> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			for (Future<MvcResult> f : pool.invokeAll(requests)) {
				results.add(f.get());
			}
		}

		Set<String> paymentIds = new HashSet<>();
		for (MvcResult result : results) {
			int status = result.getResponse().getStatus();
			assertThat(status).isIn(201, 409);
			if (status == 201) {
				paymentIds.add(JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId"));
			}
		}
		assertThat(paymentIds).hasSize(1);
		assertThat(paymentsWithReference("Concurrent duplicate")).isEqualTo(1);
	}

	// 10 payments a minute (application.properties). The 11th is refused before anything is looked up or stored.
	@Test
	void theEleventhPaymentInAMinuteIs429AndCreatesNoPayment() throws Exception {
		for (int i = 0; i < 10; i++) {
			createWithKey(UUID.randomUUID().toString(), paymentWithReference("Rate limited"))
					.andExpect(status().isCreated());
		}
		int accountLookups = externalSystems.findAll(getRequestedFor(urlEqualTo("/accounts/ACC-10001"))).size();

		createWithKey(UUID.randomUUID().toString(), paymentWithReference("Rate limited"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", matchesPattern("[1-6]")))
				.andExpect(header().string("RateLimit", containsString("\"payments\";r=0")))
				.andExpect(header().string("RateLimit-Policy", containsString("\"payments\";q=10;w=60")))
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		assertThat(paymentsWithReference("Rate limited")).isEqualTo(10);
		assertThat(externalSystems.findAll(getRequestedFor(urlEqualTo("/accounts/ACC-10001")))).hasSize(accountLookups);
		mockMvc.perform(get("/actuator/prometheus"))
				// At least 1: the retry test may have been refused here first.
				.andExpect(content().string(matchesPattern("(?s).*rate_limit_rejected_total\\{limit=\"payments\"} [1-9].*")));
		// Another customer still has their own 10.
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content(paymentWithReference("Rate limited")
								.replace("ACC-10001", "ACC-30001")).with(otherCustomer()))
				.andExpect(status().isCreated());
	}

	// A retry is a request like any other: the limit protects the service, and a retry costs it a request too. A script
	// retrying one key in a loop is exactly what the limit is for. A client that retries with backoff stays far below it.
	@Test
	void retriesWithTheSameIdempotencyKeyCountTowardsThePaymentLimit() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = paymentWithReference("Retried until limited");
		for (int i = 0; i < 10; i++) {
			createWithKey(key, body).andExpect(status().isCreated());
		}

		createWithKey(key, body).andExpect(status().isTooManyRequests());

		assertThat(paymentsWithReference("Retried until limited")).isEqualTo(1);
	}

	private ResultActions createWithKey(String key, String body) throws Exception {
		return mockMvc.perform(post("/payments").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body).with(customer()));
	}

	private int paymentsWithReference(String reference) {
		return jdbc.sql("SELECT count(*) FROM payment WHERE reference = ?").param(reference).query(Integer.class).single();
	}

	// Each test uses its own reference, so row counts don't see payments from other tests in the same database.
	private static String paymentWithReference(String reference) {
		return """
				{
				  "sourceAccountId": "ACC-10001",
				  "destinationAccountId": "ACC-20001",
				  "amount": 250.00,
				  "currency": "EUR",
				  "reference": "%s"
				}
				""".formatted(reference);
	}

	@Test
	void missingIdempotencyKeyIs400AndCreatesNothing() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
						.content(paymentWithReference("No idempotency key")).with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Required header 'Idempotency-Key' is not present."));

		assertThat(paymentsWithReference("No idempotency key")).isZero();
	}

	@Test
	void rejectsMissingFieldsWith400() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}").with(customer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.title").value("Bad Request"));
	}

	@Test
	void correlationIdIsReturnedAndPassedToTheAccountSystem() throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.header("X-Correlation-Id", "corr-episode-15").contentType(MediaType.APPLICATION_JSON)
						.content(paymentWithReference("Correlated")).with(customer()))
				.andExpect(status().isCreated())
				.andExpect(header().string("X-Correlation-Id", "corr-episode-15"));

		externalSystems.verify(getRequestedFor(urlEqualTo("/accounts/ACC-10001"))
				.withHeader("X-Correlation-Id", equalTo("corr-episode-15")));
	}

	@Test
	void malformedCorrelationIdIsReplaced() throws Exception {
		mockMvc.perform(get("/payments/" + UUID.randomUUID()).header("X-Correlation-Id", "<script>").with(customer()))
				.andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
	}

	@Test
	void prometheusSeesPaymentMetrics() throws Exception {
		String paymentId = createPayment("Metrics");
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");

		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(allOf(
						containsString("payments_total{status=\"created\"}"),
						containsString("payments_total{status=\"authorized\"}"),
						containsString("payments_total{status=\"processing\"}"),
						containsString("payments_total{status=\"completed\"}"),
						containsString("payment_execution_duration_seconds_count"),
						containsString("resilience4j_circuitbreaker_state{name=\"payment-system\",state=\"closed\"} 1.0"))));
	}

	// What the alert rules read (docs/episodes/bonus-08). Prometheus asks for OpenMetrics: only that format has exemplars.
	@Test
	void prometheusSeesTheSloInputsAndAnExemplarLinkingToATrace() throws Exception {
		String paymentId = createPayment("SLO");
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");

		mockMvc.perform(get("/actuator/prometheus").accept(MediaType.parseMediaType("application/openmetrics-text;version=1.0.0")))
				.andExpect(status().isOk())
				.andExpect(content().string(allOf(
						// The latency SLI's bucket, with the trace ID of a request that landed in it.
						matchesPattern("(?s).*http_server_requests_seconds_bucket\\{[^}]*method=\"POST\"[^}]*uri=\"/payments\",le=\"0\\.5\"} \\d+"
								+ " # \\{span_id=\"[0-9a-f]{16}\",trace_id=\"[0-9a-f]{32}\"}.*"),
						matchesPattern("(?s).*payment_executions_total\\{result=\"completed\"} [1-9].*"),
						containsString("payment_executions_total{result=\"error\"} "),
						containsString("payments_processing_oldest_age_seconds"),
						containsString("payment_events_pending"))));
	}

	// One payment, one trace: the request, the message on payment-processing, the consumer, and its call to the
	// payment system, each a span with its parent, as Tempo would show it.
	@Test
	void executingAPaymentIsOneTraceFromTheRequestToThePaymentSystem() throws Exception {
		String paymentId = completedPayment("Traced execution");

		String traceId = executeRequestSpan(paymentId).getTraceId();
		await().untilAsserted(() -> assertThat(spansOf(traceId)).extracting(SpanData::getName).contains(
				"http post /payments/{paymentId}/execute", "payment-processing send", "payment-processing process"));
		assertThat(spansOf(traceId)).anySatisfy(span -> assertThat(span.getAttributes().get(stringKey("http.url")))
				.isEqualTo("/payment-orders"));
	}

	// The relay sends later, on its own thread, in a run no request started. The traceparent stored with the outbox
	// row puts the send back in the trace of the change: its parent is the span that recorded the event.
	@Test
	void theEventIsSentInTheTraceOfTheChangeThatRecordedIt() throws Exception {
		String paymentId = completedPayment("Traced event");
		paymentEvents(paymentId, 1);

		String[] traceparent = jdbc.sql("SELECT trace_context FROM payment_outbox WHERE payment_id = ?::uuid")
				.param(paymentId).query(String.class).single().split("-");
		String traceId = executeRequestSpan(paymentId).getTraceId();
		assertThat(traceparent[1]).isEqualTo(traceId);
		await().untilAsserted(() -> {
			SpanData relay = spansOf(traceId).stream().filter(span -> span.getName().equals("payment-events relay"))
					.findFirst().orElseThrow();
			assertThat(relay.getParentSpanId()).isEqualTo(traceparent[2]);
			assertThat(spansOf(traceId)).anySatisfy(span -> {
				assertThat(span.getName()).isEqualTo("payment-events send");
				assertThat(span.getParentSpanId()).isEqualTo(relay.getSpanId());
			});
		});
	}

	// Episode 15's rule covers traces too. Span names and attributes use URI templates (/accounts/{accountNumber}),
	// and a failed call records no URL. Checked over every span the tests so far produced, plus a rejected payment.
	@Test
	void noSpanCarriesAnAccountOrCustomerId() throws Exception {
		String paymentId = completedPayment("Not in any span");
		// The event carries the reference (docs/episodes/bonus-10): wait until it has been sent and read by the statement.
		String traceId = executeRequestSpan(paymentId).getTraceId();
		await().untilAsserted(() -> assertThat(spansOf(traceId)).extracting(SpanData::getName)
				.contains("payment-events send", "payment-events process"));
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content("""
						{ "sourceAccountId": "ACC-99999", "destinationAccountId": "ACC-20001", "amount": 250.00,
						  "currency": "EUR", "reference": "Not in any span either" }
						""").with(customer()))
				.andExpect(status().isBadRequest());

		assertThat(spans.getFinishedSpanItems()).isNotEmpty().allSatisfy(span ->
				assertThat(span.getName() + span.getAttributes() + span.getEvents())
						.doesNotContain("ACC-10001", "ACC-20001", "ACC-99999", "CUST-1001", "Not in any span"));
	}

	// Both IDs in every line: correlationId for the caller and support, traceId to open the trace in Tempo.
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void aLogLineInsideARequestCarriesTheTraceId(CapturedOutput output) throws Exception {
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.header("X-Correlation-Id", "corr-bonus-05").contentType(MediaType.APPLICATION_JSON)
						.content(paymentWithReference("Logged")).with(customer()))
				.andExpect(status().isCreated());

		String line = output.getOut().lines().filter(l -> l.contains("\"correlationId\":\"corr-bonus-05\""))
				.findFirst().orElseThrow();
		String traceId = JsonPath.read(line, "$.traceId");
		assertThat((String) JsonPath.read(line, "$.spanId")).matches("[0-9a-f]{16}");
		assertThat(spansOf(traceId)).extracting(SpanData::getName).contains("http post /payments");
	}

	// What Loki receives for a log line inside a request: the request's trace and span, and its correlationId.
	@Test
	void aLogLineInsideARequestIsExportedWithItsTraceAndCorrelationId() throws Exception {
		String paymentId = JsonPath.read(mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.header("X-Correlation-Id", "corr-bonus-06").contentType(MediaType.APPLICATION_JSON)
						.content(paymentWithReference("Exported")).with(customer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.paymentId");

		LogRecordData created = exportedLine("Payment " + paymentId + " created");
		assertThat(created.getAttributes().get(stringKey("correlationId"))).isEqualTo("corr-bonus-06");
		assertThat(created.getSpanContext().getSpanId()).matches("[0-9a-f]{16}");
		assertThat(spansOf(created.getSpanContext().getTraceId())).extracting(SpanData::getName).contains("http post /payments");
	}

	// The consumer's line, on another thread and after Kafka, is in the trace of the request that queued the payment:
	// in Grafana, one trace ID finds both.
	@Test
	void theConsumersLogLineCarriesTheTraceOfTheRequestThatQueuedThePayment() throws Exception {
		String paymentId = completedPayment("Exported from the consumer");

		String queued = exportedLine("Payment " + paymentId + " queued for processing").getSpanContext().getTraceId();
		String completed = exportedLine("Payment " + paymentId + " moved from PROCESSING to COMPLETED").getSpanContext().getTraceId();
		assertThat(completed).isEqualTo(queued).isEqualTo(executeRequestSpan(paymentId).getTraceId());
	}

	// Episode 15's rule covers the log store: the same lines, kept and searchable by more people. Checked over every
	// record the tests so far exported, plus a rejected payment.
	@Test
	void noExportedLogRecordCarriesAnAccountIdCustomerIdOrReference() throws Exception {
		completedPayment("Not in any log");
		// The event carries the reference (docs/episodes/bonus-10): wait until the relay sent it and the statement has it.
		await().until(() -> jdbc.sql("SELECT count(*) FROM statement_line WHERE reference = 'Not in any log'")
				.query(Integer.class).single() == 1);
		mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content("""
						{ "sourceAccountId": "ACC-99999", "destinationAccountId": "ACC-20001", "amount": 250.00,
						  "currency": "EUR", "reference": "Not in any log either" }
						""").with(customer()))
				.andExpect(status().isBadRequest());

		await().until(() -> !logRecords.getFinishedLogRecordItems().isEmpty());
		assertThat(logRecords.getFinishedLogRecordItems()).allSatisfy(record ->
				assertThat(record.getBodyValue() + " " + record.getAttributes())
						.doesNotContain("ACC-10001", "ACC-20001", "ACC-99999", "CUST-1001", "Not in any log"));
	}

	// The OTLP exporter is there and failing (port 9, see externalSystemUrls); the payment completes, and its lines
	// still reach the console and every other exporter.
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void aPaymentCompletesWhileLokiIsDown(CapturedOutput output) throws Exception {
		assertThat(lokiExporters).hasSize(1);

		String paymentId = completedPayment("Loki down");

		await().untilAsserted(() -> assertThat(output.getOut()).contains("Payment " + paymentId + " moved from PROCESSING to COMPLETED"));
		exportedLine("Payment " + paymentId + " moved from PROCESSING to COMPLETED");
	}

	// Records leave in batches, up to a second after the line was logged.
	private LogRecordData exportedLine(String body) {
		return await().until(() -> logRecords.getFinishedLogRecordItems().stream()
				.filter(record -> body.equals(record.getBodyValue().asString())).findFirst(), Optional::isPresent).orElseThrow();
	}

	private ResultActions refund(String paymentId, String amount, RequestPostProcessor caller) throws Exception {
		return mockMvc.perform(post("/payments/{id}/refunds", paymentId).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("{ \"amount\": %s, \"currency\": \"EUR\" }".formatted(amount))
				.with(caller));
	}

	private String completedPayment(String reference) throws Exception {
		String paymentId = createPayment(reference);
		execute(paymentId);
		awaitStatus(paymentId, "COMPLETED");
		return paymentId;
	}

	// Everything on payment-events about this payment, as another service would read it, once exactly this many have arrived.
	// That service was built on schema v1 and never updated: each event is read with v1 (ReleasedSchemaV1).
	private List<GenericRecord> paymentEvents(String paymentId, int expected) {
		Properties asV1 = new Properties();
		asV1.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class);
		asV1.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		asV1.put(AvroSerdeConfig.AVRO_DATUM_PROVIDER, ReleasedSchemaV1.class.getName());
		List<GenericRecord> received = new ArrayList<>();
		try (Consumer<?, ?> consumer = consumerFactory.createConsumer("events-test-" + UUID.randomUUID(), null, null, asV1)) {
			consumer.subscribe(List.of("payment-events"));
			await().during(Duration.ofMillis(500)).untilAsserted(() -> {
				for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(100))) {
					if (paymentId.equals(record.key())) {
						received.add((GenericRecord) record.value());
					}
				}
				assertThat(received).hasSize(expected);
			});
		}
		return received;
	}

	// The server span of POST /payments/{id}/execute for this payment: its trace is the payment's execution.
	private SpanData executeRequestSpan(String paymentId) {
		return await().until(() -> spans.getFinishedSpanItems().stream()
				.filter(span -> ("/payments/" + paymentId + "/execute").equals(span.getAttributes().get(stringKey("http.url"))))
				.findFirst(), Optional::isPresent).orElseThrow();
	}

	private List<SpanData> spansOf(String traceId) {
		return spans.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
	}

	private static RequestPostProcessor customer() {
		return jwt().jwt(token -> token.subject("CUST-1001"));
	}

	private static RequestPostProcessor otherCustomer() {
		return jwt().jwt(token -> token.subject("CUST-2002"));
	}

	// What an identity provider would issue, signed with the given key. No expiry: a test token.
	private static String signedToken(String key) throws Exception {
		SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
				new JWTClaimsSet.Builder().subject("CUST-1001").build());
		token.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
		return token.serialize();
	}

	private void execute(String paymentId) throws Exception {
		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(customer())).andExpect(status().isAccepted());
	}

	private void awaitStatus(String paymentId, String expected) {
		await().untilAsserted(() -> assertThat(storedStatus(paymentId)).isEqualTo(expected));
	}

	private String storedStatus(String paymentId) {
		return jdbc.sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single();
	}

	private String createPayment(String reference) throws Exception {
		return createPayment("ACC-20001", "250.00", reference);
	}

	private String createPayment(String destinationAccountId, String amount, String reference) throws Exception {
		String response = mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "%s",
						  "amount": %s,
						  "currency": "EUR",
						  "reference": "%s"
						}
						""".formatted(destinationAccountId, amount, reference)).with(customer()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.paymentId");
	}

	private String merchantUrl(String path) {
		return "https://127.0.0.1:" + externalSystems.getHttpsPort() + path;
	}

	private ResultActions registerWebhook(String url, RequestPostProcessor caller) throws Exception {
		return mockMvc.perform(post("/webhooks").contentType(MediaType.APPLICATION_JSON)
				.content(JsonMapper.shared().writeValueAsString(Map.of("url", url))).with(caller));
	}

	private List<LoggedRequest> webhooksReceived(String path, String paymentId) {
		return externalSystems.findAll(postRequestedFor(urlEqualTo(path))
				.withRequestBody(matchingJsonPath("$.data.paymentId", equalTo(paymentId))));
	}

	// What a receiver computes from what arrived, with their copy of the secret.
	private static String signature(String secret, LoggedRequest request) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		mac.update((request.getHeader("Webhook-Id") + "." + request.getHeader("Webhook-Timestamp") + ".")
				.getBytes(StandardCharsets.UTF_8));
		return "v1=" + HexFormat.of().formatHex(mac.doFinal(request.getBody()));
	}

	private static HttpResponse<String> http(HttpRequest.Builder request) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}
}
