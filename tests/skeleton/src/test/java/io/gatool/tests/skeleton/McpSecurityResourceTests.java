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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured resource URI, which a server behind a proxy that terminates TLS sets,
 * names the metadata document in the challenge and in the document.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools",
				"gatool.mcp.security.resource=https://mcp.example.com/mcp" })
class McpSecurityResourceTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void request_withoutAToken_shouldNameTheMetadataDocumentUnderTheConfiguredResource() {
		ResponseEntity<String> response = client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			// A readable JSON-RPC call, because the compliance filter refuses a body
			// that is not one ahead of the token check, and {} lacks its jsonrpc member.
			.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));

		// The request arrived at 127.0.0.1, and the challenge names the address the
		// client reaches, which the proxy in front of the server owns.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
			.contains("resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource/mcp\"");
	}

	@Test
	void metadata_configuredResource_shouldPublishItAsTheResource() {
		ResponseEntity<String> response = client().get()
			.uri("/.well-known/oauth-protected-resource/mcp")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"resource\":\"https://mcp.example.com/mcp\"");
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
