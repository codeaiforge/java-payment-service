package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implements the application's PaymentRepository with JPA and PostgreSQL.
 * Nothing outside this package knows a database is involved.
 */
@Component
public class PaymentPersistenceAdapter implements PaymentRepository {

	private final PaymentJpaRepository jpaRepository;

	PaymentPersistenceAdapter(PaymentJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	// SHORTCUT: the id is assigned by the domain, so save() does SELECT then INSERT; implement Persistable if that query matters
	@Override
	public void save(Payment payment) {
		jpaRepository.save(PaymentEntityMapper.toEntity(payment, Instant.now()));
	}

	@Override
	public boolean updateStatus(Payment payment, PaymentStatus expected) {
		return jpaRepository.updateStatus(payment.id().value(), expected.name(), payment.status().name(),
				Instant.now()) == 1;
	}

	@Override
	public Optional<Payment> findById(PaymentId paymentId) {
		return jpaRepository.findById(paymentId.value()).map(PaymentEntityMapper::toDomain);
	}

	@Override
	public Optional<Payment> findByIdForUpdate(PaymentId paymentId) {
		return jpaRepository.findByIdForUpdate(paymentId.value()).map(PaymentEntityMapper::toDomain);
	}

	@Override
	public List<Payment> findProcessingSince(Instant before) {
		return jpaRepository.findByStatusAndStatusChangedAtLessThanEqual(PaymentStatus.PROCESSING.name(), before).stream()
				.map(PaymentEntityMapper::toDomain)
				.toList();
	}
}
