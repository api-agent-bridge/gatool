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

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
 * Tools written from the schema, served by a real MCP server and run against the real
 * movie API: a plain list, a union expanded per member, and a Relay connection reached at
 * depth three.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/",
				"gatool.dev.experimental.generate-tools=all-root-queries",
				"gatool.dev.experimental.generated-selection-depth=3" })
class GeneratedToolsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_everyQueryRootField_shouldBecomeATool() {
		try (McpSyncClient client = client()) {
			client.initialize();

			List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

			assertThat(names).containsExactlyInAnyOrder("topRatedMovies", "movie", "movies", "search", "node",
					"allMovies", "reviews");
		}
	}

	@Test
	void callTool_generatedUnionSelection_shouldReturnEveryMemberFromTheApi() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("search", Map.of("text", "a")));

			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).contains("\"__typename\":\"Movie\"").contains("\"__typename\":\"Person\"");
		}
	}

	@Test
	void callTool_generatedConnectionAtDepthThree_shouldReachTheNodes() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The written selection reaches every scalar, communityRating included, and
			// the fixture fails that field for the second movie on purpose, so one page
			// of one.
			McpSchema.CallToolResult result = client.callTool(call("movies", Map.of("first", 1)));

			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).contains("\"edges\"").contains("\"node\"").contains("\"title\"");
		}
	}

	private McpSyncClient client() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
