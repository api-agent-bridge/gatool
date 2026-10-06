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
 * An issuer that names the API by an identifier of its own, the way Microsoft Entra ID
 * does with an App ID URI such as {@code api://<client-id>}: the identifier is the
 * audience, which Spring Boot's decoder reads from
 * {@code spring.security.oauth2.resourceserver.jwt.audiences}, and the resource is the
 * URL of the endpoint.
 *
 * <p>
 * The two properties are read apart. The decoder compares the {@code aud} claim with the
 * audiences property alone, so a token issued for the App ID URI is accepted, and a token
 * whose audience is the resource URL is refused while the audiences property leaves that
 * URL out.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=" + McpSecurityAppIdUriAudienceTests.APP_ID_URI,
				"gatool.mcp.security.baseline-scopes=mcp:tools",
				"gatool.mcp.security.resource=" + McpSecurityAppIdUriAudienceTests.RESOURCE })
class McpSecurityAppIdUriAudienceTests {

	static final String APP_ID_URI = "api://2f1e8c1a-6a0b-4f0e-9a57-1d3c5b7e9f10";

	static final String RESOURCE = "https://mcp.example.com/mcp";

	private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void request_tokenIssuedForTheAppIdUri_shouldBeAccepted() {
		ResponseEntity<String> response = post(LocalIssuer.get().token(APP_ID_URI, List.of("mcp:tools")));

		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("topRatedMovies");
	}

	@Test
	void request_tokenIssuedForTheResourceUrl_shouldBeRefusedWhileTheAudiencesLeaveTheUrlOut() {
		ResponseEntity<String> response = post(LocalIssuer.get().token(RESOURCE, List.of("mcp:tools")));

		assertThat(response.getStatusCode()).as(String.valueOf(response)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token")
			.contains("resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource/mcp\"");
	}

	@Test
	void metadata_shouldPublishTheUrlAsTheResource() {
		ResponseEntity<String> response = RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.get()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"resource\":\"" + RESOURCE + "\"").doesNotContain("api://");
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
			.body(TOOLS_LIST)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
