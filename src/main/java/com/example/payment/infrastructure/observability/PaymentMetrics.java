package com.example.payment.infrastructure.observability;

import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort.Execution;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The payment metrics, in Micrometer. Prometheus scrapes them from
 * /actuator/prometheus. No tag carries an account, amount or reference:
 * metrics say how many, never whose.
 */
@Component
public class PaymentMetrics implements PaymentMetricsPort {

	private final Map<PaymentStatus, Counter> statusCounters = new EnumMap<>(PaymentStatus.class);
	private final Timer executionDuration;
	private final Counter accountErrors;
	private final MeterRegistry registry;

	public PaymentMetrics(MeterRegistry registry) {
		this.registry = registry;
		// The spec's payments.created ... payments.cancelled, as one counter tagged by status. Prometheus reserves
		// the _created suffix: a counter named payments.created would be exported as plain payments_total.
		// Registered up front so every status reads 0, not "no data", until it first happens.
		for (PaymentStatus status : PaymentStatus.values()) {
			statusCounters.put(status, registry.counter("payments", "status", status.name().toLowerCase()));
		}
		// Up front too, for the SLO's rate(): a series that first appears at 1 has no increase Prometheus can see.
		for (Execution result : Execution.values()) {
			registry.counter("payment.executions", "result", result.name().toLowerCase());
		}
		this.executionDuration = registry.timer("payment.execution.duration");
		this.accountErrors = registry.counter("external.account.errors");
	}

	@Override
	public void statusChanged(PaymentStatus status) {
		statusCounters.get(status).increment();
	}

	@Override
	public void executionTook(Duration duration) {
		executionDuration.record(duration);
	}

	@Override
	public void reconciled(PaymentExecutionPort.Outcome result) {
		registry.counter("payments.reconciled", "result", result.name().toLowerCase()).increment();
	}

	@Override
	public void refundFinished(RefundStatus status) {
		registry.counter("refunds", "status", status.name().toLowerCase()).increment();
	}

	@Override
	public void executionEnded(Execution result) {
		registry.counter("payment.executions", "result", result.name().toLowerCase()).increment();
	}

	/** The account system gave no answer: 5xx, timeout, connection refused. */
	public void accountSystemError() {
		accountErrors.increment();
	}

	/** A payment-side system (authorization, limit) gave no answer. */
	public void paymentSystemError(String system) {
		registry.counter("external.payment.errors", "system", system).increment();
	}
}
