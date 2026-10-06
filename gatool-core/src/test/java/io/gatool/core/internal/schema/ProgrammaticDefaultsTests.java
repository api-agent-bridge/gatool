/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatool.core.internal.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Month;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import graphql.GraphQLContext;
import graphql.Scalars;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.OperationFileParser;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.ParsedFile;
import io.gatool.core.internal.operation.ToolExposureType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defaults on a schema built in code.
 *
 * <p>
 * Every other schema in these tests comes from SDL, where graphql-java carries a default
 * as a GraphQL literal. An application that builds its input types in Java carries them
 * as Java values instead, and those go through GraphQL's coercion as well. Written
 * straight through Jackson, a field of type {@code [String!]} with a default of
 * {@code "drama"} would publish {@code "default": "drama"} beside a property that accepts
 * an array. That default fails its own schema, and a model that reads it and sends it
 * back is refused by the argument validation in front of the tool.
 *
 * <p>
 * GraphQL reads that same default as {@code ["drama"]}, which is why graphql-java accepts
 * it, and it is what these tests expect to see published.
 */
class ProgrammaticDefaultsTests {

	// Reads a decimal back as the digits the writer published, so a test can compare
	// them: the default reader rounds one to a double.
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	private static final GraphQLEnumType GENRE = GraphQLEnumType.newEnum()
		.name("Genre")
		.value("ACTION")
		.value("COMEDY")
		.build();

	// A scalar whose coercing takes any Java value, which is what lets a schema hold a
	// default of a type nothing here can render.
	private static final GraphQLScalarType ANYTHING = GraphQLScalarType.newScalar()
		.name("Anything")
		.coercing(new Coercing<Object, Object>() {

			@Override
			public Object serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				return dataFetcherResult;
			}

			@Override
			public Object parseValue(Object input, GraphQLContext context, Locale locale) {
				return input;
			}

			@Override
			public Value<?> valueToLiteral(Object input, GraphQLContext context, Locale locale) {
				return StringValue.of(String.valueOf(input));
			}
		})
		.build();

	private static final GraphQLInputObjectType PAGE = GraphQLInputObjectType.newInputObject()
		.name("Page")
		.field(GraphQLInputObjectField.newInputObjectField().name("size").type(Scalars.GraphQLInt))
		.field(GraphQLInputObjectField.newInputObjectField().name("cursor").type(Scalars.GraphQLString))
		.build();

	@Test
	void default_singleValueForAListField_shouldPublishTheOneElementListGraphQlReads() {
		JsonNode property = fieldWithDefault(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)), "drama");

		assertThat(property.path("default")).isEqualTo(JSON.readTree("[\"drama\"]"));
	}

	@Test
	void default_listForAListField_shouldPublishItAsItStands() {
		JsonNode property = fieldWithDefault(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)),
				List.of("drama", "scifi"));

		assertThat(property.path("default")).isEqualTo(JSON.readTree("[\"drama\",\"scifi\"]"));
	}

	@Test
	void default_singleObjectForAListOfInputObjects_shouldPublishTheOneElementList() {
		JsonNode property = fieldWithDefault(GraphQLList.list(PAGE), Map.of("size", 20));

		assertThat(property.path("default")).isEqualTo(JSON.readTree("[{\"size\":20}]"));
	}

	@Test
	void default_intForAnIdField_shouldPublishTheStringTheApiHolds() {
		// The rule the literal path already applies, so the two agree on one default.
		JsonNode property = fieldWithDefault(Scalars.GraphQLID, 4);

		assertThat(property.path("default")).isEqualTo(JSON.readTree("\"4\""));
	}

	@Test
	void default_integerBeyondALong_shouldPublishTheDigits() {
		// JSON carries the digits whatever their number, and the literal path publishes
		// them the same way. A built-in scalar cannot hold such a value, since
		// graphql-java refuses the schema, so the case arrives through a custom scalar.
		JsonNode property = fieldWithDefault(ANYTHING, new BigInteger("9223372036854775808"));

		assertThat(property.path("default").bigIntegerValue()).isEqualTo(new BigInteger("9223372036854775808"));
	}

	@Test
	void default_decimalForACustomScalar_shouldPublishEveryDigit() {
		// The value the schema holds is what the API reads. Rounded to a double, a
		// default with eighteen decimal places would publish sixteen digits in exponent
		// form.
		JsonNode property = fieldWithDefault(ANYTHING, new BigDecimal("1234567890.123456789012345678"));

		assertThat(property.path("default").decimalValue())
			.isEqualByComparingTo(new BigDecimal("1234567890.123456789012345678"));
	}

	@Test
	void default_ordinaryScalars_shouldPublishTheValue() {
		assertThat(fieldWithDefault(Scalars.GraphQLInt, 20).path("default").asInt()).isEqualTo(20);
		assertThat(fieldWithDefault(Scalars.GraphQLFloat, 5.5d).path("default").asDouble()).isEqualTo(5.5d);
		assertThat(fieldWithDefault(Scalars.GraphQLString, "kepler").path("default").asString()).isEqualTo("kepler");
		assertThat(fieldWithDefault(Scalars.GraphQLBoolean, true).path("default").asBoolean()).isTrue();
		assertThat(fieldWithDefault(GENRE, "ACTION").path("default").asString()).isEqualTo("ACTION");
	}

	@Test
	void default_inputObjectField_shouldPublishEachFieldThroughTheSameRules() {
		JsonNode property = fieldWithDefault(PAGE, Map.of("size", 20));

		assertThat(property.path("default")).isEqualTo(JSON.readTree("{\"size\":20}"));
	}

	@Test
	void default_valueOfATypeWithNoFaithfulJsonForm_shouldLeaveTheKeywordOut() {
		// A custom scalar holds any Java type its own coercing understands, and
		// rendering one by guesswork would name a value the API does not take. The
		// property for such a scalar is the empty schema, so any JSON would validate,
		// and that is exactly why a guess would go unnoticed.
		JsonNode property = fieldWithDefault(ANYTHING, Month.MARCH);

		assertThat(property.has("default")).isFalse();
	}

	@Test
	void default_mapOrListForACustomScalar_shouldPublishTheJsonItSpells() {
		// The same rule the literal path applies to an object or list literal on a
		// custom scalar: the value is spelled the way the API takes it.
		assertThat(fieldWithDefault(ANYTHING, Map.of("city", "Ljubljana")).path("default"))
			.isEqualTo(JSON.readTree("{\"city\":\"Ljubljana\"}"));
		assertThat(fieldWithDefault(ANYTHING, List.of("a", 1, true)).path("default"))
			.isEqualTo(JSON.readTree("[\"a\",1,true]"));
	}

	@Test
	void default_integerBeyondALongForAFloatField_shouldPublishTheNumber() {
		// Float coerces a whole number of any size, so a default past a long is
		// published.
		JsonNode property = fieldWithDefault(Scalars.GraphQLFloat, new BigInteger("10000000000000000000"));

		assertThat(property.path("default").asDouble()).isEqualTo(1.0E19);
	}

	@Test
	void default_stringForACustomScalar_shouldStillBePublished() {
		// A value that carries into JSON as it stands keeps its default, so a custom
		// scalar keeps everything it could have said.
		JsonNode property = fieldWithDefault(ANYTHING, "2026-09-18T00:00:00Z");

		assertThat(property.path("default").asString()).isEqualTo("2026-09-18T00:00:00Z");
	}

	// Builds a one-field Input Object carrying this default, runs the writer over an
	// operation that takes it, and returns the published property for that field.
	private static JsonNode fieldWithDefault(GraphQLInputType fieldType, Object defaultValue) {
		GraphQLInputObjectType filter = GraphQLInputObjectType.newInputObject()
			.name("Filter")
			.field(GraphQLInputObjectField.newInputObjectField()
				.name("value")
				.type(fieldType)
				.defaultValueProgrammatic(defaultValue))
			.build();
		GraphQLSchema schema = GraphQLSchema.newSchema()
			.query(GraphQLObjectType.newObject()
				.name("Query")
				.field(GraphQLFieldDefinition.newFieldDefinition()
					.name("search")
					.type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
					.argument(GraphQLArgument.newArgument().name("filter").type(filter)))
				.build())
			.build();
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser().parse(new OperationSource("file:/mcp/Search.graphql", """
				query Search($filter: Filter) { search(filter: $filter) }
				""", Set.of(ToolExposureType.MCP)), diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		JsonNode published = JSON.readTree(InputSchemaWriter.write(schema, (OperationFile) parsed).json());
		return published.path("properties").path("filter").path("anyOf").get(0).path("properties").path("value");
	}

}
