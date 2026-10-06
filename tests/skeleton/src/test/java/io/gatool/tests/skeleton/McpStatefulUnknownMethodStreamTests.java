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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins an inherited defect of MCP Java SDK 2.0.0 on the stateful transport: a request for
 * a method the server does not serve gets its {@code -32601} as an SSE event, and the
 * response stream then stays open.
 *
 * <p>
 * {@code McpStreamableServerSession.responseStream} closes the transport after a handler
 * it found and returns after the method-not-found event without closing, and Spring AI's
 * WebMvc provider opens the stream without an async timeout. The Streamable HTTP
 * transport says the server SHOULD terminate the stream after the response. GATool
 * documents this as an inherited deviation and waits for the SDK, so this test asserts
 * today's behaviour with a bounded read. The day the SDK closes the stream, the second
 * assertion fails, which is the signal to drop the deviation from the README and this
 * test with it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpStatefulUnknownMethodStreamTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	private static final String RESOURCES_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\","
			+ "\"params\":{}}";

	// How long the stream has to stay open after the event for the defect to count
	// as present. The SDK sends the event and leaves; a fixed SDK closes within
	// milliseconds of it.
	private static final Duration STILL_OPEN_FOR = Duration.ofMillis(1500);

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void resourcesList_onAServerWithoutTheResourcesCapability_shouldSendMethodNotFoundAndLeaveTheStreamOpen()
			throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		try {
			HttpResponse<String> opened = client.send(request(INITIALIZE, null), HttpResponse.BodyHandlers.ofString());
			String session = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();

			HttpResponse<InputStream> response = client.send(request(RESOURCES_LIST, session),
					HttpResponse.BodyHandlers.ofInputStream());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
			BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
			String event = CompletableFuture.supplyAsync(() -> readUntilTheError(reader)).get(10, TimeUnit.SECONDS);
			assertThat(event).contains("\"id\":2").contains("-32601");

			// The bounded part: a read that returns within the wait means the server
			// ended the stream, which is what the specification asks for and what the
			// SDK does not do yet.
			CompletableFuture<Integer> nextByte = CompletableFuture.supplyAsync(() -> {
				try {
					return reader.read();
				}
				catch (IOException ex) {
					return -2;
				}
			});
			Thread.sleep(STILL_OPEN_FOR.toMillis());
			assertThat(nextByte.isDone())
				.as("the stream ended within " + STILL_OPEN_FOR.toMillis() + " ms of the -32601 event, so MCP "
						+ "Java SDK now closes it: drop the inherited deviation from the README and this test")
				.isFalse();
			// The stream is closed and the reader is left alone. BufferedReader closes
			// under the lock its read holds, and the read above waits on a stream the
			// server keeps open, so reader.close() would wait for that lock without end.
			// Closing the stream ends the read, which the last assertion waits for.
			response.body().close();
			assertThat(nextByte.get(5, TimeUnit.SECONDS)).isNegative();
		}
		finally {
			client.shutdownNow();
		}
	}

	// Reads SSE lines until the data line carrying the JSON-RPC error arrives, and then
	// the empty line that ends that event, so the next read waits for what the server
	// sends after the event.
	private static String readUntilTheError(BufferedReader reader) {
		try {
			String line;
			String event = null;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("data:") && line.contains("\"error\"")) {
					event = line;
				}
				else if (event != null && line.isEmpty()) {
					return event;
				}
			}
			return "the stream ended without the error event";
		}
		catch (IOException ex) {
			return "the stream failed: " + ex;
		}
	}

	private HttpRequest request(String body, String sessionId) {
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
	static class SkeletonApplication {

	}

}
