package com.example.payment;

import com.example.payment.infrastructure.adapter.primary.agent.ScriptedChatModel;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operations agent in the running service (docs/episodes/bonus-14): the agent profile on, the real database,
 * Kafka and WireMock as in PaymentEndToEndTest, and a scripted model instead of a real one. No model's chat
 * auto-configuration runs (spring.ai.model.chat=none); the scripted model is the only ChatModel.
 *
 * The scheduled reconciliation waits an hour, so it never settles a payment a test has backdated. Whether it looks
 * stalled is the lock row each test writes, as a run would have.
 */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@ActiveProfiles({"local", "agent"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OperationsAgentEndToEndTest.Model.class})
class OperationsAgentEndToEndTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class Model {

		@Bean
		ScriptedChatModel scriptedChatModel() {
			return new ScriptedChatModel();
		}
	}

	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
			.build();

	@DynamicPropertySource
	static void externalSystemUrls(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", externalSystems::baseUrl);
		registry.add("authorization-system.url", externalSystems::baseUrl);
		registry.add("limit-system.url", externalSystems::baseUrl);
		registry.add("payment-system.url", externalSystems::baseUrl);
		registry.add("spring.cloud.openfeign.client.config.payment-system.read-timeout", () -> "300");
		registry.add("payment.reconciliation.interval", () -> "1h");
		registry.add("management.tracing.export.otlp.enabled", () -> "false");
		registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> "http://localhost:9/otlp/v1/logs");
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ScriptedChatModel model;

	@Autowired
	ChatModel chatModel;

	@BeforeEach
	void forgetEarlierAnswersAndRunReconciliationJustNow() {
		model.reset();
		jdbc.sql("""
				INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES ('reconciliation', now(), now() - interval '10 seconds', 'test')
				ON CONFLICT (name) DO UPDATE SET lock_until = excluded.lock_until, locked_at = excluded.locked_at""").update();
	}

	@Test
	void theScriptedModelIsTheOnlyModel() {
		assertThat(chatModel).isSameAs(model);
	}

	// The answer to its payment order was lost (TIMEOUT), and it has been PROCESSING for five minutes: the agent
	// proposes to reconcile it now, and nothing happens to the payment.
	@Test
	void anOperatorGetsADiagnosisAndAProposalAndThePaymentIsUntouched() throws Exception {
		String paymentId = processingPayment();
		backdate(paymentId, "5 minutes");
		model.answering("""
				{"cause": "PAYMENT_SYSTEM_NOT_ANSWERING", "evidenceIds": ["E1", "E3", "E7"], "confidence": "MEDIUM",
				 "summary": "Reconciliation ran, and the payment has been PROCESSING for 5 minutes."}""", """
				{"action": "RECONCILE_NOW", "escalationText": null}""");

		String location = mockMvc.perform(post("/operations/investigations").with(operator())
						.contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\": \"" + paymentId + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.startedBy").value("OPS-0001"))
				.andExpect(jsonPath("$.evidence[0].value").value("PROCESSING"))
				.andExpect(jsonPath("$.diagnosis.cause").value("PAYMENT_SYSTEM_NOT_ANSWERING"))
				.andExpect(jsonPath("$.proposal.action").value("RECONCILE_NOW"))
				.andReturn().getResponse().getHeader("Location");

		mockMvc.perform(get(location).with(operator()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.proposal.action").value("RECONCILE_NOW"));
		assertThat(storedStatus(paymentId)).isEqualTo("PROCESSING");
		assertThat(model.prompts()).hasSize(2);
	}

	@Test
	void aYoungPaymentNeedsNoModel() throws Exception {
		String paymentId = processingPayment();

		mockMvc.perform(post("/operations/investigations").with(operator())
						.contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\": \"" + paymentId + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.diagnosis.cause").value("WITHIN_THRESHOLD"))
				.andExpect(jsonPath("$.allowedActions[0]").value("NO_ACTION"));
		assertThat(model.prompts()).isEmpty();
	}

	@Test
	void customersCannotStartOne() throws Exception {
		mockMvc.perform(post("/operations/investigations").with(jwt().jwt(token -> token.subject("CUST-1001")))
						.contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\": \"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUnknownPaymentIs404AndSoIsAnUnknownInvestigation() throws Exception {
		mockMvc.perform(post("/operations/investigations").with(operator())
						.contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\": \"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/operations/investigations/{id}", UUID.randomUUID()).with(operator()))
				.andExpect(status().isNotFound());
	}

	private static RequestPostProcessor operator() {
		return jwt().jwt(token -> token.subject("OPS-0001")).authorities(new SimpleGrantedAuthority("SCOPE_payments.operate"));
	}

	private String processingPayment() throws Exception {
		String response = mockMvc.perform(post("/payments").header("Idempotency-Key", UUID.randomUUID().toString())
						.with(jwt().jwt(token -> token.subject("CUST-1001")))
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"sourceAccountId": "ACC-10001", "destinationAccountId": "ACC-20001", "amount": 250.00,
								 "currency": "EUR", "reference": "TIMEOUT investigated by the agent"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String paymentId = com.jayway.jsonpath.JsonPath.read(response, "$.paymentId");
		mockMvc.perform(post("/payments/{id}/execute", paymentId).with(jwt().jwt(token -> token.subject("CUST-1001"))))
				.andExpect(status().isAccepted());
		await().untilAsserted(() -> assertThat(storedStatus(paymentId)).isEqualTo("PROCESSING"));
		return paymentId;
	}

	// The agent reads the age from the audit trail; the payment row moves with it, as it would have.
	private void backdate(String paymentId, String interval) {
		jdbc.sql("UPDATE payment_audit SET occurred_at = occurred_at - ?::interval WHERE payment_id = ?::uuid")
				.param(interval).param(paymentId).update();
		jdbc.sql("UPDATE payment SET status_changed_at = status_changed_at - ?::interval WHERE id = ?::uuid")
				.param(interval).param(paymentId).update();
	}

	private String storedStatus(String paymentId) {
		return jdbc.sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single();
	}
}
