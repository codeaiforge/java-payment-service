package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetPaymentHistoryQuery;
import com.example.payment.application.usecase.query.StatusTransition;

import java.util.List;

public interface GetPaymentHistoryUseCase {

	/**
	 * What happened to the payment: every status change, in the order they were stored. It starts at the first
	 * change; a payment still CREATED has an empty history.
	 *
	 * @throws com.example.payment.application.exception.PaymentNotFoundException if no payment has that id, or the
	 *         caller doesn't hold its source account
	 */
	List<StatusTransition> getHistory(GetPaymentHistoryQuery query);
}
