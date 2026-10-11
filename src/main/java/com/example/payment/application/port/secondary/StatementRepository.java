package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where the payment statement is kept (docs/episodes/bonus-09): a read model,
 * one line per payment, built from payment events. Not the payment table:
 * nothing here is a Payment, and nothing here enforces a rule.
 */
public interface StatementRepository {

	/** Records the event as applied. False if it already was; nothing is recorded then. */
	boolean markProcessed(UUID eventId);

	/** The payment's line, locked until the caller's transaction ends. Only inside a transaction. */
	Optional<StatementLine> findLine(PaymentId paymentId);

	/** Inserts or replaces the line. */
	void save(StatementLine line);

	/**
	 * The account's settled lines in [from, to), newest first.
	 *
	 * @param statuses only lines with one of these; a line not settled yet has none, so it is never found
	 */
	StatementPage find(AccountId accountId, Set<PaymentStatus> statuses, Instant from, Instant to, int page, int size);

	/**
	 * A payment as the statement shows it, from its source account's side.
	 * amount, status and settledAt are null until the payment's own event has
	 * been applied: a refund event can arrive first.
	 */
	record StatementLine(PaymentId paymentId, AccountId accountId, AccountId counterpartyAccountId, BigDecimal amount,
			Currency currency, PaymentStatus status, BigDecimal refundedAmount, Instant settledAt, UUID lastEventId) {
	}

	record StatementPage(List<StatementLine> lines, long totalLines) {
	}
}
