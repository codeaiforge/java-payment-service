package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.application.usecase.query.Investigation.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL with the Flyway schema (V15): each change is a guarded UPDATE, and only one gets through. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, InvestigationPersistenceAdapter.class})
class InvestigationPersistenceAdapterTest {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@Autowired
	InvestigationPersistenceAdapter adapter;

	@Test
	void storesAProposalAndReadsItBack() {
		Investigation proposed = proposed();
		adapter.insert(proposed);

		assertThat(adapter.findById(UUID.fromString(proposed.id()))).contains(proposed);
	}

	@Test
	void onlyTheFirstDecisionGetsThrough() {
		UUID id = UUID.fromString(insert().id());

		assertThat(adapter.decide(id, Status.APPROVED, "OPS-0002", NOW)).isTrue();
		assertThat(adapter.decide(id, Status.APPROVED, "OPS-0003", NOW)).isFalse();
		assertThat(adapter.decide(id, Status.REJECTED, "OPS-0003", NOW)).isFalse();
		assertThat(adapter.findById(id).orElseThrow()).extracting(Investigation::status, Investigation::decidedBy)
				.containsExactly(Status.APPROVED, "OPS-0002");
	}

	@Test
	void onlyAnApprovedInvestigationFinishesAndOnlyOnce() {
		UUID id = UUID.fromString(insert().id());
		assertThat(adapter.finish(id, Status.EXECUTED, "done", NOW)).isFalse();

		adapter.decide(id, Status.APPROVED, "OPS-0002", NOW);
		assertThat(adapter.finish(id, Status.EXECUTED, "done", NOW)).isTrue();
		assertThat(adapter.finish(id, Status.NO_LONGER_NEEDED, "again", NOW)).isFalse();
		assertThat(adapter.findById(id).orElseThrow()).extracting(Investigation::status, Investigation::result)
				.containsExactly(Status.EXECUTED, "done");
	}

	private Investigation insert() {
		Investigation investigation = proposed();
		adapter.insert(investigation);
		return investigation;
	}

	private static Investigation proposed() {
		return new Investigation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "OPS-0001", NOW,
				"ESCALATE_TO_ON_CALL", "Reconciliation hasn't run.", "{\"diagnosis\": {}}", Status.PROPOSED, null, null, null, null);
	}
}
