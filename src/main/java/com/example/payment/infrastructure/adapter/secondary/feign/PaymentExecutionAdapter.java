package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.infrastructure.adapter.secondary.feign.PaymentExecutionClient.PaymentOrderRequest;
import com.example.payment.infrastructure.adapter.secondary.feign.PaymentExecutionClient.PaymentOrderResponse;
import com.example.payment.infrastructure.adapter.secondary.feign.PaymentExecutionClient.RefundOrderRequest;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Answers {@link PaymentExecutionPort} by sending the payment to the external
 * payment system, the one that moves the money.
 *
 * Unlike the other adapters, it has no Retry. This call is an instruction,
 * not a question: if it timed out, the payment system may have paid and the
 * answer got lost. Sending it again could pay twice. So a call that gets no
 * clear answer is {@link Outcome#UNKNOWN}, never "failed" and never retried.
 */
@Component
class PaymentExecutionAdapter implements PaymentExecutionPort {

	private static final Logger log = LoggerFactory.getLogger(PaymentExecutionAdapter.class);

	private final PaymentExecutionClient paymentExecutionClient;
	private final PaymentMetrics paymentMetrics;
	private final CircuitBreaker circuitBreaker;

	PaymentExecutionAdapter(PaymentExecutionClient paymentExecutionClient, PaymentMetrics paymentMetrics,
			CircuitBreakerRegistry circuitBreakers) {
		this.paymentExecutionClient = paymentExecutionClient;
		this.paymentMetrics = paymentMetrics;
		this.circuitBreaker = circuitBreakers.circuitBreaker("payment-system");
	}

	@Override
	public Outcome execute(Payment payment) {
		PaymentOrderRequest request = new PaymentOrderRequest(payment.sourceAccountId().value(),
				payment.destinationAccountId().value(), payment.amount().amount(),
				payment.amount().currency().name(), payment.reference().value());
		String status;
		try {
			// The payment ID is the key: whoever asks again later (reconciliation, an operator) asks about this payment.
			status = circuitBreaker.executeSupplier(
					() -> paymentExecutionClient.submit(payment.id().toString(), request)).status();
		}
		// Timeout, dropped connection, 5xx, even 4xx: we have no answer we can trust.
		catch (FeignException e) {
			log.warn("Payment system gave no answer for payment {} (status {}); outcome unknown", payment.id(), e.status());
			paymentMetrics.paymentSystemError("execution");
			return Outcome.UNKNOWN;
		}
		// SHORTCUT: an open breaker means it was certainly not sent, but it is still reported UNKNOWN;
		// a way back to AUTHORIZED would need a new domain transition.
		catch (CallNotPermittedException e) {
			log.warn("Payment system circuit breaker is open, payment {} not sent", payment.id());
			return Outcome.UNKNOWN;
		}
		return switch (status) {
			case "SETTLED" -> Outcome.EXECUTED;
			case "REJECTED" -> Outcome.REJECTED;
			// Not fail-closed like the other adapters: the money may have moved, so "no" would be a guess too.
			case null, default -> {
				log.warn("Unknown status from payment system for payment {}: {}", payment.id(), status);
				yield Outcome.UNKNOWN;
			}
		};
	}

	// An instruction like execute: no Retry, and no clear answer is UNKNOWN.
	@Override
	public Outcome refund(Refund refund) {
		RefundOrderRequest request = new RefundOrderRequest(refund.paymentId().toString(), refund.amount().amount(),
				refund.amount().currency().name());
		String status;
		try {
			status = circuitBreaker.executeSupplier(
					() -> paymentExecutionClient.refund(refund.id().toString(), request)).status();
		}
		catch (FeignException e) {
			log.warn("Payment system gave no answer for refund {} (status {}); outcome unknown", refund.id(), e.status());
			paymentMetrics.paymentSystemError("refund");
			return Outcome.UNKNOWN;
		}
		// Unlike execute, the caller is waiting and can be told: certainly not sent, try again later (503).
		catch (CallNotPermittedException e) {
			throw new ExternalSystemUnavailableException("Payment system", e);
		}
		return switch (status) {
			case "SETTLED" -> Outcome.EXECUTED;
			case "REJECTED" -> Outcome.REJECTED;
			case null, default -> {
				log.warn("Unknown status from payment system for refund {}: {}", refund.id(), status);
				yield Outcome.UNKNOWN;
			}
		};
	}

	// No Retry here either, though asking is safe: reconciliation asks again on its next run anyway.
	@Override
	public Outcome findOutcome(Payment payment) {
		return ask(() -> paymentExecutionClient.find(payment.id().toString()), "payment " + payment.id());
	}

	// The refund ID was its Idempotency-Key (refund above), so it is what we ask by.
	@Override
	public Outcome findRefundOutcome(Refund refund) {
		return ask(() -> paymentExecutionClient.findRefund(refund.id().toString()), "refund " + refund.id());
	}

	private Outcome ask(Supplier<PaymentOrderResponse> question, String subject) {
		String status;
		try {
			status = circuitBreaker.executeSupplier(question).status();
		}
		// A clear answer: they have no order with this key. Not counted by the breaker, which only records no-answers.
		catch (FeignException.NotFound e) {
			return Outcome.NOT_RECEIVED;
		}
		catch (FeignException e) {
			log.warn("Payment system gave no answer about {} (status {})", subject, e.status());
			paymentMetrics.paymentSystemError("reconciliation");
			return Outcome.UNKNOWN;
		}
		catch (CallNotPermittedException e) {
			return Outcome.UNKNOWN;
		}
		return switch (status) {
			case "SETTLED" -> Outcome.EXECUTED;
			case "REJECTED" -> Outcome.REJECTED;
			case null, default -> {
				log.warn("Unknown status from payment system for {}: {}", subject, status);
				yield Outcome.UNKNOWN;
			}
		};
	}
}
