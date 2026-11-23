package com.example.payment.application.port.secondary;

import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.valueobject.PaymentId;

import java.util.List;

/**
 * Reads the audit trail AuditPort writes. A port of its own, so the services that change a payment's status, and
 * write its audit record, are given a port that can only write.
 */
public interface AuditQueryPort {

	/** The payment's status changes in the order they were stored; empty if it has none. */
	List<StatusTransition> findTransitions(PaymentId paymentId);
}
