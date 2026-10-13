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
import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Launches {@link StdioServerApplication} as a second JVM with the test classpath, which
 * is how a local agent starts a stdio server, and speaks to it over the standard input
 * and output of that process.
 */
final class StdioServerLauncher {

	private StdioServerLauncher() {
	}

	/**
	 * Returns a client for a stdio server that calls the movies API this JVM serves.
	 * @param arguments the arguments the server takes beyond stdio, the application name,
	 * the API URL and the schema, such as the operation locations
	 * @return the client's builder with a 30-second request timeout, ready for a test to
	 * add its capabilities
	 */
	static McpClient.SyncSpec client(String... arguments) {
		List<String> command = new ArrayList<>(List.of("-cp", System.getProperty("java.class.path"),
				StdioServerApplication.class.getName(), "--spring.ai.mcp.server.stdio=true",
				"--spring.application.name=gatool-skeleton-tests", "--gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls"));
		command.addAll(List.of(arguments));
		ServerParameters parameters = ServerParameters.builder(System.getProperty("java.home") + "/bin/java")
			.args(command)
			.build();
		return McpClient
			.sync(new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JsonMapper.builder().build())))
			.requestTimeout(Duration.ofSeconds(30));
	}

}
