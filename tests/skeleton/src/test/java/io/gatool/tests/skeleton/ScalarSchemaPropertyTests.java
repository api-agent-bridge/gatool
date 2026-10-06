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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured scalar fragment, from the property to the published tool.
 *
 * <p>
 * A custom scalar reaches a model as any JSON value unless something describes it, and a
 * scalar's name is only a name: of the 31 scalars in the community registry, three
 * declare a {@code @specifiedBy} URL. So the format is stated here by whoever owns the
 * API, and these tests assert that what the fragment wrote is what the model reads.
 */
class ScalarSchemaPropertyTests {

	private static final String SDL = """
			scalar Stamp
			scalar Long

			type Query { reviews(since: Stamp, id: Long): String!  newest(id: Long): Long }
			""";

	private static final String OPERATION = """
			query Reviews($since: Stamp, $id: Long) { reviews(since: $since, id: $id) }
			""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@Test
	void property_describingAScalar_shouldReachThePublishedInputSchema(@TempDir Path folder) throws Exception {
		JsonNode schema = schemaOf(folder, "gatool.inputs.scalar-schemas.Stamp.type=string",
				"gatool.inputs.scalar-schemas.Stamp.format=date-time",
				"gatool.inputs.scalar-schemas.Stamp.description=An RFC 3339 timestamp, for example "
						+ "2026-09-18T10:00:00Z.");

		JsonNode since = schema.path("properties").path("since");
		assertThat(since.path("anyOf").get(0).path("type").asString()).isEqualTo("string");
		assertThat(since.path("anyOf").get(0).path("format").asString()).isEqualTo("date-time");
		assertThat(since.path("anyOf").get(1).path("type").asString()).isEqualTo("null");
		assertThat(since.path("description").asString()).startsWith("An RFC 3339 timestamp");
	}

	@Test
	void property_scalarLeftUnconfigured_shouldStillAcceptAnyJsonValue(@TempDir Path folder) throws Exception {
		JsonNode schema = schemaOf(folder, "gatool.inputs.scalar-schemas.Stamp.type=string");

		// The one a fragment described is described; the other keeps the empty schema,
		// and the API's own coercion answers a wrong value with a request error.
		assertThat(schema.path("properties").path("id").isEmpty()).isTrue();
	}

	@Test
	void property_twoWireFormsForALargeInteger_shouldPublishBothBranches(@TempDir Path folder) throws Exception {
		JsonNode schema = schemaOf(folder, "gatool.inputs.scalar-schemas.Long.anyOf[0].type=integer",
				"gatool.inputs.scalar-schemas.Long.anyOf[1].type=string",
				"gatool.inputs.scalar-schemas.Long.anyOf[1].pattern=^-?(0|[1-9][0-9]{0,18})$",
				"gatool.inputs.scalar-schemas.Long.description=A 64-bit signed integer. Send a value beyond "
						+ "9007199254740991 as a quoted string, because a JSON reader using IEEE 754 binary64 "
						+ "changes it.");

		JsonNode id = schema.path("properties").path("id");
		assertThat(id.path("anyOf").get(0).path("type").asString()).isEqualTo("integer");
		assertThat(id.path("anyOf").get(1).path("type").asString()).isEqualTo("string");
		assertThat(id.path("anyOf").get(1).path("pattern").asString()).isEqualTo("^-?(0|[1-9][0-9]{0,18})$");
		assertThat(id.path("anyOf").get(2).path("type").asString()).isEqualTo("null");
	}

	@Test
	void property_carryingARefusedKeyword_shouldStopStartupAndNameTheClient(@TempDir Path folder) throws Exception {
		writeOperationAndSchema(folder);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.inputs.scalar-schemas.Long.type=integer",
					"gatool.inputs.scalar-schemas.Long.minimum=0")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("minimum")
				.hasMessageContaining("Anthropic"));
	}

	@Test
	void property_namingAScalarTheSchemaLacks_shouldStopListingTheScalarsTheSchemaDeclares(@TempDir Path folder)
			throws Exception {
		writeOperationAndSchema(folder);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.inputs.scalar-schemas.Stmp.type=string")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("does not name a custom scalar")
				// The message lists what the schema does declare, so a typo is obvious.
				.hasMessageContaining("Long, Stamp"));
	}

	@Test
	void property_describingAScalarInAResult_shouldReachThePublishedOutputSchemaAlone(@TempDir Path folder)
			throws Exception {
		writeOperationAndSchema(folder);
		Files.writeString(folder.resolve("Reviews.graphql"), """
				query Reviews($id: Long) @gatool(outputSchema: true) { newest(id: $id) }
				""");

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.inputs.scalar-schemas.Long.type=integer",
					"gatool.results.scalar-schemas.Long.anyOf[0].type=integer",
					"gatool.results.scalar-schemas.Long.anyOf[1].type=string",
					"gatool.results.scalar-schemas.Long.anyOf[1].pattern=^-?(0|[1-9][0-9]{0,18})$")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				assertThat(JSON.readTree(tool.inputSchema()).at("/properties/id/anyOf").toString())
					.isEqualTo("[{\"type\":\"integer\"},{\"type\":\"null\"}]");
				assertThat(JSON.readTree(tool.outputSchema())
					.at("/properties/data/anyOf/0/properties/newest/anyOf")
					.toString())
					.isEqualTo("[{\"type\":\"integer\"},{\"type\":\"string\","
							+ "\"pattern\":\"^-?(0|[1-9][0-9]{0,18})$\"},{\"type\":\"null\"}]");
			});
	}

	@Test
	void property_forAResultCarryingARefusedKeyword_shouldStopStartupAndNameTheResultsProperty(@TempDir Path folder)
			throws Exception {
		writeOperationAndSchema(folder);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.results.scalar-schemas.Long.type=string",
					"gatool.results.scalar-schemas.Long.maxLength=19")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining(
						"The property gatool.results.scalar-schemas.Long carries the keyword " + "'maxLength'"));
	}

	private JsonNode schemaOf(Path folder, String... scalarSchemas) throws Exception {
		writeOperationAndSchema(folder);
		AtomicReference<JsonNode> schema = new AtomicReference<>();
		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues(scalarSchemas)
			.run((context) -> schema
				.set(JSON.readTree(context.getBean(GAToolCatalog.class).mcpTools().getFirst().inputSchema())));
		return schema.get();
	}

	private static String[] settings(Path folder) {
		return new String[] { "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/scalars.graphqls",
				"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/", };
	}

	private static void writeOperationAndSchema(Path folder) throws Exception {
		Files.writeString(folder.resolve("Reviews.graphql"), OPERATION);
		Files.writeString(folder.resolve("scalars.graphqls"), SDL);
	}

}
