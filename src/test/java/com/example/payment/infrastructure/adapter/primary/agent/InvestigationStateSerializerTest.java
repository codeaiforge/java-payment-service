package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Cause;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Confidence;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A checkpoint is JSON in PostgreSQL; what comes back must be the same records that went in. */
class InvestigationStateSerializerTest {

	private final InvestigationStateSerializer serializer = new InvestigationStateSerializer();

	@Test
	void everyKeyReadsBackAsTheRecordItWasWrittenFrom() {
		Instant at = Instant.parse("2026-11-25T12:00:00Z");
		Evidence evidence = new Evidence("p-1", "PROCESSING", at, Duration.ofMinutes(5), new BigDecimal("250.00"), "EUR",
				"Invoice 12345", List.of(new StatusTransition(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING, at)), at,
				null, false, Duration.ofMinutes(2), Duration.ofMinutes(1));
		InvestigationState state = new InvestigationState(Map.of(
				InvestigationState.PAYMENT_ID, "p-1",
				InvestigationState.STARTED_BY, "OPS-0001",
				InvestigationState.EVIDENCE, evidence,
				InvestigationState.DIAGNOSIS, new Diagnosis(Cause.UNCLEAR, List.of("E1"), Confidence.LOW, "Can't tell."),
				InvestigationState.DIAGNOSE_ATTEMPTS, 2,
				InvestigationState.ALLOWED_ACTIONS, List.of(Action.RECONCILE_NOW, Action.NO_ACTION),
				InvestigationState.PROPOSAL, new ProposedAction(Action.NO_ACTION, null, "proposed X"),
				InvestigationState.APPROVER, "OPS-0002"));

		InvestigationState read = serializer.readDataFromString(serializer.writeDataAsString(state));

		assertThat(read.data()).isEqualTo(state.data());
		assertThat(read.allowedActions()).first().isInstanceOf(Action.class);
	}

	@Test
	void anUnknownKeyIsAnError() {
		assertThatThrownBy(() -> serializer.readDataFromString("{\"somethingNew\": 1}"))
				.hasMessageContaining("somethingNew");
	}
}
