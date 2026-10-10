package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.ReconcileRefundsUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;

/**
 * Resolves refunds left PROCESSING because the payment system's answer was lost
 * (docs/episodes/bonus-04), as ReconcilePaymentsService does for payments, with
 * the same rules. Its own use case rather than a second loop in that one: a
 * refund is its own aggregate with its own repository, and the two runs share
 * nothing but the clock that triggers them.
 */
@Service
public class ReconcileRefundsService implements ReconcileRefundsUseCase {

	private static final Logger log = LoggerFactory.getLogger(ReconcileRefundsService.class);

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentExecutionPort paymentExecutionPort;
	private final AuditPort auditPort;
	private final PaymentEventPort paymentEventPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public ReconcileRefundsService(RefundRepository refundRepository, PaymentRepository paymentRepository,
			PaymentExecutionPort paymentExecutionPort, AuditPort auditPort, PaymentEventPort paymentEventPort,
			PaymentMetricsPort paymentMetricsPort, TransactionOperations transactions) {
		this.refundRepository = refundRepository;
		this.paymentRepository = paymentRepository;
		this.paymentExecutionPort = paymentExecutionPort;
		this.auditPort = auditPort;
		this.paymentEventPort = paymentEventPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	@Override
	public void reconcileRefunds(Duration threshold) {
		for (Refund refund : refundRepository.findProcessingSince(Instant.now().minus(threshold))) {
			try {
				reconcile(refund);
			}
			catch (RuntimeException e) {
				log.warn("Refund {} not reconciled: {}", refund.id(), e.getMessage());
			}
		}
	}

	private void reconcile(Refund refund) {
		switch (paymentExecutionPort.findRefundOutcome(refund)) {
			case EXECUTED -> refund.complete();
			// Never received: no money has gone back yet. The refund-ID idempotency key stops a duplicate order, not a
			// delayed first one: an order still in flight can arrive after this and refund. FAILED frees the amount for
			// a new refund, so that late order could over-refund. See B7's Known shortcuts.
			case REJECTED, NOT_RECEIVED -> refund.fail();
			// Still no answer: the money may have gone back. It stays PROCESSING, still counted against the payment.
			case UNKNOWN -> {
				return;
			}
		}
		// The event names the payment's accounts. A refund's payment always exists: the foreign key says so.
		Payment payment = paymentRepository.findById(refund.paymentId()).orElseThrow();
		boolean stored = Boolean.TRUE.equals(transactions.execute(tx -> {
			if (!refundRepository.updateStatus(refund, RefundStatus.PROCESSING)) {
				return false;
			}
			auditPort.recordRefundTransition(refund.id(), RefundStatus.PROCESSING, refund.status());
			paymentEventPort.record(PaymentEvent.refundFinished(payment, refund, Instant.now()));
			return true;
		}));
		if (!stored) {
			log.info("Refund {} was already reconciled by another run", refund.id());
			return;
		}
		log.info("Refund {} reconciled from PROCESSING to {}", refund.id(), refund.status());
		paymentMetricsPort.refundFinished(refund.status());
	}
}
