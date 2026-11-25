package com.example.payment.config;

import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.port.primary.GetReconciliationStatusUseCase;
import com.example.payment.application.port.primary.ReconcilePaymentUseCase;
import com.example.payment.infrastructure.adapter.primary.agent.InvestigationStateSerializer;
import com.example.payment.infrastructure.adapter.primary.agent.StuckPaymentGraph;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ApplyPolicy;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Diagnose;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Execute;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * The operations agent (docs/episodes/bonus-14, bonus-15), only with the agent profile. Its nodes get primary ports
 * and a ChatClient, nothing else: no repository, no outbound port, no tool the model could call. The checkpoints go to
 * the service's own database, in tables Flyway created (V14).
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent")
class AgentConfiguration {

	@Bean
	BaseCheckpointSaver investigationCheckpoints(DataSource dataSource) throws Exception {
		return PostgresSaverV2.builder()
				.datasource(dataSource)
				.stateSerializer(new InvestigationStateSerializer())
				// Flyway owns the schema: the saver must neither create nor drop its tables.
				.createTables(false)
				.dropTablesFirst(false)
				.build();
	}

	@Bean
	StuckPaymentGraph stuckPaymentGraph(ChatClient.Builder chatClientBuilder, GetPaymentForOperatorUseCase payments,
			GetReconciliationStatusUseCase reconciliation, ReconcilePaymentUseCase reconcile,
			BaseCheckpointSaver investigationCheckpoints, ObservationRegistry observations, Clock clock,
			@Value("${payment.reconciliation.threshold}") Duration threshold,
			@Value("${payment.reconciliation.interval}") Duration interval,
			@Value("${payment.agent.max-steps}") int maxSteps) throws GraphStateException {
		ChatClient chatClient = chatClientBuilder.build();
		return new StuckPaymentGraph(new GatherEvidence(payments, reconciliation, threshold, interval, clock),
				new Diagnose(chatClient), new ApplyPolicy(), new ProposeAction(chatClient),
				new Execute(payments, reconcile, threshold), investigationCheckpoints, maxSteps, observations);
	}
}
