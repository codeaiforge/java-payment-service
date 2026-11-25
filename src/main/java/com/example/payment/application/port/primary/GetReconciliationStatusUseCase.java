package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.ReconciliationStatus;

public interface GetReconciliationStatusUseCase {

	/**
	 * Is reconciliation running? The answer an operator otherwise needs production database access for. How many
	 * payments are stuck, and for how long, is the payments_processing_oldest_age_seconds gauge, not this.
	 */
	ReconciliationStatus getStatus();
}
