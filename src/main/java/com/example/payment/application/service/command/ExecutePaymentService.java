package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentAuthorizationPort;
import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentLimitPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort.Execution;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
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
 * Authorization -> limit check -> execution. Each step asks the system that
 * knows; the domain decides whether the payment may move to the next state.
 *
 * There is no transaction around the whole method: external calls never run
 * inside one. Each status change is its own short transaction, together with
 * its audit record, and is committed before the next external system is asked.
 */
@Service
public class ExecutePaymentService implements ExecutePaymentUseCase {

	private static final Logger log = LoggerFactory.getLogger(ExecutePaymentService.class);

	private final PaymentRepository paymentRepository;
	private final PaymentAuthorizationPort paymentAuthorizationPort;
	private final PaymentLimitPort paymentLimitPort;
	private final PaymentExecutionPort paymentExecutionPort;
	private final AuditPort auditPort;
	private final PaymentEventPort paymentEventPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public ExecutePaymentService(PaymentRepository paymentRepository, PaymentAuthorizationPort paymentAuthorizationPort,
			PaymentLimitPort paymentLimitPort, PaymentExecutionPort paymentExecutionPort, AuditPort auditPort,
			PaymentEventPort paymentEventPort, PaymentMetricsPort paymentMetricsPort, TransactionOperations transactions) {
		this.paymentRepository = paymentRepository;
		this.paymentAuthorizationPort = paymentAuthorizationPort;
		this.paymentLimitPort = paymentLimitPort;
		this.paymentExecutionPort = paymentExecutionPort;
		this.auditPort = auditPort;
		this.paymentEventPort = paymentEventPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	// Every attempt that got as far as executing is counted for the success SLO (docs/episodes/bonus-08). A business
	// "no" is a good outcome; requests that never were an execution (unknown payment, executed twice) aren't counted.
	@Override
	public Payment executePayment(ExecutePaymentCommand command) {
		try {
			Payment payment = execute(command);
			paymentMetricsPort.executionEnded(switch (payment.status()) {
				case COMPLETED -> Execution.COMPLETED;
				case FAILED -> Execution.REFUSED;
				default -> Execution.ERROR; // left PROCESSING: the customer doesn't know what happened either
			});
			return payment;
		}
		catch (PaymentAuthorizationException | PaymentLimitExceededException e) {
			paymentMetricsPort.executionEnded(Execution.REFUSED);
			throw e;
		}
		catch (PaymentValidationException | PaymentNotFoundException | InvalidPaymentStateException e) {
			throw e;
		}
		catch (RuntimeException e) {
			paymentMetricsPort.executionEnded(Execution.ERROR);
			throw e;
		}
	}

	private Payment execute(ExecutePaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// Authorization is asked once. A payment stopped earlier by its limit is already AUTHORIZED
		// and goes straight to the limit check when executed again.
		if (payment.status() == PaymentStatus.CREATED) {
			if (!paymentAuthorizationPort.isAuthorized(payment)) {
				throw new PaymentAuthorizationException(paymentId);
			}
			transition(payment::authorize);
			store(payment, PaymentStatus.CREATED);
		}

		// The domain refuses PROCESSING for anything not AUTHORIZED (COMPLETED, CANCELLED, ...) before
		// the limit system is asked. Nothing is stored until the limit check passes, so a refused
		// payment stays AUTHORIZED.
		transition(payment::startProcessing);
		if (!paymentLimitPort.isWithinLimit(payment)) {
			throw new PaymentLimitExceededException(paymentId);
		}
		// Committed before calling out: if we stop half-way, the payment reads PROCESSING, not AUTHORIZED,
		// so nobody executes it a second time. Of two concurrent executes, only one gets this far.
		store(payment, PaymentStatus.AUTHORIZED);

		// The money moves here, outside any transaction of ours: a rollback can't take it back.
		// If storing the outcome fails, the payment stays PROCESSING, which is true: we don't know.
		long started = System.nanoTime();
		PaymentExecutionPort.Outcome outcome = paymentExecutionPort.execute(payment);
		paymentMetricsPort.executionTook(Duration.ofNanos(System.nanoTime() - started));
		switch (outcome) {
			case EXECUTED -> payment.complete();
			case REJECTED -> payment.fail();
			// No answer is not a "no": the money may have moved. PROCESSING is the truth, and it also blocks
			// a second execute. Only the payment system can settle it (reconciliation, docs/episodes/16).
			case UNKNOWN -> {
				log.warn("Payment {} left PROCESSING: payment system outcome unknown", payment.id());
				return payment;
			}
		}
		store(payment, PaymentStatus.PROCESSING);
		return payment;
	}

	// The new status, its audit record and, for a final status, its event commit together or not at all. The status is only written if the
	// stored one is still `from`, so a request that loaded the payment before another one changed it loses.
	private void store(Payment payment, PaymentStatus from) {
		transactions.executeWithoutResult(tx -> {
			if (!paymentRepository.updateStatus(payment, from)) {
				throw new InvalidPaymentStateException(
						"Payment " + payment.id() + " was changed by another request and cannot become " + payment.status());
			}
			auditPort.recordTransition(payment.id(), from, payment.status());
			if (payment.status().isFinal()) {
				paymentEventPort.record(PaymentEvent.finalStatusReached(payment, Instant.now()));
			}
		});
		log.info("Payment {} moved from {} to {}", payment.id(), from, payment.status());
		paymentMetricsPort.statusChanged(payment.status());
	}

	private static void transition(Runnable step) {
		try {
			step.run();
		}
		catch (IllegalStateException e) {
			throw new InvalidPaymentStateException(e.getMessage(), e);
		}
	}
}
