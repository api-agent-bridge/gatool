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
 * Two tools whose input schemas share a {@code Map.hashCode()}, called one after the
 * other over MCP.
 *
 * <p>
 * The MCP Java SDK compiles a schema on its first use and keeps the compiled form under
 * the schema's {@code $id}, or under {@code Map.hashCode()} for a schema without one.
 * {@code MovieTitle} declares {@code $nodeId} and {@code MovieYear} declares
 * {@code $nodeID}. The hash codes of the two names differ by 32, each name sits once
 * under {@code properties} and once under {@code required}, and the two differences
 * cancel in the sum a map makes of its entries. Keyed by that sum, the tool called second
 * would be validated against the schema of the tool called first, and its every correct
 * call refused until the JVM restarts.
 *
 * <p>
 * The SDK holds one validator for the whole JVM, and every test class of this module runs
 * in that JVM, so this test is written to give the same verdict whatever ran before it.
 * The two variable names appear in this test alone, so the hash the pair shares belongs
 * to the pair. One method makes every call, so the calls run in the order written, and it
 * asserts both answers, so whichever schema was compiled first, a cache keyed by the hash
 * fails the other. The method also states the collision before it relies on it, so a
 * change to the writer that moves the two hashes apart fails here by name.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/colliding-inputs/" })
class McpCollidingInputSchemasOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_twoToolsWhoseInputSchemasShareAHashCode_shouldEachValidateAgainstTheirOwnSchema() {
		try (McpSyncClient client = client()) {
			client.initialize();
			Map<String, Object> titleSchema = withoutId(tool(client, "movieTitle").inputSchema());
			Map<String, Object> yearSchema = withoutId(tool(client, "movieYear").inputSchema());
			assertThat(titleSchema).isNotEqualTo(yearSchema);
			assertThat(titleSchema.hashCode()).isEqualTo(yearSchema.hashCode());

			McpSchema.CallToolResult title = client.callTool(call("movieTitle", Map.of("nodeId", "movie-1")));
			McpSchema.CallToolResult year = client.callTool(call("movieYear", Map.of("nodeID", "movie-1")));
			McpSchema.CallToolResult misspelt = client.callTool(call("movieYear", Map.of("nodeId", "movie-1")));

			assertThat(title.isError()).as(firstText(title)).isFalse();
			assertThat(firstText(title)).contains("Signal from Kepler");
			assertThat(year.isError()).as(firstText(year)).isFalse();
			assertThat(firstText(year)).contains("2021");
			// Each schema is closed, so the spelling the other tool takes is an argument
			// this tool leaves unnamed, and the one it requires is missing.
			assertThat(misspelt.isError()).isTrue();
			assertThat(firstText(misspelt)).contains("movieYear", "input validation failed", "nodeID");
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
