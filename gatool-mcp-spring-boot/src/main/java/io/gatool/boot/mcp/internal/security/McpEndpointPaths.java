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

package io.gatool.boot.mcp.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where the MCP endpoint and its protected resource metadata live.
 *
 * <p>
 * RFC 9728 puts the metadata of a resource at a path-inserted well-known location, so the
 * document for {@code /mcp} sits at {@code /.well-known/oauth-protected-resource/mcp},
 * and a client that reads the root document has to reject its {@code resource} value.
 * Spring Security's metadata filter answers every path under the well-known prefix, and
 * the challenge this server sends points at the path-inserted one.
 *
 * @param endpoint the MCP endpoint path, from
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}
 * @author Željko Kozina
 */
public record McpEndpointPaths(String endpoint) {

	/**
	 * Spring Boot's property naming the issuer whose tokens the endpoint accepts.
	 */
	public static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

	/**
	 * What startup says when {@link #ISSUER_PROPERTY} is unset. The settings check and
	 * the configurer both stop with it, so an application that applies the configurer in
	 * a chain of its own, which builds ahead of the check, reads the sentence the check
	 * uses.
	 */
	public static final String ISSUER_UNSET_MESSAGE = "GATool secures the MCP endpoint as an OAuth 2.1 resource "
			+ "server for JWT access tokens only, and every token has to come from one issuer. Set this property "
			+ "to the issuer's URL, which the protected resource metadata also publishes. An opaque token that "
			+ "needs introspection is unsupported.";

	/**
	 * Spring Boot's property naming the audience every token has to carry.
	 */
	public static final String AUDIENCES_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";

	/**
	 * Spring AI's property naming the endpoint.
	 */
	public static final String ENDPOINT_PROPERTY = "spring.ai.mcp.server.streamable-http.mcp-endpoint";

	private static final String METADATA_ROOT = "/.well-known/oauth-protected-resource";

	/**
	 * Reads the endpoint path from the environment.
	 * @param environment the environment
	 * @return the paths
	 */
	public static McpEndpointPaths of(Environment environment) {
		return new McpEndpointPaths(environment.getProperty(ENDPOINT_PROPERTY, "/mcp"));
	}

	/**
	 * Returns the path of this endpoint's own metadata document.
	 * @return the path-inserted well-known path
	 */
	public String metadataPath() {
		return METADATA_ROOT + this.endpoint;
	}

	/**
	 * Returns the absolute URL of this endpoint's metadata document, as seen from one
	 * request, or as the configured resource URI names it.
	 * @param request the request that earned a challenge
	 * @param resource the canonical resource URI from
	 * {@code gatool.mcp.security.resource}, or {@code null} to build the URL from the
	 * request
	 * @return the URL a client fetches
	 */
	// A challenge carries a URL. Built from the request, it names the scheme, host and
	// port the servlet container saw, which behind a proxy that terminates TLS is the
	// internal address unless server.forward-headers-strategy is set. The resource
	// property is the way out: its origin and path give the document's URL directly.
	// Spring's metadata filter answers under the well-known path within the
	// application, so behind a servlet context path the document sits at the context
	// path, then the well-known root, then the endpoint, whichever origin the
	// resource names.
	public String metadataUrl(HttpServletRequest request, @Nullable String resource) {
		if (resource != null) {
			UriComponents canonical = UriComponentsBuilder.fromUriString(resource).build();
			return UriComponentsBuilder.newInstance()
				.scheme(canonical.getScheme())
				.host(canonical.getHost())
				.port(canonical.getPort())
				.path(request.getContextPath() + metadataPath())
				.build()
				.toUriString();
		}
		return UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
			.replacePath(request.getContextPath() + metadataPath())
			.replaceQuery(null)
			.build()
			.toUriString();
	}
}
