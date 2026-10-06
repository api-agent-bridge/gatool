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

package io.gatool.tests.consumer.mcp;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that adds the MCP starter alone.
 *
 * <p>
 * The classpath here is the one a user gets from that single dependency, so a starter
 * that stops declaring spring-graphql, the REST client or the rate limiter fails here,
 * before a user's application meets it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=gatool-consumer-mcp",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:greetings.graphqls" })
class McpStarterConsumerTests {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	@Test
	void listTools_theMcpStarterAlone_shouldServeTheOperationFile() {
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build()) {
			client.initialize();

			McpSchema.ListToolsResult tools = client.listTools();

			assertThat(tools.tools()).singleElement().satisfies((tool) -> {
				assertThat(tool.name()).isEqualTo("greeting");
				assertThat(tool.description()).isEqualTo("Greets someone by name.");
				assertThat(tool.inputSchema()).containsKey("properties");
			});
		}
	}

	@Test
	void callTool_theMcpStarterAlone_shouldReachTheRateLimiterAndTheExecutor() {
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("greeting").arguments(Map.of("name", "Ada")).build());

			// The API is a closed port, so the call travels the whole path and comes back
			// as a tool error. Everything it needed on the way is on this classpath.
			assertThat(result.isError()).isTrue();
			assertThat(result.content()).isNotEmpty();
		}
	}

	@Test
	void context_theMcpStarterAlone_shouldLeaveTheInProcessHandleOut() {
		assertThat(this.context.getBeanNamesForType(Object.class)).noneMatch((name) -> name.equals("gaToolCallbacks"));
	}

	@SpringBootApplication
	static class ConsumerApplication {

	}

}
