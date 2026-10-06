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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI's own endpoint settings reach the transport GATool builds and the filter that
 * guards it: another path, deletion refused, and an Origin list.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.streamable-http.mcp-endpoint=/agent",
				"spring.ai.mcp.server.streamable-http.disallow-delete=true",
				"spring.ai.mcp.server.streamable-http.keep-alive-interval=1s",
				"gatool.mcp.transport.allowed-origins=http://localhost:3000",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpEndpointSettingsOverHttpTests {

	@LocalServerPort
	private int port;

	@Test
	void initialize_onTheConfiguredEndpoint_shouldOpenASession() {
		ResponseEntity<String> response = post("/agent", initializeBody("2025-11-25", "test"), "MCP-Protocol-Version",
				"2025-11-25");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
	}

	@Test
	void initialize_onTheConfiguredEndpointFromAForeignOrigin_shouldBeRefusedByTheFilter() {
		ResponseEntity<String> response = post("/agent", initializeBody("2025-11-25", "test"), "MCP-Protocol-Version",
				"2025-11-25", "Origin", "http://evil.example");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).contains("\"jsonrpc\":\"2.0\"").contains("http://evil.example");
	}

	@Test
	void delete_withDeletionDisallowed_shouldAnswer405() {
		ResponseEntity<String> initialized = post("/agent", initializeBody("2025-11-25", "test"),
				"MCP-Protocol-Version", "2025-11-25");
		String sessionId = initialized.getHeaders().getFirst("Mcp-Session-Id");

		ResponseEntity<String> deletion = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.delete()
			.uri("/agent")
			.header("Mcp-Session-Id", sessionId)
			.header("MCP-Protocol-Version", "2025-11-25")
			.exchange((request, reply) -> new ResponseEntity<>(reply.getHeaders(), reply.getStatusCode()));

		assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
	}

	private ResponseEntity<String> post(String path, String body, String... headers) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.headers((all) -> {
				for (int index = 0; index + 1 < headers.length; index += 2) {
					all.set(headers[index], headers[index + 1]);
				}
			})
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private static String initializeBody(String revision, String clientName) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + revision
				+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"" + clientName + "\",\"version\":\"1\"}}}";
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
