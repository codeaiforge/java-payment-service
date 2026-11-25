package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.GetReconciliationStatusUseCase;
import com.example.payment.application.port.primary.ReconcilePaymentUseCase;
import com.example.payment.application.usecase.command.ReconcilePaymentCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;

/**
 * Endpoints for the people who run the service, not customers: SecurityConfiguration lets only a token with the
 * payments.operate scope in. The caller's {@code sub} is an operator ID here, never a customer ID.
 */
@RestController
public class OperationsController {

	private final ReconcilePaymentUseCase reconcilePaymentUseCase;
	private final GetReconciliationStatusUseCase getReconciliationStatusUseCase;
	private final Duration threshold;

	public OperationsController(ReconcilePaymentUseCase reconcilePaymentUseCase,
			GetReconciliationStatusUseCase getReconciliationStatusUseCase,
			@Value("${payment.reconciliation.threshold}") Duration threshold) {
		this.reconcilePaymentUseCase = reconcilePaymentUseCase;
		this.getReconciliationStatusUseCase = getReconciliationStatusUseCase;
		this.threshold = threshold;
	}

	// Check 1 of the PaymentsStuckInProcessing runbook, without production database access.
	@GetMapping("/operations/reconciliation")
	public ReconciliationStatusResponse reconciliationStatus() {
		return ReconciliationStatusResponse.from(getReconciliationStatusUseCase.getStatus());
	}

	// 200 whatever the answer: "still unknown" is a result, not an error. stored says whether this call changed it.
	@PostMapping("/operations/payments/{id}/reconcile")
	public ReconcileResponse reconcile(@PathVariable String id, Principal operator) {
		return ReconcileResponse.from(reconcilePaymentUseCase.reconcile(
				new ReconcilePaymentCommand(id, operator.getName()), threshold));
	}
}
