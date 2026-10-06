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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default chain the starter contributes under the unsafe switch, with Spring Security
 * present and without a {@code JwtDecoder} bean: Boot's generated user exists there, so
 * the chain keeps the form and basic login Boot's own default chain has, and an error
 * dispatch of a request the MCP chain permitted is answered by Boot's error controller
 * instead of that chain's login challenge.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpSecurityUnsafeSwitchDefaultChainTests {

	@LocalServerPort
	private int port;

	@Test
	void anotherPath_withoutCredentials_shouldAnswer401WithABasicChallenge() {
		ResponseEntity<String> response = exchange(HttpMethod.GET, "/nope");

		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic ");
	}

	@Test
	void metadataPath_withTheSwitchOn_shouldReadBootsNotFoundInsteadOfTheDefaultChainsChallenge() {
		ResponseEntity<String> response = exchange(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp");

		// The MCP chain permits the path and leaves it to the application, which lacks
		// a handler for it while the resource server is off. Spring MVC sends a 404,
		// and the container dispatches /error. Boot 4.1.1 ships without
		// ErrorPageSecurityFilter, so the default chain decides that dispatch.
		assertThat(response.getStatusCode()).as(String.valueOf(response))
			.isNotEqualTo(HttpStatus.UNAUTHORIZED)
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).as("Boot's error body").contains("\"status\":404");
	}

	@Test
	void mcpEndpoint_delete_shouldBeAnsweredByTheApplicationInsteadOfTheDefaultChainsChallenge() {
		ResponseEntity<String> response = exchange(HttpMethod.DELETE, "/mcp");

		// The stateless transport serves POST and GET alone, so the answer is an error
		// dispatch decided by the default chain, or a refusal ahead of Spring Security.
		// Both are the application's own answer; a login challenge would mean the
		// default chain refused the dispatch.
		assertThat(response.getStatusCode()).as(String.valueOf(response)).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
	}

	private ResponseEntity<String> exchange(HttpMethod method, String path) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.method(method)
			.uri(path)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
