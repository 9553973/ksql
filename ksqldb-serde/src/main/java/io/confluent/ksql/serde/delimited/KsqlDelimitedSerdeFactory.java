/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.serde.delimited;

import com.google.errorprone.annotations.Immutable;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.schema.ksql.PersistenceSchema;
import io.confluent.ksql.schema.ksql.SqlTypeWalker;
import io.confluent.ksql.schema.ksql.types.SqlDecimal;
import io.confluent.ksql.schema.ksql.types.SqlPrimitiveType;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.serde.Delimiter;
import io.confluent.ksql.serde.FormatFactory;
import io.confluent.ksql.testing.EffectivelyImmutable;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import java.util.function.Supplier;
import org.apache.commons.csv.CSVFormat;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.data.Struct;


@Immutable
class KsqlDelimitedSerdeFactory {

  @EffectivelyImmutable
  private final CSVFormat csvFormat;

  KsqlDelimitedSerdeFactory(final Delimiter delimiter) {
    this.csvFormat = CSVFormat.DEFAULT.withDelimiter(delimiter.getDelimiter());
  }

  public Serde<Struct> createSerde(
      final PersistenceSchema schema,
      final KsqlConfig ksqlConfig,
      final Supplier<SchemaRegistryClient> schemaRegistryClientFactory
  ) {
    validate(schema);

    return Serdes.serdeFrom(
        new KsqlDelimitedSerializer(csvFormat),
        new KsqlDelimitedDeserializer(schema, csvFormat)
    );
  }

  private static void validate(final PersistenceSchema schema) {
    schema.columns()
        .forEach(c -> SqlTypeWalker.visit(c.type(), new SchemaValidator()));
  }

  private static class SchemaValidator implements SqlTypeWalker.Visitor<Void, Void> {

    @Override
    public Void visitPrimitive(final SqlPrimitiveType type) {
      // Primitive types are allowed.
      return null;
    }

    @Override
    public Void visitDecimal(final SqlDecimal type) {
      // Decimal types are allowed.
      return null;
    }

    @Override
    public Void visitType(final SqlType schema) {
      throw new KsqlException("The '" + FormatFactory.DELIMITED.name()
          + "' format does not support type '" + schema.baseType() + "'");
    }
  }
}
