package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetStatementQuery;
import com.example.payment.application.usecase.query.GetStatementResult;

public interface GetStatementUseCase {

	/**
	 * The account's settled payments, newest first. A payment settled a moment ago may not be on it yet.
	 *
	 * @throws com.example.payment.application.exception.AccessDeniedException if the caller doesn't hold the account
	 */
	GetStatementResult getStatement(GetStatementQuery query);
}
