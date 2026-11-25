package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.ProposedAction.Action;
import org.bsc.langgraph4j.serializer.plain_text.PlainTextStateSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An investigation's state as JSON, for its checkpoints in PostgreSQL (V14). LangGraph4j's own Jackson serializer is
 * on Jackson 2; this one uses Jackson 3, as the rest of the service does, and names the type of every key, so a
 * checkpoint reads back into the same records it was written from, on any instance and after a restart.
 */
public class InvestigationStateSerializer extends PlainTextStateSerializer<InvestigationState> {

	// An amount is read as the BigDecimal it was, 250.00 and not 250.0: a tree would otherwise hold it as a double.
	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
			.build();

	private static final Map<String, JavaType> TYPES = Map.of(
			InvestigationState.PAYMENT_ID, MAPPER.constructType(String.class),
			InvestigationState.STARTED_BY, MAPPER.constructType(String.class),
			InvestigationState.EVIDENCE, MAPPER.constructType(Evidence.class),
			InvestigationState.DIAGNOSIS, MAPPER.constructType(Diagnosis.class),
			InvestigationState.DIAGNOSE_ATTEMPTS, MAPPER.constructType(Integer.class),
			InvestigationState.ALLOWED_ACTIONS, MAPPER.getTypeFactory().constructCollectionType(List.class, Action.class),
			InvestigationState.PROPOSAL, MAPPER.constructType(ProposedAction.class),
			InvestigationState.APPROVER, MAPPER.constructType(String.class),
			InvestigationState.OUTCOME, MAPPER.constructType(String.class),
			InvestigationState.RESULT, MAPPER.constructType(String.class));

	public InvestigationStateSerializer() {
		super(InvestigationState::new);
	}

	@Override
	public String contentType() {
		return "application/json";
	}

	@Override
	public String writeDataAsString(InvestigationState state) {
		return MAPPER.writeValueAsString(state.data());
	}

	// A key this class doesn't know is an error, not something to skip: a checkpoint written by a newer release would
	// otherwise resume with part of its state missing.
	@Override
	public InvestigationState readDataFromString(String data) {
		Map<String, Object> values = new HashMap<>();
		for (Map.Entry<String, JsonNode> field : MAPPER.readTree(data).properties()) {
			JavaType type = TYPES.get(field.getKey());
			if (type == null) {
				throw new IllegalStateException("Unknown key in an investigation checkpoint: " + field.getKey());
			}
			values.put(field.getKey(), MAPPER.treeToValue(field.getValue(), type));
		}
		return new InvestigationState(values);
	}
}
