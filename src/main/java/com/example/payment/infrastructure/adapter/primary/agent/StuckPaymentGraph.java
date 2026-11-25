package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.nodes.ApplyPolicy;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Diagnose;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The runbook as a graph: gather-evidence, diagnose (once more if its answer fails), apply-policy, propose-action.
 * The control flow is here, in code, not chosen by the model: it can't skip the policy, call a node twice more, or
 * reach anything the nodes don't call. The step limit stops a loop this graph shouldn't have.
 */
public class StuckPaymentGraph {

	static final String GATHER_EVIDENCE = "gather-evidence";
	static final String DIAGNOSE = "diagnose";
	static final String APPLY_POLICY = "apply-policy";
	static final String PROPOSE_ACTION = "propose-action";

	private final CompiledGraph<InvestigationState> graph;

	/** @param maxSteps how many nodes one run may execute; a full run with one re-ask takes five */
	public StuckPaymentGraph(GatherEvidence gatherEvidence, Diagnose diagnose, ApplyPolicy applyPolicy,
			ProposeAction proposeAction, int maxSteps) throws GraphStateException {
		this.graph = new StateGraph<>(InvestigationState::new)
				.addNode(GATHER_EVIDENCE, node_async(gatherEvidence))
				.addNode(DIAGNOSE, node_async(diagnose))
				.addNode(APPLY_POLICY, node_async(applyPolicy))
				.addNode(PROPOSE_ACTION, node_async(proposeAction))
				.addEdge(START, GATHER_EVIDENCE)
				.addEdge(GATHER_EVIDENCE, DIAGNOSE)
				// Diagnose sets a diagnosis or, after a failed answer, only counts the attempt: then it is asked again.
				.addConditionalEdges(DIAGNOSE, edge_async(state -> state.diagnosis().isPresent() ? APPLY_POLICY : DIAGNOSE),
						Map.of(APPLY_POLICY, APPLY_POLICY, DIAGNOSE, DIAGNOSE))
				.addEdge(APPLY_POLICY, PROPOSE_ACTION)
				.addEdge(PROPOSE_ACTION, END)
				.compile(CompileConfig.builder().recursionLimit(maxSteps).build());
	}

	/** Runs to the end. A node's exception, or the step limit, ends the run with that exception. */
	public InvestigationState investigate(String paymentId, String operatorId) {
		return graph.invoke(Map.of(InvestigationState.PAYMENT_ID, paymentId, InvestigationState.STARTED_BY, operatorId),
				// LangGraph4j copies the state between nodes with Java serialization unless told not to. Every value here is
				// an immutable record or list, so a copy protects nothing.
				RunnableConfig.builder().disableCloneState().build())
				.orElseThrow();
	}
}
