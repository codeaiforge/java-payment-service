package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.application.usecase.query.ReconciliationStatus;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Cause;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ApplyPolicy;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Diagnose;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Execute;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent against a real model (docs/episodes/bonus-16): each scenario in src/test/resources/agent-evals is one
 * payment, its history and the reconciliation status, with the cause and action a good on-call engineer would reach.
 * The graph runs as in production up to its pause; only the primary ports are faked from the scenario.
 *
 * Never part of verify (@Tag("eval")): ./mvnw -Peval verify, with the model running. It measures, it doesn't gate:
 * the test fails only if a scenario can't run, and prints the accuracy per expected cause, also to
 * target/agent-evals.txt.
 */
@Tag("eval")
class AgentEvalTest {

	record Scenario(String status, Long ageSeconds, String reference, Long lastRunSecondsAgo, String runningOn,
			Cause expectedCause, Action expectedAction) {
	}

	/** @param attempts diagnoses asked; 2 with UNCLEAR and no evidence means both answers failed validation */
	record Outcome(String name, Scenario scenario, Cause cause, Action action, String replaced, int attempts,
			boolean rejectedTwice, List<String> answers) {

		boolean causeRight() {
			return cause == scenario.expectedCause();
		}

		boolean actionRight() {
			return action == scenario.expectedAction();
		}
	}

	// Only Spring AI's chat models and what they need: no database, Kafka or web server.
	@SpringBootConfiguration
	@ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
			RestClientAutoConfiguration.class, SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
			OllamaApiAutoConfiguration.class, OllamaChatAutoConfiguration.class, AnthropicChatAutoConfiguration.class,
			OpenAiChatAutoConfiguration.class})
	static class Models {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void scenarios() throws Exception {
		String[] profiles = System.getProperty("eval.profiles", "agent").split(",");
		try (ConfigurableApplicationContext models = new SpringApplicationBuilder(Models.class)
				.web(WebApplicationType.NONE).profiles(profiles).run("--spring.docker.compose.enabled=false")) {
			ChatModel model = models.getBean(ChatModel.class);
			String modelName = model.getDefaultOptions().getModel();
			List<Outcome> outcomes = new ArrayList<>();
			String only = System.getProperty("eval.only", "");
			for (Resource resource : scenarioFiles()) {
				if (!resource.getFilename().contains(only)) {
					continue;
				}
				Scenario scenario = JSON.readValue(resource.getContentAsByteArray(), Scenario.class);
				List<String> answers = new ArrayList<>();
				// The raw answers, for the report of misses: an eval runs locally, never against real payments.
				CallAdvisor recording = new CallAdvisor() {
					@Override
					public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
						ChatClientResponse response = chain.nextCall(request);
						answers.add(response.chatResponse() == null ? "(none)" : response.chatResponse().getResult().getOutput().getText());
						return response;
					}

					@Override
					public String getName() {
						return "eval-recording";
					}

					@Override
					public int getOrder() {
						return 0;
					}
				};
				InvestigationState state = graph(ChatClient.builder(model).defaultAdvisors(recording).build(), scenario)
						.propose(UUID.randomUUID().toString(), PaymentId.newId().toString(), "OPS-EVAL");
				ProposedAction proposal = state.proposal().orElseThrow();
				Diagnosis diagnosis = state.diagnosis().orElseThrow();
				outcomes.add(new Outcome(resource.getFilename().replace(".json", ""), scenario, diagnosis.cause(),
						proposal.action(), proposal.replaced(), state.diagnoseAttempts(),
						diagnosis.cause() == Cause.UNCLEAR && diagnosis.evidenceIds().isEmpty(), answers));
			}
			String report = report(modelName, String.join(",", profiles), outcomes);
			System.out.println(report);
			Files.writeString(Path.of("target", "agent-evals.txt"), report);
			assertThat(outcomes).hasSizeGreaterThanOrEqualTo(only.isEmpty() ? 12 : 1);
		}
	}

	private static List<Resource> scenarioFiles() throws Exception {
		return Arrays.stream(new PathMatchingResourcePatternResolver().getResources("classpath:agent-evals/*.json"))
				.sorted(Comparator.comparing(Resource::getFilename)).toList();
	}

	private static StuckPaymentGraph graph(ChatClient chatClient, Scenario scenario) throws Exception {
		Instant now = Instant.now();
		ReconciliationStatus reconciliation = scenario.lastRunSecondsAgo() == null
				? new ReconciliationStatus(null, null, null)
				: new ReconciliationStatus(now.minusSeconds(scenario.lastRunSecondsAgo()), scenario.runningOn(),
						scenario.runningOn() == null ? null : now.plusSeconds(60));
		Duration threshold = Duration.ofMinutes(2);
		GetPaymentForOperatorUseCase payments = new GetPaymentForOperatorUseCase() {
			@Override
			public GetPaymentResult getPayment(GetPaymentForOperatorQuery query) {
				return new GetPaymentResult(query.paymentId(), "ACC-10001", "ACC-20001", new BigDecimal("250.00"), "EUR",
						scenario.reference(), scenario.status());
			}

			@Override
			public List<StatusTransition> getHistory(GetPaymentForOperatorQuery query) {
				if (scenario.ageSeconds() == null) {
					return List.of();
				}
				Instant since = now.minusSeconds(scenario.ageSeconds());
				return List.of(new StatusTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, since.minusSeconds(2)),
						new StatusTransition(PaymentStatus.AUTHORIZED, PaymentStatus.valueOf(scenario.status()), since));
			}
		};
		return new StuckPaymentGraph(new GatherEvidence(payments, () -> reconciliation, threshold, Duration.ofMinutes(1),
				Clock.systemUTC()), new Diagnose(chatClient), new ApplyPolicy(), new ProposeAction(chatClient),
				new Execute(payments, (command, t) -> {
					throw new AssertionError("Nothing runs in an eval");
				}, threshold), new MemorySaver(), 10, ObservationRegistry.NOOP);
	}

	private static String report(String model, String profiles, List<Outcome> outcomes) {
		StringBuilder out = new StringBuilder();
		out.append("Agent evals, %s, model %s (profiles %s), %d scenarios%n%n".formatted(LocalDate.now(), model, profiles,
				outcomes.size()));
		out.append("%-30s %9s %8s %8s%n".formatted("expected cause", "scenarios", "cause", "action"));
		Map<Cause, List<Outcome>> byCause = new TreeMap<>();
		outcomes.forEach(o -> byCause.computeIfAbsent(o.scenario().expectedCause(), c -> new ArrayList<>()).add(o));
		byCause.forEach((cause, list) -> out.append("%-30s %9d %8s %8s%n".formatted(cause, list.size(),
				ratio(list.stream().filter(Outcome::causeRight).count(), list.size()),
				ratio(list.stream().filter(Outcome::actionRight).count(), list.size()))));
		out.append("%-30s %9d %8s %8s%n%n".formatted("all", outcomes.size(),
				ratio(outcomes.stream().filter(Outcome::causeRight).count(), outcomes.size()),
				ratio(outcomes.stream().filter(Outcome::actionRight).count(), outcomes.size())));
		outcomes.stream().filter(o -> !o.causeRight() || !o.actionRight()).forEach(o -> out.append(
				"miss %s: %s / %s, expected %s / %s%s%n".formatted(o.name(), o.cause(), o.action(),
						o.scenario().expectedCause(), o.scenario().expectedAction(),
						(o.rejectedTwice() ? " (both diagnoses failed validation)" : "")
								+ (o.replaced() == null ? "" : " (replaced: " + o.replaced() + ")")
								+ o.answers().stream().map(a -> "\n    answer: " + a.replaceAll("\\s+", " ")).reduce("", String::concat))));
		return out.toString();
	}

	private static String ratio(long right, int of) {
		return "%d/%d".formatted(right, of);
	}
}
