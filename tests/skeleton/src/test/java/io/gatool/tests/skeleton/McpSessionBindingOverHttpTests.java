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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stateful session is bound to the caller that opened it, the way MCP's security best
 * practices recommend: another caller holding a valid token and the session id reads 404
 * and starts a session of its own, whichever method the request uses, and so does a
 * caller presenting an id this server did not issue.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class McpSessionBindingOverHttpTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void session_openedByOneCallerAndUsedByAnother_shouldAnswer404AndKeepServingTheOwner() {
		String ana = LocalIssuer.get().tokenFor("ana", "gatool-skeleton", List.of("mcp:tools"));
		String ben = LocalIssuer.get().tokenFor("ben", "gatool-skeleton", List.of("mcp:tools"));
		ResponseEntity<String> opened = post(ana, null, INITIALIZE);
		String sessionId = opened.getHeaders().getFirst("Mcp-Session-Id");
		assertThat(opened.getStatusCode()).as(String.valueOf(opened)).isEqualTo(HttpStatus.OK);
		assertThat(sessionId).isNotBlank();

		ResponseEntity<String> hijackAttempt = post(ben, sessionId, TOOLS_LIST);
		ResponseEntity<String> ownerCall = post(ana, sessionId, TOOLS_LIST);

		assertThat(hijackAttempt.getStatusCode()).as(String.valueOf(hijackAttempt)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(hijackAttempt.getBody()).contains("-32001");
		assertThat(ownerCall.getStatusCode()).as(String.valueOf(ownerCall)).isEqualTo(HttpStatus.OK);
		assertThat(ownerCall.getBody()).contains("topRatedMovies");
	}

	@Test
	void session_deletedByAnotherCaller_shouldAnswer404AndStayOpen() {
		String ana = LocalIssuer.get().tokenFor("ana", "gatool-skeleton", List.of("mcp:tools"));
		String ben = LocalIssuer.get().tokenFor("ben", "gatool-skeleton", List.of("mcp:tools"));
		String sessionId = post(ana, null, INITIALIZE).getHeaders().getFirst("Mcp-Session-Id");

		ResponseEntity<String> delete = client().delete()
			.uri("/mcp")
			.header("Mcp-Session-Id", sessionId)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(ben))
			.exchange((request, reply) -> new ResponseEntity<>(reply.getHeaders(), reply.getStatusCode()));

		assertThat(delete.getStatusCode()).as(String.valueOf(delete)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(post(ana, sessionId, TOOLS_LIST).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void stream_openedWithAnotherCallersSessionId_shouldAnswer404() {
		// The binding filter reads the id whatever the method, so the GET that opens
		// the session's event stream is refused the same way as a POST into it.
		String ana = LocalIssuer.get().tokenFor("ana", "gatool-skeleton", List.of("mcp:tools"));
		String ben = LocalIssuer.get().tokenFor("ben", "gatool-skeleton", List.of("mcp:tools"));
		String sessionId = post(ana, null, INITIALIZE).getHeaders().getFirst("Mcp-Session-Id");

		ResponseEntity<String> stream = client().get()
			.uri("/mcp")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.header("Mcp-Session-Id", sessionId)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(ben))
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));

		assertThat(stream.getStatusCode()).as(String.valueOf(stream)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(stream.getBody()).contains("-32001");
	}

	@Test
	void session_idThisServerNeverIssued_shouldAnswer404() {
		// The filter answers ahead of the SDK, so the caller who presents an unknown
		// id is refused, whatever the SDK would have said about it.
		String ana = LocalIssuer.get().tokenFor("ana", "gatool-skeleton", List.of("mcp:tools"));

		ResponseEntity<String> response = post(ana, "never-issued-000", TOOLS_LIST);

		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).contains("-32001").contains("Session not found");
	}

	@Test
	void session_forgedOnInitialize_shouldBeRefusedWithoutAStackTrace() {
		// The SDK answers with an McpError, an exception, and the shared mapper leaves
		// the trace, the cause and the suppressed list out of the body.
		String ana = LocalIssuer.get().tokenFor("ana", "gatool-skeleton", List.of("mcp:tools"));

		ResponseEntity<String> response = post(ana, "forged-000", INITIALIZE);

		assertThat(response.getStatusCode().is4xxClientError()).as(String.valueOf(response)).isTrue();
		assertThat(response.getBody()).doesNotContain("stackTrace").doesNotContain("lineNumber");
	}

	private ResponseEntity<String> post(String token, String sessionId, String body) {
		return client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> {
				headers.setBearerAuth(token);
				if (sessionId != null) {
					headers.set("Mcp-Session-Id", sessionId);
				}
			})
			.body(body)
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
