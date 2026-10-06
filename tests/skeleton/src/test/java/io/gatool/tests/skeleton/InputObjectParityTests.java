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

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares the input schema of a generated tool taking an Input Object with the one
 * Spring AI generates for a hand-written tool taking the equivalent record.
 *
 * <p>
 * The two agree on the shape a model reads: the object is written in place with its
 * fields, an enum carries its values, and the object stays closed to fields the schema
 * leaves out. They part on nullability on purpose. A Java record component leaves
 * nullability unstated, so Spring lists every component as required, while GraphQL
 * declares which input fields are Non-Null and GATool publishes that instead. Publishing
 * Spring's list here would state something false about the API.
 */
class InputObjectParityTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=classpath*:gatool/inputs/");

	@Test
	void inputSchema_inputObjectVariable_shouldCarryTheFieldsAndTheEnumValues() {
		this.contextRunner.run((context) -> {
			JsonNode filter = property(gaToolSchema(context), "filter");

			// The object is written in place, as Spring writes a record used once.
			assertThat(objectBranch(filter).path("type").asString()).isEqualTo("object");
			assertThat(objectBranch(filter).path("additionalProperties").asBoolean()).isFalse();
			JsonNode genre = objectBranch(filter).path("properties").path("genre");
			JsonNode genreBranch = genre.path("anyOf").get(0);
			assertThat(genreBranch.path("type").asString()).isEqualTo("string");
			assertThat(genreBranch.path("enum").valueStream().map(JsonNode::asString)).containsExactly("ACTION",
					"COMEDY", "DRAMA", "SCIFI");
		});
	}

	@Test
	void inputSchema_generatedAndHandWritten_shouldAgreeOnTheShapeAndPartOnNullability() {
		this.contextRunner.run((context) -> {
			JsonNode generated = objectBranch(property(gaToolSchema(context), "filter"));
			JsonNode handWritten = property(JSON.readTree(handWrittenSchema()), "filter");

			// The same field names reach the model from both sides.
			assertThat(generated.path("properties").propertyNames())
				.containsExactlyElementsOf(handWritten.path("properties").propertyNames());
			assertThat(generated.path("additionalProperties").asBoolean())
				.isEqualTo(handWritten.path("additionalProperties").asBoolean());
			// Spring reads a record, whose components leave nullability unstated, so it
			// requires every one of them. GraphQL says both fields are nullable, and
			// GATool
			// says so.
			assertThat(handWritten.path("required").valueStream().map(JsonNode::asString)).containsExactly("genre",
					"minRating");
			assertThat(generated.path("required")).isEmpty();
		});
	}

	private static JsonNode gaToolSchema(AssertableApplicationContext context) {
		ToolCallback[] callbacks = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks();
		assertThat(callbacks).hasSize(1);
		return JSON.readTree(callbacks[0].getToolDefinition().inputSchema());
	}

	private static String handWrittenSchema() {
		ToolCallback[] callbacks = ToolCallbacks.from(new HandWrittenFilterTool());
		assertThat(callbacks).hasSize(1);
		return callbacks[0].getToolDefinition().inputSchema();
	}

	private static JsonNode property(JsonNode schema, String name) {
		return schema.path("properties").path(name);
	}

	// A nullable position is anyOf over the type and null, so the branch carrying the
	// object is the one to compare.
	private static JsonNode objectBranch(JsonNode property) {
		return property.has("anyOf") ? property.path("anyOf").get(0) : property;
	}

	enum Genre {

		ACTION, COMEDY, DRAMA, SCIFI

	}

	record MovieFilterInput(Genre genre, Double minRating) {
	}

	static final class HandWrittenFilterTool {

		@Tool(name = "moviesByFilter", description = "Finds movies that match a filter.")
		String moviesByFilter(@ToolParam(required = false) MovieFilterInput filter) {
			return "{}";
		}

	}

}
