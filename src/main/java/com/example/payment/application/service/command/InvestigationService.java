package com.example.payment.application.service.command;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.InvalidInvestigationStateException;
import com.example.payment.application.exception.InvestigationNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.DecideInvestigationUseCase;
import com.example.payment.application.port.primary.GetInvestigationUseCase;
import com.example.payment.application.port.primary.RecordInvestigationUseCase;
import com.example.payment.application.port.secondary.InvestigationRepository;
import com.example.payment.application.usecase.command.DecideInvestigationCommand;
import com.example.payment.application.usecase.command.FinishInvestigationCommand;
import com.example.payment.application.usecase.command.OpenInvestigationCommand;
import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.application.usecase.query.Investigation.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
public class InvestigationService implements RecordInvestigationUseCase, DecideInvestigationUseCase, GetInvestigationUseCase {

	private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

	private final InvestigationRepository repository;
	private final Clock clock;

	public InvestigationService(InvestigationRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void open(OpenInvestigationCommand command) {
		repository.insert(new Investigation(command.investigationId(), command.paymentId(), command.startedBy(),
				clock.instant(), command.proposedAction(), command.escalationText(), command.findings(), Status.PROPOSED, null, null, null, null));
	}

	@Override
	public Investigation approve(DecideInvestigationCommand command) {
		return decide(command, Status.APPROVED);
	}

	@Override
	public Investigation reject(DecideInvestigationCommand command) {
		return decide(command, Status.REJECTED);
	}

	@Override
	public void finish(FinishInvestigationCommand command) {
		if (command.outcome() != Status.EXECUTED && command.outcome() != Status.NO_LONGER_NEEDED) {
			throw new IllegalArgumentException("An approved investigation ends EXECUTED or NO_LONGER_NEEDED");
		}
		if (!repository.finish(id(command.investigationId()), command.outcome(), command.result(), clock.instant())) {
			throw new InvalidInvestigationStateException("Investigation " + command.investigationId() + " is not APPROVED");
		}
	}

	@Override
	public Investigation get(String investigationId) {
		return repository.findById(id(investigationId))
				.orElseThrow(() -> new InvestigationNotFoundException(investigationId));
	}

	private Investigation decide(DecideInvestigationCommand command, Status decision) {
		Investigation investigation = get(command.investigationId());
		// Four eyes: the person who asked the model must not be the one who accepts its answer.
		if (investigation.startedBy().equals(command.operatorId())) {
			throw new AccessDeniedException("Investigation " + investigation.id()
					+ " was started by this operator; another must decide it");
		}
		// The status check, not the read above, decides who wins: two decisions at once both read PROPOSED.
		if (!repository.decide(id(command.investigationId()), decision, command.operatorId(), clock.instant())) {
			throw new InvalidInvestigationStateException("Investigation " + investigation.id() + " was already decided");
		}
		log.info("Operator {} {} investigation {} of payment {} ({})", command.operatorId(),
				decision == Status.APPROVED ? "approved" : "rejected", investigation.id(), investigation.paymentId(),
				investigation.proposedAction());
		return get(command.investigationId());
	}

	private static UUID id(String investigationId) {
		try {
			return UUID.fromString(investigationId);
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException("Invalid investigation id: " + investigationId, e);
		}
	}
}
