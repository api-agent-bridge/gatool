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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.internal.schema.SchemaUrlReader;
import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Two cases where a credential could reach the model or the log.
 *
 * <p>
 * The first case asks whether the runner's redaction reaches a normal GraphQL response.
 * The constructor's Javadoc says the function blanks the configured credential "in any
 * text the API answered with, before that text reaches the model or the log", and an
 * {@code errors[].message} that echoes the key is such a text.
 *
 * <p>
 * The second case asks whether a password in the userinfo of the schema URL stays out of
 * the message and the log when the registry cannot be reached. The README says startup
 * names the URL without its userinfo and query.
 */
class CredentialLeakageTests {

	private static final String DOCUMENT = "query TopRatedMovies { topRatedMovies { id } }";

	private static final String API_KEY = "super-secret-key-value";

	// An API or a proxy that echoes the request into its GraphQL error message, which
	// is the case the redaction exists for.
	private static final String ECHOING_ERRORS = "{\"data\":null,\"errors\":[{\"message\":" + "\"the header X-API-Key: "
			+ API_KEY + " is not accepted here\"}]}";

	private static final String SDL = "type Query { films: [String!]! }\n";

	private static final String CACHE_MARKER = "# GATool schema cache";

	private HttpServer api;

	@BeforeEach
	void startApi() throws IOException {
		this.api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.api.createContext("/graphql", CredentialLeakageTests::answerWithEchoingErrors);
		this.api.start();
	}

	@AfterEach
	void stopApi() {
		this.api.stop(0);
	}

	@Test
	void run_graphQlErrorMessageEchoingTheStaticKey_shouldKeepTheKeyOutOfTheToolText() {
		// The redaction the auto-configuration builds for a configured static header
		// value, which blanks that value in the text.
		ToolCallRunner runner = ToolCallRunner
			.builder(
					new RemoteGraphQlExecutor(RestClient.builder(), url(),
							new StaticHeaderCredentialStrategy("X-API-Key", API_KEY)),
					new GraphQlResultWriter(JsonMapper.builder().build()))
			.maxCharacters(60000)
			.redaction((text) -> text.replace(API_KEY, "[redacted]"))
			.build();

		ToolCallOutcome outcome = runner.run(operation(), Map.of());

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).doesNotContain(API_KEY);
	}

	@Test
	void read_registryUnreachableAtAUrlCarryingAPassword_shouldKeepThePasswordOutOfTheMessageAndTheLog(
			@TempDir Path cacheDirectory) throws IOException {
		String url = "http://user:hunter2@127.0.0.1:" + closedPort() + "/schema";
		ListAppender<ILoggingEvent> warnings = watchTheReader();

		// Nothing is cached, so the fetch failure stops startup and the message it
		// carries is what an operator and a log both read.
		Throwable failure = catchThrowable(
				() -> new SchemaUrlReader(RestClient.builder().build(), DataSize.ofMegabytes(10), null).read(url, null,
						null));

		// With a copy on disk the application starts on it, and the WARN line names the
		// reason the fetch failed.
		Files.writeString(cacheDirectory.resolve(hash(url) + ".graphqls"), CACHE_MARKER + "\n" + SDL,
				StandardCharsets.UTF_8);
		String text = new SchemaUrlReader(RestClient.builder().build(), DataSize.ofMegabytes(10), cacheDirectory)
			.read(url, null, null)
			.text();

		// Both halves are asserted softly, so one output shows every place the password
		// reached.
		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(failure).isInstanceOf(SchemaUrlReader.SchemaFetchException.class);
			softly.assertThat(String.valueOf(failure.getMessage())).doesNotContain("hunter2");
			softly.assertThat(text).isEqualTo(SDL);
			softly.assertThat(warnings.list).isNotEmpty();
			warnings.list.forEach((event) -> softly.assertThat(event.getFormattedMessage()).doesNotContain("hunter2"));
		});
	}

	private static ListAppender<ILoggingEvent> watchTheReader() {
		Logger logger = (Logger) LoggerFactory.getLogger(SchemaUrlReader.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.WARN);
		logger.addAppender(appender);
		return appender;
	}

	// The reader names its cache file by a hash of the URL, so the copy is written
	// under the name the reader looks for.
	private static String hash(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JDK ships SHA-256", ex);
		}
	}

	// A port the operating system handed out and took back, so a connection to it is
	// refused the way an unreachable registry refuses one. The bind names 127.0.0.1,
	// the address the URL calls: macOS gives a bind of every address a port that
	// another program holds on 127.0.0.1, and that program would answer the call.
	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private static void answerWithEchoingErrors(HttpExchange exchange) throws IOException {
		byte[] bytes = ECHOING_ERRORS.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private String url() {
		return "http://127.0.0.1:" + this.api.getAddress().getPort() + "/graphql";
	}

	private static ToolOperation operation() {
		return ToolOperation.builder()
			.toolName("topRatedMovies")
			.operationName("TopRatedMovies")
			.operationType(OperationType.QUERY)
			.description("Returns the highest-rated movies, best first.")
			.printedDocument(DOCUMENT)
			.inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
			.location("file:/gatool/mcp/TopRatedMovies.graphql")
			.toolExposureTypes(Set.of(ToolExposureType.MCP))
			.build();
	}

}
