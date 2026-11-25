package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.usecase.query.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL with the Flyway schema: ShedLock's row, written as ShedLock writes it, read as the status. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, ReconciliationLockPersistenceAdapter.class})
class ReconciliationLockPersistenceAdapterTest {

	@Autowired
	ReconciliationLockPersistenceAdapter adapter;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void isEmptyBeforeTheFirstRun() {
		assertThat(adapter.find()).isEmpty();
	}

	@Test
	void namesTheInstanceWhileTheLockIsHeld() {
		lock("now() - interval '5 seconds'", "now() + interval '25 seconds'", "payment-service-7d9f");

		ReconciliationStatus status = adapter.find().orElseThrow();
		assertThat(status.lastRunStartedAt()).isNotNull();
		assertThat(status.runningOn()).isEqualTo("payment-service-7d9f");
		assertThat(status.runningUntil()).isAfter(status.lastRunStartedAt());
	}

	// ShedLock keeps the row after a run, so locked_by still names the last holder. Held is lock_until, not the row.
	@Test
	void namesNoInstanceOnceTheLockHasExpired() {
		lock("now() - interval '2 minutes'", "now() - interval '90 seconds'", "payment-service-7d9f");

		ReconciliationStatus status = adapter.find().orElseThrow();
		assertThat(status.lastRunStartedAt()).isNotNull();
		assertThat(status.runningOn()).isNull();
		assertThat(status.runningUntil()).isNull();
	}

	@Test
	void ignoresOtherJobsLocks() {
		jdbcTemplate.update("INSERT INTO shedlock VALUES ('outbox-cleanup', now() + interval '1 minute', now(), 'x')");

		assertThat(adapter.find()).isEmpty();
	}

	private void lock(String lockedAt, String lockUntil, String lockedBy) {
		jdbcTemplate.update("INSERT INTO shedlock (name, locked_at, lock_until, locked_by) VALUES ('reconciliation', "
				+ lockedAt + ", " + lockUntil + ", ?)", lockedBy);
	}
}
