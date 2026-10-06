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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unsafe switch that forwards the caller's own token: the movie API receives exactly
 * the token the MCP caller sent, and startup warns.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.ai.mcp.server.protocol=STATELESS",
		"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
		"gatool.mcp.operations.locations=classpath:gatool/scoped/",
		"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
		"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.unsafe.forward-client-tokens=true" })
class ForwardedTokenOverHttpTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"moviesPage\",\"arguments\":{\"first\":1}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_withTheSwitchOn_shouldForwardTheCallersTokenAndWarnAtStartup(CapturedOutput output) {
		String caller = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));

		assertThat(post(caller).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(MoviesApiServer.lastAuthorization()).isEqualTo("Bearer " + caller);
		assertThat(output.getAll()).contains("gatool.api.credentials.unsafe.forward-client-tokens is true")
			.contains("must not pass that token through");
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
