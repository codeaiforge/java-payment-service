package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.port.primary.GetReconciliationStatusUseCase;
import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.application.usecase.query.ReconciliationStatus;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.infrastructure.adapter.primary.agent.Evidence;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import org.bsc.langgraph4j.action.NodeAction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Code only: reads what check 1 and check 3 of the PaymentsStuckInProcessing runbook read, through primary ports.
 * The model is never offered tools to fetch evidence: what it may look at is decided here, and it is the same every
 * time.
 */
public class GatherEvidence implements NodeAction<InvestigationState> {

	private final GetPaymentForOperatorUseCase payments;
	private final GetReconciliationStatusUseCase reconciliation;
	private final Duration threshold;
	private final Duration interval;
	private final Clock clock;

	public GatherEvidence(GetPaymentForOperatorUseCase payments, GetReconciliationStatusUseCase reconciliation,
			Duration threshold, Duration interval, Clock clock) {
		this.payments = payments;
		this.reconciliation = reconciliation;
		this.threshold = threshold;
		this.interval = interval;
		this.clock = clock;
	}

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		GetPaymentForOperatorQuery query = new GetPaymentForOperatorQuery(state.paymentId(), state.startedBy());
		GetPaymentResult payment = payments.getPayment(query);
		List<StatusTransition> history = payments.getHistory(query);
		ReconciliationStatus status = reconciliation.getStatus();
		Instant now = clock.instant();

		// The payment's age in its status comes from the trail, not a column: the read model carries no timestamp,
		// and the last transition is written in the same transaction as the status it moved to.
		Instant since = history.isEmpty() ? null : history.getLast().at();
		Duration age = since == null ? null : Duration.between(since, now);
		boolean stalled = status.lastRunStartedAt() == null
				|| status.lastRunStartedAt().isBefore(now.minus(interval.multipliedBy(2)));

		return Map.of(InvestigationState.EVIDENCE, new Evidence(payment.paymentId(), payment.status(), since, age,
				payment.amount(), payment.currency(), payment.reference(), history, status.lastRunStartedAt(),
				status.runningOn(), stalled, threshold, interval));
	}
}
