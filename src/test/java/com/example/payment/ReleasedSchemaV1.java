package com.example.payment;

import io.apicurio.registry.serde.avro.AvroDatumProvider;
import org.apache.avro.Conversions;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * How a consumer built on schema v1 reads: the writer's schema, from the registry, resolved to v1. The registry's
 * deserializer creates it by name (apicurio.registry.avro-datum-provider), so it is public. PaymentEndToEndTest
 * reads payment-events with it.
 */
public class ReleasedSchemaV1 implements AvroDatumProvider<GenericRecord> {

	private static final Schema V1;

	static {
		try {
			V1 = new Schema.Parser().parse(new File("src/test/resources/schemas/PaymentEvent/v1.avsc"));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public DatumReader<GenericRecord> createDatumReader(Schema writer) {
		GenericData data = new GenericData();
		data.addLogicalTypeConversion(new Conversions.DecimalConversion());
		return new GenericDatumReader<>(writer, V1, data);
	}

	@Override
	public DatumWriter<GenericRecord> createDatumWriter(GenericRecord data, Schema schema) {
		throw new UnsupportedOperationException("reads only");
	}

	@Override
	public Schema toSchema(GenericRecord data) {
		return data.getSchema();
	}
}
