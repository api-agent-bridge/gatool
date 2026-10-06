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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property values the schema side refuses at startup, and the two schema fetch
 * outcomes the URL tests leave out: a cache that cannot be written and a header the JDK
 * cannot send.
 *
 * <p>
 * Each refusal is an {@link InvalidConfigurationPropertyValueException}, so Boot's own
 * analysis prints the property and the value, and each test asserts the property name and
 * the sentence that says what to set.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupSettingsSliceTests {

	private static final String API_URL = "gatool.api.url=http://127.0.0.1:1/graphql";

	private static final String MOVIES_SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String NO_IN_PROCESS_FILES = "gatool.in-process.operations.locations=optional:classpath*:gatool/none/";

	private static HttpServer registry;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(API_URL, MOVIES_SCHEMA, NO_IN_PROCESS_FILES, "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@BeforeAll
	static void startRegistry() throws IOException {
		registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		registry.createContext("/sdl", (exchange) -> {
			byte[] body = MoviesSchema.sdl().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		registry.start();
	}

	@AfterAll
	static void stopRegistry() {
		registry.stop(0);
	}

	@Test
	void startup_maxCharactersBelowOne_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.results.max-characters=0").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.results.max-characters")
				.hasMessageContaining("A tool returns at least one character, so this limit is a positive number");
		});
	}

	@Test
	void startup_maxResponseSizeAtZero_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.api.max-response-size=0B").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.max-response-size")
				.hasMessageContaining("the largest one it accepts is a positive size, such as 1MB");
		});
	}

	@Test
	void startup_schemaMaxSizeAtZero_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues(
					"gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl",
					"gatool.api.schema.max-size=0B")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.schema.max-size")
					.hasMessageContaining("the largest one it accepts is a positive size, such as 10MB");
			});
	}

	@Test
	void startup_staticHeaderStrategyWithoutAHeaderName_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.api.credentials.strategy=static-header").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.header-name")
				.hasMessageContaining("set the name of that header, such as Authorization or X-API-Key");
		});
	}

	@Test
	void startup_staticHeaderStrategyWithoutAHeaderValue_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=static-header",
					"gatool.api.credentials.header-name=X-API-Key")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.credentials.header-value")
					.hasMessageContaining("keep that value in the environment");
			});
	}

	@Test
	void startup_staticHeaderValueEndingInALineBreak_shouldStopNamingThePropertyAndKeepTheValueOut(
			CapturedOutput output) {
		// TestPropertyValues trims a value, so the line break arrives through a property
		// source of its own, the way a secret file's content reaches an environment. The
		// JDK refuses such a value on every call and names the whole value in its
		// message, so the check runs at startup, the way the registry key's does.
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=static-header",
					"gatool.api.credentials.header-name=X-API-Key")
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new MapPropertySource("secret",
						Map.of("gatool.api.credentials.header-value", "secret-key-value\n"))))
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.credentials.header-value")
					.hasMessageContaining("line break");
			});

		assertThat(output.getAll()).doesNotContain("secret-key-value");
	}

	@Test
	void startup_staticHeaderNameWithASpace_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=static-header",
					"gatool.api.credentials.header-name=Bad Name", "gatool.api.credentials.header-value=k")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.credentials.header-name")
					.hasMessageContaining("a character a header name cannot carry");
			});
	}

	@Test
	void startup_embeddingBackendWithTwoEmbeddingModelBeans_shouldStopNamingPrimaryAsTheWayOut() {
		// Two model starters each publish an EmbeddingModel, and the ranking needs one.
		// Spring's own NoUniqueBeanDefinitionException leaves both the property and the
		// way out unnamed.
		this.contextRunner
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.search-backend=embedding")
			.withBean("openAiEmbeddingModel", EmbeddingModel.class, ZeroEmbeddingModel::new)
			.withBean("ollamaEmbeddingModel", EmbeddingModel.class, OneEmbeddingModel::new)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.dev.experimental.dynamic-operations.search-backend")
					.hasMessageContaining("publishes 2")
					.hasMessageContaining("ZeroEmbeddingModel")
					.hasMessageContaining("OneEmbeddingModel")
					.hasMessageContaining("@Primary")
					.hasMessageContaining("SchemaSearch");
			});
	}

	@Test
	void startup_embedBatchSizeBelowOne_shouldStopNamingTheProperty() {
		// The model check runs first, so a model bean is published for the batch size
		// check to be reached.
		this.contextRunner
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.search-backend=embedding",
					"gatool.dev.experimental.dynamic-operations.embed-batch-size=0")
			.withBean(EmbeddingModel.class, ZeroEmbeddingModel::new)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.dev.experimental.dynamic-operations.embed-batch-size")
					.hasMessageContaining("at least one schema field to the model in a call");
			});
	}

	@Test
	void startup_schemaFileThatIsNotValidSdl_shouldStopNamingTheFileAndTheError(@TempDir Path folder) throws Exception {
		Files.writeString(folder.resolve("broken.graphqls"), "type Query { topRatedMovies: [Movie!]! }");

		this.contextRunner
			.withPropertyValues("gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/broken.graphqls")
			.run((context) -> {
				assertThat(context).hasFailed();
				// graphql-java's SchemaProblem stays the root cause, and GATool's own
				// sentence sits one level above it, naming the resource and every error.
				assertThat(context.getStartupFailure())
					.hasStackTraceContaining("broken.graphqls] is not a valid GraphQL schema:")
					.hasStackTraceContaining("The field type 'Movie' is not present");
			});
	}

	@Test
	void startup_schemaFileThatIsMissing_shouldStopNamingThePropertyAndTheFile(@TempDir Path folder) {
		this.contextRunner
			.withPropertyValues("gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/missing.graphqls")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.schema.location")
					.hasMessageContaining("missing.graphqls")
					.hasMessageContaining("The schema file cannot be read");
			});
	}

	@Test
	void startup_schemaCacheDirectoryThatIsAFile_shouldFetchAndWarnThatTheCopyCouldNotBeWritten(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The copy lands under the directory the property names, and a regular file there
		// cannot hold it, so this startup keeps its schema and the next one fetches
		// again.
		Path file = Files.writeString(folder.resolve("occupied"), "");

		this.contextRunner
			.withPropertyValues(
					"gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl",
					"gatool.api.schema.cache-directory=" + file.toAbsolutePath())
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("GATool fetched the schema at http://")
			.contains("and could not cache it under " + file.toAbsolutePath());
	}

	@Test
	void startup_schemaHeaderNameTheJdkCannotSend_shouldStopNamingThePropertyWithoutPrintingTheValue(
			@TempDir Path folder, CapturedOutput output) {
		// A space in a header name would fail while the request is built, so startup
		// refuses it by the JDK's own rule and names the property, and the message keeps
		// the value out, because the value is a registry key.
		this.contextRunner
			.withPropertyValues(
					"gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl",
					"gatool.api.schema.cache-directory=" + folder.toAbsolutePath(),
					"gatool.api.schema.header-name=Bad Name", "gatool.api.schema.header-value=secret-registry-key")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.api.schema.header-name")
					.hasMessageContaining("a character a header name cannot carry");
			});

		assertThat(output.getAll()).doesNotContain("secret-registry-key");
	}

	@Test
	void startup_schemaHeaderValueWithASpaceInside_shouldStartAndSendIt(@TempDir Path folder) {
		// "Bearer <key>" is the shape a registry behind an Authorization header takes, so
		// a space inside the value is accepted.
		this.contextRunner
			.withPropertyValues(
					"gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl",
					"gatool.api.schema.cache-directory=" + folder.toAbsolutePath(),
					"gatool.api.schema.header-name=Authorization", "gatool.api.schema.header-value=Bearer registry-key")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	/**
	 * A second model of another class, so the two-bean refusal names two classes.
	 */
	private static final class OneEmbeddingModel implements EmbeddingModel {

		@Override
		public float[] embed(String text) {
			return new float[] { 1 };
		}

		@Override
		public float[] embed(Document document) {
			return new float[] { 1 };
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> results = new ArrayList<>();
			for (int index = 0; index < request.getInstructions().size(); index++) {
				results.add(new Embedding(new float[] { 1 }, index));
			}
			return new EmbeddingResponse(results);
		}

	}

	/**
	 * An embedding model that answers every text with a zero vector, so the batch size
	 * check is reached without a provider.
	 */
	private static final class ZeroEmbeddingModel implements EmbeddingModel {

		@Override
		public float[] embed(String text) {
			return new float[1];
		}

		@Override
		public float[] embed(Document document) {
			return new float[1];
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> results = new ArrayList<>();
			for (int index = 0; index < request.getInstructions().size(); index++) {
				results.add(new Embedding(new float[1], index));
			}
			return new EmbeddingResponse(results);
		}

		@Override
		public int dimensions() {
			return 1;
		}

	}

}
