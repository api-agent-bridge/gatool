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
 * The session cap of stateful Streamable HTTP: a client that initializes past
 * {@code gatool.mcp.sessions.max-count} is refused until a session ends.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.sessions.max-count=2" })
class McpSessionCapTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@Test
	void initialize_pastTheCap_shouldAnswer503UntilASessionEnds() {
		ResponseEntity<String> first = initialize();
		ResponseEntity<String> second = initialize();
		ResponseEntity<String> third = initialize();

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(third.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

		// Ending one session frees a place for the next client.
		String endedSessionId = first.getHeaders().getFirst("Mcp-Session-Id");
		ResponseEntity<String> delete = client().delete()
			.uri("/mcp")
			.header("Mcp-Session-Id", endedSessionId)
			.header("MCP-Protocol-Version", "2025-11-25")
			.exchange((request, reply) -> new ResponseEntity<>(reply.getHeaders(), reply.getStatusCode()));
		assertThat(delete.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(initialize().getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private ResponseEntity<String> initialize() {
		return client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.body(INITIALIZE)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
