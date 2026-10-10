package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data's view of the table. Only the refund adapter uses it. */
interface RefundJpaRepository extends JpaRepository<RefundEntity, UUID> {

	// The unique key makes this atomic, as IdempotencyJpaRepository.insertIfAbsent. A duplicate key is a no-op,
	// not an error, so it doesn't abort the caller's transaction.
	@Modifying
	@Transactional
	@Query(value = """
			INSERT INTO refund (id, payment_id, amount, currency, status, idempotency_key, created_at, status_changed_at)
			VALUES (:id, :paymentId, :amount, :currency, :status, :key, :at, :at)
			ON CONFLICT (idempotency_key) DO NOTHING
			""", nativeQuery = true)
	int insertIfKeyFree(UUID id, UUID paymentId, BigDecimal amount, String currency, String status, String key, Instant at);

	@Modifying
	@Transactional
	@Query("UPDATE RefundEntity r SET r.status = :to, r.statusChangedAt = :at WHERE r.id = :id AND r.status = :from")
	int updateStatus(UUID id, String from, String to, Instant at);

	List<RefundEntity> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

	Optional<RefundEntity> findByIdempotencyKey(String idempotencyKey);

	// SHORTCUT: no (status, status_changed_at) index as payment has; refunds are few. Add one if this query shows.
	List<RefundEntity> findByStatusAndStatusChangedAtLessThanEqual(String status, Instant before);
}
