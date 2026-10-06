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
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The secured endpoint driven by the MCP SDK client with a bearer token, the way an agent
 * reaches it, with the tool call landing on the real movie API; and the tokens Spring
 * Boot's decoder refuses.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools,movies:read" })
class McpRefusedTokensOverHttpTests {

	private static final List<String> EVERY_SCOPE = List.of("mcp:tools", "movies:read");

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void sdkClient_withAGoodToken_shouldInitializeListAndCallTheToolAgainstTheMovieApi() {
		try (McpSyncClient client = client(LocalIssuer.get().token("gatool-skeleton", EVERY_SCOPE))) {
			McpSchema.InitializeResult initialized = client.initialize();

			assertThat(initialized.protocolVersion()).isEqualTo("2025-11-25");
			assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("topRatedMovies");
			McpSchema.CallToolResult result = client.callTool(call("topRatedMovies", Map.of("first", 1)));
			assertThat(result.isError()).isFalse();
			assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("Signal from Kepler");
		}
	}

	@Test
	void sdkClient_withATokenForAnotherAudience_shouldFailToInitialize() {
		try (McpSyncClient client = client(LocalIssuer.get().token("someone-else", EVERY_SCOPE))) {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(client::initialize);
		}
	}

	@Test
	void initialize_withATokenHoldingOneOfTwoBaselineScopes_shouldAnswer403NamingBoth() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")));

		// Every baseline scope is required, and the challenge names all of them.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(challenge(response)).contains("insufficient_scope").contains("scope=\"mcp:tools movies:read\"");
	}

	@Test
	void initialize_withAnExpiredToken_shouldAnswer401InvalidToken() {
		ResponseEntity<String> response = post(LocalIssuer.get().expiredToken("gatool-skeleton", EVERY_SCOPE));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge(response)).contains("error=\"invalid_token\"").contains("resource_metadata=");
	}

	@Test
	void initialize_withATokenFromAnotherIssuer_shouldAnswer401InvalidToken() {
		ResponseEntity<String> response = post(
				LocalIssuer.get().tokenFromAnotherIssuer("gatool-skeleton", EVERY_SCOPE));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge(response)).contains("error=\"invalid_token\"");
	}

	@Test
	void initialize_withATokenSignedByAnUnknownKey_shouldAnswer401InvalidToken() {
		ResponseEntity<String> response = post(
				LocalIssuer.get().tokenSignedWithAnUnknownKey("gatool-skeleton", EVERY_SCOPE));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge(response)).contains("error=\"invalid_token\"");
	}

	@Test
	void initialize_withAGoodToken_shouldLeaveNoSessionCookieBehind() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", EVERY_SCOPE));

		// A bearer chain is stateless, so the container leaves the HTTP session closed.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void metadataPath_anonymousPost_shouldLeaveNoSessionCookieBehind() {
		ResponseEntity<String> response = anonymous().post()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}")
			.retrieve()
			.onStatus((status) -> true, (request, answer) -> {
			})
			.toEntity(String.class);

		// A CSRF token repository opens a session for each such request, whatever the
		// session policy says, so with one in the chain a caller without a token could
		// fill the session store.
		assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void pathOutsideTheEndpoint_anonymousGet_shouldLeaveNoSessionCookieBehind() {
		ResponseEntity<String> response = anonymous().get()
			.uri("/nope")
			.retrieve()
			.onStatus((status) -> true, (request, answer) -> {
			})
			.toEntity(String.class);

		// The bearer shape of the default chain runs without the request cache, which
		// would save each refused request in a new session.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void pathOutsideTheEndpoint_anonymousPost_shouldLeaveNoSessionCookieBehind() {
		ResponseEntity<String> response = anonymous().post()
			.uri("/nope")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}")
			.retrieve()
			.onStatus((status) -> true, (request, answer) -> {
			})
			.toEntity(String.class);

		// A CSRF token repository would open a session for each such request and answer
		// 403. A bearer chain authenticates without a cookie, so the request is refused
		// for its missing token, as a GET is.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
	}

	private RestClient anonymous() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
	}

	private McpSyncClient client(String token) {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
				.endpoint("/mcp")
				.httpRequestCustomizer(
						(builder, method, uri, body, context) -> builder.header("Authorization", "Bearer " + token))
				.build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private ResponseEntity<String> post(String token) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
					+ "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private static String challenge(ResponseEntity<String> response) {
		String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
		assertThat(header).isNotNull();
		return header;
	}

	// The SDK deprecates the two-argument constructor in favour of its builder.
	private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
