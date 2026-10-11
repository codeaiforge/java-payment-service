package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.StatementRepository;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What each payment event does to the statement, with the statement in memory. */
class ProjectPaymentEventServiceTest {

	private static final PaymentId PAYMENT = PaymentId.newId();
	private static final Instant SETTLED_AT = Instant.parse("2026-10-10T12:00:00Z");

	private final InMemoryStatement statement = new InMemoryStatement();
	private final ProjectPaymentEventService service = new ProjectPaymentEventService(statement,
			TransactionOperations.withoutTransaction());

	@Test
	void aCompletedPaymentBecomesALineOnItsSourceAccount() {
		PaymentEvent completed = paymentEvent(PaymentEventType.PAYMENT_COMPLETED);

		service.project(completed);

		assertThat(line()).isEqualTo(new StatementRepository.StatementLine(PAYMENT, new AccountId("ACC-1"),
				new AccountId("ACC-2"), new BigDecimal("250.00"), Currency.EUR, PaymentStatus.COMPLETED,
				new BigDecimal("0.00"), SETTLED_AT, completed.eventId()));
	}

	@Test
	void aFailedPaymentIsALineWithItsStatus() {
		service.project(paymentEvent(PaymentEventType.PAYMENT_FAILED));

		assertThat(line().status()).isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	void aCancelledPaymentIsALineWithItsStatus() {
		service.project(paymentEvent(PaymentEventType.PAYMENT_CANCELLED));

		assertThat(line().status()).isEqualTo(PaymentStatus.CANCELLED);
	}

	@Test
	void completedRefundsAreSummedAndFailedOnesAreNot() {
		service.project(paymentEvent(PaymentEventType.PAYMENT_COMPLETED));
		service.project(refundEvent(PaymentEventType.REFUND_COMPLETED, "100.00"));
		service.project(refundEvent(PaymentEventType.REFUND_FAILED, "50.00"));
		service.project(refundEvent(PaymentEventType.REFUND_COMPLETED, "25.50"));

		assertThat(line().refundedAmount()).isEqualByComparingTo("125.50");
		assertThat(line().amount()).isEqualByComparingTo("250.00");
		assertThat(line().status()).isEqualTo(PaymentStatus.COMPLETED);
	}

	@Test
	void anEventDeliveredTwiceCountsOnce() {
		service.project(paymentEvent(PaymentEventType.PAYMENT_COMPLETED));
		PaymentEvent refund = refundEvent(PaymentEventType.REFUND_COMPLETED, "100.00");

		service.project(refund);
		service.project(refund);

		assertThat(line().refundedAmount()).isEqualByComparingTo("100.00");
		assertThat(statement.saves).isEqualTo(2);
	}

	// The refund creates the line with what it knows; the payment's event fills in the rest and keeps the refund.
	@Test
	void aRefundBeforeItsPaymentCreatesTheLineAndThePaymentFillsItIn() {
		service.project(refundEvent(PaymentEventType.REFUND_COMPLETED, "100.00"));

		assertThat(line().status()).isNull();
		assertThat(line().amount()).isNull();
		assertThat(line().settledAt()).isNull();
		assertThat(line().accountId()).isEqualTo(new AccountId("ACC-1"));
		assertThat(line().refundedAmount()).isEqualByComparingTo("100.00");

		service.project(paymentEvent(PaymentEventType.PAYMENT_COMPLETED));

		assertThat(line().status()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(line().amount()).isEqualByComparingTo("250.00");
		assertThat(line().settledAt()).isEqualTo(SETTLED_AT);
		assertThat(line().refundedAmount()).isEqualByComparingTo("100.00");
	}

	private StatementRepository.StatementLine line() {
		return statement.lines.get(PAYMENT);
	}

	private static PaymentEvent paymentEvent(PaymentEventType type) {
		return new PaymentEvent(UUID.randomUUID(), type, PAYMENT, null, new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), SETTLED_AT);
	}

	private static PaymentEvent refundEvent(PaymentEventType type, String amount) {
		return new PaymentEvent(UUID.randomUUID(), type, PAYMENT, RefundId.newId(), new AccountId("ACC-1"),
				new AccountId("ACC-2"), new Money(new BigDecimal(amount), Currency.EUR), SETTLED_AT.plusSeconds(60));
	}

	private static class InMemoryStatement implements StatementRepository {

		final Set<UUID> processed = new HashSet<>();
		final Map<PaymentId, StatementLine> lines = new HashMap<>();
		int saves;

		@Override
		public boolean markProcessed(UUID eventId) {
			return processed.add(eventId);
		}

		@Override
		public Optional<StatementLine> findLine(PaymentId paymentId) {
			return Optional.ofNullable(lines.get(paymentId));
		}

		@Override
		public void save(StatementLine line) {
			saves++;
			lines.put(line.paymentId(), line);
		}

		@Override
		public StatementPage find(AccountId accountId, Set<PaymentStatus> statuses, Instant from, Instant to, int page,
				int size) {
			throw new AssertionError("projecting never reads the statement");
		}
	}
}
