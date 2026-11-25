package com.example.payment.application.port.secondary;

import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.application.usecase.query.Investigation.Status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InvestigationRepository {

	void insert(Investigation investigation);

	Optional<Investigation> findById(UUID id);

	/** PROPOSED to {@code decision}, only if still PROPOSED: false when another decision got there first. */
	boolean decide(UUID id, Status decision, String decidedBy, Instant decidedAt);

	/** APPROVED to {@code outcome}, only if still APPROVED. */
	boolean finish(UUID id, Status outcome, String result, Instant finishedAt);
}
