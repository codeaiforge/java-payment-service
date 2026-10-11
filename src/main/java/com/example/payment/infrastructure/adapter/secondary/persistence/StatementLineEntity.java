package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The row in the statement_line table (docs/episodes/bonus-09). Replaced whole on every change. */
@Entity
@Table(name = "statement_line")
class StatementLineEntity {

	@Id
	@Column(name = "payment_id")
	private UUID paymentId;

	@Column(name = "account_id", nullable = false, length = 64)
	private String accountId;

	@Column(name = "counterparty_account_id", nullable = false, length = 64)
	private String counterpartyAccountId;

	@Column(precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(length = 20)
	private String status;

	@Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal refundedAmount;

	@Column(name = "settled_at")
	private Instant settledAt;

	@Column(name = "last_event_id", nullable = false)
	private UUID lastEventId;

	protected StatementLineEntity() {
		// for JPA
	}

	StatementLineEntity(UUID paymentId, String accountId, String counterpartyAccountId, BigDecimal amount, String currency,
			String status, BigDecimal refundedAmount, Instant settledAt, UUID lastEventId) {
		this.paymentId = paymentId;
		this.accountId = accountId;
		this.counterpartyAccountId = counterpartyAccountId;
		this.amount = amount;
		this.currency = currency;
		this.status = status;
		this.refundedAmount = refundedAmount;
		this.settledAt = settledAt;
		this.lastEventId = lastEventId;
	}

	UUID getPaymentId() {
		return paymentId;
	}

	String getAccountId() {
		return accountId;
	}

	String getCounterpartyAccountId() {
		return counterpartyAccountId;
	}

	BigDecimal getAmount() {
		return amount;
	}

	String getCurrency() {
		return currency;
	}

	String getStatus() {
		return status;
	}

	BigDecimal getRefundedAmount() {
		return refundedAmount;
	}

	Instant getSettledAt() {
		return settledAt;
	}

	UUID getLastEventId() {
		return lastEventId;
	}
}
