package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

/** Spring Data's view of the audit tables. Only the audit adapter uses it. */
interface AuditJpaRepository extends JpaRepository<AuditEntity, Long> {

	// SHORTCUT: refund_audit is only ever inserted into, so it gets a native INSERT here instead of its own entity.
	@Modifying
	@Query(value = "INSERT INTO refund_audit (refund_id, from_status, to_status, occurred_at) VALUES (:refundId, :from, :to, :at)",
			nativeQuery = true)
	void insertRefundTransition(UUID refundId, String from, String to, Instant at);
}
