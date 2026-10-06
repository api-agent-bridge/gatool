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

import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
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
 * The deprecated root field and the deprecated argument of the movie schema, at the
 * default of {@code include-deprecated-fields}, served by a real MCP server against the
 * real movie API.
 *
 * <p>
 * The two schema tools hide the field and the argument, and {@code executeGraphql} still
 * runs a document that names them, because the API accepts the document and refusing it
 * would be a rule GraphQL does not have.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step" })
class DynamicDeprecationsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void theTwoSchemaTools_aDeprecatedRootField_shouldLeaveItOut() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult searched = client
				.callTool(DynamicToolCalls.call("searchSchema", Map.of("question", "every movie in the catalog")));
			McpSchema.CallToolResult query = client
				.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Query")));

			assertThat(DynamicToolCalls.text(searched)).doesNotContain("allMovies");
			assertThat(DynamicToolCalls.text(query)).contains("  movies(").doesNotContain("allMovies");
		}
	}

	@Test
	void executeGraphql_aDeprecatedRootFieldTheSchemaToolsHide_shouldStillRunAgainstTheApi() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ allMovies { title } }");

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("Iron Harbor").contains("Northern Echoes");
		}
	}

	@Test
	void introspectType_aDeprecatedArgument_shouldLeaveItOut() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult query = client
				.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Query")));

			// The same rule as the field: an argument the setting hides is one the model
			// cannot pass, and GraphQL keeps a deprecated argument optional.
			assertThat(DynamicToolCalls.text(query))
				.contains("reviews(since: DateTime, country: CountryCode, filter: ReviewFilterInput, first: Int = 20)"
						+ ": [Review!]!")
				.doesNotContain("limit");
		}
	}

	@Test
	void executeGraphql_aDeprecatedArgument_shouldRunAgainstTheApi() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ reviews(limit: 1) { score } }");

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("\"reviews\":[{\"score\":");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
