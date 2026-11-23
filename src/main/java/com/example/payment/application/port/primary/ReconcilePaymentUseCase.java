package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.ReconcilePaymentCommand;
import com.example.payment.application.usecase.command.ReconcilePaymentResult;

import java.time.Duration;

public interface ReconcilePaymentUseCase {

	/**
	 * Settles one PROCESSING payment now, by asking the payment system what became of it, instead of waiting for
	 * the next reconciliation run to find it.
	 *
	 * An operator may ask before the payment is {@code threshold} old, and for most answers that is safe: EXECUTED
	 * and REJECTED are final whenever they come, and the compare-and-set still decides who stores the outcome.
	 * NOT_RECEIVED is not final until then: the order may still be in flight and pay after we fail the payment.
	 * So a NOT_RECEIVED payment younger than {@code threshold} stays PROCESSING, and the scheduled run settles it.
	 *
	 * @param threshold the same one the scheduled run uses
	 * @throws com.example.payment.application.exception.InvalidPaymentStateException if the payment is not PROCESSING
	 */
	ReconcilePaymentResult reconcile(ReconcilePaymentCommand command, Duration threshold);
}
