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
 * A response that holds data and errors, as an agent meets it over MCP: a tool error
 * carrying both in the text, and the same envelope as structured content.
 *
 * <p>
 * The envelope conforms to the published output schema whether or not the response
 * carries errors, and MCP asks a server publishing an output schema for structured
 * results that conform to it. The error flag is what says the call carries errors. The
 * structured half saves a client re-parsing the text that field exists to replace.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.ai.mcp.server.protocol=STATELESS",
		"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
		"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
		"gatool.mcp.operations.locations=classpath:gatool/partial/", "gatool.results.publish-output-schema=true" })
class McpPartialResultsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_partialResult_shouldComeBackAsAToolErrorHoldingDataAndErrorsInBothHalves() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The third movie's community rating is missing, and the rating service
			// throws for it, so the API answers with data and one error.
			McpSchema.CallToolResult result = client.callTool(call("communityRating", Map.of("first", 3)));

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("\"data\"").contains("\"errors\"").contains("Signal from Kepler");
			// The SDK types this member as Object, so the map assertion needs the cast.
			@SuppressWarnings("unchecked")
			Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
			assertThat(structured).containsKeys("data", "errors");
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
