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

import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.internal.server.RevisionLimitedServerTransportProvider;
import io.gatool.boot.mcp.internal.server.RevisionLimitedStatelessTransport;
import io.gatool.boot.mcp.internal.server.RevisionLimitedStreamableServerTransportProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that declares its own transport bean sees Spring AI's server built on
 * that bean.
 *
 * <p>
 * Spring AI's server beans take the transport by type, and GATool publishes a primary
 * wrapper of each transport type that carries the revisions GATool serves. The wrapper
 * backs off for an application's own transport, so the context resolves the application's
 * bean by type. The SDK installs its handler on the transport it is built on, so a
 * recording stub shows which transport the server took.
 */
class McpApplicationTransportTests {

	private static final String[] PROPERTIES = { "spring.application.name=movies",
			"gatool.api.url=http://127.0.0.1:1/graphql",
			"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
			"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true" };

	private final WebApplicationContextRunner stateless = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS");

	private final WebApplicationContextRunner stateful = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStreamableHttpWebMvcAutoConfiguration.class, McpServerAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE");

	// Without a web server, the way the stdio defaults run an application.
	private final ApplicationContextRunner stdio = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true");

	@Test
	void applicationStatelessTransport_shouldBeTheTransportTheContextResolvesByType() {
		this.stateless.withUserConfiguration(ApplicationTransport.class).run((context) -> {
			assertThat(context).hasNotFailed();
			RecordingTransport own = context.getBean(RecordingTransport.class);

			McpStatelessServerTransport resolved = context.getBean(McpStatelessServerTransport.class);

			assertThat(resolved).as("the McpStatelessServerTransport the context resolves by type")
				.isNotInstanceOf(RevisionLimitedStatelessTransport.class)
				.isSameAs(own);
			assertThat(context).doesNotHaveBean(RevisionLimitedStatelessTransport.class);
		});
	}

	@Test
	void applicationStatelessTransport_shouldBeTheTransportTheServerIsBuiltOn() {
		this.stateless.withUserConfiguration(ApplicationTransport.class).run((context) -> {
			assertThat(context).hasNotFailed();
			RecordingTransport own = context.getBean(RecordingTransport.class);

			McpStatelessSyncServer server = context.getBean(McpStatelessSyncServer.class);

			assertThat(server).isNotNull();
			assertThat(own.handler.get()).as("the handler the SDK installs on the transport it built the server on")
				.isNotNull();
		});
	}

	@Test
	void applicationStatelessTransportMarkedPrimary_shouldStartAndBeTheOneTheContextResolves() {
		// The wrapper backs off, so an application that marks its transport @Primary
		// starts with one primary bean. A plain bean suffices, which the two tests above
		// show.
		this.stateless.withUserConfiguration(PrimaryApplicationTransport.class).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(McpStatelessServerTransport.class))
				.isSameAs(context.getBean(RecordingTransport.class));
		});
	}

	@Test
	void applicationStdioProvider_shouldBeTheOneProviderTheServerIsBuiltOn() {
		// Spring AI's server takes one McpServerTransportProviderBase by type, so
		// GATool's stdio provider backs off for an application's own, which leaves the
		// server a single candidate.
		this.stdio.withUserConfiguration(ApplicationStdioProvider.class).run((context) -> {
			assertThat(context).hasNotFailed();
			RecordingStdioProvider own = context.getBean(RecordingStdioProvider.class);

			McpSyncServer server = context.getBean(McpSyncServer.class);

			assertThat(server).isNotNull();
			assertThat(context.getBean(McpServerTransportProviderBase.class)).isSameAs(own);
			assertThat(own.sessionFactory.get())
				.as("the session factory the SDK installs on the provider it built the server on")
				.isNotNull();
			assertThat(context).doesNotHaveBean(RevisionLimitedServerTransportProvider.class);
		});
	}

	@Test
	void applicationStreamableProvider_shouldBeTheProviderTheServerIsBuiltOn() {
		this.stateful.withUserConfiguration(ApplicationProvider.class).run((context) -> {
			assertThat(context).hasNotFailed();
			RecordingProvider own = context.getBean(RecordingProvider.class);

			McpSyncServer server = context.getBean(McpSyncServer.class);

			assertThat(server).isNotNull();
			assertThat(context.getBean(McpStreamableServerTransportProvider.class))
				.isNotInstanceOf(RevisionLimitedStreamableServerTransportProvider.class)
				.isSameAs(own);
			assertThat(own.sessionFactory.get())
				.as("the session factory the SDK installs on the provider it built the server on")
				.isNotNull();
			assertThat(context).doesNotHaveBean(RevisionLimitedStreamableServerTransportProvider.class);
		});
	}

	/**
	 * A transport that records the handler the SDK installs on it.
	 */
	static final class RecordingTransport implements McpStatelessServerTransport {

		final AtomicReference<@Nullable McpStatelessServerHandler> handler = new AtomicReference<>();

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			this.handler.set(mcpHandler);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

	/**
	 * A provider that records the session factory the SDK installs on it.
	 */
	static final class RecordingProvider implements McpStreamableServerTransportProvider {

		final AtomicReference<McpStreamableServerSession.@Nullable Factory> sessionFactory = new AtomicReference<>();

		@Override
		public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
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

	@Configuration(proxyBeanMethods = false)
	static class ApplicationTransport {

		@Bean
		RecordingTransport applicationTransport() {
			return new RecordingTransport();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PrimaryApplicationTransport {

		@Bean
		@Primary
		RecordingTransport applicationTransport() {
			return new RecordingTransport();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationProvider {

		@Bean
		RecordingProvider applicationProvider() {
			return new RecordingProvider();
		}

	}

	/**
	 * A stdio-shaped provider that records the session factory the SDK installs on it.
	 */
	static final class RecordingStdioProvider implements McpServerTransportProvider {

		final AtomicReference<McpServerSession.@Nullable Factory> sessionFactory = new AtomicReference<>();

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

	@Configuration(proxyBeanMethods = false)
	static class ApplicationStdioProvider {

		@Bean
		RecordingStdioProvider applicationStdioProvider() {
			return new RecordingStdioProvider();
		}

	}

}
