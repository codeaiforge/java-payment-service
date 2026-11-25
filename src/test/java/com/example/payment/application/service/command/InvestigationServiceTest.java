package com.example.payment.application.service.command;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.InvalidInvestigationStateException;
import com.example.payment.application.exception.InvestigationNotFoundException;
import com.example.payment.application.port.secondary.InvestigationRepository;
import com.example.payment.application.usecase.command.DecideInvestigationCommand;
import com.example.payment.application.usecase.command.FinishInvestigationCommand;
import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.application.usecase.query.Investigation.Status;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigationServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
	private final UUID id = UUID.randomUUID();
	private final Investigation proposed = new Investigation(id.toString(), UUID.randomUUID().toString(), "OPS-0001", NOW,
			"RECONCILE_NOW", null, "{}", Status.PROPOSED, null, null, null, null);

	private final InvestigationRepository repository = mock(InvestigationRepository.class);
	private final InvestigationService service = new InvestigationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void anotherOperatorApproves() {
		when(repository.findById(id)).thenReturn(Optional.of(proposed));
		when(repository.decide(id, Status.APPROVED, "OPS-0002", NOW)).thenReturn(true);

		service.approve(new DecideInvestigationCommand(id.toString(), "OPS-0002"));

		verify(repository).decide(id, Status.APPROVED, "OPS-0002", NOW);
	}

	// Four eyes: refused before the compare-and-set, so the investigation stays PROPOSED for someone else.
	@Test
	void theOperatorWhoStartedItMayNotDecideIt() {
		when(repository.findById(id)).thenReturn(Optional.of(proposed));

		assertThatThrownBy(() -> service.approve(new DecideInvestigationCommand(id.toString(), "OPS-0001")))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.reject(new DecideInvestigationCommand(id.toString(), "OPS-0001")))
				.isInstanceOf(AccessDeniedException.class);
		verify(repository, never()).decide(any(), any(), anyString(), any());
	}

	// Both read PROPOSED; the database lets one through. The other is told it was already decided.
	@Test
	void aDecisionThatLosesTheCompareAndSetIsRefused() {
		when(repository.findById(id)).thenReturn(Optional.of(proposed));
		when(repository.decide(eq(id), any(), anyString(), any())).thenReturn(false);

		assertThatThrownBy(() -> service.approve(new DecideInvestigationCommand(id.toString(), "OPS-0002")))
				.isInstanceOf(InvalidInvestigationStateException.class);
	}

	@Test
	void anUnknownInvestigationIsNotFound() {
		assertThatThrownBy(() -> service.approve(new DecideInvestigationCommand(id.toString(), "OPS-0002")))
				.isInstanceOf(InvestigationNotFoundException.class);
	}

	@Test
	void anApprovedInvestigationFinishesOnlyAsExecutedOrNoLongerNeeded() {
		assertThatThrownBy(() -> service.finish(new FinishInvestigationCommand(id.toString(), Status.REJECTED, "x")))
				.isInstanceOf(IllegalArgumentException.class);
		when(repository.finish(id, Status.EXECUTED, "done", NOW)).thenReturn(false);
		assertThatThrownBy(() -> service.finish(new FinishInvestigationCommand(id.toString(), Status.EXECUTED, "done")))
				.isInstanceOf(InvalidInvestigationStateException.class);
	}
}
