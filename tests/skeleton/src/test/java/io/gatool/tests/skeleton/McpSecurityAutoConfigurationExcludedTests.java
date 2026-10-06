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
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;
import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;

/**
 * What an application gets when it excludes GATool's MCP security auto-configuration and
 * keeps Spring Security on the classpath.
 *
 * <p>
 * The per-tool scope check lives in {@code McpCallAuthorizationManager}, which only
 * {@link McpServerSecurityConfigurer} installs. Excluding the auto-configuration removes
 * the one place that applies that configurer, and Spring Boot's own resource server chain
 * then serves the endpoint under a rule that asks for a valid token alone. A token with
 * an empty scope claim could then call a tool declaring {@code movies:read} and read its
 * data, so startup stops.
 *
 * <p>
 * Applying the configurer in a chain of the application's own is the supported way to
 * take the endpoint's security over, so the second test checks that such an application
 * starts.
 *
 * <p>
 * An application that has also left Spring Security's web layer unwired, with Boot's web
 * security auto-configurations excluded and without an {@code @EnableWebSecurity} of its
 * own, runs without a {@code springSecurityFilterChain} bean. Such an endpoint would
 * answer every caller with the unsafe switch off, so the check stops startup on the
 * absent bean. The last two tests pin the stop for that shape and the switch that keeps
 * it quiet.
 */
class McpSecurityAutoConfigurationExcludedTests {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				OAuth2ResourceServerAutoConfiguration.class, OAuth2ResourceServerWebSecurityAutoConfiguration.class,
				SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
				SecurityFilterAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"gatool.mcp.security.baseline-scopes=mcp:tools",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton");

	@Test
	void startup_securityAutoConfigurationExcluded_shouldStopNamingTheCheckThatIsGone() {
		// GAToolMcpSecurityAutoConfiguration is left out of the runner's list, which is
		// what spring.autoconfigure.exclude does to an application.
		this.contextRunner.withPropertyValues(issuer()).run((context) -> {
			assertThat(context).hasFailed();
			Throwable root = rootOf(context.getStartupFailure());
			assertThat(root).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("lacks the scope check GATool installs")
				.hasMessageContaining("including the tools an operation file gives scopes to");
			assertThat(((McpSecurityException) root).action())
				.contains("GAToolMcpSecurityAutoConfiguration is excluded")
				.contains("McpServerSecurityConfigurer");
		});
	}

	@Test
	void startup_excludedButTheConfigurerAppliedByTheApplication_shouldStart() {
		// The supported way to own the endpoint's security. The stop above must leave
		// this application alone, because the scope check is in its chain.
		this.contextRunner.withPropertyValues(issuer())
			.withUserConfiguration(OwnChain.class)
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_securityOffOnPurpose_shouldStartWithTheCheckAbsent() {
		// The switch already says the endpoint runs without authentication, so the scope
		// check does not have anything to decide and the stop stays out of the way.
		this.contextRunner
			.withPropertyValues(issuer(), "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_excludedAndTheSecurityFilterAbsent_shouldStopNamingTheMissingChain() {
		// Spring Security is on the classpath, so the classpath guard stands down, and
		// the filter bean is absent, which leaves the check without a chain to inspect.
		// That is the endpoint open to every caller, and the stop has to say which bean
		// is missing.
		withoutWebSecurity().withPropertyValues(issuer()).run((context) -> {
			assertThat(context).hasFailed();
			Throwable root = rootOf(context.getStartupFailure());
			assertThat(root).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("springSecurityFilterChain")
				.hasMessageContaining("without a security filter chain");
			assertThat(((McpSecurityException) root).action()).contains("McpServerSecurityConfigurer")
				.contains("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication");
		});
	}

	@Test
	void startup_excludedAndTheSecurityFilterAbsentOnPurpose_shouldStartUnderTheSwitch() {
		// The same application with the switch on has said the endpoint runs without
		// authentication, and the absent filter is then its own choice.
		withoutWebSecurity()
			.withPropertyValues(issuer(), "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	/**
	 * The runner above without Boot's web security auto-configurations, which is the
	 * application that excludes them without an {@code @EnableWebSecurity} of its own, so
	 * that the {@code springSecurityFilterChain} bean is absent.
	 */
	private static WebApplicationContextRunner withoutWebSecurity() {
		return new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, McpServerStatelessWebMvcAutoConfiguration.class,
					GAToolMcpAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class))
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"gatool.mcp.operations.locations=classpath:gatool/scoped/",
					"gatool.mcp.security.baseline-scopes=mcp:tools",
					"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton");
	}

	private static String issuer() {
		return "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + LocalIssuer.get().url();
	}

	private static Throwable rootOf(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root;
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnChain {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain mcpChain(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), withDefaults())
				.build();
		}

	}

}
