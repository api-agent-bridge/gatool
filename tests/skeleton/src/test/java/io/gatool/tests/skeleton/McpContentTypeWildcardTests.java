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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code Content-Type} with a wildcard names a range of media types where the transport
 * reads one, so the compliance filter answers it the way it answers
 * {@code multipart/form-data}: 415 with a JSON-RPC error object.
 *
 * <p>
 * Spring's router refuses a wildcard while it builds the request, ahead of every route,
 * so a body the filter passed on would leave the caller reading Tomcat's HTML error page
 * under a 500.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpContentTypeWildcardTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";

	@LocalServerPort
	private int port;

	@Test
	void post_contentTypeApplicationWildcard_shouldAnswer415AsAJsonRpcError() throws Exception {
		assertUnsupportedMediaType("application/*");
	}

	@Test
	void post_contentTypeAnyWildcard_shouldAnswer415AsAJsonRpcError() throws Exception {
		assertUnsupportedMediaType("*/*");
	}

	private void assertUnsupportedMediaType(String contentType) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", contentType)
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.POST(HttpRequest.BodyPublishers.ofString(PING))
			.build();

		HttpResponse<String> response;
		try (HttpClient client = HttpClient.newHttpClient()) {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		String body = response.body();

		assertThat(response.statusCode()).as("Content-Type " + contentType + ", body: " + trim(body)).isEqualTo(415);
		JsonNode json = JSON.readTree(body);
		assertThat(json.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(json.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	private static String trim(String body) {
		return (body.length() > 300) ? body.substring(0, 300) + "..." : body;
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
