package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.port.primary.ReconcilePaymentUseCase;
import com.example.payment.application.usecase.command.ReconcilePaymentCommand;
import com.example.payment.application.usecase.command.ReconcilePaymentResult;
import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction;
import org.bsc.langgraph4j.action.NodeAction;

import java.time.Duration;
import java.util.Map;

/**
 * Code only, and only after a second operator approved (the graph pauses before this node). It runs as the approver:
 * the reconcile call logs them as the operator who forced the payment's outcome.
 *
 * The payment is read again first: hours may have passed, and a payment the scheduled run has settled since needs
 * nothing. That is NO_LONGER_NEEDED, and nothing runs.
 */
public class Execute implements NodeAction<InvestigationState> {

	public static final String EXECUTED = "EXECUTED";
	public static final String NO_LONGER_NEEDED = "NO_LONGER_NEEDED";

	private final GetPaymentForOperatorUseCase payments;
	private final ReconcilePaymentUseCase reconcile;
	private final Duration threshold;

	public Execute(GetPaymentForOperatorUseCase payments, ReconcilePaymentUseCase reconcile, Duration threshold) {
		this.payments = payments;
		this.reconcile = reconcile;
		this.threshold = threshold;
	}

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		String paymentId = state.paymentId();
		String approver = state.approver();
		String status = payments.getPayment(new GetPaymentForOperatorQuery(paymentId, approver)).status();
		if (!"PROCESSING".equals(status)) {
			return finished(NO_LONGER_NEEDED, "The payment is " + status + " now; nothing ran.");
		}
		ProposedAction proposal = state.proposal().orElseThrow();
		return switch (proposal.action()) {
			case RECONCILE_NOW -> reconcile(paymentId, approver);
			// SHORTCUT: recorded, not sent. Sending means a mail or pager integration, and its own retries.
			case ESCALATE_TO_PAYMENT_SYSTEM, ESCALATE_TO_ON_CALL -> finished(EXECUTED,
					proposal.action() + " recorded, not sent: " + proposal.escalationText());
			case NO_ACTION -> finished(EXECUTED, "Nothing to do.");
		};
	}

	// UNKNOWN, and NOT_RECEIVED below the threshold, leave the payment PROCESSING. That is a result, not an error:
	// the payment system's answer decides, as it always does.
	private Map<String, Object> reconcile(String paymentId, String approver) {
		try {
			ReconcilePaymentResult result = reconcile.reconcile(new ReconcilePaymentCommand(paymentId, approver), threshold);
			return finished(EXECUTED, "Reconciled: the payment system answered %s; the payment is %s%s."
					.formatted(result.answer(), result.status(), result.stored() ? "" : " (unchanged by this call)"));
		}
		catch (InvalidPaymentStateException e) {
			// Settled between the read above and the call: another run got there first.
			return finished(NO_LONGER_NEEDED, e.getMessage());
		}
	}

	private static Map<String, Object> finished(String outcome, String result) {
		return Map.of(InvestigationState.OUTCOME, outcome, InvestigationState.RESULT, result);
	}
}
