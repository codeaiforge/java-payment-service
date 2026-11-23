package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.GetPaymentHistoryUseCase;
import com.example.payment.application.usecase.query.GetPaymentHistoryQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * What happened to a payment, from its audit trail: the answer to a reviewer's first question, without SQL.
 * Same rule as GET /payments/{id}: only the holder of the source account sees it, anyone else gets 404.
 */
@RestController
public class PaymentHistoryController {

	private final GetPaymentHistoryUseCase getPaymentHistoryUseCase;

	public PaymentHistoryController(GetPaymentHistoryUseCase getPaymentHistoryUseCase) {
		this.getPaymentHistoryUseCase = getPaymentHistoryUseCase;
	}

	@GetMapping("/payments/{paymentId}/history")
	public List<StatusTransitionResponse> history(@PathVariable String paymentId, Principal caller) {
		return getPaymentHistoryUseCase.getHistory(new GetPaymentHistoryQuery(paymentId, caller.getName())).stream()
				.map(StatusTransitionResponse::from)
				.toList();
	}
}
