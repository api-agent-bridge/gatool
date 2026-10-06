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
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
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
 * What a client sees over the wire when the output schema is on.
 *
 * <p>
 * The other tests build the schema and validate the structured result inside the process.
 * This one drives a real MCP client against a real API, so the schema and the structured
 * content are the ones that travelled.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.results.publish-output-schema=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpOutputSchemaOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_outputSchemaOn_shouldCarryStructuredContentThatConforms() {
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools().tools().getFirst();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 2)).build());

			assertThat(tool.outputSchema()).isNotNull();
			assertThat(result.structuredContent()).isNotNull();
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
					result.structuredContent());
			assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
			// MCP asks a server returning structured content to send the same JSON as
			// text.
			assertThat(result.content()).isNotEmpty();
		}
	}

	private McpSyncClient mcpClient() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
