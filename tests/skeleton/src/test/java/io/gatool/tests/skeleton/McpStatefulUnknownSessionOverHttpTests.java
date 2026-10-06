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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stateful transport's answers on the unsafe path, where the SDK's own session lookup
 * answers instead of the binding filter of the secured path.
 *
 * <p>
 * A session the server does not serve reads 404 on both paths, and a client reads one
 * code and the id of the call it sent on both: -32001, which is what the MCP SDKs use for
 * a session not found, where the SDK's transport alone answers -32603. A
 * {@code tools/call} whose arguments are not an object is answered here as well, ahead of
 * the SDK's SSE consumer, which such a call escapes with two stack traces in the log.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpStatefulUnknownSessionOverHttpTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void post_sessionIdTheServerNeverIssued_shouldAnswer404WithSessionNotFoundAndTheCallsId() throws Exception {
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
				"never-issued");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(error.path("id").asInt()).isEqualTo(2);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32001);
		assertThat(response.body()).doesNotContain("stackTrace");
	}

	@Test
	void post_toolsCallWithArgumentsThatAreNotAnObject_shouldAnswer200WithInvalidParams() throws Exception {
		String session = post(INITIALIZE, null).headers().firstValue("Mcp-Session-Id").orElseThrow();

		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"topRatedMovies\",\"arguments\":[1]}}", session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("id").asInt()).isEqualTo(6);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
	}

	private HttpResponse<String> post(String body, String sessionId) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (sessionId != null) {
			request.header("Mcp-Session-Id", sessionId);
		}
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
