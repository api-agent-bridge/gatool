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

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.SecurityFilterChain;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A servlet application that serves MCP over stdio, with Spring Security on the classpath
 * for pages of its own, starts without an issuer.
 *
 * <p>
 * Over stdio the MCP endpoint is absent from the HTTP port, so GATool's two contributed
 * chains would guard a path the application does not serve, and the MCP chain would stop
 * startup on the missing issuer of that endpoint. GATool leaves both chains out, and
 * Spring Boot's own default chain secures the application's pages.
 *
 * <p>
 * The context holds a transport provider of its own, so the SDK leaves the standard input
 * of this JVM alone.
 */
class McpStdioServletSecurityTests {

	private final WebApplicationContextRunner servletStdio = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStreamableHttpWebMvcAutoConfiguration.class, McpServerAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class, GAToolMcpSecurityAutoConfiguration.class,
				OAuth2ResourceServerAutoConfiguration.class, OAuth2ResourceServerWebSecurityAutoConfiguration.class,
				SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
		.withPropertyValues("spring.application.name=movies", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
		.withUserConfiguration(StandInStdioProviderConfiguration.class);

	@Test
	void startup_overStdioInAServletApplicationWithoutAnIssuer_shouldLeaveTheDefaultChainToSpringBoot() {
		this.servletStdio.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
				.containsExactly("defaultSecurityFilterChain");
		});
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
			// Nothing opens a session here, so the factory goes unused.
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
