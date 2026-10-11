package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.StatementRepository;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Implements the application's StatementRepository with JPA and PostgreSQL (docs/episodes/bonus-09). */
@Component
public class StatementPersistenceAdapter implements StatementRepository {

	// Newest first; the payment ID breaks ties, so a line never shows on two pages or on none.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("settledAt"), Sort.Order.desc("paymentId"));

	private final StatementLineJpaRepository jpaRepository;

	StatementPersistenceAdapter(StatementLineJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public boolean markProcessed(UUID eventId) {
		return jpaRepository.insertProcessedIfAbsent(eventId, Instant.now()) == 1;
	}

	@Override
	public Optional<StatementLine> findLine(PaymentId paymentId) {
		return jpaRepository.findForUpdate(paymentId.value()).map(StatementPersistenceAdapter::toLine);
	}

	@Override
	public void save(StatementLine line) {
		jpaRepository.save(new StatementLineEntity(line.paymentId().value(), line.accountId().value(),
				line.counterpartyAccountId().value(), line.amount(), line.currency().name(),
				line.status() == null ? null : line.status().name(), line.refundedAmount(), line.settledAt(),
				line.lastEventId()));
	}

	@Override
	public StatementPage find(AccountId accountId, Set<PaymentStatus> statuses, Instant from, Instant to, int page,
			int size) {
		Page<StatementLineEntity> rows = jpaRepository.findByAccountIdAndStatusInAndSettledAtGreaterThanEqualAndSettledAtLessThan(
				accountId.value(), statuses.stream().map(PaymentStatus::name).toList(), from, to,
				PageRequest.of(page, size, NEWEST_FIRST));
		return new StatementPage(rows.map(StatementPersistenceAdapter::toLine).getContent(), rows.getTotalElements());
	}

	private static StatementLine toLine(StatementLineEntity row) {
		return new StatementLine(new PaymentId(row.getPaymentId()), new AccountId(row.getAccountId()),
				new AccountId(row.getCounterpartyAccountId()), row.getAmount(), Currency.of(row.getCurrency()),
				row.getStatus() == null ? null : PaymentStatus.valueOf(row.getStatus()), row.getRefundedAmount(),
				row.getSettledAt(), row.getLastEventId());
	}
}
