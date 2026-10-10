package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implements the application's PaymentRepository with JPA and PostgreSQL.
 * Nothing outside this package knows a database is involved.
 *
 * Also a MeterBinder: Boot registers its gauge with Prometheus where metrics
 * are on, and nothing changes where they aren't (the @DataJpaTest slices).
 */
@Component
public class PaymentPersistenceAdapter implements PaymentRepository, MeterBinder {

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

	// How long the oldest PROCESSING payment has waited for its outcome, 0 if none does. Read from the table on each
	// scrape, so every instance reports the same value, and it still rises when reconciliation itself has stopped.
	// An alert compares it with the reconciliation threshold (docs/episodes/bonus-08).
	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("payments.processing.oldest.age", this, adapter -> adapter.jpaRepository.oldestProcessingSince()
						.map(since -> Duration.between(since, Instant.now()).toMillis() / 1000.0).orElse(0.0))
				.baseUnit("seconds")
				.register(registry);
	}

	@Override
	public List<Payment> findProcessingSince(Instant before) {
		return jpaRepository.findByStatusAndStatusChangedAtLessThanEqual(PaymentStatus.PROCESSING.name(), before).stream()
				.map(PaymentEntityMapper::toDomain)
				.toList();
	}
}
