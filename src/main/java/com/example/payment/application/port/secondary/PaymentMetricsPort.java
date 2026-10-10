package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundStatus;

import java.time.Duration;

/**
 * What the application tells operations about payments. Only facts that have
 * been committed are reported: a status change that was rolled back never happened.
 */
public interface PaymentMetricsPort {

	void statusChanged(PaymentStatus status);

	void executionTook(Duration duration);

	/** What reconciliation learned about a stuck payment: the payment system's answer, or none (UNKNOWN). */
	void reconciled(PaymentExecutionPort.Outcome result);

	/** A refund reached a final status. How many, never how much. */
	void refundFinished(RefundStatus status);

	/** An attempt to execute a payment ended. The success SLO counts these (docs/episodes/bonus-08). */
	void executionEnded(Execution result);

	enum Execution {
		/** The payment system moved the money. */
		COMPLETED,
		/** A business "no": not authorized, over the limit, or rejected by the payment system. Not an error. */
		REFUSED,
		/** No answer we could act on: an external system down, a lost answer (left PROCESSING), our own failure. */
		ERROR
	}
}
