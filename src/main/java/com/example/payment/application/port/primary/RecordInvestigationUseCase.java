package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.FinishInvestigationCommand;
import com.example.payment.application.usecase.command.OpenInvestigationCommand;

public interface RecordInvestigationUseCase {

	/** Records a proposal, PROPOSED, waiting for a second operator. */
	void open(OpenInvestigationCommand command);

	/**
	 * Records what running an approved proposal found: APPROVED to EXECUTED or NO_LONGER_NEEDED.
	 *
	 * @throws com.example.payment.application.exception.InvalidInvestigationStateException if it isn't APPROVED
	 */
	void finish(FinishInvestigationCommand command);
}
