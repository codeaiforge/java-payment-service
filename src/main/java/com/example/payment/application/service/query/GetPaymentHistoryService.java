package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.GetPaymentHistoryUseCase;
import com.example.payment.application.port.secondary.AuditQueryPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentHistoryQuery;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetPaymentHistoryService implements GetPaymentHistoryUseCase {

	private final PaymentRepository paymentRepository;
	private final AuditQueryPort auditQueryPort;
	private final AccountStateValidator accountStateValidator;

	public GetPaymentHistoryService(PaymentRepository paymentRepository, AuditQueryPort auditQueryPort,
			AccountStateValidator accountStateValidator) {
		this.paymentRepository = paymentRepository;
		this.auditQueryPort = auditQueryPort;
		this.accountStateValidator = accountStateValidator;
	}

	@Override
	public List<StatusTransition> getHistory(GetPaymentHistoryQuery query) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(query.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		// The payment first, as for refunds: another customer's payment is a 404, the same as GET /payments/{id}.
		paymentRepository.findById(paymentId)
				.filter(p -> accountStateValidator.isHeldBy(p.sourceAccountId(), query.customerId()))
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		return auditQueryPort.findTransitions(paymentId);
	}
}
