package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
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

/**
 * Runs against real PostgreSQL with the Flyway schema, so it proves the
 * entity, the mapping in both directions and the migration agree.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, PaymentPersistenceAdapter.class})
class PaymentPersistenceAdapterTest {

	@Autowired
	PaymentPersistenceAdapter adapter;

	@Autowired
	TestEntityManager entityManager;

	@Test
	void storesEveryFieldOfThePayment() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

		adapter.save(payment);
		entityManager.flush();
		entityManager.clear(); // read back from the table, not from Hibernate's cache

		PaymentEntity row = entityManager.find(PaymentEntity.class, payment.id().value());
		assertThat(row.getSourceAccountId()).isEqualTo("ACC-10001");
		assertThat(row.getDestinationAccountId()).isEqualTo("ACC-20001");
		assertThat(row.getAmount()).isEqualByComparingTo("250.00");
		assertThat(row.getCurrency()).isEqualTo("EUR");
		assertThat(row.getReference()).isEqualTo("Invoice 12345");
		assertThat(row.getStatus()).isEqualTo("CREATED");
	}

	@Test
	void readsBackTheSamePayment() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
		adapter.save(payment);
		entityManager.flush();
		entityManager.clear();

		Payment found = adapter.findById(payment.id()).orElseThrow();

		assertThat(found).usingRecursiveComparison().isEqualTo(payment);
	}

	@Test
	void updatesTheStatusOnlyFromTheExpectedOne() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
		adapter.save(payment);
		entityManager.flush();
		payment.authorize();

		assertThat(adapter.updateStatus(payment, PaymentStatus.CREATED)).isTrue();
		// A second request that also read it as CREATED is too late.
		assertThat(adapter.updateStatus(payment, PaymentStatus.CREATED)).isFalse();

		entityManager.clear();
		assertThat(adapter.findById(payment.id()).orElseThrow().status()).isEqualTo(PaymentStatus.AUTHORIZED);
	}

	@Test
	void findsPaymentsProcessingSinceBeforeTheGivenTime() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));
		adapter.save(payment);
		payment.authorize();
		adapter.updateStatus(payment, PaymentStatus.CREATED);
		payment.startProcessing();
		// A millisecond back: PostgreSQL keeps microseconds, so a time taken just before the update can round to the same value.
		Instant beforeProcessing = Instant.now().minusMillis(1);
		adapter.updateStatus(payment, PaymentStatus.AUTHORIZED);
		entityManager.clear();

		// The status change stamped the row: it is not stuck as of a moment before it, and is as of now.
		assertThat(adapter.findProcessingSince(beforeProcessing)).isEmpty();
		assertThat(adapter.findProcessingSince(Instant.now())).extracting(Payment::id).contains(payment.id());
	}

	@Test
	void findsNothingForAnUnknownId() {
		assertThat(adapter.findById(PaymentId.newId())).isEmpty();
	}
}
