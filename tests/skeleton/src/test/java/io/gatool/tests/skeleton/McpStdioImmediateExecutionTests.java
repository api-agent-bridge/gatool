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
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;
import io.gatool.boot.mcp.internal.server.ImmediateExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stdio server runs each tool call on the thread that reads stdin.
 *
 * <p>
 * The MCP SDK sends every answer through one queue that takes one answer at a time, and
 * an answer emitted beside another is dropped and closes the transport. Spring AI sets
 * {@code immediateExecution(true)} through a customizer bean that a servlet application
 * alone receives, so GATool contributes the same setting over stdio. The first test reads
 * the setting off the server bean. The second declares a customizer of an application's
 * own without the setting, which is the shape that gets past GATool's own bean, and reads
 * the stop.
 *
 * <p>
 * The context holds a transport provider of its own, so the SDK leaves the standard input
 * of this JVM alone.
 */
class McpStdioImmediateExecutionTests {

	private static final String[] PROPERTIES = { "spring.application.name=movies",
			"gatool.api.url=http://127.0.0.1:1/graphql",
			"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" };

	private final ApplicationContextRunner stdio = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
		.withUserConfiguration(OwnStdioProvider.class);

	// A servlet application that serves stdio, which the environment post-processor
	// leaves possible: it sets spring.main.web-application-type=none only while that
	// property is unset. Spring AI's own customizer applies here, and GATool's backs off.
	private final WebApplicationContextRunner servletStdio = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStreamableHttpWebMvcAutoConfiguration.class, McpServerAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
		.withUserConfiguration(OwnStdioProvider.class);

	@Test
	void mcpSyncServer_overStdioInAServletApplication_shouldTakeSpringAisCustomizerAlone() {
		this.servletStdio.run((context) -> {
			assertThat(context).hasNotFailed();

			McpSyncServer server = context.getBean(McpSyncServer.class);

			assertThat(ImmediateExecution.of(server))
				.as("immediateExecution on the server bean of a servlet application serving stdio")
				.isTrue();
			assertThat(context).hasSingleBean(McpSyncServerCustomizer.class);
		});
	}

	@Test
	void mcpSyncServer_overStdio_shouldBeBuiltWithImmediateExecution() {
		this.stdio.run((context) -> {
			assertThat(context).hasNotFailed();

			McpSyncServer server = context.getBean(McpSyncServer.class);

			assertThat(ImmediateExecution.of(server)).as("immediateExecution on the stdio server bean").isTrue();
		});
	}

	@Test
	void startup_overStdioUnderACustomizerWithoutImmediateExecution_shouldStopNamingTheQueueAndTheFix() {
		this.stdio.withUserConfiguration(OwnCustomizer.class).run((context) -> {
			assertThat(context).hasFailed();
			Throwable root = rootOf(context.getStartupFailure());
			assertThat(root).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining(
						"The MCP server bean 'mcpSyncServer' was built without " + "immediateExecution(true)")
				.hasMessageContaining("one queue that takes one answer at a time")
				.hasMessageContaining("the thread that reads stdin")
				.hasMessageContaining("'ownServerCustomizer', which the server took");
			assertThat(((McpSecurityException) root).action())
				.contains("Add immediateExecution(true) to that customizer")
				.contains("GATool's own customizer sets it");
		});
	}

	private static Throwable rootOf(@Nullable Throwable failure) {
		Throwable root = failure;
		while (root != null && root.getCause() != null) {
			root = root.getCause();
		}
		assertThat(root).isNotNull();
		return root;
	}

	/**
	 * A customizer of an application's own, which leaves immediate execution out.
	 */
	@Configuration(proxyBeanMethods = false)
	static class OwnCustomizer {

		@Bean
		McpSyncServerCustomizer ownServerCustomizer() {
			return (server) -> server.requestTimeout(Duration.ofSeconds(30));
		}

	}

	/**
	 * A provider that stands in for the SDK's stdio provider, which reads the standard
	 * input of the process it runs in.
	 */
	@Configuration(proxyBeanMethods = false)
	static class OwnStdioProvider {

		@Bean
		McpServerTransportProvider ownStdioProvider() {
			return new RecordingStdioProvider();
		}

	}

	private static final class RecordingStdioProvider implements McpServerTransportProvider {

		private final AtomicReference<McpServerSession.@Nullable Factory> sessionFactory = new AtomicReference<>();

		@Override
		public void setSessionFactory(McpServerSession.Factory sessionFactory) {
			this.sessionFactory.set(sessionFactory);
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
