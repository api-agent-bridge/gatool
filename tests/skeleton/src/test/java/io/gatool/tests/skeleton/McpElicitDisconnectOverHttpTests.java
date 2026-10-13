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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that goes away while a tool marked elicit waits for the user, over stateful
 * Streamable HTTP under a three-second request timeout: the wait runs on to the request
 * timeout, the call ends as {@code elicitation-timeout}, GATool logs the timeout at WARN,
 * and the API stays uncalled.
 *
 * <p>
 * Spring AI's WebMvc transport runs the tool call inside the SSE response of the
 * {@code tools/call} POST, on the request thread, so the handler holds that thread while
 * it waits. MCP Java SDK 2.0.0 keeps the question in the stream of that POST, and it
 * fails a waiting question with {@code RuntimeException("Stream closed")} only where it
 * closes such a stream itself: {@code McpStreamableServerSession.close()} and
 * {@code closeGracefully()} close the session's listening stream alone. A client that
 * closes its session, which sends {@code DELETE}, and a connection that drops both leave
 * the question waiting, and the request timeout ends the wait. The timeout answer then
 * goes to the closed connection, which Tomcat reports at DEBUG once it writes the answer.
 * From the disconnect to that answer, the one line at WARN or above is GATool's timeout
 * line, which names the tool and the request timeout, so it is the operator's one trace
 * of the disconnect.
 *
 * <p>
 * An accept posted on the session after the connection dropped still reaches the waiting
 * question and runs the operation, and its result goes to the closed connection.
 */
@SpringBootTest(classes = McpElicitDisconnectOverHttpTests.RecordingApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.request-timeout=3s",
				// Spring AI's transport logs the end of each POST's stream at DEBUG, so
				// the tests know when the server finished writing the timeout answer.
				// The level goes back after the class (restoreTheTransportLevel).
				"logging.level." + McpElicitDisconnectOverHttpTests.TRANSPORT_LOGGER + "=DEBUG" })
@ExtendWith(OutputCaptureExtension.class)
class McpElicitDisconnectOverHttpTests {

	static final String TRANSPORT_LOGGER = "org.springframework.ai.mcp.server.webmvc.transport."
			+ "WebMvcStreamableServerTransportProvider";

	private static @Nullable LogLevel transportLevel;

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

	// How late the ending may come after the request timeout on a loaded runner.
	private static final Duration MARGIN = Duration.ofSeconds(5);

	private static final String STREAM_COMPLETED = "Request response stream completed for session";

	private static final String TIMED_OUT = "The user did not answer the confirmation for topRatedMovies within "
			+ "3 seconds (spring.ai.mcp.server.request-timeout), so the API was not called, and the call held its "
			+ "thread for that long.";

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"elicitation\":{\"form\":{}}},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	private static final String TOOLS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"topRatedMovies\",\"arguments\":{\"first\":1}}}";

	// The first id of the event is the id of the elicitation/create request.
	private static final Pattern REQUEST_ID = Pattern.compile("\"id\":\"([^\"]+)\"");

	// A line of Spring Boot's console log at WARN or ERROR.
	private static final Pattern WARN_OR_ERROR = Pattern.compile("\\S+\\s+(WARN|ERROR)\\s");

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void apiUrl(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@BeforeAll
	static void readTheTransportLevel() {
		LoggerConfiguration configuration = loggingSystem().getLoggerConfiguration(TRANSPORT_LOGGER);
		transportLevel = (configuration != null) ? configuration.getConfiguredLevel() : null;
	}

	// The context sets the DEBUG level on the logging system the whole JVM shares. A
	// context Spring caches and resumes later keeps that level, because Spring Boot
	// resets levels only where a new context initializes the logging system.
	@AfterAll
	static void restoreTheTransportLevel() {
		loggingSystem().setLogLevel(TRANSPORT_LOGGER, transportLevel);
	}

	private static LoggingSystem loggingSystem() {
		return LoggingSystem.get(McpElicitDisconnectOverHttpTests.class.getClassLoader());
	}

	@Test
	void callTool_clientClosesItsSessionWhileTheUserDecides_shouldEndAtTheRequestTimeoutAndLeaveTheApiUncalled(
			CapturedOutput output) throws Exception {
		RecordingApplication.RECORDED.clear();
		int before = MoviesApiServer.graphQlCalls();
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).build())
			.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
			.elicitation((request) -> {
				// The user is still deciding when the client closes.
				asked.countDown();
				try {
					released.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of(), null);
			})
			.build();
		try {
			client.initialize();
			long started = System.nanoTime();
			CompletableFuture<Void> call = CompletableFuture.runAsync(() -> {
				try {
					client.callTool(
							McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());
				}
				catch (RuntimeException ex) {
					// The client ends its own call when it closes.
				}
			});
			assertThat(asked.await(10, TimeUnit.SECONDS)).as("the server asked the user").isTrue();
			Thread.sleep(500);
			int mark = output.toString().length();

			// Sends DELETE for the session, then drops the connection of the POST.
			client.closeGracefully();

			long disconnected = System.nanoTime();
			assertEndedAtTheRequestTimeout(output, mark, started, disconnected);
			assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
			call.get(10, TimeUnit.SECONDS);
		}
		finally {
			released.countDown();
			client.close();
		}
	}

	@Test
	void callTool_connectionDropsWhileTheUserDecides_shouldEndAtTheRequestTimeoutAndLeaveTheApiUncalled(
			CapturedOutput output) throws Exception {
		RecordingApplication.RECORDED.clear();
		int before = MoviesApiServer.graphQlCalls();
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		try {
			String session = openSession(client);
			long started = System.nanoTime();
			HttpResponse<InputStream> call = client.send(request(TOOLS_CALL, session),
					HttpResponse.BodyHandlers.ofInputStream());
			readTheQuestion(call);
			Thread.sleep(500);
			int mark = output.toString().length();

			// The connection closes without a DELETE, as it does when a client
			// process ends or the network fails.
			call.body().close();
			client.shutdownNow();

			long disconnected = System.nanoTime();
			assertEndedAtTheRequestTimeout(output, mark, started, disconnected);
			assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
		}
		finally {
			client.shutdownNow();
		}
	}

	@Test
	void callTool_acceptPostedAfterTheConnectionDropped_shouldRunTheOperation() throws Exception {
		RecordingApplication.RECORDED.clear();
		int before = MoviesApiServer.graphQlCalls();
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		try {
			String session = openSession(client);
			HttpResponse<InputStream> call = client.send(request(TOOLS_CALL, session),
					HttpResponse.BodyHandlers.ofInputStream());
			String questionId = readTheQuestion(call);
			call.body().close();

			HttpResponse<String> answered = client.send(
					request("{\"jsonrpc\":\"2.0\",\"id\":\"" + questionId
							+ "\",\"result\":{\"action\":\"accept\",\"content\":{}}}", session),
					HttpResponse.BodyHandlers.ofString());

			assertThat(answered.statusCode()).isEqualTo(202);
			assertThat(RecordingApplication.awaitToolCall().getLowCardinalityKeyValues())
				.contains(KeyValue.of("gatool.call.outcome", "success"));
			assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before + 1);
		}
		finally {
			client.shutdownNow();
		}
	}

	// The ending is read from the gatool.call observation and from the WARN line GATool
	// writes for a timeout. Both bounds are measured from before the POST, which comes
	// at or before the moment the server starts its timer for the question. A disconnect
	// inside the first bound therefore comes before the timer can fire, and an ending at
	// or past the second bound came from the timer, so the disconnect did not end the
	// wait. A bound measured from the question's arrival at the client would let a slow
	// delivery of the question place the disconnect after the timer fired.
	private static void assertEndedAtTheRequestTimeout(CapturedOutput output, int mark, long started, long disconnected)
			throws InterruptedException {
		assertThat(Duration.ofNanos(disconnected - started)).as("the client went away inside the request timeout")
			.isLessThan(REQUEST_TIMEOUT);
		Observation.Context toolCall = RecordingApplication.awaitToolCall();
		Duration ended = Duration.ofNanos(RecordingApplication.stoppedAt - started);
		assertThat(toolCall.getLowCardinalityKeyValues())
			.contains(KeyValue.of("gatool.call.outcome", "elicitation-timeout"));
		assertThat(ended).isGreaterThanOrEqualTo(REQUEST_TIMEOUT).isLessThan(REQUEST_TIMEOUT.plus(MARGIN));
		awaitLine(output, mark, STREAM_COMPLETED);
		String afterTheDisconnect = output.toString().substring(mark);
		assertThat(afterTheDisconnect).doesNotContain("The confirmation for topRatedMovies failed");
		assertThat(afterTheDisconnect.lines().filter((line) -> WARN_OR_ERROR.matcher(line).lookingAt()))
			.as("lines at WARN or ERROR after the disconnect")
			.singleElement(InstanceOfAssertFactories.STRING)
			.contains(" WARN ")
			.endsWith(TIMED_OUT);
	}

	private static void awaitLine(CapturedOutput output, int mark, String line) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!output.toString().substring(mark).contains(line) && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(output.toString().substring(mark)).contains(line);
	}

	private String openSession(HttpClient client) throws IOException, InterruptedException {
		HttpResponse<String> opened = client.send(request(INITIALIZE, null), HttpResponse.BodyHandlers.ofString());
		String session = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();
		HttpResponse<String> initialized = client.send(request(INITIALIZED, session),
				HttpResponse.BodyHandlers.ofString());
		assertThat(initialized.statusCode()).isEqualTo(202);
		return session;
	}

	// Reads the SSE stream of the call until the elicitation/create event arrives and
	// returns the id of that request.
	private static String readTheQuestion(HttpResponse<InputStream> call) throws Exception {
		BufferedReader reader = new BufferedReader(new InputStreamReader(call.body(), StandardCharsets.UTF_8));
		String event = CompletableFuture.supplyAsync(() -> {
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("data:") && line.contains("\"elicitation/create\"")) {
						return line;
					}
				}
				return "the stream ended before the question";
			}
			catch (IOException ex) {
				return "the stream failed: " + ex;
			}
		}).get(10, TimeUnit.SECONDS);
		Matcher id = REQUEST_ID.matcher(event);
		assertThat(id.find()).as(event).isTrue();
		return id.group(1);
	}

	private HttpRequest request(String body, @Nullable String sessionId) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (sessionId != null) {
			request.header("Mcp-Session-Id", sessionId);
		}
		return request.build();
	}

	@SpringBootApplication
	static class RecordingApplication {

		// The outbound GraphQL request is observed on the same registry, so the tool
		// call is picked out by name.
		static final List<Observation.Context> RECORDED = new CopyOnWriteArrayList<>();

		static volatile long stoppedAt;

		static Observation.Context awaitToolCall() throws InterruptedException {
			long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
			while (System.nanoTime() < deadline) {
				for (Observation.Context context : RECORDED) {
					if ("gatool.call".equals(context.getName())) {
						return context;
					}
				}
				Thread.sleep(20);
			}
			throw new AssertionError("The gatool.call observation did not stop within 15 seconds.");
		}

		@Bean
		ObservationRegistry observationRegistry() {
			ObservationRegistry registry = ObservationRegistry.create();
			registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

				@Override
				public boolean supportsContext(Observation.Context context) {
					return true;
				}

				@Override
				public void onStop(Observation.Context context) {
					if ("gatool.call".equals(context.getName())) {
						stoppedAt = System.nanoTime();
					}
					RECORDED.add(context);
				}
			});
			return registry;
		}

	}

}
