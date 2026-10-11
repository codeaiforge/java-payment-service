package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.RefundId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements PaymentEventPort with the transactional outbox: recording an
 * event is an INSERT that joins the caller's transaction, so it commits or
 * rolls back with the status change. Nothing is sent from here.
 *
 * The other methods are for PaymentEventRelay, which sends the rows on and
 * later deletes them.
 *
 * Each row also keeps the W3C traceparent of the trace the change happened
 * in, so the relay's send, seconds later on another thread, joins that trace
 * (docs/episodes/bonus-05). It stays here, next to the row: the event is a
 * business fact, and which trace recorded it is not one.
 */
@Component
public class OutboxPersistenceAdapter implements PaymentEventPort {

	private static final String TRACEPARENT = "traceparent";

	private final OutboxJpaRepository jpaRepository;
	private final Tracer tracer;
	private final Propagator propagator;

	OutboxPersistenceAdapter(OutboxJpaRepository jpaRepository, Tracer tracer, Propagator propagator) {
		this.jpaRepository = jpaRepository;
		this.tracer = tracer;
		this.propagator = propagator;
	}

	@Override
	public void record(PaymentEvent event) {
		jpaRepository.save(new OutboxEntity(event.eventId(), event.type().name(), event.paymentId().value(),
				event.refundId() == null ? null : event.refundId().value(), event.sourceAccountId().value(),
				event.destinationAccountId().value(), event.amount().amount(),
				event.amount().currency().name(), event.reference() == null ? null : event.reference().value(),
				event.occurredAt(), currentTraceparent()));
	}

	/**
	 * Up to {@code max} events to publish, oldest first, at most one per payment (its oldest unpublished one).
	 * They stay claimed, invisible to every other relay, until the caller's transaction ends; call it in one.
	 */
	public List<Pending> claimUnpublished(int max) {
		return jpaRepository.claimUnpublished(max).stream()
				.map(row -> new Pending(row.getId(), new PaymentEvent(row.getEventId(),
						PaymentEventType.valueOf(row.getEventType()), new PaymentId(row.getPaymentId()),
						row.getRefundId() == null ? null : new RefundId(row.getRefundId()),
						new AccountId(row.getSourceAccountId()), new AccountId(row.getDestinationAccountId()),
						new Money(row.getAmount(), Currency.of(row.getCurrency())),
						row.getReference() == null ? null : new PaymentReference(row.getReference()), row.getOccurredAt()),
						row.getTraceContext()))
				.toList();
	}

	public void markPublished(Pending pending) {
		jpaRepository.markPublished(pending.position(), Instant.now());
	}

	/** Deletes up to {@code max} events published before {@code before}; returns how many it deleted. */
	public int deletePublishedBefore(Instant before, int max) {
		return jpaRepository.deletePublishedBefore(before, max);
	}

	public long countUnpublished() {
		return jpaRepository.countByPublishedAtIsNull();
	}

	/**
	 * The trace to send this event in, as PaymentEventRelay needs it: a span started from here is a child of the
	 * span that recorded the change. With no stored trace context, a new trace.
	 */
	public Span.Builder spanFor(Pending pending) {
		return pending.traceContext() == null ? tracer.spanBuilder()
				: propagator.extract(Map.of(TRACEPARENT, pending.traceContext()), Map::get);
	}

	// The same header a Kafka message or an HTTP call would carry. Null outside any trace.
	private String currentTraceparent() {
		Span span = tracer.currentSpan();
		if (span == null) {
			return null;
		}
		Map<String, String> carrier = new HashMap<>();
		propagator.inject(span.context(), carrier, Map::put);
		return carrier.get(TRACEPARENT);
	}

	/**
	 * An unpublished event, its place in the outbox (also its place in the publish order), and the traceparent of
	 * the change that recorded it, or null.
	 */
	public record Pending(long position, PaymentEvent event, String traceContext) {
	}
}
