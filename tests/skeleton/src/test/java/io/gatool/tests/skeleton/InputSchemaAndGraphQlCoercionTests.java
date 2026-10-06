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

package io.gatool.tests.skeleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.OperationFileParser;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.ParsedFile;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.schema.InputSchema;
import io.gatool.core.internal.schema.InputSchemaWriter;
import io.gatool.core.internal.schema.ScalarSchemas;
import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The places where the published input schema and GraphQL's input coercion answer
 * differently for one value.
 *
 * <p>
 * The MCP SDK validates the arguments of a call against the published schema before the
 * tool runs, and the API coerces what reaches it. Each test puts one value through the
 * validator the SDK uses and through graphql-java, which is the API here, so both answers
 * are on the page: a value the schema accepts and the API refuses reaches the API and
 * comes back as its error, and a value the schema refuses stops before the API, which
 * would have taken it.
 */
class InputSchemaAndGraphQlCoercionTests {

	private static final String SDL = """
			scalar Stamp

			input MovieWhere {
			  title: String
			  and: [MovieWhere!]
			}

			type Query {
			  reviewsSince(at: Stamp!): String
			  moviesOfYears(years: [Int!]!): String
			  movies(where: MovieWhere): String
			}
			""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	@Test
	void nonNullCustomScalarWithoutAFragment_shouldAcceptANullThatTheApiRefuses() {
		String document = "query ReviewsSince($at: Stamp!) { reviewsSince(at: $at) }";
		JsonNode published = publish(SDL, document);
		Map<String, Object> arguments = Collections.singletonMap("at", null);

		// The scalar lacks a fragment and a specification GATool has read, so the
		// property is the empty schema, which leaves the type unstated, null included.
		assertThat(published.path("properties").path("at").isEmpty()).isTrue();
		assertThat(published.path("required").valueStream().map(JsonNode::asString)).containsExactly("at");

		assertThat(validate(published, arguments).valid()).isTrue();
		assertThat(messagesOf(execute(SDL, document, arguments))).singleElement()
			.asString()
			.contains("'at'")
			.contains("Stamp!");
	}

	@Test
	void nonNullCustomScalarWithoutAFragment_leftOut_shouldBeRefusedByTheSchema() {
		JsonNode published = publish(SDL, "query ReviewsSince($at: Stamp!) { reviewsSince(at: $at) }");

		// The required list still holds the variable, so what the schema lets through is
		// the null alone.
		assertThat(validate(published, Map.of()).valid()).isFalse();
	}

	@Test
	void nonNullCustomScalarWithAFragmentNamingAType_shouldRefuseTheNull() {
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(SDL, "coercion.graphqls");
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas fragments = ScalarSchemas.of(schema, Map.of("Stamp", Map.of("type", "string")), diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		JsonNode published = JSON.readTree(InputSchemaWriter
			.write(schema, parse("query ReviewsSince($at: Stamp!) { reviewsSince(at: $at) }"), fragments)
			.json());

		// A type is what closes the position: the Non-Null variable publishes the
		// fragment without a null branch beside it.
		assertThat(published.path("properties").path("at").path("type").asString()).isEqualTo("string");
		assertThat(validate(published, Collections.singletonMap("at", null)).valid()).isFalse();
		assertThat(validate(published, Map.of("at", "2026-09-28T10:00:00Z")).valid()).isTrue();
	}

	@Test
	void listPosition_shouldRefuseTheSingleValueThatGraphQlCoercesToAList() {
		String document = "query MoviesOfYears($years: [Int!]!) { moviesOfYears(years: $years) }";
		JsonNode published = publish(SDL, document);
		Map<String, Object> single = Map.of("years", 1999);
		Map<String, Object> list = Map.of("years", List.of(1999));

		assertThat(published.path("properties").path("years").path("type").asString()).isEqualTo("array");

		JsonSchemaValidator.ValidationResponse refused = validate(published, single);
		assertThat(refused.valid()).isFalse();
		assertThat(refused.errorMessage()).contains("years").contains("array");
		assertThat(validate(published, list).valid()).isTrue();

		ExecutionResult coerced = execute(SDL, document, single);
		assertThat(coerced.getErrors()).isEmpty();
		assertThat(coerced.<Map<String, Object>>getData()).containsEntry("moviesOfYears", "years [1999]");
	}

	@Test
	void recursiveInputType_shouldAcceptAFieldTheTypeLacksBelowTheRepeat() {
		String document = "query Movies($where: MovieWhere) { movies(where: $where) }";
		JsonNode published = publish(SDL, document);
		Map<String, Object> wrongAtTheTop = Map.of("where", Map.of("nonsense", 1));
		Map<String, Object> wrongBelowTheRepeat = Map.of("where", Map.of("and", List.of(Map.of("nonsense", 1))));

		// The first MovieWhere lists its fields and closes the object, and the one inside
		// it is the repeat: an object with a description, open to any field.
		JsonNode repeat = published.path("properties")
			.path("where")
			.path("anyOf")
			.get(0)
			.path("properties")
			.path("and")
			.path("anyOf")
			.get(0)
			.path("items");
		assertThat(repeat.path("type").asString()).isEqualTo("object");
		assertThat(repeat.path("description").asString()).contains("holds another of its own kind");
		assertThat(repeat.has("properties")).isFalse();
		assertThat(repeat.has("additionalProperties")).isFalse();

		assertThat(validate(published, wrongAtTheTop).valid()).isFalse();
		assertThat(validate(published, wrongBelowTheRepeat).valid()).isTrue();
		assertThat(messagesOf(execute(SDL, document, wrongBelowTheRepeat))).singleElement()
			.asString()
			.contains("nonsense")
			.contains("MovieWhere");
	}

	@Test
	void inputTypeBelowTheNodeBudget_shouldAcceptAFieldTheTypeLacks() {
		String sdl = filterGraphSdl(20, 3);
		String document = "query Rows($where: t0_bool_exp) { rows(where: $where) }";
		InputSchema written = InputSchemaWriter.write(SdlSchemaFactory.schemaFrom(sdl, "wide.graphqls"),
				parse(document));
		JsonNode published = JSON.readTree(written.json());
		assertThat(written.cutTypes()).isNotEmpty();

		// The first position the walk cut, as the names of the fields that lead to it.
		List<String> path = new ArrayList<>();
		assertThat(findTheFirstCut(published.path("properties").path("where"), path)).isTrue();
		JsonNode cut = published.path("properties").path("where");
		for (String field : path) {
			cut = objectBranchOf(cut).path("properties").path(field);
		}
		cut = objectBranchOf(cut);
		assertThat(cut.path("description").asString()).contains("this one stops here");
		assertThat(cut.has("properties")).isFalse();
		assertThat(cut.has("additionalProperties")).isFalse();

		Map<String, Object> wrongBelowTheCut = Map.of("where", nested(path, Map.of("nonsense", 1)));

		assertThat(validate(published, wrongBelowTheCut).valid()).isTrue();
		assertThat(messagesOf(execute(sdl, document, wrongBelowTheCut))).singleElement()
			.asString()
			.contains("nonsense");
	}

	// The filter graph of an API that publishes one boolean expression type per table,
	// each reaching itself and its neighbours, which is the shape that passes the budget.
	private static String filterGraphSdl(int tables, int relationships) {
		StringBuilder sdl = new StringBuilder();
		for (int table = 0; table < tables; table++) {
			sdl.append("input t")
				.append(table)
				.append("_bool_exp {\n")
				.append("  _not: t")
				.append(table)
				.append("_bool_exp\n")
				.append("  id: String\n  name: String\n");
			for (int relationship = 1; relationship <= relationships; relationship++) {
				sdl.append("  rel")
					.append(relationship)
					.append(": t")
					.append((table + relationship) % tables)
					.append("_bool_exp\n");
			}
			sdl.append("}\n");
		}
		return sdl.append("type Query { rows(where: t0_bool_exp): String }\n").toString();
	}

	// Every position of this graph is a nullable object, so the object is the first
	// branch of an anyOf beside null.
	private static JsonNode objectBranchOf(JsonNode property) {
		return property.path("anyOf").get(0);
	}

	private static boolean findTheFirstCut(JsonNode property, List<String> path) {
		JsonNode object = objectBranchOf(property);
		if (object == null) {
			return false;
		}
		if (object.path("description").asString("").contains("this one stops here")) {
			return true;
		}
		for (String field : object.path("properties").propertyNames()) {
			path.add(field);
			if (findTheFirstCut(object.path("properties").path(field), path)) {
				return true;
			}
			path.removeLast();
		}
		return false;
	}

	private static Map<String, Object> nested(List<String> path, Map<String, Object> innermost) {
		Map<String, Object> value = innermost;
		for (String field : path.reversed()) {
			Map<String, Object> outer = new LinkedHashMap<>();
			outer.put(field, value);
			value = outer;
		}
		return value;
	}

	private static JsonNode publish(String sdl, String document) {
		return JSON.readTree(
				InputSchemaWriter.write(SdlSchemaFactory.schemaFrom(sdl, "coercion.graphqls"), parse(document)).json());
	}

	private static OperationFile parse(String document) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser().parse(
				new OperationSource("file:/mcp/Operation.graphql", document, Set.of(ToolExposureType.MCP)),
				diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		return (OperationFile) parsed;
	}

	@SuppressWarnings("unchecked")
	private static JsonSchemaValidator.ValidationResponse validate(JsonNode published, Map<String, Object> arguments) {
		return VALIDATOR.validate(JSON.convertValue(published, Map.class), arguments);
	}

	// graphql-java stands in for the API: it coerces the variables the way the GraphQL
	// specification describes, and every root field answers with the argument it was
	// given, so a test reads what the coercion made of the value.
	private static ExecutionResult execute(String sdl, String document, Map<String, Object> variables) {
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(sdl, "coercion.graphqls");
		GraphQLCodeRegistry.Builder fetchers = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
		schema.getQueryType().getFields().forEach((field) -> {
			DataFetcher<Object> echo = (environment) -> environment.getArguments()
				.entrySet()
				.stream()
				.map((argument) -> argument.getKey() + " " + argument.getValue())
				.findFirst()
				.orElse("");
			fetchers.dataFetcher(FieldCoordinates.coordinates("Query", field.getName()), echo);
		});
		GraphQLSchema executable = schema.transform((builder) -> builder.codeRegistry(fetchers.build()));
		return GraphQL.newGraphQL(executable)
			.build()
			.execute(ExecutionInput.newExecutionInput(document).variables(variables).build());
	}

	private static List<String> messagesOf(ExecutionResult result) {
		return result.getErrors().stream().map((error) -> error.getMessage()).toList();
	}

}
