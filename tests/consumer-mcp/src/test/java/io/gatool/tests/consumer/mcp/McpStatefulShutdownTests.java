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

package io.gatool.tests.consumer.mcp;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stateful server with a client listening on {@code GET /mcp} stops without waiting for
 * the shutdown phase to time out.
 *
 * <p>
 * The listening stream is one active request. Spring Boot's graceful shutdown waits for
 * active requests before the beans are destroyed, so a transport that ends its streams
 * only when it is destroyed makes the wait run to
 * {@code spring.lifecycle.timeout-per-shutdown-phase}, 30 seconds by default, on every
 * restart with a connected client.
 */
class McpStatefulShutdownTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@Test
	@Timeout(120)
	void shutdown_withAClientListeningOnTheStream_shouldEndWellInsideTheShutdownPhase() throws Exception {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(StatefulApplication.class)
			// The keep-alive sends an event each second, which is what makes the server
			// write the headers of the listening stream, so the test can tell it is open.
			.properties("server.port=0", "spring.ai.mcp.server.protocol=STREAMABLE",
					"spring.ai.mcp.server.streamable-http.keep-alive-interval=1s",
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
					"gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:greetings.graphqls")
			.run();
		long closeNanos;
		// The client is shut down in the finally block, because close() would wait for
		// the listening stream to end, which is what a failing run leaves open.
		HttpClient client = HttpClient.newHttpClient();
		try {
			URI endpoint = URI
				.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/mcp");
			HttpResponse<String> initialized = client.send(
					post(endpoint).POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build(),
					HttpResponse.BodyHandlers.ofString());
			String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
			client.send(post(endpoint).header("Mcp-Session-Id", session)
				.header("MCP-Protocol-Version", "2025-11-25")
				.POST(HttpRequest.BodyPublishers
					.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<InputStream> stream = client
				.sendAsync(HttpRequest.newBuilder(endpoint)
					.header("Accept", "text/event-stream")
					.header("Mcp-Session-Id", session)
					.header("MCP-Protocol-Version", "2025-11-25")
					.GET()
					.build(), HttpResponse.BodyHandlers.ofInputStream())
				.get(10, TimeUnit.SECONDS);
			assertThat(stream.statusCode()).as("the listening stream is open").isEqualTo(200);

			long before = System.nanoTime();
			context.close();
			closeNanos = System.nanoTime() - before;
		}
		finally {
			client.shutdownNow();
			context.close();
		}

		assertThat(Duration.ofNanos(closeNanos)).isLessThan(Duration.ofSeconds(10));
	}

	private static HttpRequest.Builder post(URI endpoint) {
		return HttpRequest.newBuilder(endpoint)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream");
	}

	// Without a component scan, so that the other tests' applications stay out.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class StatefulApplication {

	}

}
