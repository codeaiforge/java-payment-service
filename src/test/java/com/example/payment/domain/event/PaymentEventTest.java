package com.example.payment.domain.event;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PaymentEventTest {

	private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

	@Test
	void eachFinalStatusHasItsEvent() {
		assertThat(PaymentEvent.finalStatusReached(payment(PaymentStatus.COMPLETED), NOW).type())
				.isEqualTo(PaymentEventType.PAYMENT_COMPLETED);
		assertThat(PaymentEvent.finalStatusReached(payment(PaymentStatus.FAILED), NOW).type())
				.isEqualTo(PaymentEventType.PAYMENT_FAILED);
		assertThat(PaymentEvent.finalStatusReached(payment(PaymentStatus.CANCELLED), NOW).type())
				.isEqualTo(PaymentEventType.PAYMENT_CANCELLED);
	}

	@Test
	void carriesThePaymentAndANewEventId() {
		Payment payment = payment(PaymentStatus.COMPLETED);

		PaymentEvent first = PaymentEvent.finalStatusReached(payment, NOW);
		PaymentEvent second = PaymentEvent.finalStatusReached(payment, NOW);

		assertThat(first.paymentId()).isEqualTo(payment.id());
		assertThat(first.sourceAccountId()).isEqualTo(payment.sourceAccountId());
		assertThat(first.destinationAccountId()).isEqualTo(payment.destinationAccountId());
		assertThat(first.amount()).isEqualTo(payment.amount());
		assertThat(first.reference()).isEqualTo(payment.reference());
		assertThat(first.occurredAt()).isEqualTo(NOW);
		assertThat(first.eventId()).isNotEqualTo(second.eventId());
	}

	@ParameterizedTest
	@EnumSource(value = PaymentStatus.class, names = {"CREATED", "AUTHORIZED", "PROCESSING"})
	void aPaymentStillOnItsWayHasNoEvent(PaymentStatus status) {
		assertThatIllegalStateException().isThrownBy(() -> PaymentEvent.finalStatusReached(payment(status), NOW))
				.withMessageEndingWith("is " + status + ", not final: no event");
	}

	@Test
	void aFinishedRefundHasItsEventWithTheRefundsAmount() {
		Payment payment = payment(PaymentStatus.COMPLETED);
		Refund refund = Refund.create(payment.id(), new Money(new BigDecimal("50.00"), Currency.EUR));
		refund.complete();

		PaymentEvent event = PaymentEvent.refundFinished(payment, refund, NOW);

		assertThat(event.type()).isEqualTo(PaymentEventType.REFUND_COMPLETED);
		assertThat(event.paymentId()).isEqualTo(payment.id());
		assertThat(event.refundId()).isEqualTo(refund.id());
		assertThat(event.amount()).isEqualTo(refund.amount());
		assertThat(event.reference()).isEqualTo(payment.reference());
		assertThat(PaymentEvent.finalStatusReached(payment, NOW).refundId()).isNull();
	}

	@Test
	void aRefundStillProcessingHasNoEvent() {
		Payment payment = payment(PaymentStatus.COMPLETED);
		Refund refund = Refund.create(payment.id(), new Money(new BigDecimal("50.00"), Currency.EUR));

		assertThatIllegalStateException().isThrownBy(() -> PaymentEvent.refundFinished(payment, refund, NOW));
		refund.fail();
		assertThat(PaymentEvent.refundFinished(payment, refund, NOW).type()).isEqualTo(PaymentEventType.REFUND_FAILED);
	}

	private static Payment payment(PaymentStatus status) {
		return Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"), status);
	}
}
