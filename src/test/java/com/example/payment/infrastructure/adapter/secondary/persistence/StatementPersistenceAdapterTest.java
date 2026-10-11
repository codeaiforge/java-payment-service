package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.port.secondary.StatementRepository.StatementLine;
import com.example.payment.application.port.secondary.StatementRepository.StatementPage;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against real PostgreSQL: the statement's filters, order and paging are the query's, and ON CONFLICT is the dedup. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, StatementPersistenceAdapter.class})
class StatementPersistenceAdapterTest {

	private static final AccountId ACCOUNT = new AccountId("ACC-1");
	private static final Set<PaymentStatus> SETTLED = EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED,
			PaymentStatus.CANCELLED);
	private static final Instant DAY = Instant.parse("2026-10-10T00:00:00Z");

	@Autowired
	StatementPersistenceAdapter adapter;

	@Test
	void anEventIsMarkedProcessedOnce() {
		UUID eventId = UUID.randomUUID();

		assertThat(adapter.markProcessed(eventId)).isTrue();
		assertThat(adapter.markProcessed(eventId)).isFalse();
	}

	@Test
	void aLineIsReadBackAsSavedAndReplacedWhole() {
		StatementLine line = line(ACCOUNT, PaymentStatus.COMPLETED, DAY);
		adapter.save(line);
		StatementLine refunded = new StatementLine(line.paymentId(), line.accountId(), line.counterpartyAccountId(),
				line.amount(), line.currency(), line.status(), new BigDecimal("40.00"), line.settledAt(), UUID.randomUUID());

		adapter.save(refunded);

		assertThat(adapter.findLine(line.paymentId())).contains(refunded);
	}

	@Test
	void newestFirstPagedWithTheTotal() {
		StatementLine oldest = save(line(ACCOUNT, PaymentStatus.COMPLETED, DAY.plusSeconds(1)));
		StatementLine middle = save(line(ACCOUNT, PaymentStatus.FAILED, DAY.plusSeconds(2)));
		StatementLine newest = save(line(ACCOUNT, PaymentStatus.CANCELLED, DAY.plusSeconds(3)));

		StatementPage first = adapter.find(ACCOUNT, SETTLED, DAY, DAY.plusSeconds(86400), 0, 2);
		StatementPage second = adapter.find(ACCOUNT, SETTLED, DAY, DAY.plusSeconds(86400), 1, 2);

		assertThat(first.lines()).containsExactly(newest, middle);
		assertThat(second.lines()).containsExactly(oldest);
		assertThat(first.totalLines()).isEqualTo(3);
	}

	@Test
	void onlyTheAccountsLinesWithTheStatusInTheRange() {
		StatementLine match = save(line(ACCOUNT, PaymentStatus.COMPLETED, DAY));
		save(line(ACCOUNT, PaymentStatus.FAILED, DAY.plusSeconds(10)));
		save(line(new AccountId("ACC-2"), PaymentStatus.COMPLETED, DAY.plusSeconds(20)));
		save(line(ACCOUNT, PaymentStatus.COMPLETED, DAY.plusSeconds(86400)));
		save(line(ACCOUNT, PaymentStatus.COMPLETED, DAY.minusSeconds(1)));

		StatementPage page = adapter.find(ACCOUNT, EnumSet.of(PaymentStatus.COMPLETED), DAY, DAY.plusSeconds(86400), 0, 20);

		assertThat(page.lines()).containsExactly(match);
	}

	// A refund's event came first: the line exists, but the payment's own event hasn't filled it in yet.
	@Test
	void aLineNotSettledYetIsLeftOut() {
		save(new StatementLine(PaymentId.newId(), ACCOUNT, new AccountId("ACC-2"), null, Currency.EUR, null,
				new BigDecimal("40.00"), null, UUID.randomUUID()));

		assertThat(adapter.find(ACCOUNT, SETTLED, Instant.EPOCH, DAY.plusSeconds(86400), 0, 20).totalLines()).isZero();
	}

	private StatementLine save(StatementLine line) {
		adapter.save(line);
		return line;
	}

	private static StatementLine line(AccountId account, PaymentStatus status, Instant settledAt) {
		return new StatementLine(PaymentId.newId(), account, new AccountId("ACC-9"), new BigDecimal("250.00"), Currency.EUR,
				status, new BigDecimal("0.00"), settledAt, UUID.randomUUID());
	}
}
