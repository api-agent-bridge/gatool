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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;
import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;
import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two checks that read the live chains once every singleton exists: the metadata
 * document has to answer an anonymous GET, and the compliance filter has to run ahead of
 * Spring Security.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpSecurityChainChecksTests {

	private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1";

	private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences=GATool";

	private static final String SCOPES = "gatool.mcp.security.baseline-scopes=mcp:tools";

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
				OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls", ISSUER, AUDIENCES,
				SCOPES);

	@Test
	void startup_endpointChainLeavingTheMetadataToACatchAllChain_shouldStopNamingTheMetadataPath() {
		// The endpoint's chain covers /mcp alone, so the metadata path falls to the
		// catch-all chain, which asks an MCP client for a password before it has a token.
		this.contextRunner.withUserConfiguration(EndpointChainWithoutTheMetadataPath.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("/.well-known/oauth-protected-resource/mcp")
				.hasMessageContaining("another chain");
			assertThat(((McpSecurityException) context.getStartupFailure()).action())
				.contains("securityMatcher(\"/mcp\", \"/.well-known/oauth-protected-resource/mcp\")");
		});
	}

	@Test
	void startup_endpointChainCoveringBothPaths_shouldStart() {
		this.contextRunner.withUserConfiguration(EndpointChainWithBothPaths.class)
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_complianceFilterRegisteredBehindSpringSecurity_shouldStopNamingBothOrders() {
		// An application that declares the registration itself, at an order that runs
		// after Spring Security, would answer a foreign origin with a 401.
		this.contextRunner.withUserConfiguration(ComplianceFilterBehindSecurity.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("registered at order 0")
				.hasMessageContaining("Spring Security's filter at -100");
		});
	}

	@Test
	void startup_applicationChainWithItsOwnResourceServerAheadOfTheEndpoint_shouldStop() {
		// The application's chain carries the same bearer token filter the configurer
		// installs, so the check looks for the marker only the configurer adds.
		this.contextRunner.withUserConfiguration(ResourceServerChainAheadOfTheEndpoint.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("lacks the resource server GATool configures");
		});
	}

	@Test
	void startup_applicationChainMatchingPostAheadOfTheEndpoint_shouldStop() {
		// FilterChainProxy's own lookup matches a GET, so the check matches each of the
		// endpoint's methods to find a chain that reads the method.
		this.contextRunner.withUserConfiguration(PostChainAheadOfTheEndpoint.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("serves POST /mcp");
		});
	}

	@Test
	void startup_applicationsOwnRegistrationOfTheFilterUnderAPrefixPattern_shouldReplaceGAToolsAndStart() {
		// A registration of GATool's filter under another bean name backs GATool's off by
		// type, and a servlet prefix pattern covers the prefix itself.
		this.contextRunner.withUserConfiguration(OwnRegistrationUnderAPrefix.class).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("gaToolMcpComplianceFilter");
		});
	}

	@Test
	void startup_complianceFilterRegistrationDisabled_shouldStopNamingTheComplianceFilter() {
		this.contextRunner.withUserConfiguration(DisabledComplianceFilter.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("compliance filter");
		});
	}

	@Test
	void startup_resourceUnsetOffLoopback_shouldWarnAboutTheHostHeader(CapturedOutput output) {
		this.contextRunner.withPropertyValues("server.address=0.0.0.0")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("gatool.mcp.security.resource is unset");
	}

	@Test
	void startup_resourceUnsetOnLoopback_shouldStayQuiet(CapturedOutput output) {
		this.contextRunner.withPropertyValues("server.address=127.0.0.1")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).doesNotContain("gatool.mcp.security.resource is unset");
	}

	@Test
	void startup_registrationUnderGAToolsNameHoldingAnotherFilter_shouldStopNamingTheComplianceFilter() {
		// A bean under the name backs GATool's registration off, and the scope check then
		// refuses every POST at runtime, so startup says so.
		this.contextRunner.withUserConfiguration(AnotherFilterUnderGAToolsName.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("compliance filter");
		});
	}

	@Test
	void startup_dispatcherServletMappedUnderAPrefix_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("spring.mvc.servlet.path=/api")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring.mvc.servlet.path"));
	}

	@Test
	void startup_endpointWrittenAsAPattern_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp/*")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring.ai.mcp.server.streamable-http.mcp-endpoint"));
	}

	@Test
	void startup_resourceNamingTheContextPathAndTheEndpoint_shouldStart() {
		// Behind a context path the endpoint's URL carries it, and the resource is that
		// URL; the metadata document then sits under the context path as well.
		this.contextRunner
			.withPropertyValues("server.servlet.context-path=/app",
					"gatool.mcp.security.resource=https://mcp.example.org/app/mcp")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_resourceLeavingTheContextPathOut_shouldStopNamingThePath() {
		this.contextRunner
			.withPropertyValues("server.servlet.context-path=/app",
					"gatool.mcp.security.resource=https://mcp.example.org/mcp")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("/app/mcp"));
	}

	@Test
	void complianceFilter_securityFilterOrderMoved_shouldFollowItOneBelow() {
		this.contextRunner.withPropertyValues("spring.security.filter.order=-500").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean("gaToolMcpComplianceFilter", FilterRegistrationBean.class).getOrder())
				.isEqualTo(-501);
		});
	}

	@Test
	void complianceFilter_securityFilterAtTheHighestPrecedence_shouldShareItAndWarn(CapturedOutput output) {
		// One below the highest precedence wraps round to the lowest, so the filter
		// shares the order, and the check says the container now decides who runs first.
		this.contextRunner.withPropertyValues("spring.security.filter.order=" + Ordered.HIGHEST_PRECEDENCE)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean("gaToolMcpComplianceFilter", FilterRegistrationBean.class).getOrder())
					.isEqualTo(Ordered.HIGHEST_PRECEDENCE);
			});

		assertThat(output.getAll()).contains("shares that order and may run behind Spring Security");
	}

	@Configuration(proxyBeanMethods = false)
	static class EndpointChainWithoutTheMetadataPath {

		@Bean
		@Order(1)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain rest(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class EndpointChainWithBothPaths {

		@Bean
		@Order(1)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain rest(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ResourceServerChainAheadOfTheEndpoint {

		@Bean
		@Order(1)
		SecurityFilterChain application(HttpSecurity http) throws Exception {
			// A path pattern, because Spring Security refuses a chain matching any
			// request ahead of another chain; this one covers every path all the same.
			return http.securityMatcher("/**")
				.oauth2ResourceServer((server) -> server.jwt(Customizer.withDefaults()))
				.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PostChainAheadOfTheEndpoint {

		@Bean
		@Order(1)
		SecurityFilterChain posts(HttpSecurity http) throws Exception {
			return http.securityMatcher((request) -> "POST".equals(request.getMethod()))
				.oauth2ResourceServer((server) -> server.jwt(Customizer.withDefaults()))
				.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OwnRegistrationUnderAPrefix {

		@Bean
		FilterRegistrationBean<McpComplianceFilter> mcpRules() {
			McpComplianceFilter filter = new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofKilobytes(256),
					JsonMapper.builder().build(), McpComplianceFilter.STATELESS_METHODS);
			FilterRegistrationBean<McpComplianceFilter> registration = new FilterRegistrationBean<>(filter);
			registration.setOrder(-101);
			registration.addUrlPatterns("/mcp/*");
			return registration;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DisabledComplianceFilter {

		@Bean
		FilterRegistrationBean<McpComplianceFilter> gaToolMcpComplianceFilter() {
			McpComplianceFilter filter = new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofKilobytes(256),
					JsonMapper.builder().build(), McpComplianceFilter.STATELESS_METHODS);
			FilterRegistrationBean<McpComplianceFilter> registration = new FilterRegistrationBean<>(filter);
			registration.setOrder(-101);
			registration.setEnabled(false);
			return registration;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AnotherFilterUnderGAToolsName {

		@Bean
		FilterRegistrationBean<CommonsRequestLoggingFilter> gaToolMcpComplianceFilter() {
			return new FilterRegistrationBean<>(new CommonsRequestLoggingFilter());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ComplianceFilterBehindSecurity {

		// The registration GATool would have made, declared by the application at an
		// order that runs after Spring Security, which is the mistake the check names.
		@Bean
		FilterRegistrationBean<McpComplianceFilter> gaToolMcpComplianceFilter() {
			McpComplianceFilter filter = new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofKilobytes(256),
					JsonMapper.builder().build(), McpComplianceFilter.STATELESS_METHODS);
			FilterRegistrationBean<McpComplianceFilter> registration = new FilterRegistrationBean<>(filter);
			registration.setOrder(0);
			return registration;
		}

	}

}
