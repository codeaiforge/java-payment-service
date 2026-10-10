package com.example.payment.application.service.command;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort.Execution;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.entity.Refund;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExecutePaymentServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	// The statuses the payment had each time it was saved, in order.
	private final List<PaymentStatus> savedStatuses = new ArrayList<>();
	private final List<PaymentStatus> statusWhenSent = new ArrayList<>();
	private int authorizationRequests;
	private boolean authorized = true;
	private boolean withinLimit = true;
	private final List<String> audited = new ArrayList<>();
	private final AuditPort audit = AuditTrail.into(audited);
	private final List<PaymentEvent> events = new ArrayList<>();
	private final PaymentMetricsPort metrics = mock(PaymentMetricsPort.class);
	// What the stored status would be if another request had changed it after we read the payment.
	private PaymentStatus changedByAnotherRequest;

	private final PaymentRepository repository = new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new AssertionError("execute only updates existing payments");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			if (expected == changedByAnotherRequest) {
				return false;
			}
			savedStatuses.add(payment.status());
			return true;
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}

		@Override
		public Optional<Payment> findByIdForUpdate(PaymentId paymentId) {
			throw new AssertionError("only refunds lock a payment");
		}

		@Override
		public List<Payment> findProcessingSince(Instant before) {
			throw new AssertionError("only reconciliation looks for stuck payments");
		}
	};

	@Test
	void completesAPaymentThePaymentSystemExecutes() {
		Payment result = serviceAnswering(Outcome.EXECUTED).executePayment(command(stored.id()));

		assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(statusWhenSent).containsExactly(PaymentStatus.PROCESSING);
		// PROCESSING is stored before the payment system is called, so a crash can't leave it looking unsent.
		assertThat(savedStatuses).containsExactly(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING,
				PaymentStatus.COMPLETED);
		assertThat(audited).containsExactly("CREATED->AUTHORIZED", "AUTHORIZED->PROCESSING", "PROCESSING->COMPLETED");
		// One event, for the final status only.
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(PaymentEventType.PAYMENT_COMPLETED);
			assertThat(event.paymentId()).isEqualTo(stored.id());
			assertThat(event.amount()).isEqualTo(stored.amount());
		});
		verify(metrics).executionEnded(Execution.COMPLETED);
	}

	@Test
	void losesToARequestThatChangedThePaymentFirstAndSendsNothing() {
		// e.g. a concurrent execute or cancel moved it on after we read it as AUTHORIZED
		changedByAnotherRequest = PaymentStatus.AUTHORIZED;

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> serviceAnswering(Outcome.EXECUTED).executePayment(command(stored.id())))
				.withMessage("Payment " + stored.id() + " was changed by another request and cannot become PROCESSING");
		assertThat(statusWhenSent).isEmpty();
		assertThat(audited).containsExactly("CREATED->AUTHORIZED");
		assertThat(events).isEmpty();
	}

	@Test
	void failsAPaymentThePaymentSystemRejects() {
		Payment result = serviceAnswering(Outcome.REJECTED).executePayment(command(stored.id()));

		assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(savedStatuses).containsExactly(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING,
				PaymentStatus.FAILED);
		assertThat(audited).endsWith("PROCESSING->FAILED");
		assertThat(events).extracting(PaymentEvent::type).containsExactly(PaymentEventType.PAYMENT_FAILED);
		verify(metrics).executionEnded(Execution.REFUSED);
	}

	@Test
	void anUnknownOutcomeLeavesThePaymentProcessing() {
		Payment result = serviceAnswering(Outcome.UNKNOWN).executePayment(command(stored.id()));

		// Neither COMPLETED nor FAILED would be true. PROCESSING is, and it blocks a second execute.
		assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(savedStatuses).containsExactly(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING);
		assertThat(audited).containsExactly("CREATED->AUTHORIZED", "AUTHORIZED->PROCESSING");
		assertThat(events).isEmpty(); // nothing final has happened yet
		verify(metrics).executionEnded(Execution.ERROR);
	}

	@Test
	void aDeclinedPaymentIsNeitherStoredNorSent() {
		authorized = false;

		assertThatExceptionOfType(PaymentAuthorizationException.class)
				.isThrownBy(() -> serviceAnswering(Outcome.EXECUTED).executePayment(command(stored.id())))
				.withMessage("Payment " + stored.id() + " was not authorized");
		assertThat(stored.status()).isEqualTo(PaymentStatus.CREATED);
		assertThat(savedStatuses).isEmpty();
		assertThat(statusWhenSent).isEmpty();
		assertThat(audited).isEmpty();
		verify(metrics).executionEnded(Execution.REFUSED);
	}

	@Test
	void aPaymentOverItsLimitStaysAuthorizedAndIsNotSent() {
		withinLimit = false;

		assertThatExceptionOfType(PaymentLimitExceededException.class)
				.isThrownBy(() -> serviceAnswering(Outcome.EXECUTED).executePayment(command(stored.id())))
				.withMessage("Payment " + stored.id() + " exceeds the source account's limit");
		assertThat(savedStatuses).containsExactly(PaymentStatus.AUTHORIZED);
		assertThat(statusWhenSent).isEmpty();
		verify(metrics).executionEnded(Execution.REFUSED);
	}

	@Test
	void retryAfterTheLimitAllowsItIsNotAuthorizedAgain() {
		Payment authorizedEarlier = Payment.restore(stored.id(), stored.sourceAccountId(),
				stored.destinationAccountId(), stored.amount(), stored.reference(), PaymentStatus.AUTHORIZED);
		ExecutePaymentService service = new ExecutePaymentService(new PaymentRepository() {
			@Override
			public void save(Payment payment) {
				throw new AssertionError("execute only updates existing payments");
			}

			@Override
			public boolean updateStatus(Payment payment, PaymentStatus expected) {
				return true;
			}

			@Override
			public Optional<Payment> findById(PaymentId paymentId) {
				return Optional.of(authorizedEarlier);
			}

			@Override
			public Optional<Payment> findByIdForUpdate(PaymentId paymentId) {
				throw new AssertionError("only refunds lock a payment");
			}

			@Override
			public List<Payment> findProcessingSince(Instant before) {
				throw new AssertionError("only reconciliation looks for stuck payments");
			}
		}, this::authorize, payment -> true, executing(payment -> Outcome.EXECUTED), audit, events::add, mock(PaymentMetricsPort.class), TransactionOperations.withoutTransaction());

		assertThat(service.executePayment(command(stored.id())).status()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(authorizationRequests).isZero();
	}

	@Test
	void refusesToExecuteTheSamePaymentTwice() {
		ExecutePaymentService service = serviceAnswering(Outcome.EXECUTED);
		service.executePayment(command(stored.id()));

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.executePayment(command(stored.id())))
				.withMessage("Payment " + stored.id() + " is COMPLETED and cannot become PROCESSING");
		assertThat(statusWhenSent).hasSize(1);
		// A finished payment is refused before any external system is asked again.
		assertThat(authorizationRequests).isEqualTo(1);
		verify(metrics).executionEnded(Execution.COMPLETED); // once: the second wasn't an execution
	}

	@Test
	void rejectsAnUnknownPayment() {
		UUID unknown = UUID.randomUUID();

		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> serviceAnswering(Outcome.EXECUTED).executePayment(new ExecutePaymentCommand(unknown.toString(), null)));
		assertThat(statusWhenSent).isEmpty();
		verifyNoInteractions(metrics);
	}

	@Test
	void rejectsAMalformedPaymentId() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> serviceAnswering(Outcome.EXECUTED).executePayment(new ExecutePaymentCommand("not-a-uuid", null)))
				.withMessage("Invalid payment id: not-a-uuid");
	}

	// The success SLO's input (docs/episodes/bonus-08): a business "no" is not an error, no answer is.
	@Test
	void anExternalSystemThatIsDownCountsAsAnErrorForTheSuccessSlo() {
		ExecutePaymentService service = new ExecutePaymentService(repository, this::authorize, payment -> true,
				executing(payment -> {
					throw new ExternalSystemUnavailableException("payment", new RuntimeException("connection refused"));
				}), audit, events::add, metrics, TransactionOperations.withoutTransaction());

		assertThatExceptionOfType(ExternalSystemUnavailableException.class)
				.isThrownBy(() -> service.executePayment(command(stored.id())));
		verify(metrics).executionEnded(Execution.ERROR);
	}

	private ExecutePaymentService serviceAnswering(Outcome outcome) {
		return new ExecutePaymentService(repository, this::authorize, payment -> withinLimit, executing(payment -> {
			statusWhenSent.add(payment.status());
			return outcome;
		}), audit, events::add, metrics, TransactionOperations.withoutTransaction());
	}

	// Execute only sends; asking about a stuck payment is reconciliation's job.
	private static PaymentExecutionPort executing(Function<Payment, Outcome> answer) {
		return new PaymentExecutionPort() {
			@Override
			public Outcome execute(Payment payment) {
				return answer.apply(payment);
			}

			@Override
			public Outcome refund(Refund refund) {
				throw new AssertionError("no refunds here");
			}

			@Override
			public Outcome findOutcome(Payment payment) {
				throw new AssertionError("execute never asks about an outcome");
			}

			@Override
			public Outcome findRefundOutcome(Refund refund) {
				throw new AssertionError("no refunds here");
			}
		};
	}

	private boolean authorize(Payment payment) {
		authorizationRequests++;
		return authorized;
	}

	private static ExecutePaymentCommand command(PaymentId id) {
		return new ExecutePaymentCommand(id.toString(), null); // as the consumer sends it: no caller
	}
}
