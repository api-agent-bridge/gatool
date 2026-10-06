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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scalar fragment written in {@code application.yml} and in the file of a profile,
 * which Spring Boot reads as two property sources.
 *
 * <p>
 * {@code gatool.inputs.scalar-schemas} and {@code gatool.results.scalar-schemas} are maps
 * of maps, and Spring Boot binds a map from every property source that holds a key of it,
 * so the fragment a tool publishes holds the keywords of both files. A profile changes
 * the value of a keyword the base file wrote, and it adds keywords. The keywords of the
 * base file that the profile leaves out stay in the fragment.
 */
class ScalarSchemaProfileTests {

	private static final String SDL = """
			scalar Stamp

			type Query { reviews(since: Stamp): String!  newest: Stamp }
			""";

	private static final String BASE = """
			gatool:
			  inputs:
			    scalar-schemas:
			      Stamp:
			        type: string
			        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
			        description: A date, for example 2026-09-28.
			""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@Test
	void baseFileAlone_shouldPublishTheFragmentItWrote(@TempDir Path folder) throws Exception {
		write(folder, "application.yml", BASE);

		JsonNode since = inputPropertyOf(folder, "default");

		assertThat(since.toString())
			.isEqualTo("{\"type\":\"string\"," + "\"pattern\":\"^[0-9]{4}-[0-9]{2}-[0-9]{2}$\"}");
	}

	@Test
	void profileWritingTheFragmentWithoutThePattern_shouldStillPublishThePatternOfTheBaseFile(@TempDir Path folder)
			throws Exception {
		write(folder, "application.yml", BASE);
		write(folder, "application-timestamps.yml", """
				gatool:
				  inputs:
				    scalar-schemas:
				      Stamp:
				        type: string
				        format: date-time
				        description: An RFC 3339 timestamp, for example 2026-09-28T10:00:00Z.
				""");

		JsonNode since = inputPropertyOf(folder, "timestamps");

		// The profile wrote a whole fragment and left the pattern out, and the fragment
		// published holds the pattern all the same, beside the format the profile added,
		// so the timestamp the description asks for fails the pattern.
		assertThat(since.path("format").asString()).isEqualTo("date-time");
		assertThat(since.path("pattern").asString()).isEqualTo("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
	}

	@Test
	void profileWritingAnotherValueForThePattern_shouldPublishTheValueOfTheProfile(@TempDir Path folder)
			throws Exception {
		write(folder, "application.yml", BASE);
		write(folder, "application-timestamps.yml", """
				gatool:
				  inputs:
				    scalar-schemas:
				      Stamp:
				        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}"
				""");

		JsonNode since = inputPropertyOf(folder, "timestamps");

		assertThat(since.path("type").asString()).isEqualTo("string");
		assertThat(since.path("pattern").asString()).isEqualTo("^[0-9]{4}-[0-9]{2}-[0-9]{2}");
	}

	@Test
	void baseFileLeavingThePatternToTheProfiles_shouldPublishItUnderTheProfileAlone(@TempDir Path folder)
			throws Exception {
		write(folder, "application.yml", """
				gatool:
				  inputs:
				    scalar-schemas:
				      Stamp:
				        type: string
				        description: A date, for example 2026-09-28.
				""");
		write(folder, "application-dates.yml", """
				gatool:
				  inputs:
				    scalar-schemas:
				      Stamp:
				        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
				""");

		assertThat(inputPropertyOf(folder, "dates").path("pattern").asString())
			.isEqualTo("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
		assertThat(inputPropertyOf(folder, "default").has("pattern")).isFalse();
	}

	@Test
	void profileWritingAResultFragmentWithoutThePattern_shouldStillPublishThePatternOfTheBaseFile(@TempDir Path folder)
			throws Exception {
		write(folder, "application.yml", """
				gatool:
				  results:
				    scalar-schemas:
				      Stamp:
				        type: string
				        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
				""");
		write(folder, "application-timestamps.yml", """
				gatool:
				  results:
				    scalar-schemas:
				      Stamp:
				        type: string
				        format: date-time
				""");
		Files.writeString(folder.resolve("Newest.graphql"), "query Newest @gatool(outputSchema: true) { newest }");
		Files.writeString(folder.resolve("scalars.graphqls"), SDL);
		AtomicReference<JsonNode> newest = new AtomicReference<>();

		this.contextRunner.withPropertyValues(settings(folder, "timestamps")).run((context) -> {
			assertThat(context).hasNotFailed();
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			newest.set(JSON.readTree(tool.outputSchema()).at("/properties/data/anyOf/0/properties/newest/anyOf/0"));
		});

		assertThat(newest.get().path("format").asString()).isEqualTo("date-time");
		assertThat(newest.get().path("pattern").asString()).isEqualTo("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
	}

	// The typed branch of the nullable variable, which holds the keywords of the
	// fragment.
	private JsonNode inputPropertyOf(Path folder, String profile) throws Exception {
		Files.writeString(folder.resolve("Reviews.graphql"), "query Reviews($since: Stamp) { reviews(since: $since) }");
		Files.writeString(folder.resolve("scalars.graphqls"), SDL);
		AtomicReference<JsonNode> property = new AtomicReference<>();
		this.contextRunner.withPropertyValues(settings(folder, profile)).run((context) -> {
			assertThat(context).hasNotFailed();
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			property.set(JSON.readTree(tool.inputSchema()).at("/properties/since/anyOf/0"));
		});
		return property.get();
	}

	private static String[] settings(Path folder, String profile) {
		return new String[] { "spring.config.location=file:" + folder.toAbsolutePath() + "/",
				"spring.profiles.active=" + profile, "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/scalars.graphqls",
				"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/", };
	}

	private static void write(Path folder, String name, String text) throws Exception {
		Files.writeString(folder.resolve(name), text);
	}

}
