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

package io.gatool.boot.mcp.autoconfigure;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;

import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.mcp.internal.security.McpEndpointChainMarkerFilter;
import io.gatool.boot.mcp.internal.security.McpEndpointPaths;

/**
 * The check {@link GAToolMcpAutoConfiguration.ScopeCheck} runs once every singleton
 * exists: the chain serving the MCP endpoint carries the per-tool scope check.
 *
 * <p>
 * The class holds the check so that the auto-configuration keeps its bean methods and
 * little else. It is loaded only when the bean runs, under the class condition that
 * bean's configuration carries, so it names Spring Security's types freely.
 *
 * @author Željko Kozina
 */
final class McpScopeCheckAtStartup {

	private McpScopeCheckAtStartup() {
	}

	/**
	 * Stops startup where the chain serving the MCP endpoint lacks the scope check.
	 * @param securityAutoConfiguration the security auto-configuration's own check,
	 * present while that auto-configuration applied
	 * @param springSecurityFilterChain spring Security's filter bean
	 * @param properties the GATool properties
	 * @param environment carries the endpoint path and the stdio flag
	 */
	static void run(
			ObjectProvider<GAToolMcpSecurityAutoConfiguration.ResourceServerSettingsCheck> securityAutoConfiguration,
			ObjectProvider<Filter> springSecurityFilterChain, GAToolProperties properties, Environment environment) {
		if (securityAutoConfiguration.getIfAvailable() != null) {
			// The auto-configuration applied, and its own check covers this chain.
			return;
		}
		if (SpringAiMcpKeys.servesStdio(environment)
				|| properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		McpEndpointPaths paths = McpEndpointPaths.of(environment);
		Filter securityFilter = springSecurityFilterChain.getIfAvailable();
		if (securityFilter == null) {
			// Spring Security is on the classpath, which stands the classpath guard down,
			// and the filter bean is absent. It is the endpoint open to every caller: an
			// application that excludes Boot's web security auto-configuration alongside
			// this one runs without a security filter chain.
			throw new McpSecurityException(
					"The bean " + AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME
							+ " is missing, so the MCP endpoint runs without a security filter chain, "
							+ "and every caller would call every tool, including the tools an "
							+ "operation file gives scopes to.",
					"GAToolMcpSecurityAutoConfiguration is excluded and Spring Security's filter is "
							+ "absent. Leave the auto-configuration in with "
							+ "spring-boot-starter-security-oauth2-resource-server on the classpath, "
							+ "or register the filter (Spring Boot's web security "
							+ "auto-configuration, or @EnableWebSecurity) and give the endpoint a "
							+ "chain of its own that applies the configurer: " + "http.securityMatcher(\""
							+ paths.endpoint() + "\", \"" + paths.metadataPath()
							+ "\").with(McpServerSecurityConfigurer" + ".mcpServer(), withDefaults()). Set "
							+ McpStartupStops.UNAUTHENTICATED_SWITCH
							+ "=true to serve the endpoint without authentication on purpose.");
		}
		FilterChainProxy proxy = GAToolMcpSecurityAutoConfiguration.unwrap(securityFilter);
		for (String method : GAToolMcpSecurityAutoConfiguration.ENDPOINT_METHODS) {
			boolean checked = GAToolMcpSecurityAutoConfiguration.filtersFor(proxy, paths.endpoint(), method)
				.stream()
				.anyMatch(McpEndpointChainMarkerFilter.class::isInstance);
			if (!checked) {
				throw new McpSecurityException(
						"The security filter chain that serves " + method + " " + paths.endpoint()
								+ " lacks the scope check GATool installs, so any token for "
								+ "the audience would call every tool, including the tools an operation file "
								+ "gives scopes to.",
						"GAToolMcpSecurityAutoConfiguration is excluded. Leave it in, or give the "
								+ "endpoint a chain of its own that applies the configurer: "
								+ "http.securityMatcher(\"" + paths.endpoint() + "\", \"" + paths.metadataPath()
								+ "\").with(McpServerSecurityConfigurer" + ".mcpServer(), withDefaults()).");
			}
		}
	}

}
