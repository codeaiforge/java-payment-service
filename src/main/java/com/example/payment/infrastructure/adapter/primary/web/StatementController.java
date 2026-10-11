package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.GetStatementUseCase;
import com.example.payment.application.usecase.query.GetStatementQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;

/**
 * An account's payment statement (docs/episodes/bonus-09). Like every endpoint,
 * it needs a token, and the caller's customer ID comes from it.
 */
@RestController
public class StatementController {

	private final GetStatementUseCase getStatementUseCase;

	public StatementController(GetStatementUseCase getStatementUseCase) {
		this.getStatementUseCase = getStatementUseCase;
	}

	// from and to are days (2026-10-10), both included. A size above the cap is a 400, not quietly cut down.
	@GetMapping("/accounts/{accountId}/statement")
	public StatementResponse statement(@PathVariable String accountId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Principal caller) {
		return StatementResponse.from(getStatementUseCase.getStatement(
				new GetStatementQuery(accountId, caller.getName(), status, from, to, page, size)));
	}
}
