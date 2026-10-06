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
 * The client credentials strategy on the wire: GATool obtains a token for itself from the
 * issuer through Spring Boot's client registration, the movie API receives it, and every
 * call shares the one token.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.strategy=client-credentials",
				"gatool.api.credentials.client-registration-id=movies",
				"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"spring.security.oauth2.client.registration.movies.client-secret=secret",
				"spring.security.oauth2.client.registration.movies.authorization-grant-type=client_credentials",
				"spring.security.oauth2.client.registration.movies.scope=movies:api",
				"spring.security.oauth2.client.registration.movies.provider=local" })
class ClientCredentialsOverHttpTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"searchMovies\",\"arguments\":{\"text\":\"a\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("spring.security.oauth2.client.provider.local.token-uri", () -> LocalIssuer.get().tokenUrl());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_underClientCredentials_shouldReachTheApiWithATokenIssuedToGAToolAndReuseIt() throws ParseException {
		int before = LocalIssuer.get().tokenRequests();
		String caller = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));

		assertThat(post(caller).getStatusCode()).isEqualTo(HttpStatus.OK);

		// The API sees GATool as the caller: the token names the client id as its subject
		// and the registration's own scope, and the caller's identity stays behind.
		JWTClaimsSet receivedClaims = LocalIssuer.claimsOf(MoviesApiServer.lastAuthorization());
		assertThat(receivedClaims.getSubject()).isEqualTo("gatool-server");
		assertThat(receivedClaims.getStringClaim("scope")).isEqualTo("movies:api");
		assertThat(LocalIssuer.get().lastTokenRequest()).containsEntry("grant_type", "client_credentials")
			.containsEntry("client_id", "gatool-server");

		// One token for the application, held until it expires, so a second call by
		// another caller reaches the API without another round trip to the issuer.
		String another = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));
		assertThat(post(another).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(LocalIssuer.get().tokenRequests()).isEqualTo(before + 1);
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
