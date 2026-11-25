package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetPaymentForOperatorQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.application.usecase.query.StatusTransition;

import java.util.List;

/**
 * Any payment and its history, for the people who run the service. The customer reads (GetPaymentUseCase,
 * GetPaymentHistoryUseCase) answer only the holder of the payment's source account; an operator holds none, and must
 * see the payment anyway, as ReconcilePaymentUseCase already acts on it. Who counts as an operator is decided before
 * this is called (the payments.operate scope); this port only records who looked.
 */
public interface GetPaymentForOperatorUseCase {

	/** @throws com.example.payment.application.exception.PaymentNotFoundException if no payment has that id */
	GetPaymentResult getPayment(GetPaymentForOperatorQuery query);

	/**
	 * The same history a customer sees: every status change in the order stored, from the first change.
	 *
	 * @throws com.example.payment.application.exception.PaymentNotFoundException if no payment has that id
	 */
	List<StatusTransition> getHistory(GetPaymentForOperatorQuery query);
}
