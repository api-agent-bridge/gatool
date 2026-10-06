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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;

/**
 * Builds the compliance filter that {@link GAToolMcpAutoConfiguration.ComplianceFilter}
 * registers, and the order it runs at.
 *
 * <p>
 * The class holds the building so that the auto-configuration keeps its bean methods and
 * little else.
 *
 * @author Željko Kozina
 */
final class McpComplianceFilters {

	private McpComplianceFilters() {
	}

	/**
	 * Builds the filter from the endpoint path, the transport properties and the mapper
	 * the MCP server itself reads bodies with.
	 * @param properties the GATool properties
	 * @param environment carries the endpoint path
	 * @param mcpServerJsonMapper the mapper the MCP server itself reads bodies with, so
	 * the filter and the server parse under the same rules and the tool name the filter
	 * reads is the one the server calls. A context without that bean gets a mapper built
	 * here
	 * @return the filter
	 */
	static McpComplianceFilter build(GAToolProperties properties, Environment environment,
			ObjectProvider<JsonMapper> mcpServerJsonMapper) {
		String endpoint = GAToolMcpAutoConfiguration.bindStreamableHttp(environment).getMcpEndpoint();
		requirePlainEndpointPath(endpoint, environment);
		DataSize maxRequestBodySize = properties.getMcp().getTransport().getMaxRequestBodySize();
		// A cap of zero or less would refuse every call that carries a body, which is
		// every call after the handshake.
		if (maxRequestBodySize.toBytes() < 1) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.transport.max-request-body-size",
					maxRequestBodySize, "The MCP endpoint reads a request body, so this limit is at least one byte.");
		}
		// The stateful router serves DELETE as well, so the filter reads the protocol
		// to know which methods to answer 405 for.
		boolean stateful = "STREAMABLE"
			.equalsIgnoreCase(environment.getProperty(GAToolEnvironmentPostProcessor.PROTOCOL));
		return new McpComplianceFilter(endpoint, properties.getMcp().getTransport().getAllowedOrigins(),
				properties.getMcp().getSecurity().getUnsafe().isAllowSupersededMcpRevisions(), maxRequestBodySize,
				mcpServerJsonMapper.getIfAvailable(() -> JsonMapper.builder().build()),
				stateful ? McpComplianceFilter.STATEFUL_METHODS : McpComplianceFilter.STATELESS_METHODS);
	}

	// The filter, the security chain and Spring AI's router have to agree on one
	// path. They do while the endpoint is a plain path and the dispatcher servlet is
	// mapped at the root. A pattern would match in the router and the chain and
	// miss here. A servlet path prefix would move the endpoint from under the
	// chain's matcher, where Spring Boot's default chain has already backed off.
	// A character that would make the endpoint a pattern or a matrix path, or break the
	// matcher.
	private static boolean isPatternCharacter(int c) {
		return "*?{};".indexOf(c) >= 0 || Character.isWhitespace(c);
	}

	private static void requirePlainEndpointPath(String endpoint, Environment environment) {
		if (!endpoint.startsWith("/") || endpoint.chars().anyMatch(McpComplianceFilters::isPatternCharacter)) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.mcp.server.streamable-http.mcp-endpoint",
					endpoint,
					"GATool matches the MCP endpoint as one plain path starting with a slash, and a pattern "
							+ "would reach Spring AI's router and Spring Security's chain while missing "
							+ "GATool's own filter.");
		}
		// Boot maps an empty value at the root as well.
		String servletPath = environment.getProperty("spring.mvc.servlet.path", "/");
		if (!"/".equals(servletPath) && !servletPath.isEmpty()) {
			throw new InvalidConfigurationPropertyValueException("spring.mvc.servlet.path", servletPath,
					"GATool serves the MCP endpoint with the dispatcher servlet mapped at the root. Under "
							+ "another mapping the endpoint moves from under the security chain that "
							+ "GATool contributes, so leave this property at its default, or serve MCP from "
							+ "an application of its own.");
		}
	}

	/**
	 * Returns the order the compliance filter runs at: one below Spring Security's,
	 * clamped at the highest precedence.
	 * @param environment carries {@code spring.security.filter.order}
	 * @return the order of the filter's registration
	 */
	static int order(Environment environment) {
		int securityOrder = environment.getProperty(GAToolMcpAutoConfiguration.SECURITY_FILTER_ORDER_PROPERTY,
				Integer.class, GAToolMcpAutoConfiguration.DEFAULT_SECURITY_FILTER_ORDER);
		return (securityOrder == Ordered.HIGHEST_PRECEDENCE) ? Ordered.HIGHEST_PRECEDENCE : securityOrder - 1;
	}

}
