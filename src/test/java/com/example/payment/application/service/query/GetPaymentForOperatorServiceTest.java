package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetPaymentForOperatorServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
	private final List<StatusTransition> trail = List.of(
			new StatusTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, Instant.parse("2026-11-23T10:00:00Z")));

	private final PaymentRepository repository = mock(PaymentRepository.class);
	private final GetPaymentForOperatorService service = new GetPaymentForOperatorService(repository, paymentId -> trail);

	// No account check: an operator holds no accounts, and still sees the payment and its trail.
	@Test
	void anOperatorSeesAPaymentTheyDoNotHold() {
		when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
		GetPaymentForOperatorQuery query = new GetPaymentForOperatorQuery(stored.id().toString(), "OPS-0001");

		assertThat(service.getPayment(query).paymentId()).isEqualTo(stored.id().toString());
		assertThat(service.getHistory(query)).isEqualTo(trail);
	}

	@Test
	void anUnknownPaymentIsNotFound() {
		GetPaymentForOperatorQuery query = new GetPaymentForOperatorQuery(PaymentId.newId().toString(), "OPS-0001");

		assertThatThrownBy(() -> service.getHistory(query)).isInstanceOf(PaymentNotFoundException.class);
	}

	@Test
	void aMalformedIdIsInvalid() {
		assertThatThrownBy(() -> service.getPayment(new GetPaymentForOperatorQuery("not-a-uuid", "OPS-0001")))
				.isInstanceOf(PaymentValidationException.class);
	}
}
