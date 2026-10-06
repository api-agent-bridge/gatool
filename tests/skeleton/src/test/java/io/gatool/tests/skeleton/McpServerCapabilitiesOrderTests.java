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

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the advertised capabilities reach Spring AI's properties before Spring AI
 * builds a server from them, however that server comes to be built.
 *
 * <p>
 * An application's own bean definitions are registered ahead of every auto-configuration,
 * so a bean that holds the server pulls it into existence first. What that bean reads off
 * the server is what a client reads in the answer to {@code initialize}. Injecting the
 * server is an ordinary thing for an application to do, because the type publishes
 * {@code addTool}, {@code removeTool} and {@code getServerCapabilities}.
 *
 * <p>
 * The server name is a default that {@code GAToolEnvironmentPostProcessor} contributes,
 * which a context runner leaves out, so {@code McpProtocolRevisionTests} covers it over
 * the wire.
 */
class McpServerCapabilitiesOrderTests {

	private static final String CAPABILITIES_MARKER = "gaToolServerCapabilitiesMarker";

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=movies",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void anApplicationBeanHoldingTheServer_shouldReadTheCapabilitiesGAToolSettled() {
		this.contextRunner.withUserConfiguration(ServerHolder.class).run((context) -> {
			assertThat(context).hasNotFailed();
			WhatTheServerAdvertised advertised = context.getBean(WhatTheServerAdvertised.class);
			assertThat(advertised.resources()).isFalse();
			assertThat(advertised.prompts()).isFalse();
			assertThat(advertised.completions()).isFalse();
		});
	}

	@Test
	void lazyInitialization_shouldBuildTheServerFromTheCapabilitiesGAToolSettled() {
		// Under lazy initialization a bean is created when something first asks for it,
		// so without the order the first caller would build the server ahead of the
		// marker bean.
		this.contextRunner
			.withInitializer(
					(context) -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
			.run((context) -> {
				McpStatelessSyncServer server = context.getBean(McpStatelessSyncServer.class);
				assertThat(server.getServerCapabilities().resources()).isNull();
				assertThat(server.getServerCapabilities().prompts()).isNull();
				assertThat(server.getServerCapabilities().completions()).isNull();
			});
	}

	@Test
	void serverBeanDefinition_shouldDependOnTheCapabilitiesMarker() {
		// The order is written into the definition, so it holds however the server comes
		// to be built. Boot's post-processor finds the definition by its type, so the
		// entry is there under Spring AI's bean name without that name being listed.
		this.contextRunner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanFactory().getBeanDefinition("mcpStatelessSyncServer").getDependsOn())
				.contains(CAPABILITIES_MARKER);
		});
	}

	/**
	 * What one application bean read off the server it was given.
	 */
	record WhatTheServerAdvertised(boolean resources, boolean prompts, boolean completions) {

	}

	/**
	 * An application's own configuration, holding the server the way an application that
	 * registers a tool at runtime or logs the advertised capabilities holds it.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ServerHolder {

		@Bean
		WhatTheServerAdvertised whatTheServerAdvertised(McpStatelessSyncServer server) {
			return new WhatTheServerAdvertised(server.getServerCapabilities().resources() != null,
					server.getServerCapabilities().prompts() != null,
					server.getServerCapabilities().completions() != null);
		}

	}

}
