package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ReconcilePaymentUseCase;
import com.example.payment.application.port.primary.ReconcilePaymentsUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ReconcilePaymentCommand;
import com.example.payment.application.usecase.command.ReconcilePaymentResult;
import com.example.payment.application.usecase.command.ReconcilePaymentResult.Answer;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;

/**
 * Resolves payments left PROCESSING because the payment system's answer was lost
 * (docs/episodes/16). Only the payment system knows what happened, so it is asked;
 * the domain decides whether the payment may move.
 *
 * Like ExecutePaymentService, no transaction is held across the external call:
 * each payment gets its own short one, with its audit record and event, once the answer is in.
 *
 * The scheduled run settles every stuck payment; an operator can settle one by id. Both store an answer the
 * same way (settle), so a payment settled by hand is audited, published and counted like any other.
 */
@Service
public class ReconcilePaymentsService implements ReconcilePaymentsUseCase, ReconcilePaymentUseCase {

	private static final Logger log = LoggerFactory.getLogger(ReconcilePaymentsService.class);

	private final PaymentRepository paymentRepository;
	private final PaymentExecutionPort paymentExecutionPort;
	private final AuditPort auditPort;
	private final PaymentEventPort paymentEventPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public ReconcilePaymentsService(PaymentRepository paymentRepository, PaymentExecutionPort paymentExecutionPort,
			AuditPort auditPort, PaymentEventPort paymentEventPort, PaymentMetricsPort paymentMetricsPort,
			TransactionOperations transactions) {
		this.paymentRepository = paymentRepository;
		this.paymentExecutionPort = paymentExecutionPort;
		this.auditPort = auditPort;
		this.paymentEventPort = paymentEventPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	// SHORTCUT: reads every stuck payment in one query; page through them if a run ever finds thousands.
	@Override
	public void reconcilePayments(Duration threshold) {
		for (Payment payment : paymentRepository.findProcessingSince(Instant.now().minus(threshold))) {
			// One payment's failure (a corrupt row, the database blinking) doesn't stop the others.
			try {
				settle(payment, paymentExecutionPort.findOutcome(payment));
			}
			catch (RuntimeException e) {
				log.warn("Payment {} not reconciled: {}", payment.id(), e.getMessage());
			}
		}
	}

	@Override
	public ReconcilePaymentResult reconcile(ReconcilePaymentCommand command, Duration threshold) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
		if (payment.status() != PaymentStatus.PROCESSING) {
			throw new InvalidPaymentStateException(
					"Payment " + paymentId + " is " + payment.status() + ", and only a PROCESSING payment is reconciled");
		}
		log.info("Operator {} asked to reconcile payment {}", command.operatorId(), paymentId);

		Outcome outcome = paymentExecutionPort.findOutcome(payment);
		// See ReconcilePaymentUseCase: a young NOT_RECEIVED order may still be in flight, so it isn't failed yet.
		// SHORTCUT: reuses the run's query, which reads every payment stuck past the threshold; a by-id query if that grows.
		if (outcome == Outcome.NOT_RECEIVED && paymentRepository.findProcessingSince(Instant.now().minus(threshold))
				.stream().noneMatch(p -> p.id().equals(paymentId))) {
			log.info("Payment {} not received yet, but younger than {}: left PROCESSING for the scheduled run",
					paymentId, threshold);
			return new ReconcilePaymentResult(paymentId, answer(outcome), PaymentStatus.PROCESSING, false);
		}
		boolean stored = settle(payment, outcome);
		PaymentStatus status = stored || outcome == Outcome.UNKNOWN ? payment.status()
				// Another run stored first: report what it stored, not what this call would have.
				: paymentRepository.findById(paymentId).map(Payment::status).orElseThrow();
		return new ReconcilePaymentResult(paymentId, answer(outcome), status, stored);
	}

	private static Answer answer(Outcome outcome) {
		return switch (outcome) {
			case EXECUTED -> Answer.EXECUTED;
			case REJECTED -> Answer.REJECTED;
			case NOT_RECEIVED -> Answer.NOT_RECEIVED;
			case UNKNOWN -> Answer.UNKNOWN;
		};
	}

	/** Stores what the payment system answered. False if there was nothing to store, or another run stored first. */
	private boolean settle(Payment payment, Outcome outcome) {
		switch (outcome) {
			case EXECUTED -> payment.complete();
			// Never received: no money has moved yet. The payment-ID idempotency key stops a duplicate order, not a
			// delayed first one: an order still in flight can arrive after this and pay. See B2's Known shortcuts.
			case REJECTED, NOT_RECEIVED -> payment.fail();
			// Still no answer: PROCESSING is still the truth. The next run asks again.
			case UNKNOWN -> {
				paymentMetricsPort.reconciled(outcome);
				return false;
			}
		}
		// Compare-and-set, as everywhere: if another instance's run got here first, it has already stored this.
		boolean stored = Boolean.TRUE.equals(transactions.execute(tx -> {
			if (!paymentRepository.updateStatus(payment, PaymentStatus.PROCESSING)) {
				return false;
			}
			auditPort.recordTransition(payment.id(), PaymentStatus.PROCESSING, payment.status());
			paymentEventPort.record(PaymentEvent.finalStatusReached(payment, Instant.now()));
			return true;
		}));
		if (!stored) {
			log.info("Payment {} was already reconciled by another run", payment.id());
			return false;
		}
		log.info("Payment {} reconciled from PROCESSING to {}", payment.id(), payment.status());
		paymentMetricsPort.statusChanged(payment.status());
		paymentMetricsPort.reconciled(outcome);
		return true;
	}
}
