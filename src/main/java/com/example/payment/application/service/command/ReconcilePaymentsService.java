package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.ReconcilePaymentsUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
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
 */
@Service
public class ReconcilePaymentsService implements ReconcilePaymentsUseCase {

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
				reconcile(payment);
			}
			catch (RuntimeException e) {
				log.warn("Payment {} not reconciled: {}", payment.id(), e.getMessage());
			}
		}
	}

	private void reconcile(Payment payment) {
		Outcome outcome = paymentExecutionPort.findOutcome(payment);
		switch (outcome) {
			case EXECUTED -> payment.complete();
			// Never received: no money has moved yet. The payment-ID idempotency key stops a duplicate order, not a
			// delayed first one: an order still in flight can arrive after this and pay. See B2's Known shortcuts.
			case REJECTED, NOT_RECEIVED -> payment.fail();
			// Still no answer: PROCESSING is still the truth. The next run asks again.
			case UNKNOWN -> {
				paymentMetricsPort.reconciled(outcome);
				return;
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
			return;
		}
		log.info("Payment {} reconciled from PROCESSING to {}", payment.id(), payment.status());
		paymentMetricsPort.statusChanged(payment.status());
		paymentMetricsPort.reconciled(outcome);
	}
}
