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
 * The MCP endpoint as an OAuth 2.1 resource server, against a local issuer: the challenge
 * a client reads to find the issuer and the scopes, the metadata document it fetches
 * next, and what a token gets.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class McpBearerChallengeOverHttpTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void initialize_withoutAToken_shouldAnswer401NamingTheMetadataDocumentAndTheBaselineScopes() {
		ResponseEntity<String> response = post(null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// The path-inserted document, because a client has to reject the resource value
		// of the root one, and the scopes it asks for at sign-in.
		assertThat(challenge(response))
			.contains(
					"resource_metadata=\"http://127.0.0.1:" + this.port + "/.well-known/oauth-protected-resource/mcp\"")
			.contains("scope=\"mcp:tools\"");
	}

	@Test
	void metadata_anonymousGet_shouldPublishTheIssuerTheScopesAndThisResource() {
		ResponseEntity<String> response = client().get()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"authorization_servers\":[\"" + LocalIssuer.get().url() + "\"]")
			.contains("\"scopes_supported\":[\"mcp:tools\"]")
			.contains("\"resource\":\"http://127.0.0.1:" + this.port + "/mcp\"")
			.doesNotContain("tls_client_certificate_bound_access_tokens");
	}

	@Test
	void metadata_rootDocument_shouldStayUnserved() {
		// The root document would have to carry the root resource's identifier, and the
		// challenge names the path-inserted one, so only that one is served. The root
		// path falls outside the MCP chain, so the default chain the starter keeps
		// beside it answers an anonymous GET the way Boot's own jwt chain would: with
		// 401 and a plain bearer challenge, and without the document.
		ResponseEntity<String> response = client().get()
			.uri("/.well-known/oauth-protected-resource")
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
		assertThat(String.valueOf(response.getBody())).doesNotContain("authorization_servers");
	}

	@Test
	void initialize_withATokenForAnotherAudience_shouldAnswer401InvalidToken() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("someone-else", List.of("mcp:tools")));

		// MCP makes the audience check a MUST, and Spring Boot's decoder runs it from
		// the audiences property.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge(response)).contains("error=\"invalid_token\"").contains("resource_metadata=");
	}

	@Test
	void initialize_withATokenLackingTheBaselineScope_shouldAnswer403InsufficientScope() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("profile")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(challenge(response)).contains("error=\"insufficient_scope\"")
			.contains("scope=\"mcp:tools\"")
			.contains("resource_metadata=");
	}

	@Test
	void initialize_withAGoodToken_shouldReachTheServer() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("serverInfo");
	}

	private ResponseEntity<String> post(String token) {
		return client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> {
				if (token != null) {
					headers.setBearerAuth(token);
				}
			})
			.body(INITIALIZE)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
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
