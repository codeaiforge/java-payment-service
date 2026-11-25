package com.example.payment.infrastructure.adapter.primary.agent;

import com.example.payment.infrastructure.adapter.primary.agent.nodes.ApplyPolicy;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Diagnose;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Execute;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.util.Map;
import java.util.Optional;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The runbook as a graph: gather-evidence, diagnose (once more if its answer fails), apply-policy, propose-action,
 * then a pause, then execute. The control flow is here, in code, not chosen by the model: it can't skip the policy,
 * call a node twice more, or reach anything the nodes don't call. The step limit stops a loop this graph shouldn't
 * have.
 *
 * The pause before execute is a checkpoint: each run is a thread named after its investigation, saved after every
 * node. Resuming reads the last checkpoint, on whichever instance the approval reaches, and runs execute alone. Who
 * may resume, and that only one does, is decided before this is called (DecideInvestigationUseCase).
 */
public class StuckPaymentGraph {

	static final String GATHER_EVIDENCE = "gather-evidence";
	static final String DIAGNOSE = "diagnose";
	static final String APPLY_POLICY = "apply-policy";
	static final String PROPOSE_ACTION = "propose-action";
	static final String EXECUTE = "execute";

	private final CompiledGraph<InvestigationState> graph;

	/** @param maxSteps how many nodes one run may execute; a full run with one re-ask takes six */
	public StuckPaymentGraph(GatherEvidence gatherEvidence, Diagnose diagnose, ApplyPolicy applyPolicy,
			ProposeAction proposeAction, Execute execute, BaseCheckpointSaver checkpoints, int maxSteps)
			throws GraphStateException {
		this.graph = new StateGraph<>(new InvestigationStateSerializer())
				.addNode(GATHER_EVIDENCE, node_async(gatherEvidence))
				.addNode(DIAGNOSE, node_async(diagnose))
				.addNode(APPLY_POLICY, node_async(applyPolicy))
				.addNode(PROPOSE_ACTION, node_async(proposeAction))
				.addNode(EXECUTE, node_async(execute))
				.addEdge(START, GATHER_EVIDENCE)
				.addEdge(GATHER_EVIDENCE, DIAGNOSE)
				// Diagnose sets a diagnosis or, after a failed answer, only counts the attempt: then it is asked again.
				.addConditionalEdges(DIAGNOSE, edge_async(state -> state.diagnosis().isPresent() ? APPLY_POLICY : DIAGNOSE),
						Map.of(APPLY_POLICY, APPLY_POLICY, DIAGNOSE, DIAGNOSE))
				.addEdge(APPLY_POLICY, PROPOSE_ACTION)
				.addEdge(PROPOSE_ACTION, EXECUTE)
				.addEdge(EXECUTE, END)
				.compile(CompileConfig.builder()
						.checkpointSaver(checkpoints)
						.interruptBefore(EXECUTE)
						.recursionLimit(maxSteps)
						.build());
	}

	/** Runs up to the pause before execute, and returns the proposal. A node's exception, or the step limit, ends it. */
	public InvestigationState propose(String investigationId, String paymentId, String operatorId) {
		// A run that stops at the pause returns nothing; its state is the checkpoint it stopped at.
		graph.invoke(Map.of(InvestigationState.PAYMENT_ID, paymentId, InvestigationState.STARTED_BY, operatorId),
				config(investigationId));
		return state(investigationId).orElseThrow();
	}

	/** Resumes the paused run from its last checkpoint and runs execute as the approver. */
	public InvestigationState execute(String investigationId, String approverId) {
		try {
			RunnableConfig resumed = graph.updateState(config(investigationId), Map.of(InvestigationState.APPROVER, approverId));
			return graph.invoke(GraphInput.resume(), resumed).orElseThrow();
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("Investigation " + investigationId + " could not be resumed", e);
		}
	}

	/** The last checkpoint of an investigation's run, if it has one. */
	public Optional<InvestigationState> state(String investigationId) {
		return graph.lastStateOf(config(investigationId)).map(StateSnapshot::state);
	}

	private static RunnableConfig config(String investigationId) {
		return RunnableConfig.builder().threadId(investigationId).build();
	}
}
