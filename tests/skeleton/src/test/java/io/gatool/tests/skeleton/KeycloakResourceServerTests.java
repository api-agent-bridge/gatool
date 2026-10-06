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
import org.junit.jupiter.api.condition.EnabledIf;
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
 * The MCP endpoint as a resource server of a real issuer: Keycloak signs the user in,
 * Spring Boot's decoder reads the realm's keys, and the tool scopes decide each call and
 * drive the step-up from a 403.
 */
@EnabledIf("io.gatool.tests.skeleton.KeycloakServer#dockerAvailable")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-server",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class KeycloakResourceServerTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakServer::issuer);
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_withAKeycloakTokenHoldingTheToolsScope_shouldReachTheApi() {
		String token = KeycloakServer.signIn(List.of("mcp:tools", "movies:read"));

		ResponseEntity<String> response = post(token, callBody("topRatedMovies"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Signal from Kepler");
	}

	@Test
	void callTool_withAKeycloakTokenLackingTheToolsScope_shouldAnswer403ThenPassAfterStepUp() {
		String baseline = KeycloakServer.signIn(List.of("mcp:tools", "movies:read"));

		ResponseEntity<String> refused = post(baseline,
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"movieByLookup\","
						+ "\"arguments\":{\"by\":{\"id\":\"movie-1\"}}}}");

		// The challenge names the scope to ask for, the client signs in again with it,
		// and the same call passes: step-up as the specification describes it.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(refused.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"insufficient_scope\"")
			.contains("scope=\"mcp:tools movies:read movies:detail\"");

		String steppedUp = KeycloakServer.signIn(List.of("mcp:tools", "movies:read", "movies:detail"));
		ResponseEntity<String> accepted = post(steppedUp,
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"movieByLookup\","
						+ "\"arguments\":{\"by\":{\"id\":\"movie-1\"}}}}");
		assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(accepted.getBody()).contains("\"isError\":false");
	}

	@Test
	void callTool_withoutAToken_shouldPointAtTheMetadataThatNamesKeycloak() {
		ResponseEntity<String> response = post(null, callBody("topRatedMovies"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		ResponseEntity<String> metadata = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.get()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.retrieve()
			.toEntity(String.class);
		assertThat(metadata.getBody()).contains("\"authorization_servers\":[\"" + KeycloakServer.issuer() + "\"]");
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

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
