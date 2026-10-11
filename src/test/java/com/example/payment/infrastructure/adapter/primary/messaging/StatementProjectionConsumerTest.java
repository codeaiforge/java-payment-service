package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import org.apache.avro.Conversions;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement reads every event on payment-events, back to the first
 * (docs/episodes/bonus-10): those written with schema v1, which has no
 * reference, and those written now. Avro resolves the writer's schema to this
 * service's, as the deserializer does with the schema the registry returns.
 */
class StatementProjectionConsumerTest {

	private static final String PAYMENT = UUID.randomUUID().toString();
	private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T09:30:00.123456Z");

	// As config/MessagingConfiguration does at start-up: Avro creates only the classes it is told to trust.
	@BeforeAll
	static void trustTheGeneratedClasses() {
		ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
				ClassSecurityValidator.builder().add(com.example.payment.events.PaymentEvent.class)
						.add(com.example.payment.events.PaymentEventType.class).build()));
	}

	@Test
	void readsAnEventWrittenBeforeEventsCarriedAReference() throws IOException {
		Schema v1 = new Schema.Parser().parse(Files.readString(Path.of("src/test/resources/schemas/PaymentEvent/v1.avsc")));

		PaymentEvent event = StatementProjectionConsumer.toEvent(read(write(v1, eventWith(v1).build()), v1));

		assertThat(event.reference()).isNull();
		assertThat(event.type()).isEqualTo(PaymentEventType.PAYMENT_COMPLETED);
		assertThat(event.paymentId()).isEqualTo(PaymentId.of(PAYMENT));
		assertThat(event.refundId()).isNull();
		assertThat(event.sourceAccountId()).isEqualTo(new AccountId("ACC-1"));
		assertThat(event.destinationAccountId()).isEqualTo(new AccountId("ACC-2"));
		assertThat(event.amount()).isEqualTo(new Money(new BigDecimal("250.00"), Currency.EUR));
		assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
	}

	@Test
	void readsAnEventWithItsReference() throws IOException {
		Schema current = com.example.payment.events.PaymentEvent.getClassSchema();
		GenericRecord written = eventWith(current).set("reference", "Invoice 12345").build();

		PaymentEvent event = StatementProjectionConsumer.toEvent(read(write(current, written), current));

		assertThat(event.reference()).isEqualTo(new PaymentReference("Invoice 12345"));
	}

	private static GenericRecordBuilder eventWith(Schema schema) {
		return new GenericRecordBuilder(schema)
				.set("eventId", UUID.randomUUID().toString())
				.set("type", new GenericData.EnumSymbol(schema.getField("type").schema(), "PAYMENT_COMPLETED"))
				.set("paymentId", PAYMENT)
				.set("sourceAccountId", "ACC-1")
				.set("destinationAccountId", "ACC-2")
				.set("amount", new BigDecimal("250.00"))
				.set("currency", "EUR")
				.set("occurredAt", OCCURRED_AT);
	}

	private static byte[] write(Schema schema, GenericRecord record) throws IOException {
		GenericData data = new GenericData();
		data.addLogicalTypeConversion(new Conversions.DecimalConversion());
		data.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
		new GenericDatumWriter<GenericRecord>(schema, data).write(record, encoder);
		encoder.flush();
		return out.toByteArray();
	}

	// What the deserializer does with use-specific-avro-reader: the writer's schema, resolved to the generated class's.
	private static com.example.payment.events.PaymentEvent read(byte[] bytes, Schema writer) throws IOException {
		return new SpecificDatumReader<com.example.payment.events.PaymentEvent>(writer,
				com.example.payment.events.PaymentEvent.getClassSchema())
				.read(null, DecoderFactory.get().binaryDecoder(bytes, null));
	}
}
