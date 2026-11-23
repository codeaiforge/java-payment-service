package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per status change. Rows are only ever inserted. */
@Entity
@Table(name = "payment_audit")
class AuditEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Column(name = "from_status", nullable = false, length = 20)
	private String fromStatus;

	@Column(name = "to_status", nullable = false, length = 20)
	private String toStatus;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected AuditEntity() {
		// for JPA
	}

	AuditEntity(UUID paymentId, String fromStatus, String toStatus, Instant occurredAt) {
		this.paymentId = paymentId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.occurredAt = occurredAt;
	}

	String getFromStatus() {
		return fromStatus;
	}

	String getToStatus() {
		return toStatus;
	}

	Instant getOccurredAt() {
		return occurredAt;
	}
}
