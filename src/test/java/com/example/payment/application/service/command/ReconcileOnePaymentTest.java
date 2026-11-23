package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ReconcilePaymentCommand;
import com.example.payment.application.usecase.command.ReconcilePaymentResult;
import com.example.payment.application.usecase.command.ReconcilePaymentResult.Answer;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ReconcilePaymentsService's single-payment path: an operator settling one payment by id. */
class ReconcileOnePaymentTest {

	private static final Duration THRESHOLD = Duration.ofMinutes(2);

	private final PaymentRepository repository = mock(PaymentRepository.class);
	private final PaymentExecutionPort paymentSystem = mock(PaymentExecutionPort.class);
	private final List<String> audited = new ArrayList<>();
	private final List<PaymentEvent> events = new ArrayList<>();
	private final PaymentMetricsPort metrics = mock(PaymentMetricsPort.class);

	private final ReconcilePaymentsService service = new ReconcilePaymentsService(repository, paymentSystem,
			AuditTrail.into(audited), events::add, metrics, TransactionOperations.withoutTransaction());

	// Young on purpose: the payment system's answer is final, so the operator needn't wait for the threshold.
	@Test
	void anExecutedPaymentIsCompletedEvenBeforeTheThreshold() {
		Payment payment = processingPaymentAnswered(Outcome.EXECUTED);

		ReconcilePaymentResult result = reconcile(payment);

		assertThat(result).isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.EXECUTED, PaymentStatus.COMPLETED, true));
		assertThat(audited).containsExactly("PROCESSING->COMPLETED");
		assertThat(events).hasSize(1);
		verify(metrics).reconciled(Outcome.EXECUTED);
	}

	@Test
	void aRejectedPaymentIsFailed() {
		Payment payment = processingPaymentAnswered(Outcome.REJECTED);

		assertThat(reconcile(payment))
				.isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.REJECTED, PaymentStatus.FAILED, true));
		assertThat(audited).containsExactly("PROCESSING->FAILED");
	}

	@Test
	void aPaymentNotReceivedPastTheThresholdIsFailed() {
		Payment payment = processingPaymentAnswered(Outcome.NOT_RECEIVED);
		when(repository.findProcessingSince(any())).thenReturn(List.of(payment));

		assertThat(reconcile(payment))
				.isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.NOT_RECEIVED, PaymentStatus.FAILED, true));
		assertThat(audited).containsExactly("PROCESSING->FAILED");
	}

	// The order may still be in flight and pay after we fail it: only the scheduled run, past the threshold, may.
	@Test
	void aPaymentNotReceivedBeforeTheThresholdStaysProcessing() {
		Payment payment = processingPaymentAnswered(Outcome.NOT_RECEIVED);
		when(repository.findProcessingSince(any())).thenReturn(List.of());

		assertThat(reconcile(payment))
				.isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.NOT_RECEIVED, PaymentStatus.PROCESSING, false));
		assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
		verify(repository, never()).updateStatus(any(), any());
		assertThat(audited).isEmpty();
	}

	@Test
	void stillNoAnswerLeavesItProcessing() {
		Payment payment = processingPaymentAnswered(Outcome.UNKNOWN);

		assertThat(reconcile(payment))
				.isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.UNKNOWN, PaymentStatus.PROCESSING, false));
		verify(repository, never()).updateStatus(any(), any());
		assertThat(audited).isEmpty();
	}

	@Test
	void aPaymentThatIsNotProcessingIsRefusedWithoutAsking() {
		Payment payment = payment(PaymentStatus.COMPLETED);

		assertThatThrownBy(() -> reconcile(payment))
				.isInstanceOf(InvalidPaymentStateException.class)
				.hasMessageContaining("is COMPLETED");
		verify(paymentSystem, never()).findOutcome(any());
	}

	@Test
	void anUnknownPaymentIsNotFound() {
		assertThatThrownBy(() -> service.reconcile(new ReconcilePaymentCommand(PaymentId.newId().toString(), "OPS-0001"),
				THRESHOLD)).isInstanceOf(PaymentNotFoundException.class);
	}

	// The scheduled run stored the outcome between our read and our write: report what it stored.
	@Test
	void aPaymentAnotherRunSettledFirstReportsTheStoredStatus() {
		Payment payment = processingPaymentAnswered(Outcome.EXECUTED);
		when(repository.updateStatus(any(), eq(PaymentStatus.PROCESSING))).thenReturn(false);
		Payment storedByTheRun = Payment.restore(payment.id(), payment.sourceAccountId(), payment.destinationAccountId(),
				payment.amount(), payment.reference(), PaymentStatus.COMPLETED);
		when(repository.findById(payment.id())).thenReturn(Optional.of(payment)).thenReturn(Optional.of(storedByTheRun));

		assertThat(reconcile(payment))
				.isEqualTo(new ReconcilePaymentResult(payment.id(), Answer.EXECUTED, PaymentStatus.COMPLETED, false));
		assertThat(audited).isEmpty();
		assertThat(events).isEmpty();
	}

	private ReconcilePaymentResult reconcile(Payment payment) {
		return service.reconcile(new ReconcilePaymentCommand(payment.id().toString(), "OPS-0001"), THRESHOLD);
	}

	private Payment processingPaymentAnswered(Outcome outcome) {
		Payment payment = payment(PaymentStatus.PROCESSING);
		when(paymentSystem.findOutcome(payment)).thenReturn(outcome);
		when(repository.updateStatus(payment, PaymentStatus.PROCESSING)).thenReturn(true);
		return payment;
	}

	private Payment payment(PaymentStatus status) {
		Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"), status);
		when(repository.findById(payment.id())).thenReturn(Optional.of(payment));
		return payment;
	}
}
