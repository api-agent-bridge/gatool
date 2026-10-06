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

import java.text.ParseException;
import java.util.List;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
 * Client credentials against a real issuer: GATool obtains its own token from Keycloak's
 * service account, and the movie API sees GATool as the caller.
 */
@EnabledIf("io.gatool.tests.skeleton.KeycloakServer#dockerAvailable")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-server",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.strategy=client-credentials",
				"gatool.api.credentials.client-registration-id=keycloak",
				"spring.security.oauth2.client.registration.keycloak.client-id=gatool-server",
				"spring.security.oauth2.client.registration.keycloak.client-secret=gatool-secret",
				"spring.security.oauth2.client.registration.keycloak.authorization-grant-type=client_credentials",
				"spring.security.oauth2.client.registration.keycloak.provider=keycloak" })
class KeycloakClientCredentialsTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"searchMovies\",\"arguments\":{\"text\":\"a\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakServer::issuer);
		registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", KeycloakServer::tokenUrl);
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_underClientCredentialsWithKeycloak_shouldHandTheApiGAToolsOwnToken() throws ParseException {
		String caller = KeycloakServer.signIn(List.of("mcp:tools"));

		ResponseEntity<String> response = post(caller);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"isError\":false");
		// The service account of gatool-server, with the audience and the scope its
		// registration in Keycloak gives it, and without a trace of the user.
		String authorization = MoviesApiServer.lastAuthorization();
		assertThat(authorization).startsWith("Bearer ").isNotEqualTo("Bearer " + caller);
		JWTClaimsSet receivedClaims = SignedJWT.parse(authorization.substring("Bearer ".length())).getJWTClaimsSet();
		assertThat(receivedClaims.getStringClaim("azp")).isEqualTo("gatool-server");
		assertThat(receivedClaims.getAudience()).containsExactly("movies-api");
		assertThat(receivedClaims.getStringClaim("scope")).contains("movies:api");
		assertThat(receivedClaims.getStringClaim("preferred_username")).isNotEqualTo(KeycloakServer.USER);
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
			.body(CALL)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
