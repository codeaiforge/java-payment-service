package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.ReconciliationLockPort;
import com.example.payment.application.usecase.query.ReconciliationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads ShedLock's row for the reconciliation job (V8). ShedLock owns the table and its layout, so this is plain
 * SQL, not an entity. "Held" is decided by the database's clock, as ShedLock decides it (SchedulerLockConfiguration),
 * so every instance gives the same answer.
 */
@Component
class ReconciliationLockPersistenceAdapter implements ReconciliationLockPort {

	// SHORTCUT: the name ReconciliationScheduler's @SchedulerLock uses. A secondary adapter can't import the primary one.
	private static final String LOCK_NAME = "reconciliation";

	private final JdbcTemplate jdbcTemplate;

	ReconciliationLockPersistenceAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<ReconciliationStatus> find() {
		return jdbcTemplate.query("""
				SELECT locked_at,
				       CASE WHEN lock_until > now() THEN locked_by END AS running_on,
				       CASE WHEN lock_until > now() THEN lock_until END AS running_until
				FROM shedlock WHERE name = ?""",
				(rs, row) -> new ReconciliationStatus(rs.getTimestamp("locked_at").toInstant(),
						rs.getString("running_on"), toInstant(rs.getTimestamp("running_until"))),
				LOCK_NAME).stream().findFirst();
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
