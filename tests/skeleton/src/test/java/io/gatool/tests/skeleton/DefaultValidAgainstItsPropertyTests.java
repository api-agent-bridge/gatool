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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import graphql.Scalars;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
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
import io.gatool.core.internal.schema.InputSchemaWriter;
import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rule JSON Schema states about the keyword: "It is RECOMMENDED that a default
 * value be valid against the associated schema."
 *
 * <p>
 * These tests walk the whole published schema, find every {@code default}, and run each
 * one through the same validator the MCP SDK puts in front of a tool call. A default that
 * fails here is one a model can read, send back, and be refused for.
 *
 * <p>
 * Both ways a schema reaches GATool are covered, because the two start from different
 * values: a schema read from SDL carries a GraphQL literal, and a schema built in code
 * carries a Java value. A Java value published without GraphQL's coercion would put
 * {@code "drama"} on a field of type {@code [String!]}.
 */
class DefaultValidAgainstItsPropertyTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	@Test
	void everyDefault_fromASchemaReadFromSdl_shouldValidateAgainstItsOwnProperty() {
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				enum Genre { ACTION COMEDY }

				input Page { size: Int = 20  cursor: String }

				input Filter {
				  tags: [String!] = "drama"
				  ids: [ID!] = 4
				  genre: Genre = ACTION
				  page: Page = { size: 50 }
				  pages: [Page!] = { size: 50 }
				  rate: Float = 5.0
				  id: ID = 4
				}

				type Query {
				  search(filter: Filter, first: Int = 10, tags: [String!] = "drama"): String!
				}
				""", "defaults.graphqls");

		assertEveryDefaultValidates(publish(schema));
	}

	@Test
	void everyDefault_fromASchemaBuiltInCode_shouldValidateAgainstItsOwnProperty() {
		assertEveryDefaultValidates(publish(programmaticSchema()));
	}

	@Test
	void theTwoSchemas_carryingTheSameDefaults_shouldPublishTheSameJson() {
		// The property worth having beyond validity: one default, one published value,
		// whichever way the schema was built.
		JsonNode fromSdl = publish(SdlSchemaFactory.schemaFrom("""
				input Filter { tags: [String!] = "drama"  id: ID = 4  size: Int = 20 }

				type Query { search(filter: Filter): String! }
				""", "same.graphqls"));

		JsonNode fromCode = publish(programmaticSchema());

		assertThat(fieldDefaults(fromCode)).isEqualTo(fieldDefaults(fromSdl));
	}

	private static GraphQLSchema programmaticSchema() {
		GraphQLInputObjectType filter = GraphQLInputObjectType.newInputObject()
			.name("Filter")
			.field(GraphQLInputObjectField.newInputObjectField()
				.name("tags")
				.type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
				.defaultValueProgrammatic("drama"))
			.field(GraphQLInputObjectField.newInputObjectField()
				.name("id")
				.type(Scalars.GraphQLID)
				.defaultValueProgrammatic(4))
			.field(GraphQLInputObjectField.newInputObjectField()
				.name("size")
				.type(Scalars.GraphQLInt)
				.defaultValueProgrammatic(20))
			.build();
		return GraphQLSchema.newSchema()
			.query(GraphQLObjectType.newObject()
				.name("Query")
				.field(GraphQLFieldDefinition.newFieldDefinition()
					.name("search")
					.type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
					.argument(GraphQLArgument.newArgument().name("filter").type(filter)))
				.build())
			.build();
	}

	private static JsonNode publish(GraphQLSchema schema) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser().parse(new OperationSource("file:/mcp/Search.graphql", """
				query Search($filter: Filter) { search(filter: $filter) }
				""", Set.of(ToolExposureType.MCP)), diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		return JSON.readTree(InputSchemaWriter.write(schema, (OperationFile) parsed).json());
	}

	// The defaults of the one Input Object, by field name, for comparing the two paths.
	private static Map<String, JsonNode> fieldDefaults(JsonNode published) {
		Map<String, JsonNode> defaults = new TreeMap<>();
		JsonNode fields = published.path("properties").path("filter").path("anyOf").get(0).path("properties");
		for (String name : fields.propertyNames()) {
			JsonNode property = fields.path(name);
			if (property.has("default")) {
				defaults.put(name, property.path("default"));
			}
		}
		return defaults;
	}

	private static void assertEveryDefaultValidates(JsonNode published) {
		List<String> checked = new ArrayList<>();
		walk(published, "", checked);
		// A walk that did not find a default would pass silently, and every schema here
		// carries several defaults.
		assertThat(checked).isNotEmpty();
	}

	private static void walk(JsonNode node, String path, List<String> checked) {
		if (node.isObject() && node.has("default")) {
			checked.add(path);
			assertThat(validates(node)).as("the default at %s validates against its own property: %s", path, node)
				.isTrue();
		}
		for (String name : node.propertyNames()) {
			walk(node.path(name), path + "/" + name, checked);
		}
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				walk(node.get(index), path + "/" + index, checked);
			}
		}
	}

	// The validator takes an object instance, so the property and its default are each
	// wrapped in one, which leaves what is under test unchanged.
	@SuppressWarnings("unchecked")
	private static boolean validates(JsonNode property) {
		JsonNode defaultValue = property.path("default");
		Map<String, Object> wrapper = Map.of("type", "object", "properties",
				Map.of("value", JSON.convertValue(property, Map.class)), "required", List.of("value"));
		Map<String, Object> instance = JSON.convertValue(JSON.createObjectNode().set("value", defaultValue), Map.class);
		return VALIDATOR.validate(wrapper, instance).valid();
	}

}
