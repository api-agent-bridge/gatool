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
 * {@code executeGraphql} with {@code allow-mutations} on, served by a real MCP server
 * against the real movie API, so a mutation the model wrote is applied.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step",
				"gatool.dev.experimental.dynamic-operations.allow-mutations=true" })
class DynamicMutationsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_mutationsOn_shouldPublishExecuteGraphqlAsWritingAndDestructive() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.Tool executeGraphql = DynamicToolCalls.tool(client, "executeGraphql");

			// The hints say what the tool can do, and with the switch on it can write,
			// so a client that asks a person before a destructive call asks here.
			assertThat(executeGraphql.description()).doesNotContain("Queries only");
			assertThat(executeGraphql.annotations().readOnlyHint()).isFalse();
			assertThat(executeGraphql.annotations().destructiveHint()).isTrue();
			assertThat(executeGraphql.annotations().idempotentHint()).isFalse();
			// The two reading tools keep their hints whatever the switch says.
			assertThat(DynamicToolCalls.tool(client, "searchSchema").annotations().readOnlyHint()).isTrue();
			assertThat(DynamicToolCalls.tool(client, "introspectType").annotations().readOnlyHint()).isTrue();
		}
	}

	@Test
	void executeGraphql_aMutation_shouldRunItAgainstTheApiAndLeaveTheReviewBehind() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult added = DynamicToolCalls
				.execute(client, "mutation Add { addReview(input: {movieId: \"movie-4\", "
						+ "score: 8, comment: \"Written by a model through executeGraphql.\"}) { review { score comment } } }");
			McpSchema.CallToolResult read = DynamicToolCalls.execute(client,
					"{ movie(by: {id: \"movie-4\"}) { reviews { comment } } }");

			assertThat(added.isError()).isFalse();
			assertThat(DynamicToolCalls.text(added)).contains("\"score\":8");
			assertThat(read.isError()).isFalse();
			assertThat(DynamicToolCalls.text(read)).contains("Written by a model through executeGraphql.");
		}
	}

	@Test
	void executeGraphql_aSubscriptionWithMutationsOn_shouldStillBeRefused() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"subscription Added { reviewAdded(movieId: \"movie-1\") { id } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.startsWith("That document is a subscription, and a tool call returns a single "
						+ "result, so only a query or a mutation runs here.");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
