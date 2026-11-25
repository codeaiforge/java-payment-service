package com.example.payment.application.service.query;

import com.example.payment.application.port.primary.GetReconciliationStatusUseCase;
import com.example.payment.application.port.secondary.ReconciliationLockPort;
import com.example.payment.application.usecase.query.ReconciliationStatus;
import org.springframework.stereotype.Service;

@Service
public class GetReconciliationStatusService implements GetReconciliationStatusUseCase {

	private final ReconciliationLockPort reconciliationLockPort;

	public GetReconciliationStatusService(ReconciliationLockPort reconciliationLockPort) {
		this.reconciliationLockPort = reconciliationLockPort;
	}

	@Override
	public ReconciliationStatus getStatus() {
		return reconciliationLockPort.find().orElse(ReconciliationStatus.NEVER_RUN);
	}
}
