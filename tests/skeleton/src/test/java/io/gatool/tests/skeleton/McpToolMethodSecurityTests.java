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
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application's own {@code @McpTool} method behind Spring Security method security:
 * whether {@code @PreAuthorize} takes effect on the MCP server's thread, and what the
 * client reads when it denies the call.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
class McpToolMethodSecurityTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"secretNumber\",\"arguments\":{}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void callTool_annotatedMethodWithTheAuthority_shouldReturnItsResult() {
		ResponseEntity<String> response = post(
				LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools", "admin")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("42").contains("\"isError\":false");
	}

	@Test
	void callTool_annotatedMethodWithoutTheAuthority_shouldComeBackAsAToolError() {
		// Method security runs on the MCP server's thread, where the security context
		// holds the caller's token, so the annotation takes effect. Spring AI catches
		// the denial and answers a tool error, without the 403 challenge a scope check
		// at the endpoint would send, which is why per-tool rules belong in
		// @gatool(scopes:) for GATool's own tools.
		ResponseEntity<String> response = post(LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"isError\":true").contains("Access Denied").doesNotContain("42");
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
	@EnableMethodSecurity
	static class SkeletonApplication {

		@Bean
		SecretTools secretTools() {
			return new SecretTools();
		}

	}

	static class SecretTools {

		@PreAuthorize("hasAuthority('SCOPE_admin')")
		@McpTool(name = "secretNumber", description = "A number for administrators.")
		String secretNumber() {
			return "42";
		}

	}

}
