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

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;
import io.gatool.boot.mcp.internal.server.ImmediateExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit on a deployment that cannot ask: startup fails and names the tool
 * and the fix, and the ignore property lets it start and run without asking. The stdio
 * property turns the channel on, and the startup check then requires the stdio server to
 * run each tool call off the thread that reads stdin, where the user's answer arrives.
 *
 * <p>
 * The tests of that check run with Spring AI's server bean, so the check reads the
 * setting off a real server. A provider stands in for the SDK's stdio provider, which
 * reads the standard input of the process it runs in.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpElicitStartupTests {

	private static final String[] PROPERTIES = { "gatool.api.url=http://127.0.0.1:1/graphql",
			"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
			"gatool.mcp.operations.locations=classpath:gatool/elicit/",
			"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
			"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true" };

	private static final ResolvableType STATELESS_TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpStatelessServerFeatures.SyncToolSpecification.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES);

	private final ApplicationContextRunner stdioServer = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.application.name=movies", "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.stdio=true")
		.withUserConfiguration(StandInStdioProviderConfiguration.class);

	// A servlet application that serves stdio, as one that sets
	// spring.main.web-application-type=servlet beside spring.ai.mcp.server.stdio=true
	// does.
	private final WebApplicationContextRunner servletStdioServer = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true");

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
	void callTool_statelessServerUnderTheIgnoreProperty_shouldRunTheOperationWithoutAsking() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS",
				"gatool.elicitation.ignore-where-unsupported=true", "gatool.api.url=" + MoviesApiServer.graphQlUrl())
			.run((context) -> {
				int before = MoviesApiServer.graphQlCalls();

				McpSchema.CallToolResult result = statelessSpecification(context, "topRatedMovies").callHandler()
					.apply(McpTransportContext.EMPTY,
							McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

				assertThat(result.isError()).as(((McpSchema.TextContent) result.content().getFirst()).text()).isFalse();
				assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before + 1);
			});
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
	void startup_stdioWithoutTheStdioPropertyUnderTheIgnoreProperty_shouldStartAndWarnNamingTheTool(
			CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.elicitation.ignore-where-unsupported=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("gatool.elicitation.ignore-where-unsupported is true, so these tools run "
				+ "without asking the user: topRatedMovies." + System.lineSeparator());
	}

	@Test
	void startup_stdioElicitationSetToFalse_shouldKeepTheReaderThreadCustomizerAndTheCheck() {
		// The word false is what the customizer's condition compares with, so setting it
		// keeps the customizer, as leaving the property unset does. The ignore property
		// lets the elicit tool past the stop for stdio without a channel.
		this.stdioServer
			.withPropertyValues("gatool.mcp.stdio.elicitation=false",
					"gatool.elicitation.ignore-where-unsupported=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasBean("gaToolStdioMcpSyncServerCustomizer");
				assertThat(context).hasBean("gaToolStdioImmediateExecutionStartupCheck");
				assertThat(ImmediateExecution.of(context.getBean(McpSyncServer.class)))
					.as("immediateExecution on the stdio server bean while gatool.mcp.stdio.elicitation is false")
					.isTrue();
			});
	}

	@Test
	void startup_stdioWithTheStdioProperty_shouldDropTheReaderThreadCustomizerKeepTheCheckAndWarn(
			CapturedOutput output) {
		this.stdioServer.withPropertyValues("gatool.mcp.stdio.elicitation=true").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("gaToolStdioMcpSyncServerCustomizer");
			assertThat(context).hasBean("gaToolStdioImmediateExecutionStartupCheck");
			assertThat(ImmediateExecution.of(context.getBean(McpSyncServer.class)))
				.as("immediateExecution on the stdio server bean while a tool can ask the user")
				.isFalse();
		});

		assertThat(output).contains("stdio runs each tool call on the MCP SDK's thread pool")
			.contains("java-sdk issue 686");
	}

	@Test
	void startup_stdioElicitationWithAnApplicationCustomizerThatSetsImmediateExecution_shouldStop() {
		this.stdioServer.withPropertyValues("gatool.mcp.stdio.elicitation=true")
			.withUserConfiguration(ImmediateExecutionCustomizer.class)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(stopOf(context)).isInstanceOf(McpSecurityException.class)
					.hasMessageContaining("gatool.mcp.stdio.elicitation is true")
					.hasMessageContaining("The MCP server bean 'mcpSyncServer' was built with immediateExecution(true)")
					.hasMessageContaining("'immediateServerCustomizer', which the server took")
					.isInstanceOfSatisfying(McpSecurityException.class,
							(stop) -> assertThat(stop.action())
								.contains("Remove immediateExecution(true) from that customizer")
								.contains("gatool.mcp.stdio.elicitation"));
			});
	}

	@Test
	void startup_stdioElicitationSpelledOff_shouldStopOnTheMissingCustomizer(CapturedOutput output) {
		// Boot binds off as false, while the customizer's condition compares the word
		// itself with false and leaves the bean out. The ignore property lets the elicit
		// tool past the stop for stdio without a channel, so the stop below is the
		// check's.
		this.stdioServer
			.withPropertyValues("gatool.mcp.stdio.elicitation=off", "gatool.elicitation.ignore-where-unsupported=true")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(stopOf(context)).isInstanceOf(McpSecurityException.class)
					.hasMessageContaining(
							"The MCP server bean 'mcpSyncServer' was built without immediateExecution(true)")
					.hasMessageContaining("gatool.mcp.stdio.elicitation holds another word that binds false, such as "
							+ "off: GATool registers its own customizer only while that property is unset or is the "
							+ "word false.")
					.isInstanceOfSatisfying(McpSecurityException.class, (stop) -> assertThat(stop.action())
						.endsWith("Where gatool.mcp.stdio.elicitation holds another word for false, write false."));
			});

		// The warning is written where the bound value is false, so the server runs
		// every tool without asking.
		assertThat(output).contains("these tools run without asking the user: topRatedMovies");
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
	void startup_statefulHttpUnderAShortRequestTimeout_shouldWarnNamingTheProperty(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.request-timeout=5s")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("GATool serves MCP over stateful Streamable HTTP")
			.contains("1 tool asks the user before each call, and spring.ai.mcp.server.request-timeout gives the "
					+ "user 5 seconds to answer; set it to the time a person needs, such as 5m.");
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
	void startup_askingTransportUnderARequestTimeoutOfSixtySeconds_shouldStayQuiet(CapturedOutput output) {
		// The warning fires below 60 seconds, so 60 seconds itself stays quiet.
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.request-timeout=60s")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).contains("GATool serves MCP over stateful Streamable HTTP").doesNotContain("gives the user");
	}

	@Test
	void startup_stdioElicitationInAServletApplication_shouldStop() {
		this.servletStdioServer.withPropertyValues("gatool.mcp.stdio.elicitation=true").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.stdio.elicitation")
				.hasMessageContaining("servlet application");
		});
	}

	@Test
	void startup_stdioWithoutTheStdioPropertyInAServletApplication_shouldLeadWithServingStdioWithoutAWebServer() {
		// Setting the property alone would move this application to the stop above, so
		// the fix names the web application type first.
		this.servletStdioServer.run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("1 tool asks the user before each call (topRatedMovies), and this deployment "
						+ "runs on stdio while gatool.mcp.stdio.elicitation is false, which cannot ask. Serve stdio "
						+ "from an application without a web server, with spring.main.web-application-type=none, and "
						+ "set gatool.mcp.stdio.elicitation=true, or set gatool.elicitation.ignore-where-unsupported=true "
						+ "to serve these tools without asking.");
		});
	}

	// The check runs as a SmartInitializingSingleton, whose exception reaches the
	// context unwrapped, so the stop is the most specific cause of the failure.
	private static Throwable stopOf(AssertableApplicationContext context) {
		Throwable failure = context.getStartupFailure();
		assertThat(failure).isNotNull();
		return NestedExceptionUtils.getMostSpecificCause(failure);
	}

	@SuppressWarnings("unchecked")
	private static McpStatelessServerFeatures.SyncToolSpecification statelessSpecification(
			AssertableApplicationContext context, String tool) {
		List<McpStatelessServerFeatures.SyncToolSpecification> specifications = (List<McpStatelessServerFeatures.SyncToolSpecification>) context
			.getBeanProvider(STATELESS_TOOL_SPECIFICATIONS)
			.getObject();
		return specifications.stream()
			.filter((candidate) -> candidate.tool().name().equals(tool))
			.findFirst()
			.orElseThrow();
	}

	/**
	 * A customizer of an application's own that runs each tool call on the thread that
	 * reads stdin.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ImmediateExecutionCustomizer {

		@Bean
		McpSyncServerCustomizer immediateServerCustomizer() {
			return (server) -> server.immediateExecution(true);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class StandInStdioProviderConfiguration {

		@Bean
		McpServerTransportProvider standInStdioProvider() {
			return new StandInStdioProvider();
		}

	}

	/**
	 * A provider that stands in for the SDK's stdio provider, which reads the standard
	 * input of the process it runs in.
	 */
	private static final class StandInStdioProvider implements McpServerTransportProvider {

		@Override
		public void setSessionFactory(McpServerSession.Factory sessionFactory) {
			// These tests start the server and stop, so the factory stays unused.
		}

		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
