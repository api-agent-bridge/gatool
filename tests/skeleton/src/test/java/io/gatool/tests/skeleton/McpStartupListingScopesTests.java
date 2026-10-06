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
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup listing names every scope a call to each MCP tool needs: the baseline of
 * {@code gatool.mcp.security.baseline-scopes} and the tool's own.
 *
 * <p>
 * The listing prints the baseline beside the tool's own scopes, and the baseline alone
 * for a tool whose file leaves scopes undeclared, so an operator reads what the endpoint
 * asks of a token. The 401 and 403 challenges name both as well; the listing is where an
 * operator reads them before a client arrives.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpStartupListingScopesTests {

	// This module ships an operation file under the default in-process location, and
	// the MCP listing alone is under test here.
	private static final String[] PROPERTIES = { "spring.ai.mcp.server.protocol=STATELESS",
			"gatool.api.url=http://127.0.0.1:1/graphql",
			"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
			"gatool.mcp.operations.locations=classpath:gatool/scoped/",
			"gatool.in-process.operations.locations=optional:classpath*:gatool/none/" };

	private final WebApplicationContextRunner securedRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
				OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
				ManagementWebSecurityAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1",
				"spring.security.oauth2.resourceserver.jwt.audiences=GATool",
				"gatool.mcp.security.baseline-scopes=mcp:tools");

	// The resource server requires a baseline, so the listing without one runs with the
	// unsafe switch, where the listing is the same code.
	private final WebApplicationContextRunner openRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues(PROPERTIES)
		.withPropertyValues("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_scopedToolsBehindABaseline_shouldNameTheBaselineBesideEachToolsOwnScopes(CapturedOutput output) {
		this.securedRunner.run((context) -> assertThat(context).hasNotFailed());

		// The baseline comes first, then the file's own list in its order.
		assertThat(listingLine(output, "topRatedMovies")).contains("needs the scopes mcp:tools movies:read");
		assertThat(listingLine(output, "movieByLookup"))
			.contains("needs the scopes mcp:tools movies:read movies:detail");
		// A file that leaves scopes undeclared, and one that declares an empty list,
		// both need the baseline alone.
		assertThat(listingLine(output, "moviesPage")).contains("needs the scopes mcp:tools");
		assertThat(listingLine(output, "searchMovies")).contains("needs the scopes mcp:tools");
	}

	@Test
	void startup_scopedToolsWithoutABaseline_shouldNameEachToolsOwnScopesAlone(CapturedOutput output) {
		this.openRunner.run((context) -> assertThat(context).hasNotFailed());

		assertThat(listingLine(output, "topRatedMovies")).contains("needs the scopes movies:read");
		// An empty list is a statement the file made on purpose, so it is named; a
		// file without a list has its line end at the token count.
		assertThat(listingLine(output, "searchMovies")).contains("open to every caller");
		assertThat(listingLine(output, "moviesPage")).endsWith("tokens)");
	}

	// A stdio server checks a tool's own scopes against gatool.mcp.stdio.granted-scopes
	// and leaves the baseline unread, so the listing leaves the baseline out there.
	@Test
	void startup_scopedToolsOverStdioWithABaselineSet_shouldLeaveTheBaselineOutOfTheListing(CapturedOutput output) {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues(PROPERTIES)
			.withPropertyValues("spring.ai.mcp.server.stdio=true", "gatool.mcp.security.baseline-scopes=mcp:tools")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(listingLine(output, "topRatedMovies")).contains("needs the scopes movies:read")
			.doesNotContain("mcp:tools");
		assertThat(listingLine(output, "moviesPage")).endsWith("tokens)");
	}

	private static String listingLine(CapturedOutput output, String toolName) {
		List<String> lines = output.getAll()
			.lines()
			.filter((line) -> line.contains("  " + toolName + " from ") && line.contains(" tokens)"))
			.map(String::strip)
			.toList();
		assertThat(lines).as("the listing line of " + toolName).hasSize(1);
		return lines.get(0);
	}

}
