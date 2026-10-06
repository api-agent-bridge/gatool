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
import java.util.LinkedHashMap;
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
 * Two tools whose output schemas share a {@code Map.hashCode()}, called one after the
 * other over MCP.
 *
 * <p>
 * The MCP Java SDK validates a successful result against the output schema the tool
 * publishes, with the validator it validates arguments with, and that validator keeps a
 * compiled schema under its {@code $id}, or under {@code Map.hashCode()} for a schema
 * without one. {@code FirstPick} answers under the alias {@code movie1} and
 * {@code ThirdPick} under {@code movie3}, and the two output schemas share a hash code
 * the way two variable names do on the input side. Keyed by the hash, the tool called
 * second would have its result validated against the schema of the tool called first: the
 * request runs, the API answers, and the caller reads an output validation error in place
 * of the answer.
 *
 * <p>
 * The SDK holds one validator for the whole JVM, and every test class of this module runs
 * in that JVM, so this test is written to give the same verdict whatever ran before it.
 * The two aliases appear in this test alone. The listing test beside the calling one
 * reads the schemas without validating against them, so the calls compile the two schemas
 * in the order written. The calling test asserts both answers, so whichever schema was
 * compiled first, a cache keyed by the hash fails the other, and it states the collision
 * before it relies on it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/colliding-outputs/",
				"gatool.results.publish-output-schema=true" })
class McpCollidingOutputSchemasOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_twoToolsWhoseOutputSchemasShareAHashCode_shouldEachReturnTheirOwnResult() {
		try (McpSyncClient client = client()) {
			client.initialize();
			Map<String, Object> firstSchema = withoutId(tool(client, "firstPick").outputSchema());
			Map<String, Object> thirdSchema = withoutId(tool(client, "thirdPick").outputSchema());
			assertThat(firstSchema).isNotEqualTo(thirdSchema);
			assertThat(firstSchema.hashCode()).isEqualTo(thirdSchema.hashCode());

			McpSchema.CallToolResult first = client.callTool(call("firstPick", Map.of("id", "movie-1")));
			McpSchema.CallToolResult third = client.callTool(call("thirdPick", Map.of("id", "movie-3")));

			assertThat(first.isError()).as(firstText(first)).isFalse();
			assertThat(first.structuredContent()).isEqualTo(Map.of("data", Map.of("movie1", Map.of("id", "movie-1"))));
			assertThat(third.isError()).as(firstText(third)).isFalse();
			assertThat(third.structuredContent()).isEqualTo(Map.of("data", Map.of("movie3", Map.of("id", "movie-3"))));
		}
	}

	@Test
	void listTools_twoToolsWhoseInputSchemasAreTheSameText_shouldShareOneId() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// Both operations declare $id: ID! alone, so the writer produces one text for
			// both, and one compiled validator is the right one for either tool.
			assertThat(tool(client, "firstPick").inputSchema().get("$id")).isNotNull()
				.isEqualTo(tool(client, "thirdPick").inputSchema().get("$id"));
			assertThat(tool(client, "firstPick").outputSchema().get("$id")).isNotNull()
				.isNotEqualTo(tool(client, "thirdPick").outputSchema().get("$id"));
		}
	}

	private static McpSchema.Tool tool(McpSyncClient client, String name) {
		return client.listTools().tools().stream().filter((tool) -> tool.name().equals(name)).findFirst().orElseThrow();
	}

	// The hash the SDK would fall back to is the one of the schema as the writer produced
	// it, so the comparison leaves out the $id that GATool adds on the way to the SDK.
	private static Map<String, Object> withoutId(Map<String, Object> schema) {
		Map<String, Object> written = new LinkedHashMap<>(schema);
		written.remove("$id");
		return written;
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
