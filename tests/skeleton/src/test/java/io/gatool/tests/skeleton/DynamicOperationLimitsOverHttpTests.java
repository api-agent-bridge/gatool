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
 * The three ceilings {@code executeGraphql} checks before it sends a document, each set
 * low enough for a short document to hit, served by a real MCP server against the real
 * movie API.
 *
 * <p>
 * A refused document stops before the API, and a document within every limit reaches it,
 * so the two outcomes are told apart by whether data comes back.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step",
				"gatool.dev.experimental.dynamic-operations.max-depth=3",
				"gatool.dev.experimental.dynamic-operations.max-fields=8",
				"gatool.dev.experimental.dynamic-operations.max-aliases=2" })
class DynamicOperationLimitsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void executeGraphql_deeperThanMaxDepth_shouldRefuseNamingTheDepthAndTheLimit() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			// movies, edges, node and id sit at levels one to four, and a root field
			// counts as level one.
			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ movies { edges { node { id } } } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.startsWith("That document is larger than this server runs: it nests fields 4 levels deep, and the "
						+ "limit is 3.")
				.contains("Select fewer fields, nest less deeply, or split the question into two operations.");
		}
	}

	@Test
	void executeGraphql_moreFieldsThanMaxFields_shouldRefuseSayingTheWalkStoppedAtTheLimit() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			// Ten fields. The walk stops one past the limit, so the refusal says "more
			// than" the limit in place of the exact count.
			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ topRatedMovies { id title releaseYear genre "
							+ "rating communityRating directors { id name } } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).contains(
					"it selects more fields than the limit of 8, counting every " + "fragment where it is spread");
		}
	}

	@Test
	void executeGraphql_aFragmentSpreadTwice_shouldCountItsFieldsTwice() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();
			String card = " fragment Card on Movie { id title releaseYear genre }";

			// One spread is five fields and runs; the same fragment spread under two
			// aliased roots is ten and is refused, which is the count the API would
			// resolve.
			McpSchema.CallToolResult once = DynamicToolCalls.execute(client,
					"{ topRatedMovies(first: 1) { ...Card } }" + card);
			McpSchema.CallToolResult twice = DynamicToolCalls.execute(client,
					"{ a: topRatedMovies(first: 1) { ...Card } " + "b: topRatedMovies(first: 1) { ...Card } }" + card);

			assertThat(once.isError()).isFalse();
			assertThat(DynamicToolCalls.text(once)).contains("Signal from Kepler");
			assertThat(twice.isError()).isTrue();
			assertThat(DynamicToolCalls.text(twice)).contains("it selects more fields than the limit of 8");
		}
	}

	@Test
	void executeGraphql_moreAliasesThanMaxAliases_shouldRefuseNamingTheCountAndTheLimit() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ a: topRatedMovies { id } b: topRatedMovies { id } " + "c: topRatedMovies { id } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).contains("it uses 3 aliases, and the limit is 2");
		}
	}

	@Test
	void executeGraphql_aliasesAtTheLimit_shouldRun() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			// The limit is inclusive: two aliases under a limit of two run.
			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ a: topRatedMovies(first: 1) { title } " + "b: topRatedMovies(first: 1) { id } }");

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("\"a\"")
				.contains("\"b\"")
				.contains("Signal from Kepler");
		}
	}

	@Test
	void executeGraphql_withinEveryLimit_shouldReachTheApi() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ topRatedMovies(first: 1) { title } }");

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("\"data\"").contains("Signal from Kepler");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
