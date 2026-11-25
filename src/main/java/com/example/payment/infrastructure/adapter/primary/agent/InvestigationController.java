package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.DecideInvestigationUseCase;
import com.example.payment.application.port.primary.GetInvestigationUseCase;
import com.example.payment.application.port.primary.RecordInvestigationUseCase;
import com.example.payment.application.usecase.command.DecideInvestigationCommand;
import com.example.payment.application.usecase.command.FinishInvestigationCommand;
import com.example.payment.application.usecase.command.OpenInvestigationCommand;
import com.example.payment.application.usecase.query.Investigation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;

/**
 * The operations agent's driving side, like OperationsController: /operations/** needs the payments.operate scope
 * (SecurityConfiguration). Starting an investigation reads and proposes; a second operator's approval runs the
 * proposal, once. Only with the agent profile: without it these endpoints don't exist.
 *
 * Metrics: agent.investigations counts each investigation once when it is proposed (outcome PROPOSED) and once when
 * it ends (EXECUTED, NO_LONGER_NEEDED or REJECTED), by cause and action. agent.approval.latency is how long a
 * proposal waited for its decision.
 */
@RestController
@Profile("agent")
public class InvestigationController {

	private final StuckPaymentGraph graph;
	private final RecordInvestigationUseCase records;
	private final DecideInvestigationUseCase decisions;
	private final GetInvestigationUseCase investigations;
	private final MeterRegistry meters;

	public InvestigationController(StuckPaymentGraph graph, RecordInvestigationUseCase records,
			DecideInvestigationUseCase decisions, GetInvestigationUseCase investigations, MeterRegistry meters) {
		this.graph = graph;
		this.records = records;
		this.decisions = decisions;
		this.investigations = investigations;
		this.meters = meters;
	}

	// SHORTCUT: runs in the request, model calls and all, so the caller waits for it (seconds on a small model).
	@PostMapping("/operations/investigations")
	public ResponseEntity<InvestigationView> start(@RequestBody StartInvestigationRequest request, Principal operator) {
		String id = UUID.randomUUID().toString();
		InvestigationState state;
		try {
			state = graph.propose(id, request.paymentId(), operator.getName());
		}
		catch (RuntimeException e) {
			throw callersFaultOr(e);
		}
		ProposedAction proposal = state.proposal().orElseThrow();
		records.open(new OpenInvestigationCommand(id, request.paymentId(), operator.getName(), proposal.action().name(),
				proposal.escalationText(), Findings.of(state).toJson()));
		InvestigationView view = view(id);
		count(view);
		return ResponseEntity.created(URI.create("/operations/investigations/" + id)).body(view);
	}

	@GetMapping("/operations/investigations/{id}")
	public InvestigationView get(@PathVariable String id) {
		return view(id);
	}

	// Only the operator whose approval wins the compare-and-set gets here; any other is refused before the graph runs.
	// SHORTCUT: if this instance dies between the approval and finish, the investigation stays APPROVED with no
	// result; nothing retries it. The reconcile it runs is safe to run again, an escalation is only recorded.
	@PostMapping("/operations/investigations/{id}/approve")
	public InvestigationView approve(@PathVariable String id, Principal operator) {
		timeDecision(decisions.approve(new DecideInvestigationCommand(id, operator.getName())));
		InvestigationState state = graph.execute(id, operator.getName());
		records.finish(new FinishInvestigationCommand(id, Investigation.Status.valueOf(state.outcome().orElseThrow()),
				state.result().orElseThrow()));
		InvestigationView view = view(id);
		count(view);
		return view;
	}

	@PostMapping("/operations/investigations/{id}/reject")
	public InvestigationView reject(@PathVariable String id, Principal operator) {
		timeDecision(decisions.reject(new DecideInvestigationCommand(id, operator.getName())));
		InvestigationView view = view(id);
		count(view);
		return view;
	}

	private void count(InvestigationView view) {
		Counter.builder("agent.investigations")
				.description("Investigations by the operations agent, counted when proposed and when they end")
				.tag("cause", view.diagnosis().cause().name())
				.tag("action", view.proposal().action().name())
				.tag("outcome", view.status())
				.register(meters).increment();
	}

	private void timeDecision(Investigation decided) {
		Timer.builder("agent.approval.latency")
				.description("How long an agent's proposal waited for a second operator's decision")
				.tag("decision", decided.status().name())
				.publishPercentileHistogram()
				.register(meters).record(Duration.between(decided.startedAt(), decided.decidedAt()));
	}

	private InvestigationView view(String id) {
		return InvestigationView.of(investigations.get(id));
	}

	// The graph wraps what a node throws. An unknown or malformed payment id is the caller's mistake, a 404 or 400 as
	// on every other endpoint; anything else is ours, a 500.
	private static RuntimeException callersFaultOr(RuntimeException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof PaymentNotFoundException || cause instanceof PaymentValidationException) {
				return (RuntimeException) cause;
			}
		}
		return e;
	}
}
