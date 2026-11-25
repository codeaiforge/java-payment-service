package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The operations agent's driving side, like OperationsController: /operations/** needs the payments.operate scope
 * (SecurityConfiguration). An investigation reads and proposes; nothing it proposes runs in bonus 14.
 * Only with the agent profile: without it these endpoints don't exist.
 */
@RestController
@Profile("agent")
public class InvestigationController {

	private static final Logger log = LoggerFactory.getLogger(InvestigationController.class);

	private final StuckPaymentGraph graph;
	private final Clock clock;
	// SHORTCUT: kept in memory, so a restart loses them and each instance knows only its own (bonus 15 stores them).
	private final Map<String, InvestigationView> investigations = new ConcurrentHashMap<>();

	public InvestigationController(StuckPaymentGraph graph, Clock clock) {
		this.graph = graph;
		this.clock = clock;
	}

	// SHORTCUT: runs in the request, model calls and all, so the caller waits for it (seconds on a small model).
	@PostMapping("/operations/investigations")
	public ResponseEntity<InvestigationView> start(@RequestBody StartInvestigationRequest request, Principal operator) {
		String id = UUID.randomUUID().toString();
		Instant startedAt = clock.instant();
		InvestigationView view;
		try {
			view = InvestigationView.of(id, startedAt, graph.investigate(request.paymentId(), operator.getName()));
		}
		catch (RuntimeException e) {
			rethrowIfTheCallersFault(e);
			log.warn("Investigation {} of payment {} stopped: {}", id, request.paymentId(), e.getMessage());
			view = InvestigationView.failed(id, request.paymentId(), operator.getName(), startedAt, e.getMessage());
		}
		investigations.put(id, view);
		return ResponseEntity.created(URI.create("/operations/investigations/" + id)).body(view);
	}

	@GetMapping("/operations/investigations/{id}")
	public InvestigationView get(@PathVariable String id) {
		InvestigationView view = investigations.get(id);
		if (view == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return view;
	}

	// The graph wraps what a node throws. An unknown or malformed payment id is the caller's mistake, a 404 or 400 as
	// on every other endpoint, not a failed investigation.
	private static void rethrowIfTheCallersFault(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof PaymentNotFoundException || cause instanceof PaymentValidationException) {
				throw (RuntimeException) cause;
			}
		}
	}
}
