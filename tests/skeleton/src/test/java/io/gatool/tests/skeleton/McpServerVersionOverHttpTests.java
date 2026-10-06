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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server introduces itself with the application's name and version.
 *
 * <p>
 * The name follows {@code spring.application.name} and the version follows
 * {@code spring.application.version}. Spring AI's own default is {@code 1.0.0}, a version
 * the application does not carry.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=movie-tools",
				"spring.application.version=2.3.4",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpServerVersionOverHttpTests {

	@LocalServerPort
	private int port;

	@Test
	void initialize_applicationVersionSet_shouldIntroduceTheServerWithIt() {
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.build()) {
			McpSchema.Implementation serverInfo = client.initialize().serverInfo();

			assertThat(serverInfo.name()).isEqualTo("movie-tools");
			assertThat(serverInfo.version()).isEqualTo("2.3.4");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
