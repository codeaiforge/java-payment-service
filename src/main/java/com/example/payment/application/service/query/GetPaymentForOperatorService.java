package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.port.secondary.AuditQueryPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetPaymentForOperatorService implements GetPaymentForOperatorUseCase {

	private static final Logger log = LoggerFactory.getLogger(GetPaymentForOperatorService.class);

	private final PaymentRepository paymentRepository;
	private final AuditQueryPort auditQueryPort;

	public GetPaymentForOperatorService(PaymentRepository paymentRepository, AuditQueryPort auditQueryPort) {
		this.paymentRepository = paymentRepository;
		this.auditQueryPort = auditQueryPort;
	}

	@Override
	public GetPaymentResult getPayment(GetPaymentForOperatorQuery query) {
		return GetPaymentResult.from(find(query));
	}

	@Override
	public List<StatusTransition> getHistory(GetPaymentForOperatorQuery query) {
		return auditQueryPort.findTransitions(find(query).id());
	}

	// SHORTCUT: every operator may read every payment, and the record of who looked is this log line. A bank would
	// keep it as an audit row, like the status changes.
	private Payment find(GetPaymentForOperatorQuery query) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(query.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		log.info("Operator {} read payment {}", query.operatorId(), paymentId);
		return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
	}
}
