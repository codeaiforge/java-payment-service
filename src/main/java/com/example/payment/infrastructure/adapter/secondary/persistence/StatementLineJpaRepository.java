package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Spring Data's view of the statement tables. Only the statement adapter uses it. */
interface StatementLineJpaRepository extends JpaRepository<StatementLineEntity, UUID> {

	// The primary key makes this atomic, as IdempotencyJpaRepository.insertIfAbsent.
	@Modifying
	@Transactional
	@Query(value = """
			INSERT INTO statement_processed_event (event_id, processed_at) VALUES (:eventId, :at)
			ON CONFLICT (event_id) DO NOTHING
			""", nativeQuery = true)
	int insertProcessedIfAbsent(UUID eventId, Instant at);

	// One payment's events come from one partition, so one consumer at a time; but during a rebalance two instances can
	// briefly both hold it. The lock makes the second wait, so neither overwrites the other's refund total.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT l FROM StatementLineEntity l WHERE l.paymentId = :paymentId")
	Optional<StatementLineEntity> findForUpdate(UUID paymentId);

	Page<StatementLineEntity> findByAccountIdAndStatusInAndSettledAtGreaterThanEqualAndSettledAtLessThan(String accountId,
			Collection<String> statuses, Instant from, Instant to, Pageable pageable);
}
