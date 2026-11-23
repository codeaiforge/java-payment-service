package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.AuditQueryPort;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Implements the application's AuditPort with a PostgreSQL table. It joins
 * whatever transaction the caller started, which is what ties the audit row
 * to the status change. It also reads the payment trail back (AuditQueryPort).
 */
@Component
public class AuditPersistenceAdapter implements AuditPort, AuditQueryPort {

	private final AuditJpaRepository jpaRepository;

	AuditPersistenceAdapter(AuditJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public void recordTransition(PaymentId paymentId, PaymentStatus from, PaymentStatus to) {
		jpaRepository.save(new AuditEntity(paymentId.value(), from.name(), to.name(), Instant.now()));
	}

	@Override
	public void recordRefundTransition(RefundId refundId, RefundStatus from, RefundStatus to) {
		jpaRepository.insertRefundTransition(refundId.value(), from == null ? null : from.name(), to.name(), Instant.now());
	}

	@Override
	public List<StatusTransition> findTransitions(PaymentId paymentId) {
		return jpaRepository.findByPaymentIdOrderByIdAsc(paymentId.value()).stream()
				.map(row -> new StatusTransition(PaymentStatus.valueOf(row.getFromStatus()),
						PaymentStatus.valueOf(row.getToStatus()), row.getOccurredAt()))
				.toList();
	}
}
