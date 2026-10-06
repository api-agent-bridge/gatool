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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.OperationFileProblemsException;
import io.gatool.core.model.GATool;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In registry mode the schema is fetched at every startup, and the copy on disk is what
 * lets the application start while the registry is down. The fetched text reaches that
 * copy after every operation file validated against it. When the API team removes a field
 * one operation selects, startup stops on that file, as documented, and the copy keeps
 * the last schema that validated, so the fallback on the day the registry is down starts
 * every tool on it.
 *
 * <p>
 * The registry stub serves the movies schema, then the same schema without
 * {@code Movie.rating}, then a 503. Four operation files stand in for four tools; one of
 * them selects {@code rating}.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaUrlDriftTests {

	private static final String WITH_RATING = MoviesSchema.sdl();

	// The API team removed Movie.rating, with the description above it.
	private static final String WITHOUT_RATING = WITH_RATING.replace(
			"  \"The catalog's average rating, from 0.0 to 10.0. Null when the catalog has not rated the movie.\"\n"
					+ "  rating: Float\n",
			"");

	private static final AtomicReference<String> SERVED = new AtomicReference<>(WITH_RATING);

	private static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);

	private static HttpServer registry;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	@BeforeAll
	static void startRegistry() throws IOException {
		registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		registry.createContext("/sdl", (exchange) -> {
			int status = STATUS.get();
			if (status != 200) {
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
				return;
			}
			byte[] body = SERVED.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.getResponseHeaders().add("ETag", "\"" + Integer.toHexString(SERVED.get().hashCode()) + "\"");
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
	void startup_registryServingASchemaOneOperationFailsAgainst_shouldKeepTheLastCopyThatValidated(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		assertThat(WITHOUT_RATING).doesNotContain("  rating: Float\n");
		Path operations = folder.resolve("ops");
		Path cache = folder.resolve("cache");
		Files.createDirectories(operations);
		Files.writeString(operations.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies($first: Int = 10) { topRatedMovies(first: $first) { id title rating } }
				""");
		Files.writeString(operations.resolve("MovieTitles.graphql"), """
				# Returns the titles of the highest-rated movies.
				query MovieTitles($first: Int = 10) { topRatedMovies(first: $first) { id title } }
				""");
		Files.writeString(operations.resolve("MovieYears.graphql"), """
				# Returns the release years of the highest-rated movies.
				query MovieYears { topRatedMovies { id releaseYear } }
				""");
		Files.writeString(operations.resolve("MovieById.graphql"), """
				# Looks up one movie by id.
				query MovieById($id: ID!) { movie(by: { id: $id }) { id title } }
				""");
		String[] properties = {
				"gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl",
				"gatool.api.schema.cache-directory=" + cache.toAbsolutePath(),
				"gatool.mcp.operations.locations=file:" + operations.toAbsolutePath() + "/" };

		// Day 0: the registry serves the schema every file validates against.
		SERVED.set(WITH_RATING);
		STATUS.set(200);
		this.contextRunner.withPropertyValues(properties).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(toolNames(context.getBean(GAToolCatalog.class))).hasSize(4);
		});
		assertThat(cachedSchema(cache)).contains("  rating: Float\n");

		// Day 1: the API team removed Movie.rating. Startup stops on the one file that
		// selects it, which is the documented contract, and the copy on disk is the
		// schema that validated the day before.
		SERVED.set(WITHOUT_RATING);
		this.contextRunner.withPropertyValues(properties).run((context) -> {
			assertThat(context).hasFailed();
			String message = String.valueOf(context.getStartupFailure().getMessage());
			assertThat(message).contains("GATool found these problems in the operation files:")
				.contains("TopRatedMovies.graphql")
				.contains("Field 'rating' in type 'Movie' is undefined");
			assertThat(problemsOf(context.getStartupFailure())).hasSize(1);
		});
		assertThat(cachedSchema(cache)).as("the copy on disk after a fetch that failed validation")
			.contains("  rating: Float\n");

		// Day 1, later: the registry is down. The fallback starts on the copy, which is
		// the last schema that validated, so every tool comes up.
		STATUS.set(503);
		this.contextRunner.withPropertyValues(properties).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(toolNames(context.getBean(GAToolCatalog.class))).hasSize(4);
		});
		assertThat(output).contains("so it starts on the copy cached at");
	}

	private static List<String> toolNames(GAToolCatalog catalog) {
		return catalog.mcpTools().stream().map(GATool::name).toList();
	}

	private static List<String> problemsOf(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof OperationFileProblemsException problems) {
				return problems.problems();
			}
		}
		throw new AssertionError("no OperationFileProblemsException in " + failure);
	}

	private static String cachedSchema(Path cache) throws IOException {
		try (Stream<Path> files = Files.walk(cache)) {
			Path sdl = files.filter((file) -> file.getFileName().toString().endsWith(".graphqls"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no cached schema under " + cache));
			return Files.readString(sdl, StandardCharsets.UTF_8);
		}
	}

}
