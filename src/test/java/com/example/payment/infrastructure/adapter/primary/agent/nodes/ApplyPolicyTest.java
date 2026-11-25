package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.infrastructure.adapter.primary.agent.Evidence;
import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The policy as a table: the facts in, the actions the model may choose from out. Threshold 120s. */
class ApplyPolicyTest {

	@ParameterizedTest(name = "{0}, {1}s old, stalled={2} -> {3}")
	@CsvSource(delimiter = '|', nullValues = "unknown", textBlock = """
			COMPLETED  | 600     | false | NO_ACTION
			FAILED     | 600     | true  | NO_ACTION
			AUTHORIZED | 600     | true  | NO_ACTION
			PROCESSING | 30      | false | NO_ACTION
			PROCESSING | 30      | true  | NO_ACTION
			PROCESSING | unknown | true  | NO_ACTION
			PROCESSING | 120     | false | RECONCILE_NOW ESCALATE_TO_PAYMENT_SYSTEM NO_ACTION
			PROCESSING | 600     | false | RECONCILE_NOW ESCALATE_TO_PAYMENT_SYSTEM NO_ACTION
			PROCESSING | 600     | true  | RECONCILE_NOW ESCALATE_TO_PAYMENT_SYSTEM ESCALATE_TO_ON_CALL NO_ACTION
			""")
	void allowsOnlyWhatTheFactsAllow(String status, Long ageSeconds, boolean stalled, String expected) {
		Duration age = ageSeconds == null ? null : Duration.ofSeconds(ageSeconds);
		Evidence evidence = new Evidence("id", status, age == null ? null : Instant.EPOCH, age, BigDecimal.TEN, "EUR",
				"Invoice", List.of(), null, null, stalled, Duration.ofSeconds(120), Duration.ofSeconds(60));

		assertThat(ApplyPolicy.allowed(evidence))
				.containsExactlyElementsOf(Arrays.stream(expected.split(" ")).map(Action::valueOf).toList());
	}
}
