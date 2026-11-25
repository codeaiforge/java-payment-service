package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.DecideInvestigationCommand;
import com.example.payment.application.usecase.query.Investigation;

/**
 * Four eyes on a model's proposal: the operator who decides must not be the one who started the investigation. The
 * decision is a compare-and-set from PROPOSED, so of two operators deciding at once, on one instance or two, exactly
 * one wins; the other is refused. Only the winner of an approval runs the action.
 */
public interface DecideInvestigationUseCase {

	/**
	 * @return the investigation, now APPROVED by this operator
	 * @throws com.example.payment.application.exception.InvestigationNotFoundException if no investigation has that id
	 * @throws com.example.payment.application.exception.AccessDeniedException if this operator started it
	 * @throws com.example.payment.application.exception.InvalidInvestigationStateException if it was already decided
	 */
	Investigation approve(DecideInvestigationCommand command);

	/** The same rules as approve; a rejected proposal never runs. */
	Investigation reject(DecideInvestigationCommand command);
}
