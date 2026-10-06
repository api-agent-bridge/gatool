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

package io.gatool.boot.internal.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.internal.credentials.ForwardedTokenCredentialStrategy;
import io.gatool.boot.internal.execution.ToolCallRunner.PartialResults;
import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner behind the remote executor, against the JDK's own HTTP server, so each
 * assertion reads what the model would read for an answer that left the process.
 * {@link ToolCallRunnerTests} covers the runner over a fake executor, and
 * {@link RemoteGraphQlExecutorTests} the executor alone.
 */
class ToolCallOverHttpTests {

	private static final String DOCUMENT = "query TopRatedMovies { topRatedMovies { id } }";

	private static final String PARTIAL_RESULT = "{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\"}]},"
			+ "\"errors\":[{\"message\":\"rating unavailable\"}]}";

	private final AtomicReference<Integer> status = new AtomicReference<>(200);

	private final AtomicReference<String> contentType = new AtomicReference<>("application/json");

	private final AtomicReference<String> body = new AtomicReference<>("{\"data\":{\"topRatedMovies\":[]}}");

	private HttpServer server;

	@BeforeEach
	void startApi() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/graphql", this::answer);
		this.server.start();
	}

	@AfterEach
	void stopApi() {
		this.server.stop(0);
		SecurityContextHolder.clearContext();
	}

	@Test
	void run_apiAnswering294WithAPartialResult_shouldReturnAnErrorCarryingTheDataAndTheErrors() {
		this.status.set(294);
		this.contentType.set("application/graphql-response+json");
		this.body.set(PARTIAL_RESULT);

		ToolCallOutcome outcome = runner(null, false).run(operationWithOutputSchema(), Map.of());

		// The partial result rules apply as they do to a 200: an error by default, with
		// the data in the text and the envelope as structured content.
		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("\"id\":\"m1\"")
			.contains("rating unavailable")
			.doesNotContain("answered 294");
		assertThat(outcome.structuredContent()).isNotNull().containsKeys("data", "errors");
	}

	@Test
	void run_apiAnswering294WithTheSwitchOn_shouldReturnTheDataWithoutAnError() {
		this.status.set(294);
		this.contentType.set("application/graphql-response+json");
		this.body.set(PARTIAL_RESULT);

		ToolCallOutcome outcome = runner(null, true).run(operationWithOutputSchema(), Map.of());

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).contains("rating unavailable");
	}

	@Test
	void run_apiAnswering200WithAJsonObjectWithoutDataOrErrors_shouldSayTheBodyIsNotAGraphQlResponse() {
		this.body.set("{\"message\":\"ok\"}");

		ToolCallOutcome outcome = runner(null, false).run(operation(), Map.of());

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("answered 200 with a body that is not a GraphQL response")
			.contains("lacks both data and errors")
			.contains("different arguments will not help")
			.doesNotContain("{\"message\":\"ok\"}");
	}

	@Test
	void run_forwardedTokenWithoutACaller_shouldNameTheSwitchAndLeaveTheRetrySentenceOut() {
		ToolCallRunner runner = runner(
				new ForwardedTokenCredentialStrategy(SecurityContextHolder.getContextHolderStrategy()), false);

		ToolCallOutcome outcome = runner.run(operation(), Map.of());

		// The call stopped before the API, so the transport sentence, which invites a
		// retry, is the wrong one. The credential is the operator's to fix.
		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.CREDENTIAL_UNAVAILABLE);
		assertThat(outcome.text()).contains("topRatedMovies")
			.contains("gatool.api.credentials.unsafe.forward-client-tokens")
			.contains("without the caller's token")
			.contains("The arguments are not the cause")
			.doesNotContain("a later call may succeed")
			.doesNotContain("could not reach");
	}

	@Test
	void run_staticHeaderValueEndingInALineBreak_shouldKeepTheValueOutOfTheWarnLine() {
		// The JDK refuses the header while it builds the request and names the whole
		// value in its message. Startup refuses such a value, and the runner still
		// redacts, because a strategy bean of the application's own sends what it likes.
		ListAppender<ILoggingEvent> warnings = watchTheRunner();
		ToolCallRunner runner = ToolCallRunner
			.builder(
					new RemoteGraphQlExecutor(RestClient.builder(), url(),
							new StaticHeaderCredentialStrategy("X-API-Key", "secret-key-value\n")),
					new GraphQlResultWriter(JsonMapper.builder().build()))
			.maxCharacters(60000)
			.redaction((text) -> text.replace("secret-key-value\n", "[redacted]"))
			.build();

		try {
			runner.run(operation(), Map.of());
		}
		catch (RuntimeException ex) {
			// The call fails either way; the line it leaves behind is what is asserted.
		}

		assertThat(warnings.list).isNotEmpty()
			.allSatisfy((event) -> assertThat(event.getFormattedMessage()).doesNotContain("secret-key-value"));
	}

	private static ListAppender<ILoggingEvent> watchTheRunner() {
		Logger logger = (Logger) LoggerFactory.getLogger(ToolCallRunner.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.WARN);
		logger.addAppender(appender);
		return appender;
	}

	private ToolCallRunner runner(@Nullable ClientHttpRequestInterceptor credential, boolean partialResultsAsSuccess) {
		return ToolCallRunner
			.builder(new RemoteGraphQlExecutor(RestClient.builder(), url(), credential),
					new GraphQlResultWriter(JsonMapper.builder().build()))
			.maxCharacters(60000)
			.partialResults(partialResultsAsSuccess ? PartialResults.AS_SUCCESS : PartialResults.AS_ERROR)
			.build();
	}

	private void answer(HttpExchange exchange) throws IOException {
		byte[] bytes = this.body.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", this.contentType.get());
		exchange.sendResponseHeaders(this.status.get(), (bytes.length != 0) ? bytes.length : -1);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/graphql";
	}

	private static ToolOperation operation() {
		return operation(null);
	}

	private static ToolOperation operationWithOutputSchema() {
		return operation("{\"type\":\"object\"}");
	}

	private static ToolOperation operation(@Nullable String outputSchema) {
		return ToolOperation.builder()
			.toolName("topRatedMovies")
			.operationName("TopRatedMovies")
			.operationType(OperationType.QUERY)
			.description("Returns the highest-rated movies, best first.")
			.printedDocument(DOCUMENT)
			.inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
			.location("file:/gatool/mcp/TopRatedMovies.graphql")
			.toolExposureTypes(Set.of(ToolExposureType.MCP))
			.outputSchema(outputSchema)
			.build();
	}

}
