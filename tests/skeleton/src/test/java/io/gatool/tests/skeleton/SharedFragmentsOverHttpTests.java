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
 * An operation file that spreads a fragment kept in a file of its own, served by a real
 * MCP server and answered by the real movie API.
 *
 * <p>
 * {@code gatool/shared-fragments/} holds {@code TopRatedCards.graphql}, which spreads
 * {@code MovieCard}, and {@code fragments/MovieCard.graphql}, which defines it. The
 * fragment file holds fragments alone, so it becomes a shared fragment file for that
 * location and stays out of the tool list. {@link SharedFragmentsTests} reads the
 * document that reaches the API, and {@link SharedFragmentsStartupTests} covers the files
 * that stop startup.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/shared-fragments/",
				"gatool.results.publish-output-schema=true" })
class SharedFragmentsOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_locationHoldingAnOperationAndAFragmentFile_shouldServeTheOperationAlone() {
		try (McpSyncClient client = client()) {
			client.initialize();

			List<McpSchema.Tool> tools = client.listTools().tools();

			assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("topRatedCards");
			// The output schema is written from the assembled document, so the
			// fragment's fields are in it.
			assertThat(tools.getFirst().outputSchema()).isNotNull();
			assertThat(String.valueOf(tools.getFirst().outputSchema())).contains("rating").contains("title");
		}
	}

	@Test
	void callTool_operationSpreadingASharedFragment_shouldReturnTheFragmentsFields() {
		try (McpSyncClient client = client()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools().tools().getFirst();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedCards").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).isEqualTo("{\"data\":{\"topRatedMovies\":[{\"id\":\"movie-1\","
					+ "\"title\":\"Signal from Kepler\",\"rating\":8.6}]}}");
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
					result.structuredContent());
			assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
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

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
