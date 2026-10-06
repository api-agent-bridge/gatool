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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.McpSecurityException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What startup says about tool scopes: the tools that run open while security is off, the
 * stop for a shared credential behind tools whose files leave scopes undeclared, and the
 * line for an application's own strategy in the same place.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpScopeStartupSliceTests {

	private static final String SECURED = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1";

	private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences=GATool";

	private static final String SCOPES = "gatool.mcp.security.baseline-scopes=mcp:tools";

	private static final String STATIC_HEADER = "gatool.api.credentials.strategy=static-header";

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
				OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
				ManagementWebSecurityAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/");

	@Test
	void startup_securityOffWithScopedTools_shouldWarnNamingEachToolAndItsScopes(CapturedOutput output) {
		this.contextRunner.withPropertyValues("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true")
			.run((context) -> assertThat(context).hasNotFailed());

		// The tool that lists an empty scope set is open on purpose, so it stays out of
		// the warning, and so does the tool without a list. The order is the
		// listing's, by name.
		assertThat(output.getAll())
			.contains("these tools, whose operation files require scopes, are open to any unauthenticated "
					+ "caller: movieByLookup (movies:read movies:detail), topRatedMovies (movies:read).");
	}

	@Test
	void startup_staticHeaderBehindAToolWithoutScopes_shouldStopNamingTheToolAndTheLineToAdd() {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES, STATIC_HEADER, "gatool.api.credentials.header-name=X-Key",
					"gatool.api.credentials.header-value=secret")
			.run((context) -> {
				assertThat(context).hasFailed();
				// The stop is thrown while the settings bean is created, so Spring wraps
				// it, and the failure analyzer reads the root cause the same way.
				Throwable root = rootOf(context.getStartupFailure());
				assertThat(root).isInstanceOf(McpSecurityException.class)
					.hasMessageContaining("gatool.api.credentials.strategy is static-header")
					.hasMessageContaining("These MCP tools do not declare any: moviesPage.");
				assertThat(((McpSecurityException) root).action()).contains("@gatool(scopes: [...])")
					.contains("@gatool(scopes: [])");
			});
	}

	private static Throwable rootOf(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root;
	}

	@Test
	void startup_staticHeaderWithEveryToolDeclared_shouldStartAndNameTheOpenTool(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES, STATIC_HEADER, "gatool.api.credentials.header-name=X-Key",
					"gatool.api.credentials.header-value=secret",
					"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/")
			.run((context) -> assertThat(context).hasNotFailed());

		// The listing prints every scope a call needs: the baseline alone for the tool
		// whose file declares an empty list, the baseline and the file's own for the
		// other.
		assertThat(output.getAll()).contains("searchMovies from ")
			.contains("needs the scopes mcp:tools")
			.contains("topRatedMovies from ")
			.contains("needs the scopes mcp:tools movies:read");
	}

	@Test
	void startup_staticHeaderWithTheDynamicToolsUnscoped_shouldStopNamingTheirProperty() {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES, STATIC_HEADER, "gatool.api.credentials.header-name=X-Key",
					"gatool.api.credentials.header-value=secret",
					"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/",
					"gatool.dev.experimental.generate-tools=dynamic-three-step")
			.run((context) -> {
				assertThat(context).hasFailed();
				Throwable root = rootOf(context.getStartupFailure());
				assertThat(root).isInstanceOf(McpSecurityException.class)
					.hasMessageContaining("searchSchema, introspectType, executeGraphql");
				assertThat(((McpSecurityException) root).action())
					.contains("gatool.dev.experimental.dynamic-operations.required-scopes");
			});
	}

	@Test
	void startup_staticHeaderWithTheDynamicToolsScoped_shouldStart() {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES, STATIC_HEADER, "gatool.api.credentials.header-name=X-Key",
					"gatool.api.credentials.header-value=secret",
					"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/",
					"gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.required-scopes=graphql:run")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_securedWithoutACredentialStrategy_shouldSayTheApiSeesEveryCallerAlike(CapturedOutput output) {
		this.contextRunner.withPropertyValues(SECURED, AUDIENCES, SCOPES)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("gatool.api.credentials.strategy is unset, so the GraphQL API is called "
				+ "without a credential and sees every MCP caller alike");
	}

	@Test
	void startup_dynamicScopeWithASpace_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES, "gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.required-scopes=graphql run")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.hasMessageContaining("gatool.dev.experimental.dynamic-operations.required-scopes")
				.hasMessageContaining("holds a space"));
	}

	@Test
	void startup_applicationsOwnStrategyBehindAToolWithoutScopes_shouldStartAndSayTheApiMaySeeGATool(
			CapturedOutput output) {
		// Whether the application's strategy carries the caller is the application's
		// own business, so the reasoning behind the static-header stop goes to the log
		// as one line, with the tools named, and startup goes on.
		this.contextRunner.withPropertyValues(SECURED, AUDIENCES, SCOPES)
			.withUserConfiguration(OwnStrategy.class)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("The ApiCredentialStrategy bean " + SignedRequests.class.getName())
			.contains("If it sends one credential for every MCP caller")
			.contains("These MCP tools do not declare any: moviesPage.");
	}

	@Test
	void startup_applicationsOwnStrategyWithEveryToolDeclared_shouldStayQuietAboutIt(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues(SECURED, AUDIENCES, SCOPES,
					"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/")
			.withUserConfiguration(OwnStrategy.class)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).doesNotContain("If it sends one credential for every MCP caller");
	}

	@Test
	void startup_securedWithAToolWithoutScopesAndNoSharedCredential_shouldStart() {
		// Without a credential the API is called plainly, and a tool without scopes needs
		// the baseline alone, which is the rule of a credential that keeps the caller.
		this.contextRunner.withPropertyValues(SECURED, AUDIENCES, SCOPES)
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnStrategy {

		@Bean
		ApiCredentialStrategy signedRequests() {
			return new SignedRequests();
		}

	}

	static final class SignedRequests implements ApiCredentialStrategy {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			request.getHeaders().set("X-Signature", "signed");
			return execution.execute(request, body);
		}

	}

}
