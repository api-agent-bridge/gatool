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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.nimbusds.jwt.JWTClaimsSet;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token exchange on the stateful transport, where the tool runs inside a session: the
 * caller's token still has to reach the exchange, whichever thread the server runs the
 * call on.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.strategy=token-exchange",
				"gatool.api.credentials.client-registration-id=movies", "gatool.api.credentials.audience=movies-api",
				"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"spring.security.oauth2.client.registration.movies.client-secret=secret",
				"spring.security.oauth2.client.registration.movies.authorization-grant-type="
						+ "urn:ietf:params:oauth:grant-type:token-exchange",
				"spring.security.oauth2.client.registration.movies.provider=local" })
class TokenExchangeOverStatefulHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("spring.security.oauth2.client.provider.local.token-uri", () -> LocalIssuer.get().tokenUrl());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_underTokenExchangeInASession_shouldStillExchangeTheCallersToken() throws ParseException {
		String caller = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
				.endpoint("/mcp")
				.httpRequestCustomizer(
						(builder, method, uri, body, context) -> builder.header("Authorization", "Bearer " + caller))
				.build())
			.requestTimeout(Duration.ofSeconds(20))
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("moviesPage").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(((McpSchema.TextContent) result.content().getFirst()).text()).isFalse();
			assertThat(LocalIssuer.get().lastTokenRequest()).containsEntry("subject_token", caller);
			JWTClaimsSet exchangedClaims = LocalIssuer.claimsOf(MoviesApiServer.lastAuthorization());
			assertThat(exchangedClaims.getSubject()).isEqualTo("agent");
			assertThat(exchangedClaims.getAudience()).containsExactly("movies-api");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
