package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter.Pending;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The relay's own rules, without a broker: the end-to-end test sends through a real Kafka. */
class PaymentEventRelayTest {

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, com.example.payment.events.PaymentEvent> kafka = mock(KafkaTemplate.class);
	private final OutboxPersistenceAdapter outbox = mock(OutboxPersistenceAdapter.class);
	private final PaymentEventRelay relay = new PaymentEventRelay(outbox, kafka, 100, Duration.ofDays(7),
			new SimpleMeterRegistry(), Tracer.NOOP, TransactionOperations.withoutTransaction());

	private final Pending first = pending(1);
	private final Pending second = pending(2);
	private final Pending third = pending(3);

	// Tracing is the end-to-end test's subject; here every send is in no trace at all.
	@BeforeEach
	void noTrace() {
		when(outbox.spanFor(any())).thenAnswer(invocation -> Tracer.NOOP.spanBuilder());
	}

	@Test
	void sendsInOrderAndMarksEachPublishedAfterTheAck() {
		when(outbox.claimUnpublished(100)).thenReturn(List.of(first, second));
		when(kafka.send(any(), any(), any())).thenReturn(acked());

		relay.relay();

		InOrder order = inOrder(kafka, outbox);
		order.verify(kafka).send("payment-events", key(first), PaymentEventMessage.from(first.event()));
		order.verify(outbox).markPublished(first);
		order.verify(kafka).send("payment-events", key(second), PaymentEventMessage.from(second.event()));
		order.verify(outbox).markPublished(second);
	}

	// The broker is likely down: stop rather than wait out every send's deadline. The claim ends with the run.
	@Test
	void stopsAtTheFirstFailure() {
		when(outbox.claimUnpublished(100)).thenReturn(List.of(first, second, third));
		when(kafka.send(any(), any(), any())).thenReturn(acked());
		when(kafka.send(any(), eq(key(second)), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

		relay.relay();

		verify(outbox).markPublished(first);
		verify(outbox, never()).markPublished(second);
		verify(kafka, never()).send(any(), eq(key(third)), any());
	}

	// Batch after batch, until one comes back short: nothing older than the retention is left.
	@Test
	void deletesPublishedRowsOlderThanTheRetentionInBatches() {
		when(outbox.deletePublishedBefore(any(), eq(1000))).thenReturn(1000, 1000, 7);

		relay.deletePublished();

		verify(outbox, times(3)).deletePublishedBefore(any(), eq(1000));
	}

	private static CompletableFuture<SendResult<String, com.example.payment.events.PaymentEvent>> acked() {
		return CompletableFuture.completedFuture(null);
	}

	private static String key(Pending pending) {
		return pending.event().paymentId().value().toString();
	}

	private static Pending pending(long position) {
		Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"),
				PaymentStatus.COMPLETED);
		return new Pending(position, PaymentEvent.finalStatusReached(payment, Instant.now()), null);
	}
}
