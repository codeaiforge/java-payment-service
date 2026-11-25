package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Cause;
import com.example.payment.infrastructure.adapter.primary.agent.Diagnosis.Confidence;
import com.example.payment.infrastructure.adapter.primary.agent.Evidence;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationState;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Why is the payment where it is? Code answers what the facts settle, and the model is asked only the rest. Its
 * answer is checked against the facts: every cited item must exist, and the cause must be one the facts allow.
 * An answer that fails is asked again once (the graph loops back here); a second failure is UNCLEAR.
 */
public class Diagnose implements NodeAction<InvestigationState> {

	static final int MAX_ATTEMPTS = 2;

	private static final Logger log = LoggerFactory.getLogger(Diagnose.class);

	private final ChatClient chatClient;

	public Diagnose(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@Override
	public Map<String, Object> apply(InvestigationState state) {
		Evidence evidence = state.evidence();
		Optional<Diagnosis> settled = fromFacts(evidence);
		if (settled.isPresent()) {
			return Map.of(InvestigationState.DIAGNOSIS, settled.get());
		}
		int attempt = state.diagnoseAttempts() + 1;
		Optional<Diagnosis> answer = ask(evidence).filter(diagnosis -> agreesWithTheFacts(diagnosis, evidence));
		if (answer.isPresent()) {
			return Map.of(InvestigationState.DIAGNOSIS, answer.get(), InvestigationState.DIAGNOSE_ATTEMPTS, attempt);
		}
		// Only the ids and the count are logged: never the prompt, the answer or the payment's text.
		log.warn("Diagnosis of payment {} failed validation (attempt {} of {})", evidence.paymentId(), attempt, MAX_ATTEMPTS);
		if (attempt < MAX_ATTEMPTS) {
			return Map.of(InvestigationState.DIAGNOSE_ATTEMPTS, attempt);
		}
		return Map.of(InvestigationState.DIAGNOSIS, new Diagnosis(Cause.UNCLEAR, List.of(), Confidence.LOW,
				"The model's diagnosis disagreed with the evidence twice; check the evidence by hand."),
				InvestigationState.DIAGNOSE_ATTEMPTS, attempt);
	}

	static Optional<Diagnosis> fromFacts(Evidence evidence) {
		if (!evidence.processing()) {
			return Optional.of(new Diagnosis(Cause.NOT_STUCK, List.of("E1"), Confidence.HIGH,
					"The payment is " + evidence.status() + ", not PROCESSING."));
		}
		if (evidence.age() == null) {
			return Optional.of(new Diagnosis(Cause.UNCLEAR, List.of("E1", "E6"), Confidence.HIGH,
					"The payment is PROCESSING but its audit trail has no transition: its age is unknown."));
		}
		if (!evidence.pastThreshold()) {
			return Optional.of(new Diagnosis(Cause.WITHIN_THRESHOLD, List.of("E1", "E3", "E10"), Confidence.HIGH,
					"PROCESSING for less than the reconciliation threshold: the scheduled run hasn't asked about it yet."));
		}
		return Optional.empty();
	}

	static boolean agreesWithTheFacts(Diagnosis diagnosis, Evidence evidence) {
		return diagnosis.cause() != null && diagnosis.confidence() != null
				&& diagnosis.summary() != null && !diagnosis.summary().isBlank()
				&& diagnosis.evidenceIds() != null && !diagnosis.evidenceIds().isEmpty()
				&& evidence.ids().containsAll(diagnosis.evidenceIds())
				&& evidence.causesTheFactsAllow().contains(diagnosis.cause());
	}

	// An answer that isn't the asked-for JSON, or names a cause that doesn't exist, is a failed answer like any other.
	private Optional<Diagnosis> ask(Evidence evidence) {
		try {
			return Optional.ofNullable(chatClient.prompt()
					.system(Prompts.SYSTEM)
					.user("""
							Diagnose why this payment is still PROCESSING. Cite the ids of the facts you rely on.
							The causes:
							- RECONCILIATION_NOT_RUNNING: reconciliation is stalled (E9 is YES), so nothing has asked
							  the payment system about this payment.
							- PAYMENT_SYSTEM_NOT_ANSWERING: reconciliation is running on schedule (E9 is NO), it keeps
							  asking, and the payment is still PROCESSING: the payment system has no answer for it.
							- UNCLEAR: the facts don't tell you.

							""" + Prompts.dataBlock(evidence))
					.call()
					.entity(Diagnosis.class));
		}
		catch (RuntimeException e) {
			log.warn("Diagnosis of payment {} could not be read: {}", evidence.paymentId(), e.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
