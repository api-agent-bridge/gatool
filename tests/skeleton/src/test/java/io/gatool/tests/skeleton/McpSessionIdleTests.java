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

import java.time.Duration;
import java.time.Instant;

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
 * The idle timeout of stateful Streamable HTTP: a session left alone is evicted, which
 * frees its place under the cap and refuses its id afterwards.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.sessions.max-count=1", "gatool.mcp.sessions.idle-timeout=1s" })
class McpSessionIdleTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@Test
	void session_leftIdle_shouldBeEvictedAndFreeItsPlace() throws Exception {
		ResponseEntity<String> first = initialize();
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		String idleSessionId = first.getHeaders().getFirst("Mcp-Session-Id");
		assertThat(initialize().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

		// The eviction runs on the provider's own schedule, so the place frees itself
		// some time after the timeout, within a bounded wait.
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
		while (status == HttpStatus.SERVICE_UNAVAILABLE && Instant.now().isBefore(deadline)) {
			Thread.sleep(500);
			status = HttpStatus.valueOf(initialize().getStatusCode().value());
		}
		assertThat(status).isEqualTo(HttpStatus.OK);

		// The evicted id answers as an unknown session, so a client re-initializes.
		ResponseEntity<String> stale = client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.header("Mcp-Session-Id", idleSessionId)
			.body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
			.exchange((request, reply) -> new ResponseEntity<>(reply.getHeaders(), reply.getStatusCode()));
		assertThat(stale.getStatusCode().value()).isIn(400, 404);
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
