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
 * The set-up that lets a caller sign in against Microsoft Entra ID without a change to
 * GATool: an authority prefix that turns a token's short scope names into the qualified
 * authorities a qualified baseline and a qualified {@code @gatool(scopes:)} expect. The
 * metadata and the challenge publish the qualified names, and a token that holds only the
 * short names still passes the check, because the prefix supplies the App ID URI the
 * check compares against.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/entra/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"spring.security.oauth2.resourceserver.jwt.authority-prefix=SCOPE_api://6e74172b/",
				"gatool.mcp.security.baseline-scopes=api://6e74172b/mcp.tools" })
class EntraIdQualifiedScopesOverHttpTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final String QUALIFIED_BASELINE = "api://6e74172b/mcp.tools";

	private static final String QUALIFIED_TOOL_SCOPE = "api://6e74172b/movies.read";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_withAnAuthorityPrefixAndQualifiedScopesInTheBaselineAndTheFile_shouldServeATokenThatHoldsShortNames() {
		ResponseEntity<String> metadata = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.get()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.retrieve()
			.toEntity(String.class);
		assertThat(metadata.getBody()).contains("\"scopes_supported\":[\"" + QUALIFIED_BASELINE + "\"]");

		ResponseEntity<String> unauthorized = post(null, callBody("topRatedMovies"));
		assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge(unauthorized))
			.contains("scope=\"" + QUALIFIED_BASELINE + " " + QUALIFIED_TOOL_SCOPE + "\"");

		String token = LocalIssuer.get().token(AUDIENCE, List.of("mcp.tools", "movies.read"));
		ResponseEntity<String> response = post(token, callBody("topRatedMovies"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Signal from Kepler");
	}

	private static String callBody(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{\"first\":1}}}";
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
			.headers((headers) -> {
				if (token != null) {
					headers.setBearerAuth(token);
				}
			})
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private static String challenge(ResponseEntity<String> response) {
		String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
		assertThat(header).isNotNull();
		return header;
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
