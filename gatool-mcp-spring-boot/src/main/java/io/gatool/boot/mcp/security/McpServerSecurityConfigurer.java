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

package io.gatool.boot.mcp.security;

import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.mcp.internal.security.McpBearerChallenges;
import io.gatool.boot.mcp.internal.security.McpCallAuthorizationManager;
import io.gatool.boot.mcp.internal.security.McpEndpointChainMarkerFilter;
import io.gatool.boot.mcp.internal.security.McpEndpointPaths;
import io.gatool.boot.mcp.internal.security.McpScopes;
import io.gatool.boot.mcp.internal.security.McpSessionBindingFilter;

/**
 * Secures the MCP endpoint as an OAuth 2.1 resource server, in one line of an
 * application's own filter chain:
 *
 * <pre>{@code
 * http.with(McpServerSecurityConfigurer.mcpServer(), withDefaults());
 * }</pre>
 *
 * <p>
 * The configurer holds everything the endpoint needs. Every call to the endpoint has to
 * carry a JWT access token for the issuer and the audience Spring Boot's
 * {@code spring.security.oauth2.resourceserver.jwt} properties name, with every scope in
 * {@code gatool.mcp.security.baseline-scopes}. A {@code tools/call} needs the scopes its
 * tool lists in {@code @gatool(scopes:)} as well. The protected resource metadata answers
 * anonymously at the path-inserted location RFC 9728 gives it,
 * {@code /.well-known/oauth-protected-resource/mcp} for an endpoint at {@code /mcp}, and
 * names the issuer and the baseline scopes. The root document at
 * {@code /.well-known/oauth-protected-resource} stays unserved, because it would carry
 * the root resource's identifier. A call without a token gets a 401 whose challenge names
 * the metadata document of this endpoint and the scopes to ask for. A token that lacks a
 * scope gets a 403 with {@code insufficient_scope} naming what the call needed and what
 * the token held. CSRF stays off for the endpoint, because an MCP client authenticates
 * with a bearer token alone.
 *
 * <p>
 * With {@code gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication} on, the
 * configurer permits every request to the endpoint, so an application that declares its
 * own chain gets the same behaviour the contributed chain has.
 *
 * <p>
 * An application without a chain of its own gets one from the MCP starter with this
 * configurer applied, and its {@code securityMatcher} covers the endpoint and the
 * metadata path. An application that declares its own chains gives the endpoint a chain
 * of its own, ordered ahead of the rest, whose matcher covers both paths, and applies the
 * configurer there:
 *
 * <pre>
 * &#64;Bean
 * &#64;Order(1)
 * SecurityFilterChain mcp(HttpSecurity http) throws Exception {
 *     return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
 *             .with(McpServerSecurityConfigurer.mcpServer(), withDefaults())
 *             .build();
 * }
 * </pre>
 *
 * <p>
 * Startup checks that the chain serving the endpoint is this configurer's, matching a
 * request of each method the endpoint takes. A chain ordered ahead whose matcher reads a
 * header stays out of that check's reach.
 *
 * <p>
 * On the stateful transport a session is bound to the caller that opened it, under
 * {@code Authentication.getName()}, the JWT subject with Spring's converter, and under
 * the token's fingerprint where a converter of the application's own leaves the name
 * blank. A converter has to keep that name stable across a user's requests. A filter the
 * application adds with {@code addFilterAfter(filter, AuthorizationFilter.class)} runs
 * ahead of the binding, which shares that position.
 *
 * <p>
 * The configurer refuses a chain that already carries {@code authorizeHttpRequests} rules
 * of its own. Spring Security runs a configurer's {@code init} at build time, after every
 * lambda the bean method applied, and the first matching rule wins. A rule such as
 * {@code requestMatchers("/**").authenticated()} written beside the configurer would
 * decide the endpoint ahead of the scope check, and an {@code anyRequest()} rule would
 * make Spring reject the configurer's matchers outright.
 *
 * @author Željko Kozina
 */
public final class McpServerSecurityConfigurer
		extends AbstractHttpConfigurer<McpServerSecurityConfigurer, HttpSecurity> {

	private @Nullable McpEndpointChainMarkerFilter marker;

	private @Nullable McpSessionBindingFilter sessionBinding;

	private McpServerSecurityConfigurer() {
	}

	/**
	 * Creates the configurer.
	 * @return the configurer, which reads its settings from the application context it is
	 * applied in
	 */
	public static McpServerSecurityConfigurer mcpServer() {
		return new McpServerSecurityConfigurer();
	}

	// The configurer class is generic and a class literal is raw, which is the one way
	// Spring offers to ask for it.
	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void init(HttpSecurity http) {
		if (http.getConfigurer((Class) AuthorizeHttpRequestsConfigurer.class) != null) {
			throw new IllegalStateException("McpServerSecurityConfigurer is applied in a chain that already holds "
					+ "authorizeHttpRequests rules, which would decide the MCP endpoint ahead of the scope check. "
					+ "Give the endpoint a chain of its own, ordered ahead of the rest, with "
					+ "securityMatcher(\"/mcp\", \"/.well-known/oauth-protected-resource/mcp\") and the configurer "
					+ "alone.");
		}
		ApplicationContext context = http.getSharedObject(ApplicationContext.class);
		GAToolProperties properties = context.getBean(GAToolProperties.class);
		Environment environment = context.getBean(Environment.class);
		McpEndpointPaths paths = McpEndpointPaths.of(environment);
		// A bearer token is what an MCP client sends, so the CSRF token a browser form
		// carries is not expected on the endpoint, and a request without any token has to
		// reach the entry point for its challenge. The metadata path is ignored as well:
		// the document is public and read-only, and the CSRF token repository would open
		// an HTTP session for every POST to it, whatever the session policy below says,
		// so a caller without a token could fill the session store.
		http.csrf((csrf) -> csrf.ignoringRequestMatchers(paths.endpoint(), paths.metadataPath()));
		// A bearer token arrives on every call, so the chain works without a session
		// or a saved request, the way a resource server chain is written.
		http.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		http.requestCache(RequestCacheConfigurer::disable);
		if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			// The metadata path is permitted too, so the chain leaves it to the
			// application, where it answers 404 while the resource server is off.
			http.authorizeHttpRequests(
					(requests) -> requests.requestMatchers(paths.endpoint(), paths.metadataPath()).permitAll());
			return;
		}
		List<String> baselineScopes = properties.getMcp().getSecurity().getBaselineScopes();
		McpScopes scopes = McpScopes.of(baselineScopes, context.getBean(GAToolCatalog.class).mcpTools(),
				properties.getMcp().getSecurity().getUnsafe().isRequestEveryScopeAtSignIn());
		String resource = properties.getMcp().getSecurity().getResource();
		String issuer = environment.getProperty(McpEndpointPaths.ISSUER_PROPERTY);
		if (issuer == null) {
			throw new InvalidConfigurationPropertyValueException(McpEndpointPaths.ISSUER_PROPERTY, null,
					McpEndpointPaths.ISSUER_UNSET_MESSAGE);
		}
		// The strategy bean of an application that declares one, which is what Spring
		// Security's own filters read and write, and the static default otherwise.
		SecurityContextHolderStrategy contextHolderStrategy = getSecurityContextHolderStrategy();
		McpBearerChallenges challenges = new McpBearerChallenges(paths, scopes, resource, contextHolderStrategy);
		// A stateless server leaves every request without a session, so the binding
		// filter serves the stateful transport alone.
		this.marker = new McpEndpointChainMarkerFilter();
		if ("STREAMABLE".equalsIgnoreCase(environment.getProperty("spring.ai.mcp.server.protocol"))) {
			this.sessionBinding = new McpSessionBindingFilter(contextHolderStrategy, paths.endpoint(),
					properties.getMcp().getSessions().getIdleTimeout(),
					properties.getMcp().getSessions().getMaxCountPerCaller(), challenges.entryPoint());
		}
		secureEndpoint(http, paths, scopes, challenges,
				(builder) -> publishMetadata(builder, scopes, issuer, resource, environment));
	}

	// The rules, the challenges and the resource server of the endpoint, in that order.
	private static void secureEndpoint(HttpSecurity http, McpEndpointPaths paths, McpScopes scopes,
			McpBearerChallenges challenges, Consumer<OAuth2ProtectedResourceMetadata.Builder> metadata) {
		// The path-inserted document alone is served, because the root document would
		// have to carry the root resource's identifier, and the challenge names the
		// path-inserted one, which is where a client looks. The endpoint's own manager
		// reads the tool a call names, which the compliance filter left on the request.
		http.authorizeHttpRequests((requests) -> requests.requestMatchers(paths.metadataPath())
			.permitAll()
			.requestMatchers(paths.endpoint())
			.access(new McpCallAuthorizationManager(scopes)));
		// Set on the chain, so a basic or form login configured beside this one cannot
		// answer an MCP client with a challenge it cannot follow.
		http.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(challenges.entryPoint())
			.accessDeniedHandler(challenges.accessDeniedHandler()));
		http.oauth2ResourceServer((resourceServer) -> resourceServer.jwt(Customizer.withDefaults())
			.authenticationEntryPoint(challenges.entryPoint())
			.accessDeniedHandler(challenges.accessDeniedHandler())
			.protectedResourceMetadata((configurer) -> configurer.protectedResourceMetadataCustomizer(metadata)));
	}

	// The filter fills resource from the request. The issuer and the
	// scopes are this server's to publish, and the filter's default
	// claims a binding to a client certificate this server does not
	// check.
	private static void publishMetadata(OAuth2ProtectedResourceMetadata.Builder builder, McpScopes scopes,
			String issuer, @Nullable String resource, Environment environment) {
		builder.authorizationServer(issuer);
		scopes.publishedAtSignIn(null).forEach(builder::scope);
		if (resource != null) {
			builder.resource(resource);
		}
		// RFC 9728 recommends a name a client can show its user, and Spring
		// AI's server name is what this server already calls itself.
		String resourceName = environment.getProperty("spring.ai.mcp.server.name",
				environment.getProperty("spring.application.name", ""));
		if (!resourceName.isBlank()) {
			builder.resourceName(resourceName);
		}
		builder.claims((claims) -> claims.remove("tls_client_certificate_bound_access_tokens"));
	}

	// Filters are added in configure, where Spring Security's own configurers add
	// theirs. The marker lets the startup check tell this chain from an application
	// chain that carries a resource server of its own ahead of it. The session
	// binding runs after the authorization filter, so a request without a token or a
	// scope reads its challenge first.
	@Override
	public void configure(HttpSecurity http) {
		if (this.marker != null) {
			http.addFilterBefore(this.marker, AuthorizationFilter.class);
		}
		if (this.sessionBinding != null) {
			http.addFilterAfter(this.sessionBinding, AuthorizationFilter.class);
		}
	}

}
