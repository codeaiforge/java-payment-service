package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data's view of the table. Only the persistence adapter uses it. */
interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

	// Compare-and-set: PostgreSQL locks the row, so of two concurrent updates from the same status,
	// the second re-checks the WHERE after the first commits and matches nothing.
	@Modifying
	@Transactional
	@Query("UPDATE PaymentEntity p SET p.status = :to, p.statusChangedAt = :at WHERE p.id = :id AND p.status = :from")
	int updateStatus(UUID id, String from, String to, Instant at);

	// SELECT ... FOR UPDATE: the row stays locked until the caller's transaction ends.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
	Optional<PaymentEntity> findByIdForUpdate(UUID id);

	List<PaymentEntity> findByStatusAndStatusChangedAtLessThanEqual(String status, Instant before);

	// Answered from the (status, status_changed_at) index.
	@Query("SELECT min(p.statusChangedAt) FROM PaymentEntity p WHERE p.status = 'PROCESSING'")
	Optional<Instant> oldestProcessingSince();
}
