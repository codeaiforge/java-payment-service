package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.events.PaymentEvent;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter.Pending;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;

/**
 * Moves events from the outbox table to Kafka. Not a primary adapter: it calls
 * no use case and decides nothing about payments. It is the second half of the
 * PaymentEventPort adapter, the half that runs after the transaction.
 *
 * A row is marked published only after the broker acknowledged it. A crash in
 * between sends it again on the next run: at least once, never lost.
 *
 * Each run is a new trace: nothing called it. Each send joins the trace of the
 * change that recorded the event instead (docs/episodes/bonus-05), through a
 * span whose parent is the traceparent stored in the row.
 *
 * Every instance runs a relay (docs/episodes/bonus-07). Each batch is one
 * transaction that claims its rows (SELECT ... FOR UPDATE SKIP LOCKED), so two
 * relays never send the same row while both are alive, and only each payment's
 * oldest unpublished event is claimed, so one payment's events stay in order
 * whichever relay sends them.
 */
@Component
class PaymentEventRelay {

	private static final Logger log = LoggerFactory.getLogger(PaymentEventRelay.class);
	private static final int CLEANUP_BATCH_SIZE = 1000;

	private final OutboxPersistenceAdapter outbox;
	private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
	private final int batchSize;
	private final Duration retention;
	private final Tracer tracer;
	private final TransactionOperations transactions;

	PaymentEventRelay(OutboxPersistenceAdapter outbox, KafkaTemplate<String, PaymentEvent> kafkaTemplate,
			@Value("${payment.events.relay-batch-size}") int batchSize, @Value("${payment.events.retention}") Duration retention,
			MeterRegistry registry, Tracer tracer, TransactionOperations transactions) {
		this.outbox = outbox;
		this.kafkaTemplate = kafkaTemplate;
		this.batchSize = batchSize;
		this.retention = retention;
		this.tracer = tracer;
		this.transactions = transactions;
		// How far behind the relay is. Read from the table on each scrape.
		Gauge.builder("payment.events.pending", outbox, OutboxPersistenceAdapter::countUnpublished).register(registry);
	}

	// The claimed rows stay locked while they are sent: up to batchSize sends, each with the producer's deadlines.
	// A payment with several unpublished events sends one per run, the next once the earlier one is marked.
	@Scheduled(fixedDelayString = "${payment.events.relay-interval}")
	void relay() {
		transactions.executeWithoutResult(tx -> sendClaimed());
	}

	private void sendClaimed() {
		for (Pending pending : outbox.claimUnpublished(batchSize)) {
			String key = pending.event().paymentId().value().toString();
			// The KafkaTemplate's own send span becomes a child of this one, so it lands in the change's trace.
			Span span = outbox.spanFor(pending).name("payment-events relay").start();
			try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
				kafkaTemplate.send(PaymentEventMessage.TOPIC, key, PaymentEventMessage.from(pending.event())).join();
			}
			// Stop here: the broker or the schema registry is likely down, or the registry refused the schema
			// (docs/episodes/bonus-10), and each further send would fail the same way, or wait out its deadline holding the
			// claim. What was sent so far commits as published. This payment's later events aren't claimable until this one is.
			// RuntimeException, not only KafkaException: the registry's errors come straight out of send(), unwrapped.
			catch (RuntimeException e) {
				span.error(e);
				log.warn("Event {} not published, will retry on the next run: {}", pending.event().eventId(), e.getMessage());
				return;
			}
			finally {
				span.end();
			}
			outbox.markPublished(pending);
		}
	}

	// A published row is the record of what was sent, and what an operator re-publishes by hand (reset published_at)
	// if a consumer lost it. Kept for the retention period, then deleted. One instance at a time: two would only race
	// to delete the same rows.
	@Scheduled(fixedDelayString = "${payment.events.cleanup-interval}")
	@SchedulerLock(name = "outbox-cleanup")
	void deletePublished() {
		Instant before = Instant.now().minus(retention);
		int deleted;
		int total = 0;
		do {
			deleted = outbox.deletePublishedBefore(before, CLEANUP_BATCH_SIZE);
			total += deleted;
		}
		while (deleted == CLEANUP_BATCH_SIZE);
		if (total > 0) {
			log.info("Deleted {} outbox rows published before {}", total, before);
		}
	}
}
