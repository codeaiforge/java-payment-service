package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.InvestigationRepository;
import com.example.payment.application.usecase.query.Investigation;
import com.example.payment.application.usecase.query.Investigation.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The investigation table (V15) in plain SQL: each change is one guarded UPDATE, the compare-and-set itself. */
@Component
class InvestigationPersistenceAdapter implements InvestigationRepository {

	private final JdbcClient jdbc;

	InvestigationPersistenceAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(Investigation investigation) {
		jdbc.sql("""
				INSERT INTO investigation (id, payment_id, started_by, started_at, proposed_action, escalation_text, findings,
				                           status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(UUID.fromString(investigation.id()), UUID.fromString(investigation.paymentId()),
						investigation.startedBy(), Timestamp.from(investigation.startedAt()), investigation.proposedAction(),
						investigation.escalationText(), investigation.findings(), investigation.status().name())
				.update();
	}

	@Override
	public Optional<Investigation> findById(UUID id) {
		return jdbc.sql("SELECT * FROM investigation WHERE id = ?").param(id)
				.query((rs, row) -> map(rs)).optional();
	}

	@Override
	public boolean decide(UUID id, Status decision, String decidedBy, Instant decidedAt) {
		return jdbc.sql("""
				UPDATE investigation SET status = ?, decided_by = ?, decided_at = ?
				WHERE id = ? AND status = 'PROPOSED'""")
				.params(decision.name(), decidedBy, Timestamp.from(decidedAt), id).update() == 1;
	}

	@Override
	public boolean finish(UUID id, Status outcome, String result, Instant finishedAt) {
		return jdbc.sql("""
				UPDATE investigation SET status = ?, result = ?, finished_at = ?
				WHERE id = ? AND status = 'APPROVED'""")
				.params(outcome.name(), result, Timestamp.from(finishedAt), id).update() == 1;
	}

	private static Investigation map(ResultSet rs) throws SQLException {
		return new Investigation(rs.getString("id"), rs.getString("payment_id"), rs.getString("started_by"),
				instant(rs, "started_at"), rs.getString("proposed_action"), rs.getString("escalation_text"),
				rs.getString("findings"), Status.valueOf(rs.getString("status")), rs.getString("decided_by"), instant(rs, "decided_at"),
				rs.getString("result"), instant(rs, "finished_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
