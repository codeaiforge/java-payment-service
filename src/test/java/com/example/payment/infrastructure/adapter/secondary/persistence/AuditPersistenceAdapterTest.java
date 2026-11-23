package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Real PostgreSQL with the Flyway schema: the audit trail is written and read back as the history shows it. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, AuditPersistenceAdapter.class, PaymentPersistenceAdapter.class})
class AuditPersistenceAdapterTest {

	@Autowired
	AuditPersistenceAdapter adapter;

	@Autowired
	PaymentPersistenceAdapter payments;

	@Autowired
	TestEntityManager entityManager;

	@Test
	void readsBackOnePaymentsTransitionsInTheOrderTheyWereStored() {
		Payment payment = storedPayment();
		Payment other = storedPayment();
		adapter.recordTransition(payment.id(), PaymentStatus.CREATED, PaymentStatus.AUTHORIZED);
		adapter.recordTransition(other.id(), PaymentStatus.CREATED, PaymentStatus.CANCELLED);
		adapter.recordTransition(payment.id(), PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING);
		entityManager.flush();
		entityManager.clear();

		assertThat(adapter.findTransitions(payment.id()))
				.extracting(StatusTransition::from, StatusTransition::to)
				.containsExactly(
						tuple(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED),
						tuple(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING));
	}

	// Another instance's clock ran behind: its later row carries an earlier time. The order is still the stored one.
	@Test
	void ordersByStorageNotByEachInstancesClock() {
		Payment payment = storedPayment();
		Instant now = Instant.now();
		entityManager.persist(new AuditEntity(payment.id().value(), "CREATED", "AUTHORIZED", now));
		entityManager.persist(new AuditEntity(payment.id().value(), "AUTHORIZED", "PROCESSING", now.minusSeconds(5)));
		entityManager.flush();
		entityManager.clear();

		assertThat(adapter.findTransitions(payment.id())).extracting(StatusTransition::to)
				.containsExactly(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING);
	}

	@Test
	void aPaymentWithNoChangesHasAnEmptyHistory() {
		assertThat(adapter.findTransitions(storedPayment().id())).isEmpty();
	}

	private Payment storedPayment() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
		payments.save(payment);
		entityManager.flush();
		return payment;
	}
}
