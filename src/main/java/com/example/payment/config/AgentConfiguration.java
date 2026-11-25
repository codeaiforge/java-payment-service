package com.example.payment.config;

import com.example.payment.application.port.primary.GetPaymentForOperatorUseCase;
import com.example.payment.application.port.primary.GetReconciliationStatusUseCase;
import com.example.payment.infrastructure.adapter.primary.agent.StuckPaymentGraph;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ApplyPolicy;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.Diagnose;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.GatherEvidence;
import com.example.payment.infrastructure.adapter.primary.agent.nodes.ProposeAction;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;

/**
 * The operations agent (docs/episodes/bonus-14), only with the agent profile. Its nodes get primary ports and a
 * ChatClient, nothing else: no repository, no outbound port, no tool the model could call.
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent")
class AgentConfiguration {

	@Bean
	StuckPaymentGraph stuckPaymentGraph(ChatClient.Builder chatClientBuilder, GetPaymentForOperatorUseCase payments,
			GetReconciliationStatusUseCase reconciliation, Clock clock,
			@Value("${payment.reconciliation.threshold}") Duration threshold,
			@Value("${payment.reconciliation.interval}") Duration interval,
			@Value("${payment.agent.max-steps}") int maxSteps) throws GraphStateException {
		ChatClient chatClient = chatClientBuilder.build();
		return new StuckPaymentGraph(new GatherEvidence(payments, reconciliation, threshold, interval, clock),
				new Diagnose(chatClient), new ApplyPolicy(), new ProposeAction(chatClient), maxSteps);
	}
}
