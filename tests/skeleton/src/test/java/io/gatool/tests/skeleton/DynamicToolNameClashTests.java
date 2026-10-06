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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.OperationFileProblemsException;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operation file whose tool takes the name of one of the three dynamic tools stops
 * startup on either side, naming the file and the property that publishes the dynamic
 * tools.
 */
class DynamicToolNameClashTests {

	private static final String API_URL = "gatool.api.url=http://127.0.0.1:1/graphql";

	private static final String MOVIES_SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String DYNAMIC = "gatool.dev.experimental.generate-tools=dynamic-three-step";

	// An anonymous operation is named after its file, so this file's tool is
	// searchSchema.
	private static final String SEARCH_SCHEMA = "# The application's own search.\n{ topRatedMovies { title } }\n";

	@Test
	void startup_mcpOperationFileNamedLikeADynamicTool_shouldStopNamingTheFileAndTheProperty(@TempDir Path folder)
			throws Exception {
		// GATool is the source of both tools, so the message names the file as well as
		// the name that clashed.
		Files.writeString(folder.resolve("searchSchema.graphql"), SEARCH_SCHEMA);

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
					GAToolAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, MOVIES_SCHEMA, DYNAMIC, "spring.ai.mcp.server.protocol=STATELESS",
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(OperationFileProblemsException.class)
					.hasMessageContaining("searchSchema.graphql")
					.hasMessageContaining("gatool.dev.experimental.generate-tools");
			});
	}

	@Test
	void startup_inProcessOperationFileNamedLikeADynamicTool_shouldStopInsteadOfServingTwoTools(@TempDir Path folder)
			throws Exception {
		// A ChatClient holds one tool per name, so the in-process side checks the names
		// as well.
		Files.writeString(folder.resolve("searchSchema.graphql"), SEARCH_SCHEMA);

		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
							GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
			.withPropertyValues(API_URL, MOVIES_SCHEMA, DYNAMIC,
					"gatool.in-process.operations.locations=file:" + folder.toAbsolutePath() + "/")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(OperationFileProblemsException.class)
					.hasMessageContaining("searchSchema.graphql")
					.hasMessageContaining("gatool.dev.experimental.generate-tools");
			});
	}

}
