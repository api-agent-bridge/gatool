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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scopes the three dynamic tools need, from their own property: a caller with the
 * baseline alone cannot run GraphQL it wrote, and one with the listed scope can.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/",
				"gatool.dev.experimental.generate-tools=dynamic-three-step",
				"gatool.dev.experimental.dynamic-operations.required-scopes=graphql:run",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class McpDynamicScopesOverHttpTests {

	private static final String EXECUTE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"executeGraphql\",\"arguments\":{\"document\":\"query Top { topRatedMovies(first: 1) { title } }\"}}}";

	private static final String SEARCH = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"searchSchema\",\"arguments\":{\"question\":\"movies\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_executeGraphqlWithTheBaselineAlone_shouldAnswer403NamingTheDynamicScope() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")),
				EXECUTE);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
			.contains("scope=\"mcp:tools graphql:run\"")
			.contains("executeGraphql needs mcp:tools graphql:run.");
	}

	@Test
	void callTool_searchSchemaWithTheBaselineAlone_shouldAnswer403() {
		// The three tools share the list, because a caller who may run GraphQL may
		// search the schema for it, and the other way round.
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")),
				SEARCH);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void callTool_executeGraphqlWithTheDynamicScope_shouldReachTheApi() {
		ResponseEntity<String> response = post(
				LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools", "graphql:run")), EXECUTE);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Signal from Kepler");
	}

	private ResponseEntity<String> post(String token, String body) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
