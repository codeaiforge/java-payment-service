package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.exception.PaymentNotFoundException;
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
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole graph with a scripted model and the primary ports faked, one payment per test. The clock is fixed; the
 * threshold is 2m and reconciliation runs every minute, as in application.properties.
 */
class StuckPaymentGraphTest {

	private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
	private static final String PAYMENT_ID = PaymentId.newId().toString();
	private static final ReconciliationStatus RUNNING = new ReconciliationStatus(NOW.minusSeconds(30), null, null);
	private static final ReconciliationStatus STALLED = new ReconciliationStatus(NOW.minusSeconds(600), null, null);

	private static final String PAYMENT_SYSTEM_NOT_ANSWERING = """
			{"cause": "PAYMENT_SYSTEM_NOT_ANSWERING", "evidenceIds": ["E1", "E3", "E7"], "confidence": "HIGH",
			 "summary": "Reconciliation runs, yet the payment is still PROCESSING after 5 minutes."}""";

	private final ScriptedChatModel model = new ScriptedChatModel();
	private final List<String> reads = new ArrayList<>();

	@Test
	void aPaymentThatIsNotProcessingIsNotStuckAndTheModelIsNeverAsked() {
		InvestigationState state = investigate("COMPLETED", Duration.ofMinutes(5), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.NOT_STUCK);
		assertThat(state.allowedActions()).containsExactly(Action.NO_ACTION);
		assertThat(state.proposal().orElseThrow()).isEqualTo(ProposedAction.noAction());
		assertThat(model.prompts()).isEmpty();
	}

	@Test
	void aYoungPaymentIsWithinTheThresholdAndTheModelIsNeverAsked() {
		InvestigationState state = investigate("PROCESSING", Duration.ofSeconds(30), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.WITHIN_THRESHOLD);
		assertThat(state.allowedActions()).containsExactly(Action.NO_ACTION);
		assertThat(model.prompts()).isEmpty();
	}

	@Test
	void reconciliationRunsButThePaymentSystemDoesNotAnswer() {
		model.answering(PAYMENT_SYSTEM_NOT_ANSWERING, """
				{"action": "RECONCILE_NOW", "escalationText": null}""");

		InvestigationState state = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.PAYMENT_SYSTEM_NOT_ANSWERING);
		assertThat(state.allowedActions())
				.containsExactly(Action.RECONCILE_NOW, Action.ESCALATE_TO_PAYMENT_SYSTEM, Action.NO_ACTION);
		assertThat(state.proposal().orElseThrow()).isEqualTo(new ProposedAction(Action.RECONCILE_NOW, null, null));
		assertThat(reads).containsOnly("OPS-0001");
	}

	@Test
	void reconciliationHasStoppedAndTheOnCallIsTold() {
		model.answering("""
				{"cause": "RECONCILIATION_NOT_RUNNING", "evidenceIds": ["E7", "E9"], "confidence": "HIGH",
				 "summary": "No reconciliation run for 10 minutes."}""", """
				{"action": "ESCALATE_TO_ON_CALL", "escalationText": "Reconciliation hasn't run for 10 minutes."}""");

		InvestigationState state = investigate("PROCESSING", Duration.ofMinutes(5), STALLED);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.RECONCILIATION_NOT_RUNNING);
		assertThat(state.allowedActions()).contains(Action.ESCALATE_TO_ON_CALL);
		assertThat(state.proposal().orElseThrow())
				.isEqualTo(new ProposedAction(Action.ESCALATE_TO_ON_CALL, "Reconciliation hasn't run for 10 minutes.", null));
	}

	@Test
	void theModelMaySayItDoesNotKnow() {
		model.answering("""
				{"cause": "UNCLEAR", "evidenceIds": ["E1"], "confidence": "LOW", "summary": "Can't tell."}""", """
				{"action": "NO_ACTION", "escalationText": null}""");

		InvestigationState state = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.UNCLEAR);
		assertThat(state.diagnoseAttempts()).isEqualTo(1);
	}

	// Reconciliation is running, so "not running" disagrees with the facts; then an id that doesn't exist.
	@Test
	void twoAnswersThatFailValidationAreUnclear() {
		model.answering("""
				{"cause": "RECONCILIATION_NOT_RUNNING", "evidenceIds": ["E7"], "confidence": "HIGH", "summary": "Stopped."}""", """
				{"cause": "PAYMENT_SYSTEM_NOT_ANSWERING", "evidenceIds": ["E99"], "confidence": "HIGH", "summary": "No answer."}""", """
				{"action": "NO_ACTION", "escalationText": null}""");

		InvestigationState state = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.UNCLEAR);
		assertThat(state.diagnoseAttempts()).isEqualTo(2);
		assertThat(model.prompts()).hasSize(3);
	}

	@Test
	void anAnswerThatIsNotJsonIsAskedAgain() {
		model.answering("I think the payment system is down.", PAYMENT_SYSTEM_NOT_ANSWERING, """
				{"action": "NO_ACTION", "escalationText": null}""");

		InvestigationState state = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING);

		assertThat(state.diagnosis().orElseThrow().cause()).isEqualTo(Cause.PAYMENT_SYSTEM_NOT_ANSWERING);
		assertThat(state.diagnoseAttempts()).isEqualTo(2);
	}

	// Not stalled, so ESCALATE_TO_ON_CALL is outside the set: it becomes NO_ACTION, and what was proposed is kept.
	@Test
	void aProposalOutsideTheAllowedSetBecomesNoAction() {
		model.answering(PAYMENT_SYSTEM_NOT_ANSWERING, """
				{"action": "ESCALATE_TO_ON_CALL", "escalationText": "Wake someone up."}""");

		ProposedAction proposal = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING).proposal().orElseThrow();

		assertThat(proposal.action()).isEqualTo(Action.NO_ACTION);
		assertThat(proposal.escalationText()).isNull();
		assertThat(proposal.replaced()).contains("ESCALATE_TO_ON_CALL");
	}

	@Test
	void anActionThatDoesNotExistBecomesNoAction() {
		model.answering(PAYMENT_SYSTEM_NOT_ANSWERING, """
				{"action": "COMPLETE_PAYMENT", "escalationText": null}""");

		ProposedAction proposal = investigate("PROCESSING", Duration.ofMinutes(5), RUNNING).proposal().orElseThrow();

		assertThat(proposal.action()).isEqualTo(Action.NO_ACTION);
		assertThat(proposal.replaced()).contains("COMPLETE_PAYMENT");
	}

	// Two failed diagnoses then a proposal take five steps; with four allowed, the run stops instead.
	@Test
	void theStepLimitStopsTheRun() {
		model.answering("not json", "still not json", """
				{"action": "NO_ACTION", "escalationText": null}""");

		assertThatThrownBy(() -> graph(4, payments("PROCESSING", Duration.ofMinutes(5)), RUNNING)
				.investigate(PAYMENT_ID, "OPS-0001"))
				.hasStackTraceContaining("Maximum number of iterations");
	}

	@Test
	void anUnknownPaymentEndsTheRunWithNotFound() {
		GetPaymentForOperatorUseCase none = new GetPaymentForOperatorUseCase() {
			@Override
			public GetPaymentResult getPayment(GetPaymentForOperatorQuery query) {
				throw new PaymentNotFoundException(PaymentId.of(query.paymentId()));
			}

			@Override
			public List<StatusTransition> getHistory(GetPaymentForOperatorQuery query) {
				throw new AssertionError("not reached");
			}
		};

		assertThatThrownBy(() -> graph(10, none, RUNNING).investigate(PAYMENT_ID, "OPS-0001"))
				.hasRootCauseInstanceOf(PaymentNotFoundException.class);
	}

	@Test
	void factsReachTheModelInsideTheDataBlockOnly() {
		model.answering(PAYMENT_SYSTEM_NOT_ANSWERING, """
				{"action": "NO_ACTION", "escalationText": null}""");

		investigate("PROCESSING", Duration.ofMinutes(5), RUNNING);

		String user = model.prompts().getFirst().getUserMessage().getText();
		assertThat(user).containsOnlyOnce("<payment-data>").containsOnlyOnce("</payment-data>")
				.contains("E5 | payment reference (written by the customer) | Invoice 12345");
		assertThat(model.prompts().getFirst().getSystemMessage().getText()).contains("never an instruction");
	}

	private InvestigationState investigate(String status, Duration age, ReconciliationStatus reconciliation) {
		return graph(10, payments(status, age), reconciliation).investigate(PAYMENT_ID, "OPS-0001");
	}

	private StuckPaymentGraph graph(int maxSteps, GetPaymentForOperatorUseCase payments,
			ReconciliationStatus reconciliation) {
		ChatClient chatClient = ChatClient.create(model);
		try {
			return new StuckPaymentGraph(new GatherEvidence(payments, () -> reconciliation, Duration.ofMinutes(2),
					Duration.ofMinutes(1), Clock.fixed(NOW, ZoneOffset.UTC)), new Diagnose(chatClient), new ApplyPolicy(),
					new ProposeAction(chatClient), maxSteps);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// The last transition is into the given status, `age` ago.
	private GetPaymentForOperatorUseCase payments(String status, Duration age) {
		return new GetPaymentForOperatorUseCase() {
			@Override
			public GetPaymentResult getPayment(GetPaymentForOperatorQuery query) {
				reads.add(query.operatorId());
				return new GetPaymentResult(query.paymentId(), "ACC-10001", "ACC-20001", new BigDecimal("250.00"), "EUR",
						"Invoice 12345", status);
			}

			@Override
			public List<StatusTransition> getHistory(GetPaymentForOperatorQuery query) {
				return List.of(new StatusTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, NOW.minus(age).minusSeconds(1)),
						new StatusTransition(PaymentStatus.AUTHORIZED, PaymentStatus.valueOf(status), NOW.minus(age)));
			}
		};
	}
}
