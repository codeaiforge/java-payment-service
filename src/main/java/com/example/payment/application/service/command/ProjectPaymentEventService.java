package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.ProjectPaymentEventUseCase;
import com.example.payment.application.port.secondary.StatementRepository;
import com.example.payment.application.port.secondary.StatementRepository.StatementLine;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;

/**
 * Keeps the payment statement in step with payment events (docs/episodes/bonus-09).
 * The statement is a copy, so nothing here decides anything about a payment: each
 * event is a fact that already happened, and is written down as it says.
 *
 * Kafka delivers at least once. The event is recorded as applied in the same
 * transaction as the line it changed, so a second delivery finds it and stops.
 *
 * Events of one payment arrive in order (one key, one partition, B3), but the
 * line doesn't count on it: a refund event can come first after a rebuild from a
 * topic missing the payment's event, a retry topic, or an event re-sent by hand.
 * Then the line is created from what the refund event carries, and the payment's
 * event fills in the rest when it comes.
 */
@Service
public class ProjectPaymentEventService implements ProjectPaymentEventUseCase {

	private final StatementRepository statementRepository;
	private final TransactionOperations transactions;

	public ProjectPaymentEventService(StatementRepository statementRepository, TransactionOperations transactions) {
		this.statementRepository = statementRepository;
		this.transactions = transactions;
	}

	@Override
	public void project(PaymentEvent event) {
		transactions.executeWithoutResult(tx -> {
			if (!statementRepository.markProcessed(event.eventId())) {
				return;
			}
			StatementLine line = statementRepository.findLine(event.paymentId()).orElseGet(() -> newLine(event));
			statementRepository.save(apply(event, line));
		});
	}

	private static StatementLine apply(PaymentEvent event, StatementLine line) {
		return switch (event.type()) {
			case PAYMENT_COMPLETED -> settled(event, line, PaymentStatus.COMPLETED);
			case PAYMENT_FAILED -> settled(event, line, PaymentStatus.FAILED);
			case PAYMENT_CANCELLED -> settled(event, line, PaymentStatus.CANCELLED);
			// A refund event carries the refund's amount, not the payment's.
			case REFUND_COMPLETED -> new StatementLine(line.paymentId(), line.accountId(), line.counterpartyAccountId(),
					line.amount(), line.currency(), line.status(), line.refundedAmount().add(event.amount().amount()),
					line.settledAt(), event.eventId());
			// Nothing was sent back. Applied all the same, so a redelivery is still recognised.
			case REFUND_FAILED -> new StatementLine(line.paymentId(), line.accountId(), line.counterpartyAccountId(),
					line.amount(), line.currency(), line.status(), line.refundedAmount(), line.settledAt(), event.eventId());
		};
	}

	private static StatementLine settled(PaymentEvent event, StatementLine line, PaymentStatus status) {
		return new StatementLine(line.paymentId(), line.accountId(), line.counterpartyAccountId(), event.amount().amount(),
				event.amount().currency(), status, line.refundedAmount(), event.occurredAt(), event.eventId());
	}

	// Every event names both accounts and the currency, so a line created by any of them knows whose statement it is on.
	private static StatementLine newLine(PaymentEvent event) {
		return new StatementLine(event.paymentId(), event.sourceAccountId(), event.destinationAccountId(), null,
				event.amount().currency(), null, BigDecimal.ZERO.setScale(2), null, event.eventId());
	}
}
