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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpJsonMapperAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper every Spring AI MCP transport reads and writes with: GATool's bean keeps a
 * null argument, writes the protocol's own types the way Spring AI's bean does, and
 * yields to a bean the application declares.
 */
class McpJsonMapperTests {

	private static final String BEAN = "mcpServerJsonMapper";

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"moviesOfAGenre\",\"arguments\":{\"filter\":null}}}";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolMcpJsonMapperAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void mcpServerJsonMapper_readingACallWithANullArgument_shouldKeepTheNull() {
		// The transports read a body with the SDK's own entry point, which parses the
		// text and converts the parsed map into the request record with this mapper.
		this.contextRunner.run((context) -> {
			JsonMapper mapper = context.getBean(BEAN, JsonMapper.class);

			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) McpSchema
				.deserializeJsonRpcMessage(new JacksonMcpJsonMapper(mapper), CALL);
			McpSchema.CallToolRequest call = mapper.convertValue(request.params(), McpSchema.CallToolRequest.class);

			assertThat(call.arguments()).containsKey("filter");
			assertThat(call.arguments().get("filter")).isNull();
		});
	}

	@Test
	void mcpServerJsonMapper_springAisOwnBean_shouldDropTheNull() {
		// The behaviour GATool's bean replaces, read from Spring AI's definition, so the
		// difference the test above relies on is the one this run measures.
		JsonMapper springAiMapper = new McpServerJsonMapperAutoConfiguration().mcpServerJsonMapper();

		McpSchema.CallToolRequest call = springAiMapper.convertValue(
				Map.of("name", "moviesOfAGenre", "arguments", Collections.singletonMap("filter", null)),
				McpSchema.CallToolRequest.class);

		assertThat(call.arguments()).doesNotContainKey("filter");
	}

	@Test
	void mcpServerJsonMapper_writingTheProtocolsOwnTypes_shouldMatchSpringAisBean() {
		// The SDK's records carry their own inclusion rule, so a tool without a title
		// and a result without structured content write the same with either mapper,
		// and the wire an MCP client reads is unchanged.
		JsonMapper springAiMapper = new McpServerJsonMapperAutoConfiguration().mcpServerJsonMapper();
		McpSchema.Tool tool = McpSchema.Tool.builder("topRatedMovies", Map.of("type", "object"))
			.description("The top movies.")
			.build();
		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
			.addTextContent("{\"data\":{\"movies\":null}}")
			.isError(false)
			.build();

		this.contextRunner.run((context) -> {
			JsonMapper mapper = context.getBean(BEAN, JsonMapper.class);

			assertThat(mapper.writeValueAsString(tool)).isEqualTo(springAiMapper.writeValueAsString(tool));
			assertThat(mapper.writeValueAsString(result)).isEqualTo(springAiMapper.writeValueAsString(result));
			assertThat(mapper.writeValueAsString(List.of(tool))).doesNotContain("null");
		});
	}

	@Test
	void mcpServerJsonMapper_applicationsOwnBean_shouldWin() {
		JsonMapper applicationMapper = JsonMapper.builder().build();

		this.contextRunner.withBean(BEAN, JsonMapper.class, () -> applicationMapper)
			.run((context) -> assertThat(context.getBean(BEAN, JsonMapper.class)).isSameAs(applicationMapper));
	}

	@Test
	void mcpServerJsonMapper_withoutGATool_shouldStaySpringAis() {
		// The bean steps in for an application that runs GATool, and leaves a Spring AI
		// server without GATool exactly as Spring AI configured it.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GAToolMcpJsonMapperAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class))
			.run((context) -> {
				JsonMapper mapper = context.getBean(BEAN, JsonMapper.class);

				assertThat(mapper.writeValueAsString(Collections.singletonMap("probe", null))).isEqualTo("{}");
			});
	}

}
