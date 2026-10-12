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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit on a deployment that cannot ask: startup stops naming the tool and
 * the fix, the ignore property lets it start without asking, and the stdio property turns
 * the channel on.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpElicitStartupTests {

	private static final String[] PROPERTIES = { "gatool.api.url=http://127.0.0.1:1/graphql",
			"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
			"gatool.mcp.operations.locations=classpath:gatool/elicit/",
			"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
			"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true" };

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES);

	@Test
	void startup_statelessServerWithAnElicitTool_shouldStopNamingTheToolAndTheFix() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("topRatedMovies")
				.hasMessageContaining("the stateless Streamable HTTP transport")
				.hasMessageContaining("spring.ai.mcp.server.protocol=STREAMABLE")
				.hasMessageContaining("gatool.elicitation.ignore-where-unsupported=true");
		});
	}

	@Test
	void startup_statelessServerUnderTheIgnoreProperty_shouldStartAndWarn(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS",
					"gatool.elicitation.ignore-where-unsupported=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("gatool.elicitation.ignore-where-unsupported is true, so these tools run "
				+ "without asking the user: topRatedMovies");
		assertThat(output).contains("topRatedMovies from ").contains(", asks the user before each call");
	}

	@Test
	void startup_stdioWithoutTheStdioProperty_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("topRatedMovies")
					.hasMessageContaining("gatool.mcp.stdio.elicitation=true");
			});
	}

	@Test
	void startup_stdioWithTheStdioProperty_shouldDropTheReaderThreadCustomizerAndWarn(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.mcp.stdio.elicitation=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean("gaToolStdioMcpSyncServerCustomizer");
				assertThat(context).doesNotHaveBean("gaToolStdioImmediateExecutionStartupCheck");
			});

		assertThat(output).contains("stdio runs each tool call on the MCP SDK's thread pool")
			.contains("java-sdk issue 686");
	}

	@Test
	void startup_askingTransportUnderAShortRequestTimeout_shouldWarnNamingTheProperty(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.mcp.stdio.elicitation=true", "spring.ai.mcp.server.request-timeout=5s")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("1 tool asks the user before each call, and "
				+ "spring.ai.mcp.server.request-timeout gives the user 5 seconds to answer");
	}

	@Test
	void startup_askingTransportUnderTheDefaultRequestTimeout_shouldWarnWithTwentySeconds(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.mcp.stdio.elicitation=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("gives the user 20 seconds to answer");
	}

	@Test
	void startup_askingTransportUnderALongRequestTimeout_shouldStayQuiet(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.mcp.stdio.elicitation=true", "spring.ai.mcp.server.request-timeout=5m")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).doesNotContain("gives the user");
	}

	@Test
	void startup_stdioElicitationInAServletApplication_shouldStop() {
		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(PROPERTIES)
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.mcp.stdio.elicitation=true")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.mcp.stdio.elicitation")
					.hasMessageContaining("servlet application");
			});
	}

}
