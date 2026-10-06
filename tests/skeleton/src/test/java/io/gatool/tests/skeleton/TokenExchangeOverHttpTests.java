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
 * The token exchange strategy on the wire: GATool hands the caller's token to the issuer
 * with the configured audience and resource, and the movie API receives a token issued
 * for itself that still names the caller.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.strategy=token-exchange",
				"gatool.api.credentials.client-registration-id=movies", "gatool.api.credentials.audience=movies-api",
				"gatool.api.credentials.resource=https://movies.example.org/graphql",
				"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"spring.security.oauth2.client.registration.movies.client-secret=secret",
				"spring.security.oauth2.client.registration.movies.authorization-grant-type="
						+ "urn:ietf:params:oauth:grant-type:token-exchange",
				"spring.security.oauth2.client.registration.movies.provider=local" })
class TokenExchangeOverHttpTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"moviesPage\",\"arguments\":{\"first\":1}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("spring.security.oauth2.client.provider.local.token-uri", () -> LocalIssuer.get().tokenUrl());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_underTokenExchange_shouldReachTheApiWithATokenForItThatNamesTheCaller() throws ParseException {
		String caller = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));

		assertThat(post(caller).getStatusCode()).isEqualTo(HttpStatus.OK);

		// The issuer was asked for the configured audience and resource, with the
		// caller's own token as the subject, and the API receives a token for itself
		// whose subject is still the caller.
		assertThat(LocalIssuer.get().lastTokenRequest())
			.containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
			.containsEntry("subject_token", caller)
			.containsEntry("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
			.containsEntry("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
			.containsEntry("audience", "movies-api")
			.containsEntry("resource", "https://movies.example.org/graphql")
			.containsEntry("client_id", "gatool-server");
		JWTClaimsSet exchangedClaims = LocalIssuer.claimsOf(MoviesApiServer.lastAuthorization());
		assertThat(exchangedClaims.getSubject()).isEqualTo("agent");
		assertThat(exchangedClaims.getAudience()).containsExactly("movies-api");
		assertThat(MoviesApiServer.lastAuthorization()).isNotEqualTo("Bearer " + caller);
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
