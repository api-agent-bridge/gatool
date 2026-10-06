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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * A response far larger than the result limit is refused while it arrives.
 *
 * <p>
 * {@code gatool.results.max-characters} is checked on the finished text, so reaching it
 * takes the bytes, the parsed map and the text at once: a 200 MB response ends in
 * {@code OutOfMemoryError} on a tool whose result limit is 60,000 characters.
 * {@code gatool.api.max-response-size} stops the read before any of that is built.
 */
class ResponseSizeTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	// Each movie title is padded, so the response grows past any cap the tests set
	// while staying valid JSON for as long as it is read.
	private static final String PADDING = "x".repeat(4096);

	private static final AtomicLong SENT = new AtomicLong();

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA);

	@BeforeAll
	static void startLargeApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			byte[] head = "{\"data\":{\"topRatedMovies\":[".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			long sent = 0;
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(head);
				sent += head.length;
				// Twelve megabytes, well past every limit these tests set, including
				// the one megabyte default, so the same payload trips the cap under
				// each of them.
				for (int movie = 0; movie < 3000; movie++) {
					byte[] entry = ((movie == 0) ? "" : ",")
						.concat("{\"id\":\"" + movie + "\",\"title\":\"" + PADDING + "\",\"rating\":9.1}")
						.getBytes(StandardCharsets.UTF_8);
					body.write(entry);
					sent += entry.length;
				}
				body.write("]}}".getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException ex) {
				// GATool stopped reading, which is what the test expects.
			}
			SENT.set(sent);
		});
		server.start();
	}

	@AfterAll
	static void stopLargeApi() {
		server.stop(0);
	}

	@Test
	void call_responsePastTheLimit_shouldAnswerWithAToolErrorNamingTheProperty() {
		this.contextRunner.withPropertyValues(apiUrlProperty(), "gatool.api.max-response-size=64KB").run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

			ToolCallOutcome outcome = tool.call(Map.of());

			// This is a tool error, because the call reached the API and asking for less
			// is something the model can do.
			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains("gatool.api.max-response-size")
				.contains("64KB")
				.contains("smaller page with first");
		});
	}

	@Test
	void call_responsePastTheLimit_shouldStopReadingLongBeforeTheWholeBody() {
		this.contextRunner.withPropertyValues(apiUrlProperty(), "gatool.api.max-response-size=64KB").run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

			tool.call(Map.of());

			// The handler writes about twelve megabytes. Whatever the socket buffers
			// hold, the refusal arrives while the body is still being written, so the
			// process does not hold the whole response.
			assertThat(SENT.get()).isGreaterThan(1_000_000L);
		});
	}

	@Test
	void callTool_responseAbove1MbUnderTheDefaults_shouldBeRefusedNamingTheProperty() {
		// The default is one megabyte, which is the safe point of a 512 MiB container.
		// This call leaves every size property unset, so the refusal names the default
		// the application runs under.
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

			ToolCallOutcome outcome = tool.call(Map.of());

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains("gatool.api.max-response-size").contains("1024KB");
		});
	}

	@Test
	void call_responseUnderTheLimit_shouldReachTheModelAsUsual() {
		this.contextRunner
			.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl(), "gatool.api.max-response-size=1MB")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

				ToolCallOutcome outcome = tool.call(Map.of());

				assertThat(outcome.isError()).isFalse();
				assertThat(outcome.text()).contains("topRatedMovies");
			});
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

}
