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

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two chains the starter contributes to an application without a chain of its own:
 * the MCP chain for the endpoint and its metadata path, and a default chain that keeps
 * every other path behind a bearer token for the same issuer, the chain Boot's resource
 * server auto-configuration would have contributed.
 *
 * <p>
 * With the MCP chain alone, Boot's default chain would back off and a request outside the
 * endpoint would run without any security filter, so {@code GET /nope} would answer 404
 * where a secured application answers 401.
 *
 * <p>
 * The default chain reads bearer tokens. Boot's generated user backs off for a
 * {@code JwtDecoder} bean, so a form or basic login on that chain would lack a user
 * store, and every credential would fail.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class McpSecurityContributedChainsTests {

	private static final String AUDIENCE = "gatool-skeleton";

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void context_withoutAChainOfTheApplicationsOwn_shouldHoldBothContributedChains() {
		assertThat(this.context.getBeanNamesForType(SecurityFilterChain.class))
			.containsExactlyInAnyOrder("gaToolMcpSecurityFilterChain", "gaToolDefaultSecurityFilterChain");
	}

	@Test
	void anotherPath_withoutAChainOfTheApplicationsOwn_shouldBeHandledBySomeChain() {
		FilterChainProxy proxy = this.context.getBean("springSecurityFilterChain", FilterChainProxy.class);

		List<Filter> filters = proxy.getFilters("/nope");

		assertThat(filters).as("security filters that handle GET /nope").isNotEmpty();
	}

	@Test
	void defaultChain_withADecoderBean_shouldReadBearerTokensInsteadOfOfferingALoginWithoutAUserStore() {
		// Boot's UserDetailsServiceAutoConfiguration backs off for a JwtDecoder bean, so
		// a form or basic login on the default chain would lack a user store.
		assertThat(this.context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(AuthenticationProvider.class)).isEmpty();
		FilterChainProxy proxy = this.context.getBean("springSecurityFilterChain", FilterChainProxy.class);

		List<Filter> filters = proxy.getFilters("/nope");

		assertThat(filters).as("security filters that handle GET /nope")
			.anyMatch(BearerTokenAuthenticationFilter.class::isInstance)
			.noneMatch(BasicAuthenticationFilter.class::isInstance)
			.noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
	}

	@Test
	void anotherPath_withoutCredentials_shouldAnswer401WithABearerChallengeFromTheDefaultChain() {
		ResponseEntity<String> response = get("/nope", null);

		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.UNAUTHORIZED);
		// The default chain is a resource server for the same issuer, so its challenge
		// is the one Boot's own jwt chain answers with.
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
	}

	@Test
	void anotherPath_withAValidToken_shouldReachTheApplicationAndReadBootsErrorStatus() {
		String token = LocalIssuer.get().token(AUDIENCE, List.of("mcp:tools"));

		ResponseEntity<String> response = get("/nope", token);

		// Spring MVC lacks a handler for the path, so Boot's error controller answers
		// the error dispatch.
		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void mcpEndpoint_deleteWithAValidToken_shouldBeAnsweredByTheApplicationInsteadOfALoginChallenge() {
		String token = LocalIssuer.get().token(AUDIENCE, List.of("mcp:tools"));

		ResponseEntity<String> response = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.method(HttpMethod.DELETE)
			.uri("/mcp")
			.headers((headers) -> headers.setBearerAuth(token))
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));

		// The stateless transport serves POST and GET alone. Spring MVC's error dispatch
		// for the method reaches the default chain with the authentication the MCP
		// chain saved on the request, so Boot's error controller answers it; a refusal
		// ahead of Spring Security is the application's own as well.
		assertThat(response.getStatusCode()).as(String.valueOf(response)).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(response.getBody()).as("the body of the application's answer").isNotBlank();
	}

	@Test
	void mcpEndpoint_withoutAToken_shouldKeepTheBearerChallengeOfTheMcpChain() {
		ResponseEntity<String> response = get("/mcp", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer ")
			.contains("resource_metadata=");
	}

	@Test
	void metadataDocument_withoutAToken_shouldStayAnonymous() {
		ResponseEntity<String> response = get("/.well-known/oauth-protected-resource/mcp", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("authorization_servers");
	}

	private ResponseEntity<String> get(String path, String token) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.get()
			.uri(path)
			.headers((headers) -> {
				if (token != null) {
					headers.setBearerAuth(token);
				}
			})
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
