package com.example.payment.application.service.query;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.StatementRepository;
import com.example.payment.application.usecase.query.GetStatementQuery;
import com.example.payment.application.usecase.query.GetStatementResult;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class GetStatementServiceTest {

	private static final StatementRepository.StatementLine LINE = new StatementRepository.StatementLine(PaymentId.newId(),
			new AccountId("ACC-1"), new AccountId("ACC-2"), new BigDecimal("250.00"), Currency.EUR, PaymentStatus.COMPLETED,
			new BigDecimal("40.00"), Instant.parse("2026-10-10T12:00:00Z"), UUID.randomUUID());

	private Object[] asked;

	// ACC-1 is held by CUST-1; every other account by CUST-2.
	private final GetStatementService service = new GetStatementService(new StatementRepository() {
		@Override
		public boolean markProcessed(UUID eventId) {
			throw new AssertionError("reading never writes");
		}

		@Override
		public Optional<StatementLine> findLine(PaymentId paymentId) {
			throw new AssertionError("reading never writes");
		}

		@Override
		public void save(StatementLine line) {
			throw new AssertionError("reading never writes");
		}

		@Override
		public StatementPage find(AccountId accountId, Set<PaymentStatus> statuses, Instant from, Instant to, int page,
				int size) {
			asked = new Object[] {accountId, statuses, from, to, page, size};
			return new StatementPage(List.of(LINE), 41);
		}
	}, new AccountStateValidator(accountId -> Optional.of(
			new Account(AccountStatus.ACTIVE, accountId.value().equals("ACC-1") ? "CUST-1" : "CUST-2"))));

	@Test
	void theHolderGetsTheLinesOfTheDaysAskedFor() {
		GetStatementResult result = service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", "completed",
				LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-10"), 2, 20));

		// Both days included: up to the start of the day after "to".
		assertThat(asked).containsExactly(new AccountId("ACC-1"), Set.of(PaymentStatus.COMPLETED),
				Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-11T00:00:00Z"), 2, 20);
		assertThat(result).isEqualTo(new GetStatementResult(List.of(new GetStatementResult.Line(LINE.paymentId().toString(),
				"ACC-2", new BigDecimal("250.00"), "EUR", "COMPLETED", new BigDecimal("40.00"), LINE.settledAt())), 2, 20, 41));
	}

	@Test
	void noStatusMeansEverySettledOne() {
		service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", null, null, null, 0, 20));

		assertThat(asked[1]).isEqualTo(Set.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED, PaymentStatus.CANCELLED));
	}

	@Test
	void anAccountTheCallerDoesNotHoldIsDenied() {
		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> service.getStatement(new GetStatementQuery("ACC-9", "CUST-1", null, null, null, 0, 20)))
				.withMessage("Account ACC-9 is not held by the caller");
		assertThat(asked).isNull();
	}

	@Test
	void aPageLargerThanTheCapIsRefusedNotCutDown() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", null, null, null, 0, 101)))
				.withMessage("size must be between 1 and 100");
		service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", null, null, null, 0, 100));
	}

	@Test
	void aStatusThatIsNotSettledIsRefused() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", "PROCESSING", null, null, 0, 20)));
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", "SETTLED", null, null, 0, 20)));
	}

	@Test
	void fromAfterToIsRefused() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getStatement(new GetStatementQuery("ACC-1", "CUST-1", null,
						LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-09"), 0, 20)));
	}
}
