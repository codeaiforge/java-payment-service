package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.Investigation;

public interface GetInvestigationUseCase {

	/** @throws com.example.payment.application.exception.InvestigationNotFoundException if no investigation has that id */
	Investigation get(String investigationId);
}
