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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application with chains of its own: the dedicated MCP chain the README shows ahead
 * of a basic-auth chain for the rest, so the endpoint answers the bearer challenge and
 * every other path keeps the application's own rules.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
@Import(McpSecurityOwnChainTests.Chains.class)
class McpSecurityOwnChainTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void mcpEndpoint_withoutAToken_shouldAnswerTheBearerChallengeFromTheDedicatedChain() {
		ResponseEntity<String> response = post(null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer ")
			.contains("resource_metadata=");
	}

	@Test
	void mcpEndpoint_withAGoodToken_shouldReachTheServer() {
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("serverInfo");
	}

	@Test
	void anotherPath_withoutCredentials_shouldKeepTheApplicationsOwnBasicChallenge() {
		ResponseEntity<String> response = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.get()
			.uri("/anything-else")
			.exchange((request, reply) -> new ResponseEntity<>(reply.getHeaders(), reply.getStatusCode()));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic ");
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
			.headers((headers) -> {
				if (token != null) {
					headers.setBearerAuth(token);
				}
			})
			.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
					+ "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Chains {

		@Bean
		@Order(1)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain rest(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
		}

	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
