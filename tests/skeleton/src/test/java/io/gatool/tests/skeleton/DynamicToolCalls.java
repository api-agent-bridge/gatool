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

/**
 * What every wire test of the dynamic tools needs: a client on the server's port, a call
 * request, and the text of a result.
 */
final class DynamicToolCalls {

	private DynamicToolCalls() {
	}

	static McpSyncClient client(int port) {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	// The SDK deprecates the two-argument constructor in favour of its builder.
	static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	static McpSchema.CallToolResult execute(McpSyncClient client, String document) {
		return client.callTool(call("executeGraphql", Map.of("document", document)));
	}

	static String text(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.reduce("", String::concat);
	}

	static McpSchema.Tool tool(McpSyncClient client, String name) {
		return client.listTools()
			.tools()
			.stream()
			.filter((tool) -> name.equals(tool.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no tool named " + name));
	}

}
