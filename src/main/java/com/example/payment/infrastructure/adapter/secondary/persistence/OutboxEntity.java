package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per event. Inserted with the status change; only published_at is ever updated.
 * trace_context is not part of the event: it is the trace the change happened in (docs/episodes/bonus-05).
 */
@Entity
@Table(name = "payment_outbox")
class OutboxEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	@Column(name = "event_type", nullable = false, length = 40)
	private String eventType;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Column(name = "refund_id")
	private UUID refundId;

	@Column(name = "source_account_id", nullable = false)
	private String sourceAccountId;

	@Column(name = "destination_account_id", nullable = false)
	private String destinationAccountId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(length = 140)
	private String reference;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "trace_context", length = 55)
	private String traceContext;

	protected OutboxEntity() {
		// for JPA
	}

	OutboxEntity(UUID eventId, String eventType, UUID paymentId, UUID refundId, String sourceAccountId,
			String destinationAccountId, BigDecimal amount, String currency, String reference, Instant occurredAt,
			String traceContext) {
		this.eventId = eventId;
		this.eventType = eventType;
		this.paymentId = paymentId;
		this.refundId = refundId;
		this.sourceAccountId = sourceAccountId;
		this.destinationAccountId = destinationAccountId;
		this.amount = amount;
		this.currency = currency;
		this.reference = reference;
		this.occurredAt = occurredAt;
		this.traceContext = traceContext;
	}

	Long getId() {
		return id;
	}

	UUID getEventId() {
		return eventId;
	}

	String getEventType() {
		return eventType;
	}

	UUID getPaymentId() {
		return paymentId;
	}

	UUID getRefundId() {
		return refundId;
	}

	String getSourceAccountId() {
		return sourceAccountId;
	}

	String getDestinationAccountId() {
		return destinationAccountId;
	}

	BigDecimal getAmount() {
		return amount;
	}

	String getCurrency() {
		return currency;
	}

	String getReference() {
		return reference;
	}

	Instant getOccurredAt() {
		return occurredAt;
	}

	String getTraceContext() {
		return traceContext;
	}
}
