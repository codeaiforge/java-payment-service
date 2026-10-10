package com.example.payment.infrastructure.observability;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The alert rules are code (docs/episodes/bonus-08), so they are tested like
 * code: each alert fires on the series it is about and stays quiet on a
 * healthy one, and every runbook it links to exists.
 */
class AlertRulesTest {

	private static final Path PROMETHEUS_RULES = Path.of("observability/prometheus/rules/payment-slo.yml");
	private static final Path LOKI_RULES = Path.of("observability/loki/rules/payment-logs.yml");
	private static final String REPOSITORY = "https://github.com/dsofianos78/java-payment-service/blob/main/";

	// promtool ships in the Prometheus image docker-compose.yml runs, so the rules are tested by the same version.
	// The test cases are in src/test/resources/prometheus/payment-slo-test.yml.
	@Test
	void prometheusAlertsFireOnTheirInputAndStayQuietWhenHealthy() {
		ToStringConsumer output = new ToStringConsumer();
		try (GenericContainer<?> promtool = new GenericContainer<>("prom/prometheus:v3.7.3")
				.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/promtool"))
				.withCommand("test", "rules", "/rules/payment-slo-test.yml")
				.withCopyFileToContainer(MountableFile.forHostPath(PROMETHEUS_RULES), "/rules/payment-slo.yml")
				.withCopyFileToContainer(MountableFile.forClasspathResource("prometheus/payment-slo-test.yml"),
						"/rules/payment-slo-test.yml")
				.withLogConsumer(output)
				.withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))) {
			// A failed test case makes promtool exit non-zero, and the container fails to "start".
			assertThatCode(promtool::start).as(output::toUtf8String).doesNotThrowAnyException();
			assertThat(output.toUtf8String()).contains("SUCCESS");
		}
	}

	@Test
	void everyAlertLinksToARunbookThatExists() throws IOException {
		for (Path rules : List.of(PROMETHEUS_RULES, LOKI_RULES)) {
			String content = Files.readString(rules);
			Matcher alert = Pattern.compile("- alert: (\\w+)").matcher(content);
			Matcher runbook = Pattern.compile("runbook_url: (\\S+)").matcher(content);
			while (alert.find()) {
				assertThat(runbook.find()).as("%s in %s has a runbook_url", alert.group(1), rules).isTrue();
				assertThat(runbook.group(1)).startsWith(REPOSITORY);
				assertThat(Path.of(runbook.group(1).substring(REPOSITORY.length()))).as(runbook.group(1)).isRegularFile();
			}
			assertThat(runbook.find()).as("a runbook_url without an alert in %s", rules).isFalse();
		}
	}
}
