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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fragment kept in a file of its own, under a {@code fragments/} subfolder of a
 * location, reaches the API inside the document of the operation that spreads it.
 */
class SharedFragmentsTests {

	private static final AtomicReference<String> REQUEST = new AtomicReference<>("");

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	@BeforeAll
	static void startApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"data\":{\"topRatedMovies\":[{\"id\":\"1\",\"title\":\"Heat\"}]}}"
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopApi() {
		server.stop(0);
	}

	@Test
	void call_operationSpreadingASharedFragment_shouldSendTheAssembledDocument(@TempDir Path folder) throws Exception {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""");
		Files.writeString(Files.createDirectories(folder.resolve("fragments")).resolve("MovieCard.graphql"),
				"fragment MovieCard on Movie { id title }");

		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql",
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				ToolCallOutcome outcome = tool.call(Map.of());

				assertThat(outcome.isError()).isFalse();
				assertThat(outcome.text()).contains("Heat");
				// The API receives one document, so the fragment travels inside it.
				assertThat(REQUEST.get()).contains("...MovieCard", "fragment MovieCard on Movie");
			});
	}

}
