package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.AuditQueryPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentHistoryQuery;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetPaymentHistoryServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
	private final List<StatusTransition> trail = List.of(
			new StatusTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, Instant.parse("2026-11-23T10:00:00Z")));

	private final PaymentRepository repository = mock(PaymentRepository.class);
	// The trail is there for any id: the service must not hand it out before the ownership check.
	private final AuditQueryPort audit = paymentId -> trail;
	// CUST-1 holds ACC-1, the payment's source.
	private final GetPaymentHistoryService service = new GetPaymentHistoryService(repository, audit,
			new AccountStateValidator(accountId -> Optional.of(new Account(AccountStatus.ACTIVE, "CUST-1"))
					.filter(account -> accountId.value().equals("ACC-1"))));

	@Test
	void theHolderGetsTheTrail() {
		when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

		assertThat(service.getHistory(new GetPaymentHistoryQuery(stored.id().toString(), "CUST-1"))).isEqualTo(trail);
	}

	// The same answer as GetPaymentService gives: another customer can't tell the payment exists.
	@Test
	void anotherCustomerGetsNotFoundAndNoTrail() {
		when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.getHistory(new GetPaymentHistoryQuery(stored.id().toString(), "CUST-2")))
				.withMessage("Payment " + stored.id() + " does not exist");
	}

	@Test
	void anUnknownPaymentIsNotFound() {
		when(repository.findById(stored.id())).thenReturn(Optional.empty());

		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.getHistory(new GetPaymentHistoryQuery(stored.id().toString(), "CUST-1")));
	}

	@Test
	void aMalformedIdIsInvalid() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getHistory(new GetPaymentHistoryQuery("not-a-uuid", "CUST-1")));
	}
}
