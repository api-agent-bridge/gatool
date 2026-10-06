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
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;
import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What startup does with the security settings: which stop names which property, when the
 * starter contributes its two chains, and when they back off.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpSecuritySliceTests {

	private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1";

	private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences=GATool";

	private static final String SCOPES = "gatool.mcp.security.baseline-scopes=mcp:tools";

	private static final String UNSAFE_SWITCH_ON = "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true";

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
				OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
				ManagementWebSecurityAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void startup_switchOffWithoutSpringSecurity_shouldStopNamingTheStarter() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(HttpSecurity.class)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring-boot-starter-security-oauth2-resource-server")
				.hasMessageContaining("allow-mcp-calls-without-authentication");
		});
	}

	@Test
	void startup_switchOffWithoutAnIssuer_shouldStopNamingTheIssuerPropertyAndTheTokenKind() {
		this.contextRunner.withPropertyValues(AUDIENCES, SCOPES).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri")
				.hasMessageContaining("JWT access tokens only")
				.hasMessageContaining("opaque token");
		});
	}

	@Test
	void startup_applicationChainWithTheConfigurerAndWithoutAnIssuer_shouldStopWithTheSentenceTheCheckUses() {
		// The application's own chain builds ahead of the settings check, so the
		// configurer is what stops startup here, and an application reads one sentence
		// for the missing issuer whichever of the two stops it.
		this.contextRunner.withPropertyValues(AUDIENCES, SCOPES)
			.withUserConfiguration(ChainWithTheConfigurer.class)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri")
					.hasMessageContaining("JWT access tokens only")
					.hasMessageContaining("opaque token");
			});
	}

	@Test
	void startup_switchOffWithoutAnAudience_shouldStopNamingTheAudiencesProperty() {
		this.contextRunner.withPropertyValues(ISSUER, SCOPES).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("spring.security.oauth2.resourceserver.jwt.audiences");
		});
	}

	@Test
	void startup_switchOffWithoutBaselineScopes_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("gatool.mcp.security.baseline-scopes");
		});
	}

	@Test
	void startup_everySettingPresent_shouldContributeBothChainsAndSaySo(CapturedOutput output) {
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, SCOPES).run((context) -> {
			assertThat(context).hasNotFailed();
			// Boot's own chains back off once any chain exists, so the starter keeps a
			// default chain of the shape Boot's resource server chain has, since a
			// decoder bean exists here, beside the MCP chain, and startup says so.
			assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
				.containsExactlyInAnyOrder("gaToolMcpSecurityFilterChain", "gaToolDefaultSecurityFilterChain");
		});
		assertThat(output.getAll()).contains("beside a default chain for every other path of this application")
			.contains("keeps every other path of this application behind a bearer token for the same issuer")
			.doesNotContain("with form and basic login")
			.contains("Declaring any SecurityFilterChain of the application's own replaces both contributed chains")
			.doesNotContain("runs without a security filter");
	}

	@Test
	void startup_switchOnWithSpringSecurity_shouldContributeAChainThatPermitsTheEndpointBesideTheDefault(
			CapturedOutput output) {
		this.contextRunner.withPropertyValues(UNSAFE_SWITCH_ON).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
				.containsExactlyInAnyOrder("gaToolMcpSecurityFilterChain", "gaToolDefaultSecurityFilterChain");
		});
		assertThat(output.getAll()).contains("permits every request to /mcp")
			.contains("replaces both contributed chains");
	}

	@Test
	void startup_applicationChainWithTheConfigurer_shouldBackOffBothChains() {
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, SCOPES)
			.withUserConfiguration(ChainWithTheConfigurer.class)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean("gaToolMcpSecurityFilterChain");
				assertThat(context).doesNotHaveBean("gaToolDefaultSecurityFilterChain");
				assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).containsExactly("ownChain");
			});
	}

	@Test
	void startup_bootsWebSecurityExcludedWithTheSwitchOn_shouldStartWithoutASecurityFilter() {
		// Spring Security stays on the classpath, Boot's web security auto-configuration
		// is out, so both HttpSecurity and springSecurityFilterChain are absent. The
		// switch says the endpoint runs open, and the startup check returns early.
		withoutBootsWebSecurity().withPropertyValues(UNSAFE_SWITCH_ON).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
		});
	}

	@Test
	void startup_bootsWebSecurityExcludedWithTheSwitchOff_shouldStopNamingTheMissingFilter() {
		withoutBootsWebSecurity().withPropertyValues(ISSUER, AUDIENCES, SCOPES).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
				.hasMessageContaining("springSecurityFilterChain");
			assertThat(((McpSecurityException) context.getStartupFailure()).action()).contains("@EnableWebSecurity");
		});
	}

	private WebApplicationContextRunner withoutBootsWebSecurity() {
		return new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, McpServerStatelessWebMvcAutoConfiguration.class,
					GAToolMcpAutoConfiguration.class, GAToolMcpSecurityAutoConfiguration.class))
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");
	}

	@Test
	void startup_applicationChainWithoutTheConfigurer_shouldStopNamingTheLineToAdd() {
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, SCOPES)
			.withUserConfiguration(ChainWithoutTheConfigurer.class)
			.run((context) -> {
				assertThat(context).hasFailed();
				// The exception carries the description, and its action names the line
				// to add, which the failure analyzer prints under it.
				assertThat(context.getStartupFailure()).hasMessageContaining("lacks the resource server")
					.hasMessageContaining("/mcp");
				assertThat(context.getStartupFailure()).isInstanceOf(McpSecurityException.class)
					.extracting((failure) -> ((McpSecurityException) failure).action())
					.asString()
					.contains("McpServerSecurityConfigurer.mcpServer()");
			});
	}

	@Test
	void startup_applicationChainWithRulesOfItsOwnBesideTheConfigurer_shouldStopNamingTheDedicatedChain() {
		// The configurer's rules register at build time, after the chain's own, and the
		// first matching rule wins, so a chain that mixes both would decide the endpoint
		// ahead of the scope check. The configurer refuses it.
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, SCOPES)
			.withUserConfiguration(ChainMixingRulesWithTheConfigurer.class)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasStackTraceContaining("chain of its own")
					.hasStackTraceContaining("securityMatcher");
			});
	}

	@Test
	void startup_resourceEndingInASlash_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES, "gatool.mcp.security.resource=https://mcp.example.com/mcp/")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.security.resource")
				.hasMessageContaining("cannot end in a slash"));
	}

	@Test
	void startup_resourceOnAnotherPathThanTheEndpoint_shouldStopNamingBothPaths() {
		// The metadata document is served under the endpoint's own path, so a resource
		// on another path would send clients to a document that answers 401 or 404.
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES,
					"gatool.mcp.security.resource=https://mcp.example.com/api/mcp")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("/mcp"));
	}

	@Test
	void startup_resourceWithAQueryOrAFragment_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES,
					"gatool.mcp.security.resource=https://mcp.example.com/mcp?x=1")
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("query or a fragment"));
	}

	@Test
	void startup_resourceWrittenAsAnAppIdUri_shouldNameTheUrlToSetAndThePropertyThatTakesTheAudience() {
		// Microsoft Entra ID names an API by an App ID URI, and an operator who knows the
		// API under that identifier writes it here. The stop has to say which value the
		// property takes and where the identifier goes.
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES,
					"gatool.mcp.security.resource=api://2f1e8c1a-6a0b-4f0e-9a57-1d3c5b7e9f10")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.security.resource")
				.hasMessageContaining("such as https://mcp.example.com/mcp")
				.hasMessageContaining("api://<client-id>")
				.hasMessageContaining("spring.security.oauth2.resourceserver.jwt.audiences")
				.hasMessageContaining("aud claim"));
	}

	@Test
	void startup_resourceWrittenAsAnAppIdUriBehindAContextPath_shouldNameTheUrlWithTheContextPath() {
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES, "server.servlet.context-path=/app",
					"gatool.mcp.security.resource=api://2f1e8c1a-6a0b-4f0e-9a57-1d3c5b7e9f10")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("such as https://mcp.example.com/app/mcp"));
	}

	@Test
	void startup_resourceWithoutAScheme_shouldKeepTheSentenceAboutAnAbsoluteUrl() {
		// A value without a scheme is a URL written short, so the stop says what is
		// missing and leaves the audience out of it.
		this.contextRunner
			.withPropertyValues(ISSUER, AUDIENCES, SCOPES, "gatool.mcp.security.resource=mcp.example.com/mcp")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("it has to be an absolute http or https URL")
				.message()
				.doesNotContain("audiences"));
	}

	@Test
	void startup_baselineScopeWithASpace_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, "gatool.mcp.security.baseline-scopes=mcp tools")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.security.baseline-scopes")
				.hasMessageContaining("holds a space"));
	}

	@Test
	void startup_audiencesUnsetWithTheApplicationsOwnDecoder_shouldStartAndLeaveTheAudienceCheckToIt(
			CapturedOutput output) {
		// Spring Boot's decoder is the one that reads the audiences property, and it
		// backs off for an application's own bean, which then owns the check.
		this.contextRunner.withPropertyValues(ISSUER, SCOPES)
			.withBean("applicationDecoder", JwtDecoder.class, () -> (token) -> {
				throw new JwtException("refused");
			})
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("leaves the audience check");
	}

	@Test
	void startup_audiencesSetBesideTheApplicationsOwnDecoder_shouldWarnThatThePropertyIsUnread(CapturedOutput output) {
		// Boot's decoder is the one that adds the audience validator, so with a custom
		// decoder the property is unread, and silence would say the check is on.
		this.contextRunner.withPropertyValues(ISSUER, AUDIENCES, SCOPES)
			.withBean("applicationDecoder", JwtDecoder.class, () -> (token) -> {
				throw new JwtException("refused");
			})
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("that property is unread by the bean");
	}

	@Test
	void startup_switchOffWithSpringSecurityButWithoutTheResourceServer_shouldStopNamingTheStarter() {
		this.contextRunner
			// The filter ships in the resource server module, where the configurer ships
			// in spring-security-config, which every Spring Security application has.
			.withClassLoader(new FilteredClassLoader(BearerTokenAuthenticationFilter.class))
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("spring-boot-starter-security-oauth2-resource-server")
					.hasMessageContaining("resource server support is missing");
			});
	}

	@Test
	void startup_securedStatefulServerWithTheCapOff_shouldWarnNamingBothProperties(CapturedOutput output) {
		// The per-caller cap defaults to 100, so an operator reaches this warning by
		// setting it to 0 or to a value at or above max-count, both of which leave one
		// caller free to fill the whole server cap.
		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, McpServerStreamableHttpWebMvcAutoConfiguration.class,
					GAToolMcpAutoConfiguration.class, GAToolMcpSecurityAutoConfiguration.class,
					OAuth2ResourceServerAutoConfiguration.class, OAuth2ResourceServerWebSecurityAutoConfiguration.class,
					SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
					SecurityFilterAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class))
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls", ISSUER, AUDIENCES,
					SCOPES, "gatool.mcp.sessions.max-count-per-caller=0")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("gatool.mcp.sessions.max-count-per-caller")
			.contains("gatool.mcp.sessions.max-count");
	}

	@Configuration(proxyBeanMethods = false)
	static class ChainWithTheConfigurer {

		@Bean
		SecurityFilterChain ownChain(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChainMixingRulesWithTheConfigurer {

		@Bean
		SecurityFilterChain ownChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((requests) -> requests.requestMatchers("/**").authenticated())
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChainWithoutTheConfigurer {

		@Bean
		SecurityFilterChain ownChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
		}

	}

}
