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
 * What a method this server does not serve answers over HTTP.
 *
 * <p>
 * JSON-RPC 2.0 section 5 requires a Response object for every call, and reserves
 * {@code -32601} for a method that does not exist. This server serves tools, so it
 * advertises tools alone, and MCP Java SDK 2.0.0 then leaves resources, prompts and
 * completions without a handler. Its stateless handler raises an error for such a method
 * before the step that turns an error into a response, so left to the SDK the answer is
 * HTTP 500 carrying a serialized Java exception: no {@code jsonrpc}, no {@code id} to
 * match the request with, and a stack trace of internal class, file and line names on the
 * wire.
 *
 * <p>
 * A client that probes {@code resources/list} and {@code prompts/list} whatever the
 * capabilities say meets this, and the official MCP conformance suite is one such client.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpUnknownMethodTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int METHOD_NOT_FOUND = -32601;

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void resourcesList_onAServerThatServesToolsAlone_shouldAnswerMethodNotFound() throws Exception {
		HttpResponse<String> response = post("resources/list", 11);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode message = JSON.readTree(response.body());
		assertThat(message.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(message.path("id").asInt()).isEqualTo(11);
		assertThat(message.path("error").path("code").asInt()).isEqualTo(METHOD_NOT_FOUND);
	}

	@Test
	void promptsList_onAServerThatServesToolsAlone_shouldAnswerMethodNotFound() throws Exception {
		HttpResponse<String> response = post("prompts/list", 12);

		assertThat(JSON.readTree(response.body()).path("error").path("code").asInt()).isEqualTo(METHOD_NOT_FOUND);
	}

	@Test
	void request_aMethodNoMcpRevisionDefines_shouldAnswerMethodNotFound() throws Exception {
		// The SDK does not register a handler for this one either, and JSON-RPC reserves
		// the same code for it.
		HttpResponse<String> response = post("gatool/invented", 13);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("error").path("code").asInt()).isEqualTo(METHOD_NOT_FOUND);
	}

	@Test
	void request_anUnknownMethod_shouldKeepThisServersInternalsOffTheWire() throws Exception {
		// A serialized Throwable carries the class names, the file names and the line
		// numbers of every frame below the call.
		String body = post("resources/list", 14).body();

		assertThat(body).doesNotContain("stackTrace").doesNotContain("java").doesNotContain("io.gatool");
	}

	@Test
	void toolsList_theMethodThisServerDoesServe_shouldStillAnswerItsResult() throws Exception {
		HttpResponse<String> response = post("tools/list", 15);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("result").path("tools")).isNotEmpty();
	}

	private HttpResponse<String> post(String method, int id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{}}";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
