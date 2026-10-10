package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The same rules as ReconcilePaymentsServiceTest, for a refund whose answer was lost. */
class ReconcileRefundsServiceTest {

	private final Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"), PaymentStatus.COMPLETED);
	private final Refund refund = Refund.restore(RefundId.newId(), payment.id(),
			new Money(new BigDecimal("40.00"), Currency.EUR), RefundStatus.PROCESSING);

	private final RefundRepository refunds = mock(RefundRepository.class);
	private final PaymentRepository payments = mock(PaymentRepository.class);
	private final PaymentExecutionPort paymentSystem = mock(PaymentExecutionPort.class);
	private final PaymentMetricsPort metrics = mock(PaymentMetricsPort.class);
	private final List<String> audited = new ArrayList<>();
	private final List<PaymentEvent> events = new ArrayList<>();

	private final ReconcileRefundsService service = new ReconcileRefundsService(refunds, payments, paymentSystem,
			AuditTrail.into(audited), events::add, metrics, TransactionOperations.withoutTransaction());

	@BeforeEach
	void aRefundIsStuck() {
		when(refunds.findProcessingSince(any())).thenReturn(List.of(refund));
		when(refunds.updateStatus(refund, RefundStatus.PROCESSING)).thenReturn(true);
		when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
	}

	@Test
	void aSettledRefundIsCompletedWithItsAuditRecordAndEvent() {
		when(paymentSystem.findRefundOutcome(refund)).thenReturn(Outcome.EXECUTED);

		service.reconcileRefunds(Duration.ofMinutes(2));

		assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
		verify(refunds).updateStatus(refund, RefundStatus.PROCESSING);
		assertThat(audited).containsExactly("PROCESSING->COMPLETED");
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(PaymentEventType.REFUND_COMPLETED);
			assertThat(event.refundId()).isEqualTo(refund.id());
		});
		verify(metrics).refundFinished(RefundStatus.COMPLETED);
	}

	// Never received: no money went back yet, so the refund fails (the key stops a duplicate, not a delayed first order).
	@Test
	void aRefundThePaymentSystemNeverReceivedIsFailed() {
		when(paymentSystem.findRefundOutcome(refund)).thenReturn(Outcome.NOT_RECEIVED);

		service.reconcileRefunds(Duration.ofMinutes(2));

		assertThat(audited).containsExactly("PROCESSING->FAILED");
		assertThat(events).extracting(PaymentEvent::type).containsExactly(PaymentEventType.REFUND_FAILED);
	}

	@Test
	void stillNoAnswerLeavesItProcessingForTheNextRun() {
		when(paymentSystem.findRefundOutcome(refund)).thenReturn(Outcome.UNKNOWN);

		service.reconcileRefunds(Duration.ofMinutes(2));

		assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
		verify(refunds, never()).updateStatus(any(), any());
		assertThat(audited).isEmpty();
		assertThat(events).isEmpty();
	}

	@Test
	void aRefundAnotherRunReconciledFirstIsLeftAlone() {
		when(paymentSystem.findRefundOutcome(refund)).thenReturn(Outcome.EXECUTED);
		when(refunds.updateStatus(refund, RefundStatus.PROCESSING)).thenReturn(false);

		service.reconcileRefunds(Duration.ofMinutes(2));

		assertThat(audited).isEmpty();
		assertThat(events).isEmpty();
		verify(metrics, never()).refundFinished(eq(RefundStatus.COMPLETED));
	}
}
