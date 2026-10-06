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
 * The three dynamic tools served by a real MCP server and called by a real client, with
 * {@code executeGraphql} reaching the real movie API.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step" })
class DynamicToolsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_dynamicThreeStep_shouldServeTheThreeToolsBesideTheOperationFile() {
		try (McpSyncClient client = client()) {
			client.initialize();

			List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

			assertThat(names).containsExactlyInAnyOrder("topRatedMovies", "searchSchema", "introspectType",
					"executeGraphql");
		}
	}

	@Test
	void searchSchema_shouldRankTheFieldThatAnswersTheQuestion() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(call("searchSchema", Map.of("question", "highest rated films")));

			assertThat(result.isError()).isFalse();
			assertThat(text(result)).contains("Query.topRatedMovies");
		}
	}

	@Test
	void introspectType_shouldWriteTheTypeAsSdl() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(call("introspectType", Map.of("name", "MovieFilterInput")));

			assertThat(result.isError()).isFalse();
			assertThat(text(result)).contains("input MovieFilterInput").contains("minRating");
		}
	}

	@Test
	void executeGraphql_aQuery_shouldReachTheMovieApiAndReturnData() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("executeGraphql",
					Map.of("document", "query Top($first: Int) { topRatedMovies(first: $first) { title } }",
							"variables", Map.of("first", 2))));

			assertThat(result.isError()).isFalse();
			assertThat(text(result)).contains("\"data\"")
				.contains("Signal from Kepler")
				.contains("The Last Lighthouse")
				.doesNotContain("Midnight Recipe");
		}
	}

	@Test
	void executeGraphql_aMutation_shouldRefuseNamingTheSwitch() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("executeGraphql", Map.of("document",
					"mutation Add { addReview(input: {movieId: \"movie-1\", score: 9}) " + "{ review { id } } }")));

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("allow-mutations");
		}
	}

	@Test
	void executeGraphql_anUnknownField_shouldRefuseWithTheValidationError() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(call("executeGraphql", Map.of("document", "{ topRatedMovies { tagline } }")));

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("tagline").contains("Read every type");
		}
	}

	private McpSyncClient client() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private static String text(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.reduce("", String::concat);
	}

	// The SDK deprecates the two-argument constructor in favour of its builder.
	private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
