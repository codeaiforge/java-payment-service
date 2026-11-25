package com.example.payment.infrastructure.adapter.primary.agent.nodes;

import com.example.payment.application.port.secondary.PaymentRepository;

/** Never wired: a node that reaches a repository, which AgentPackageIsAPrimaryAdapterTest shows the rules refuse. */
public class PlantedRepositoryNode {

	final PaymentRepository repository;

	PlantedRepositoryNode(PaymentRepository repository) {
		this.repository = repository;
	}
}
