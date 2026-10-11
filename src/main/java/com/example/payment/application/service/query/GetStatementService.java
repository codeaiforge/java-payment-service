package com.example.payment.application.service.query;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.GetStatementUseCase;
import com.example.payment.application.port.secondary.StatementRepository;
import com.example.payment.application.port.secondary.StatementRepository.StatementLine;
import com.example.payment.application.port.secondary.StatementRepository.StatementPage;
import com.example.payment.application.usecase.query.GetStatementQuery;
import com.example.payment.application.usecase.query.GetStatementResult;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reads the payment statement, the read model ProjectPaymentEventService keeps
 * (docs/episodes/bonus-09). Never the payment table.
 */
@Service
public class GetStatementService implements GetStatementUseCase {

	/** Larger asks are refused, not cut down: a client that gets fewer lines than it asked for may think it has them all. */
	public static final int MAX_PAGE_SIZE = 100;

	private static final Set<PaymentStatus> SETTLED = EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED,
			PaymentStatus.CANCELLED);
	// The database's timestamps can't hold every LocalDate; no payment settles outside these.
	private static final LocalDate FIRST_DAY = LocalDate.of(1970, 1, 1);
	private static final LocalDate LAST_DAY = LocalDate.of(9999, 12, 30);

	private final StatementRepository statementRepository;
	private final AccountStateValidator accountStateValidator;

	public GetStatementService(StatementRepository statementRepository, AccountStateValidator accountStateValidator) {
		this.statementRepository = statementRepository;
		this.accountStateValidator = accountStateValidator;
	}

	@Override
	public GetStatementResult getStatement(GetStatementQuery query) {
		AccountId accountId;
		try {
			accountId = new AccountId(query.accountId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Set<PaymentStatus> statuses = statuses(query.status());
		LocalDate from = query.from() == null ? FIRST_DAY : query.from();
		LocalDate to = query.to() == null ? LAST_DAY : query.to();
		if (from.isBefore(FIRST_DAY) || to.isAfter(LAST_DAY)) {
			throw new PaymentValidationException("Dates must be between " + FIRST_DAY + " and " + LAST_DAY);
		}
		if (from.isAfter(to)) {
			throw new PaymentValidationException("from (" + from + ") is after to (" + to + ")");
		}
		if (query.page() < 0) {
			throw new PaymentValidationException("page must be 0 or more");
		}
		if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
			throw new PaymentValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		// 403, not 404 as for another customer's payment: the caller named the account, so its existence is no secret
		// to them. An unknown account is held by nobody, so it is a 403 too, and doesn't reveal which accounts exist.
		if (!accountStateValidator.isHeldBy(accountId, query.customerId())) {
			throw new AccessDeniedException(accountId);
		}
		StatementPage page = statementRepository.find(accountId, statuses, startOf(from), startOf(to.plusDays(1)),
				query.page(), query.size());
		return new GetStatementResult(page.lines().stream().map(GetStatementService::toLine).toList(), query.page(),
				query.size(), page.totalLines());
	}

	private static Set<PaymentStatus> statuses(String status) {
		if (status == null || status.isBlank()) {
			return SETTLED;
		}
		PaymentStatus requested;
		try {
			requested = PaymentStatus.valueOf(status.strip().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			requested = null;
		}
		if (!SETTLED.contains(requested)) {
			throw new PaymentValidationException("status must be one of " + SETTLED + ": the statement lists settled payments");
		}
		return EnumSet.of(requested);
	}

	private static Instant startOf(LocalDate day) {
		return day.atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	private static GetStatementResult.Line toLine(StatementLine line) {
		return new GetStatementResult.Line(line.paymentId().toString(), line.counterpartyAccountId().value(), line.amount(),
				line.currency().name(), line.status().name(), line.refundedAmount(), line.settledAt());
	}
}
