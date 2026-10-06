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
 * The stateful server answers a client that asks for a superseded revision with the
 * newest one GATool serves, as the stateless one does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpStatefulProtocolRevisionTests {

	@LocalServerPort
	private int port;

	@Test
	void initialize_supersededRevisionOnTheStatefulServer_shouldBeAnsweredWithTheNewestServed() {
		ResponseEntity<String> response = post("/mcp", initializeBody("2025-03-26", "old"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"protocolVersion\":\"2025-11-25\"");
		assertThat(response.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
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
