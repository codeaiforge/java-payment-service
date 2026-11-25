package com.example.payment.infrastructure.architecture;

import com.example.payment.infrastructure.adapter.primary.agent.nodes.PlantedRepositoryNode;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ArchitectureTest skips test classes, so it can't show that its primary-ports rule reaches the agent package; a
 * rule that silently missed it would pass just the same. This checks the rule against a planted node that holds a
 * repository.
 */
class AgentPackageIsAPrimaryAdapterTest {

	@Test
	void aNodeThatReachesARepositoryBreaksThePrimaryPortsRule() {
		JavaClasses planted = new ClassFileImporter().importClasses(PlantedRepositoryNode.class);

		assertThatThrownBy(() -> ArchitectureTest.primaryAdaptersUsePrimaryPortsOnly.check(planted))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("PlantedRepositoryNode")
				.hasMessageContaining("PaymentRepository");
	}
}
