package com.example.payment.infrastructure.adapter.secondary.messaging;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build's check on the event contracts (docs/episodes/bonus-10): a schema
 * change that would break a consumer fails here, before anything is deployed.
 *
 * Every schema in src/main/avro is checked against every released version of
 * it, kept in src/test/resources/schemas/{name}/, in both directions: a consumer
 * built on any released version reads what we publish now, and a consumer built
 * on the current schema reads anything published before, all the way back. That
 * is the registry's FULL_TRANSITIVE rule; the registry applies it again when the
 * relay registers the schema.
 *
 * Releasing a version is copying its schema into that directory, unchanged.
 */
class PaymentEventSchemaTest {

	private static final Path CURRENT = Path.of("src/main/avro");
	private static final Path RELEASED = Path.of("src/test/resources/schemas");

	@Test
	void everySchemaIsCompatibleBothWaysWithEveryReleasedVersion() throws IOException {
		List<Path> schemas = files(CURRENT);
		assertThat(schemas).isNotEmpty();
		for (Path file : schemas) {
			Schema current = parse(Files.readString(file));
			List<Path> released = files(RELEASED.resolve(current.getName()));
			assertThat(released).as("released versions of %s", current.getName()).isNotEmpty();
			for (Path version : released) {
				assertThat(fullyCompatible(current, parse(Files.readString(version))))
						.as("%s is compatible both ways with %s", file, version).isTrue();
			}
		}
	}

	// The reference was added the safe way: optional, with a default. Required, an old event has no value to give it.
	@Test
	void aRequiredReferenceWouldBreakIt() throws IOException {
		String required = currentPaymentEvent().replace(
				"\"name\": \"reference\", \"type\": [\"null\", \"string\"], \"default\": null",
				"\"name\": \"reference\", \"type\": \"string\"");

		assertThat(fullyCompatible(parse(required), releasedV1())).isFalse();
	}

	// A rename is a removed field and a new one: old consumers lose amount, and new ones find no amount in old events.
	@Test
	void renamingAFieldWouldBreakIt() throws IOException {
		String renamed = currentPaymentEvent().replace("\"name\": \"amount\"", "\"name\": \"value\"");

		assertThat(fullyCompatible(parse(renamed), releasedV1())).isFalse();
	}

	// An old consumer's enum has no symbol for a new event type, and no default to fall back on.
	@Test
	void aNewEventTypeWouldBreakIt() throws IOException {
		String newType = currentPaymentEvent().replace("\"REFUND_FAILED\"]", "\"REFUND_FAILED\", \"PAYMENT_REVERSED\"]");

		assertThat(fullyCompatible(parse(newType), releasedV1())).isFalse();
	}

	private static boolean fullyCompatible(Schema a, Schema b) {
		return readable(a, b) && readable(b, a);
	}

	private static boolean readable(Schema reader, Schema writer) {
		return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType()
				== SchemaCompatibilityType.COMPATIBLE;
	}

	private static String currentPaymentEvent() throws IOException {
		return Files.readString(CURRENT.resolve("PaymentEvent.avsc"));
	}

	private static Schema releasedV1() throws IOException {
		return parse(Files.readString(RELEASED.resolve("PaymentEvent/v1.avsc")));
	}

	private static Schema parse(String json) {
		return new Schema.Parser().parse(json);
	}

	private static List<Path> files(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.toString().endsWith(".avsc")).sorted().toList();
		}
	}
}
