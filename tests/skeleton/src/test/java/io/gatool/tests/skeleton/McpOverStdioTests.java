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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same transport assertions over stdio.
 *
 * <p>
 * The server runs as a second JVM, launched with the test classpath, which is how a local
 * agent starts it. Two of the defects this suite guards against show on this transport: a
 * handshake that agrees to a revision the server refuses, and console logging writing
 * into the JSON-RPC stream.
 */
class McpOverStdioTests extends McpTransportTestBase {

	@Override
	McpSyncClient client() {
		ServerParameters parameters = ServerParameters.builder(javaCommand())
			.args("-cp", System.getProperty("java.class.path"), StdioServerApplication.class.getName(),
					"--spring.ai.mcp.server.stdio=true", "--spring.application.name=gatool-skeleton-tests",
					"--gatool.api.url=" + MoviesApiServer.graphQlUrl(),
					"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls")
			.build();
		return McpClient
			.sync(new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JsonMapper.builder().build())))
			.requestTimeout(Duration.ofSeconds(30))
			.build();
	}

	private static String javaCommand() {
		return System.getProperty("java.home") + "/bin/java";
	}

}
