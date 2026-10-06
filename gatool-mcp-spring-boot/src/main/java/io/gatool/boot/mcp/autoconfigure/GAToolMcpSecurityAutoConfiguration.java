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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultWebSecurity;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.PathPatternRequestTransformer;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.security.web.debug.DebugFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.cors.CorsConfigurationSource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolApiProperties;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.internal.credentials.BuiltInCredentialStrategy;
import io.gatool.boot.mcp.internal.limit.RequestCaller;
import io.gatool.boot.mcp.internal.security.McpEndpointChainMarkerFilter;
import io.gatool.boot.mcp.internal.security.McpEndpointPaths;
import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;
import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;
import io.gatool.core.internal.operation.ScopeNames;
import io.gatool.core.model.GATool;

/**
 * Secures the MCP endpoint once Spring Security is on the classpath.
 *
 * <p>
 * With {@code gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication} off, the
 * endpoint is an OAuth 2.1 resource server, and startup stops until the issuer, the
 * audience and the baseline scopes are set, each through the property that names it. With
 * the switch on, the contributed chain permits every request to the endpoint, so Spring
 * Boot's default chain, which would ask for a password, stays off it.
 *
 * <p>
 * The chains follow Spring Boot's own back-off pattern, the one its management and
 * authorization server auto-configurations use: an application without a
 * {@link SecurityFilterChain} of its own gets two. The MCP chain, at the highest
 * precedence, covers the endpoint and its metadata path. A default chain at
 * {@link SecurityFilterProperties#BASIC_AUTH_ORDER} takes the shape of the one Boot would
 * have contributed: {@code anyRequest().authenticated()} behind a bearer token for the
 * same issuer where a {@code JwtDecoder} bean exists, the way Boot's resource server
 * chain is built, and behind form and basic login against Boot's generated user
 * otherwise, the way Boot's plain default chain is built. Boot's generated user backs off
 * for a decoder bean, which is why a login is offered only without one. Two rules are
 * GATool's own: the actuator's health endpoint stays open where the actuator is present,
 * and an error dispatch is permitted, so Boot's error controller answers a request the
 * MCP chain let through and Spring MVC refused, where the chain would otherwise answer
 * 401. The rest of the application keeps the protection Boot gave it. Both chains back
 * off as soon as the application declares any chain, and an application that declares its
 * own applies {@link McpServerSecurityConfigurer} in one of them. After the singletons
 * exist, a check reads the live filters for the endpoint and stops startup when the chain
 * that serves it lacks the bearer token filter, which is what shows the configurer ran.
 *
 * <p>
 * The bean condition keeps this configuration out of an application that excludes
 * GATool's own, such as a GraphQL API that a test starts beside the server under test
 * with GATool on the classpath.
 *
 * @author Željko Kozina
 */
@AutoConfiguration(after = GAToolMcpAutoConfiguration.class,
		beforeName = { "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.actuate.web.servlet"
						+ ".ManagementWebSecurityAutoConfiguration",
				"org.springframework.boot.security.oauth2.server.resource.autoconfigure.web"
						+ ".OAuth2ResourceServerWebSecurityAutoConfiguration",
				"org.springframework.boot.security.oauth2.client.autoconfigure.servlet"
						+ ".OAuth2ClientWebSecurityAutoConfiguration" })
@ConditionalOnClass(name = { GAToolMcpSecurityAutoConfiguration.HTTP_SECURITY,
		"org.springframework.security.web.SecurityFilterChain",
		"org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultWebSecurity" })
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(GAToolProperties.class)
public final class GAToolMcpSecurityAutoConfiguration {

	static final String HTTP_SECURITY = McpSecurityClasses.HTTP_SECURITY;

	static final List<String> ENDPOINT_METHODS = List.of("POST", "GET", "DELETE");

	// The configurer class ships in spring-security-config, which every Spring Security
	// application has, so the probe names a class of the resource server module itself.
	private static final String RESOURCE_SERVER_PROBE_CLASS = "org.springframework.security.oauth2.server.resource"
			+ ".web.authentication.BearerTokenAuthenticationFilter";

	private static final String RESOURCE_SERVER_STARTER = "spring-boot-starter-security-oauth2-resource-server";

	private static final String JWT_DECODER = "org.springframework.security.oauth2.jwt.JwtDecoder";

	private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

	private static final String MVC_HANDLER_MAPPING_INTROSPECTOR = "mvcHandlerMappingIntrospector";

	private static final String WEB_ENDPOINT_AUTO_CONFIGURATION = "org.springframework.boot.actuate.autoconfigure"
			+ ".endpoint.web.WebEndpointAutoConfiguration";

	private static final String HEALTH_ENDPOINT = "org.springframework.boot.health.actuate.endpoint.HealthEndpoint";

	private static final String HEALTH_ENDPOINT_ID = "health";

	private static final Log logger = LogFactory.getLog(GAToolMcpSecurityAutoConfiguration.class);

	/**
	 * Creates the auto-configuration, which Spring Boot instantiates at startup.
	 */
	public GAToolMcpSecurityAutoConfiguration() {
		// The beans come from the methods below; Spring Boot instantiates the class.
	}

	/**
	 * The caller the rate limiter counts against, read through the
	 * {@link SecurityContextHolderStrategy} bean an application declares.
	 * @param contextHolderStrategy the application's strategy bean, absent in most
	 * applications
	 * @return the caller
	 */
	// Spring Security's filters write the authentication through that bean and leave the
	// static holder untouched, so a limiter that read the static holder under such a bean
	// would see every caller as anonymous and count by address. The MCP
	// auto-configuration takes this bean where it exists and falls back to the static
	// holder, which serves an application without Spring Security's web support.
	@Bean
	RequestCaller gaToolMcpRequestCaller(ObjectProvider<SecurityContextHolderStrategy> contextHolderStrategy) {
		return RequestCaller
			.through(contextHolderStrategy.getIfUnique(SecurityContextHolder::getContextHolderStrategy));
	}

	/**
	 * Checks the settings the resource server needs, before any chain is built.
	 * @param properties the GATool properties
	 * @param environment the environment, for Spring Boot's resource server properties
	 * @param beanFactory the bean factory, for the application's own decoder
	 * @param catalog the catalog, for the tools and their scopes
	 * @param credentialStrategy the strategy that authenticates calls to the GraphQL API,
	 * present where the properties or the application select one
	 * @return a marker bean, so the check runs at startup
	 */
	@Bean
	ResourceServerSettingsCheck gaToolResourceServerSettingsCheck(GAToolProperties properties, Environment environment,
			ConfigurableListableBeanFactory beanFactory, GAToolCatalog catalog,
			ObjectProvider<ApiCredentialStrategy> credentialStrategy) {
		if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()
				|| SpringAiMcpKeys.servesStdio(environment)) {
			return new ResourceServerSettingsCheck();
		}
		stopWhenASharedCredentialServesToolsWithoutScopes(properties, catalog, credentialStrategy);
		if (!ClassUtils.isPresent(RESOURCE_SERVER_PROBE_CLASS, null)) {
			throw new InvalidConfigurationPropertyValueException(
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication", "false",
					"GATool secures the MCP endpoint as an OAuth 2.1 resource server, and Spring Security's resource "
							+ "server support is missing from this application. Add " + RESOURCE_SERVER_STARTER
							+ ", or set this property to true to serve the endpoint without authentication.");
		}
		if (!environment.containsProperty(McpEndpointPaths.ISSUER_PROPERTY)) {
			throw new InvalidConfigurationPropertyValueException(McpEndpointPaths.ISSUER_PROPERTY, null,
					McpEndpointPaths.ISSUER_UNSET_MESSAGE);
		}
		List<String> audiences = Binder.get(environment)
			.bind(McpEndpointPaths.AUDIENCES_PROPERTY, Bindable.listOf(String.class))
			.orElseGet(List::of);
		if (applicationDeclaresItsOwnDecoder(beanFactory)) {
			// Spring Boot's decoder is the one that reads the audiences property, so an
			// application with a decoder of its own owns the audience check, and the
			// property sits unread whether or not it is set.
			logger.warn("GATool leaves the audience check to this application's own JwtDecoder bean, because Spring "
					+ "Boot's decoder, which reads " + McpEndpointPaths.AUDIENCES_PROPERTY + ", backs off for it"
					+ (audiences.isEmpty() ? "" : ", and that property is unread by the bean") + ". MCP requires "
					+ "that check, so make sure that bean validates the aud claim.");
		}
		else if (audiences.isEmpty()) {
			throw new InvalidConfigurationPropertyValueException(McpEndpointPaths.AUDIENCES_PROPERTY, null,
					"MCP requires a server to check that a token was issued for it, so set this property to the "
							+ "audience the issuer writes for this server, and Spring Boot's decoder refuses every "
							+ "other token.");
		}
		if (properties.getMcp().getSecurity().getBaselineScopes().isEmpty()) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.security.baseline-scopes", null,
					"Every MCP call needs the baseline scopes, and the protected resource metadata and every 401 "
							+ "name them, so a client asks for them at sign-in. Set at least one, such as "
							+ "mcp:tools.");
		}
		for (String scope : properties.getMcp().getSecurity().getBaselineScopes()) {
			String problem = ScopeNames.problemWith(scope);
			if (problem != null) {
				throw new InvalidConfigurationPropertyValueException("gatool.mcp.security.baseline-scopes", scope,
						"The scope " + problem + ". A scope is printable ASCII without a space, a quote or a "
								+ "backslash, so that every challenge carries it as one value.");
			}
		}
		requireMetadataResource(properties.getMcp().getSecurity().getResource(), McpEndpointPaths.of(environment),
				contextPath(environment));
		warnWhenTheResourceFollowsTheHostHeader(properties.getMcp().getSecurity().getResource(), environment);
		if (properties.getApi().getCredentials().getStrategy() == null) {
			logger.info("gatool.api.credentials.strategy is unset, so the GraphQL API is called without a "
					+ "credential and sees every MCP caller alike. A tool whose file lists scopes is closed to "
					+ "callers without them, and a tool without a list needs the baseline scopes alone.");
		}
		return new ResourceServerSettingsCheck();
	}

	// RFC 9728 inserts the well-known path into the resource identifier, so the
	// identifier has to be a URI the client and the server spell the same way. It is
	// absolute, without a query or a fragment or a trailing slash, and on the endpoint's
	// own path, because the metadata document is served under that path alone.
	// Without the property, every challenge and the metadata document name the address
	// the request carried in its Host header, which the caller writes. On loopback that
	// is what a local client expects, and on any other address it is worth a line.
	private static void warnWhenTheResourceFollowsTheHostHeader(@Nullable String resource, Environment environment) {
		if (resource != null) {
			return;
		}
		String address = environment.getProperty("server.address");
		if (address != null && !address.isBlank() && isLoopback(address)) {
			return;
		}
		logger.warn("gatool.mcp.security.resource is unset, so every challenge and the protected resource metadata "
				+ "name the address each request carried in its Host header. Set it to the URL clients reach, "
				+ "such as https://mcp.example.com/mcp, so a client behind a proxy reads the right document and "
				+ "the audience is tied to one canonical URL.");
	}

	private static boolean isLoopback(String address) {
		try {
			return InetAddress.getByName(address).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

	// Boot strips a trailing slash from the context path before it maps the servlet,
	// so the check compares against the path Boot serves.
	private static String contextPath(Environment environment) {
		String contextPath = environment.getProperty("server.servlet.context-path", "");
		return contextPath.endsWith("/") ? contextPath.substring(0, contextPath.length() - 1) : contextPath;
	}

	private static void requireMetadataResource(@Nullable String resource, McpEndpointPaths paths, String contextPath) {
		if (resource == null) {
			return;
		}
		String reason = null;
		try {
			URI uri = new URI(resource);
			if (!uri.isAbsolute() || uri.getHost() == null
					|| (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) {
				reason = namesAnIdentifierOfAnotherScheme(uri)
						? describeTheUrlAndTheAudience(contextPath + paths.endpoint())
						: "it has to be an absolute http or https URL";
			}
			else if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
				reason = "it cannot carry a query or a fragment";
			}
			else if (resource.endsWith("/")) {
				reason = "it cannot end in a slash";
			}
			else if (!(contextPath + paths.endpoint()).equals(uri.getPath())) {
				reason = "its path has to be the MCP endpoint's, " + contextPath + paths.endpoint()
						+ ", because the metadata document is served under that path";
			}
		}
		catch (URISyntaxException ex) {
			reason = "it fails to parse as a URI";
		}
		if (reason != null) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.security.resource", resource,
					"The canonical resource URI is what clients send as the resource parameter and what the "
							+ "metadata document publishes, and " + reason + ".");
		}
	}

	// An issuer may name an API by an identifier of its own: Microsoft Entra ID's App ID
	// URI is api://<client-id> by default. An operator who knows the API under that name
	// writes it here, so a value under a scheme other than http and https gets the
	// longer sentence. A value without a scheme is a URL written short, and keeps the
	// sentence about the URL alone.
	private static boolean namesAnIdentifierOfAnotherScheme(URI uri) {
		String scheme = uri.getScheme();
		return scheme != null && !"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme);
	}

	// The refusal stays, for two reasons. RFC 9728 defines the resource identifier as an
	// https URL, and every challenge builds the address of the metadata document from the
	// scheme, host and port of this value, which an identifier such as api://<client-id>
	// does not hold. The sentence says which value the property takes and what the
	// audiences property takes: the aud claim as the issuer writes it, which Entra ID
	// fills with the client ID alone in a v2.0 token. GATool leaves the aud claim to the
	// JwtDecoder: Spring Boot's
	// decoder compares the claim with its audiences property, and this property stays
	// out of that check, so a token issued for the identifier is accepted once the
	// audiences property names it.
	private static String describeTheUrlAndTheAudience(String endpointPath) {
		return "it has to be an absolute http or https URL, because every challenge builds the address of the "
				+ "metadata document from it. Set it to the URL clients reach the MCP endpoint at, such as "
				+ "https://mcp.example.com" + endpointPath + ". An identifier under another scheme, such as the "
				+ "App ID URI api://<client-id> of Microsoft Entra ID, names the API to the issuer and stays out "
				+ "of this property. " + McpEndpointPaths.AUDIENCES_PROPERTY + " takes the value the issuer "
				+ "writes as the aud claim, which for Microsoft Entra ID is the client ID alone in a v2.0 token "
				+ "and the client ID or the App ID URI in a v1.0 token. Spring Boot's decoder compares the two "
				+ "on every request, and an application with a JwtDecoder bean of its own makes that check there";
	}

	// Stops startup when the GraphQL API sees GATool as the caller and a tool is
	// published without scopes, because the per-tool scope check is then the only check
	// that depends on the caller. Under a static header the API cannot tell one MCP
	// caller from another, so a tool without scopes is a tool every signed-in caller may
	// use, and a file has to say that on purpose with an empty list. Token exchange keeps
	// the caller's identity, so it is exempt. An application's own strategy replaces the
	// built-in one, and whether it carries the caller is its own business, so the same
	// reasoning goes to the log as one line instead of a stop.
	private static void stopWhenASharedCredentialServesToolsWithoutScopes(GAToolProperties properties,
			GAToolCatalog catalog, ObjectProvider<ApiCredentialStrategy> credentialStrategy) {
		GAToolApiProperties.Credentials.CredentialStrategy strategy = properties.getApi()
			.getCredentials()
			.getStrategy();
		ApiCredentialStrategy applicationStrategy = applicationStrategyAmong(credentialStrategy);
		if (applicationStrategy != null) {
			List<String> unscoped = toolsWithoutScopes(catalog);
			if (!unscoped.isEmpty()) {
				logger.info("The ApiCredentialStrategy bean " + applicationStrategy.getClass().getName()
						+ " authenticates every call to the GraphQL API. If it sends one credential for every MCP "
						+ "caller, the API sees GATool as the caller of every tool, and the scopes a tool requires "
						+ "are the only check that depends on who is calling. These MCP tools do not declare any: "
						+ String.join(", ", unscoped) + ".");
			}
			return;
		}
		if (strategy == null || !strategy.sharesOneIdentity()) {
			return;
		}
		List<String> unscoped = toolsWithoutScopes(catalog);
		if (unscoped.isEmpty()) {
			return;
		}
		throw new McpSecurityException("gatool.api.credentials.strategy is " + strategy.propertyValue()
				+ ", so the GraphQL API sees GATool as the caller of every tool, and the scopes a tool requires are "
				+ "the only check that depends on who is calling. These MCP tools do not declare any: "
				+ String.join(", ", unscoped) + ".",
				"Add @gatool(scopes: [...]) to each operation file, or @gatool(scopes: []) for a tool that "
						+ "every caller with the baseline scopes may use. The three dynamic tools take their list "
						+ "from gatool.dev.experimental.dynamic-operations.required-scopes.");
	}

	// The built-in strategies share a type, which is how the executor tells them from
	// the application's own; the lookup is by uniqueness, so
	// two application beans pass here and fail where the executor reads them, with
	// Spring's own message.
	private static @Nullable ApiCredentialStrategy applicationStrategyAmong(
			ObjectProvider<ApiCredentialStrategy> credentialStrategy) {
		ApiCredentialStrategy candidate = credentialStrategy.getIfUnique();
		if (candidate == null || candidate instanceof BuiltInCredentialStrategy) {
			return null;
		}
		return candidate;
	}

	private static List<String> toolsWithoutScopes(GAToolCatalog catalog) {
		return catalog.mcpTools().stream().filter((tool) -> tool.scopes() == null).map(GATool::name).toList();
	}

	// Boot names its own decoder beans jwtDecoderByIssuerUri, jwtDecoderByJwkKeySetUri
	// and jwtDecoderByPublicKeyValue, and any other bean of the type is the
	// application's.
	private static boolean applicationDeclaresItsOwnDecoder(ConfigurableListableBeanFactory beanFactory) {
		if (!ClassUtils.isPresent(JWT_DECODER, null)) {
			return false;
		}
		Class<?> decoderType = ClassUtils.resolveClassName(JWT_DECODER, null);
		for (String name : beanFactory.getBeanNamesForType(decoderType, true, false)) {
			if (!name.startsWith("jwtDecoderBy")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks, once every singleton exists, that the chain serving the MCP endpoint
	 * carries the resource server, that the metadata document answers anonymously, and
	 * that the compliance filter runs ahead of Spring Security.
	 * @param springSecurityFilterChain spring Security's filter bean, whose proxy knows
	 * the live chains, absent where Boot's web security auto-configuration is excluded
	 * @param privilegeEvaluator spring Security's evaluator, which answers what a chain
	 * decides for one request without sending it
	 * @param properties the GATool properties
	 * @param environment the environment
	 * @param beanFactory the bean factory, for the compliance filter's registration
	 * @param settings the bean whose creation ran the resource server settings check, so
	 * that check runs first
	 * @return the check, which runs after the singletons are instantiated
	 */
	// An application that declares its own chain and forgets the configurer would
	// serve the endpoint under whatever that chain does, form login included, which
	// an MCP client cannot use. A chain whose matcher covers the endpoint alone leaves
	// the metadata document to another chain, which answers a client's anonymous GET
	// with a 401 it cannot follow. Both are plain reads of the live chains, so each
	// check names the line to change. The filter bean is taken through a provider,
	// because an application that excludes Boot's web security auto-configuration has
	// Spring Security on the classpath and lacks the filter. With the switch on that
	// is its own choice, and with the switch off the endpoint would be open, so the
	// check says which bean is missing.
	@Bean
	SmartInitializingSingleton gaToolMcpChainStartupCheck(
			@Qualifier(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME) ObjectProvider<Filter> springSecurityFilterChain,
			ObjectProvider<WebInvocationPrivilegeEvaluator> privilegeEvaluator, GAToolProperties properties,
			Environment environment, ConfigurableListableBeanFactory beanFactory,
			ResourceServerSettingsCheck settings) {
		return () -> {
			boolean unsecured = properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()
					|| SpringAiMcpKeys.servesStdio(environment);
			Filter securityFilter = springSecurityFilterChain.getIfAvailable();
			if (securityFilter == null) {
				if (unsecured) {
					return;
				}
				throw missingSecurityFilter();
			}
			FilterChainProxy filterChainProxy = unwrap(securityFilter);
			requireComplianceFilterAheadOfSecurity(beanFactory, environment);
			if (unsecured) {
				return;
			}
			McpEndpointPaths paths = McpEndpointPaths.of(environment);
			String lineToAdd = "http.securityMatcher(\"" + paths.endpoint() + "\", \"" + paths.metadataPath()
					+ "\").with(McpServerSecurityConfigurer.mcpServer(), withDefaults()).";
			requireComplianceFilter(beanFactory, paths.endpoint());
			requireEndpointUnderTheConfigurer(filterChainProxy, paths, lineToAdd);
			requireMetadataOpenToAnonymousReads(filterChainProxy, privilegeEvaluator.getIfAvailable(), paths,
					lineToAdd);
		};
	}

	private static McpSecurityException missingSecurityFilter() {
		return new McpSecurityException(
				"The bean " + AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME
						+ " is missing, so the MCP endpoint runs without a security filter chain, and the "
						+ "resource server GATool configures cannot run.",
				"Leave Spring Boot's web security "
						+ "auto-configuration in, or add @EnableWebSecurity, so that Spring Security registers "
						+ "its filter.");
	}

	// The marker is what only the configurer installs, so an application chain with a
	// resource server of its own, ordered ahead, is told apart. Each of the endpoint's
	// three methods is matched on its own, so a chain whose matcher reads the method is
	// seen as well.
	private static void requireEndpointUnderTheConfigurer(FilterChainProxy filterChainProxy, McpEndpointPaths paths,
			String lineToAdd) {
		for (String method : ENDPOINT_METHODS) {
			List<Filter> filters = filtersFor(filterChainProxy, paths.endpoint(), method);
			boolean secured = filters.stream().anyMatch(BearerTokenAuthenticationFilter.class::isInstance)
					&& filters.stream().anyMatch(McpEndpointChainMarkerFilter.class::isInstance);
			if (!secured) {
				throw new McpSecurityException(
						"The security filter chain that serves " + method + " " + paths.endpoint()
								+ " lacks the resource server GATool configures, so the MCP endpoint "
								+ "would answer under that chain's own rules.",
						"Give the endpoint a chain of its own, " + "ordered ahead of the rest: " + lineToAdd);
			}
		}
	}

	private static void requireMetadataOpenToAnonymousReads(FilterChainProxy filterChainProxy,
			@Nullable WebInvocationPrivilegeEvaluator evaluator, McpEndpointPaths paths, String lineToAdd) {
		List<Filter> metadataFilters = filterChainProxy.getFilters(paths.metadataPath());
		boolean served = metadataFilters != null
				&& metadataFilters.stream().anyMatch(OAuth2ProtectedResourceMetadataFilter.class::isInstance);
		boolean open = evaluator == null || evaluator.isAllowed(paths.metadataPath(), ANONYMOUS);
		if (!served || !open) {
			throw new McpSecurityException(
					"The security filter chain that serves " + paths.endpoint()
							+ " leaves its protected resource metadata at " + paths.metadataPath() + " to "
							+ (served ? "a rule that refuses an anonymous GET" : "another chain")
							+ ", and an MCP client reads that document before it has a token.",
					"Cover both paths in the endpoint's own chain: " + lineToAdd);
		}
	}

	// FilterChainProxy.getFilters matches a synthetic GET, so the chains are matched
	// here against a request of each method the endpoint takes. A chain whose matcher
	// reads a header stays out of reach, and the Javadoc of the configurer says so.
	// The transformer is the wrapper Spring's own lookup applies, and a path matcher
	// needs it because the stand-in request refuses attributes.
	static List<Filter> filtersFor(FilterChainProxy filterChainProxy, String endpoint, String method) {
		HttpServletRequest request = new PathPatternRequestTransformer()
			.transform(new FilterInvocation(endpoint, method).getRequest());
		for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
			if (chain.matches(request)) {
				return chain.getFilters();
			}
		}
		return List.of();
	}

	// Spring's filter bean is a FilterChainProxy, wrapped in a DebugFilter under
	// @EnableWebSecurity(debug = true), so the bean is taken as a Filter and unwrapped.
	static FilterChainProxy unwrap(Filter springSecurityFilterChain) {
		if (springSecurityFilterChain instanceof DebugFilter debug) {
			return debug.getFilterChainProxy();
		}
		if (springSecurityFilterChain instanceof FilterChainProxy proxy) {
			return proxy;
		}
		throw new McpSecurityException(
				"The bean " + AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME + " is a "
						+ springSecurityFilterChain.getClass().getName() + ", and GATool's startup check reads the "
						+ "live chains from Spring Security's FilterChainProxy.",
				"Leave that bean to Spring Security.");
	}

	// The security chain decides a tools/call by what the compliance filter read, so a
	// context without that filter would refuse every POST at runtime, and startup says
	// so first. The lookup is by type, because a registration under another name backs
	// GATool's own off.
	private static void requireComplianceFilter(ConfigurableListableBeanFactory beanFactory, String endpoint) {
		// A registration that is disabled, or whose URL patterns leave the endpoint out,
		// holds the filter and leaves it unrun on the endpoint.
		boolean present = beanFactory.getBeansOfType(FilterRegistrationBean.class)
			.values()
			.stream()
			.anyMatch((registration) -> registration.getFilter() instanceof McpComplianceFilter
					&& registration.isEnabled() && covers(urlPatternsOf(registration), endpoint));
		if (!present) {
			throw new McpSecurityException(
					"No FilterRegistrationBean in this context holds GATool's MCP compliance filter, "
							+ "which reads each request body ahead of Spring Security so that the scope check can tell "
							+ "which tool a call names. Without it every POST to the MCP endpoint is refused.",
					"Leave the registration " + GAToolMcpAutoConfiguration.COMPLIANCE_FILTER_BEAN_NAME
							+ " to GATool, or register a FilterRegistrationBean holding a McpComplianceFilter.");
		}
	}

	// The bean lookup hands back raw registrations, and the patterns are strings.
	private static Collection<String> urlPatternsOf(FilterRegistrationBean<?> registration) {
		return registration.getUrlPatterns();
	}

	// A registration without patterns runs on every request. Servlet patterns are an
	// exact path, a prefix ending in /*, or an extension, and the endpoint is a plain
	// path, so the first two are what can cover it.
	private static boolean covers(Collection<String> urlPatterns, String endpoint) {
		if (urlPatterns.isEmpty()) {
			return true;
		}
		for (String pattern : urlPatterns) {
			if (pattern.equals(endpoint) || "/*".equals(pattern)) {
				return true;
			}
			// A prefix mapping matches the prefix itself as well as what lies under it.
			if (pattern.endsWith("/*")) {
				String prefix = pattern.substring(0, pattern.length() - 2);
				if (endpoint.equals(prefix) || endpoint.startsWith(prefix + "/")) {
					return true;
				}
			}
		}
		return false;
	}

	// The compliance filter answers a foreign origin and a superseded revision ahead of
	// Spring Security, which is only true while its registration order is below the
	// security filter's. Both are integers read from beans, so the check compares them.
	private static void requireComplianceFilterAheadOfSecurity(ConfigurableListableBeanFactory beanFactory,
			Environment environment) {
		beanFactory.getBeansOfType(FilterRegistrationBean.class)
			.values()
			.stream()
			.filter((candidate) -> candidate.getFilter() instanceof McpComplianceFilter)
			.forEach((registration) -> requireComplianceFilterAheadOfSecurity(registration.getOrder(), beanFactory,
					environment));
	}

	private static void requireComplianceFilterAheadOfSecurity(int complianceOrder,
			ConfigurableListableBeanFactory beanFactory, Environment environment) {
		int securityOrder = securityFilterOrder(beanFactory, environment);
		if (securityOrder == Ordered.HIGHEST_PRECEDENCE) {
			// Nothing runs ahead of the highest precedence, so the two share it and the
			// container decides, which the log says once.
			logger.warn(GAToolMcpAutoConfiguration.SECURITY_FILTER_ORDER_PROPERTY + " is the highest precedence, so "
					+ "the MCP compliance filter shares that order and may run behind Spring Security, where a "
					+ "foreign origin reads a 401 in place of the 403 MCP asks for. Leave that property at its "
					+ "default to keep the filter ahead.");
			return;
		}
		if (complianceOrder >= securityOrder) {
			throw new McpSecurityException(
					"The MCP compliance filter is registered at order " + complianceOrder
							+ " and Spring Security's filter at " + securityOrder + ", so a foreign origin and a "
							+ "superseded revision would meet the bearer token check first and read a 401 in place of "
							+ "the answer MCP asks for.",
					"Register " + GAToolMcpAutoConfiguration.COMPLIANCE_FILTER_BEAN_NAME + " below "
							+ GAToolMcpAutoConfiguration.SECURITY_FILTER_ORDER_PROPERTY + " (" + securityOrder
							+ "), or leave the registration to gatool.");
		}
	}

	// Boot registers Spring Security's filter through the securityFilterChainRegistration
	// bean, which carries the order it was given, so that bean is read where it exists,
	// and the property it was built from otherwise.
	private static int securityFilterOrder(ConfigurableListableBeanFactory beanFactory, Environment environment) {
		if (beanFactory.containsBean(SECURITY_FILTER_REGISTRATION)
				&& beanFactory.getBean(SECURITY_FILTER_REGISTRATION) instanceof Ordered ordered) {
			return ordered.getOrder();
		}
		return environment.getProperty(GAToolMcpAutoConfiguration.SECURITY_FILTER_ORDER_PROPERTY, Integer.class,
				GAToolMcpAutoConfiguration.DEFAULT_SECURITY_FILTER_ORDER);
	}

	private static final String SECURITY_FILTER_REGISTRATION = "securityFilterChainRegistration";

	// An anonymous caller, the way Spring Security represents a request without a
	// token, for asking the chains what they would decide.
	private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken("GATool", "anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	/**
	 * The two chains the starter contributes while the application is without one of its
	 * own.
	 */
	// Both chains are lazy, so an application that keeps Spring Security on the
	// classpath but excludes Boot's web security auto-configuration, and so lacks the
	// HttpSecurity to build from, starts with the unsafe switch on. Nothing asks for a
	// chain there, and the startup check below says what is missing when the switch
	// is off. With Boot's auto-configuration in, its WebSecurityConfiguration collects
	// every chain bean, lazy or not, while the singletons are created.
	//
	// The stdio condition keeps both chains out of a servlet application that serves
	// MCP over stdio. The endpoint is absent from its HTTP port, so the MCP chain
	// would stop startup on the missing issuer of an endpoint nobody can reach, and
	// Spring Boot's own default chain secures the application's pages.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnDefaultWebSecurity
	@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "false",
			matchIfMissing = true)
	static class ContributedChain {

		static final String REPLACES_BOTH = " Declaring any SecurityFilterChain of the application's own replaces "
				+ "both contributed chains.";

		// The settings check is a parameter, so a missing issuer stops startup with its
		// own message before this chain asks for a decoder it cannot build.
		@Bean
		@Lazy
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain gaToolMcpSecurityFilterChain(HttpSecurity http, GAToolProperties properties,
				Environment environment, ResourceServerSettingsCheck settings) {
			McpEndpointPaths paths = McpEndpointPaths.of(environment);
			if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
				logger
					.warn("GATool contributed a security filter chain that permits every request to " + paths.endpoint()
							+ ", because gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication is on, "
							+ "beside a default chain for every other path of this application." + REPLACES_BOTH);
			}
			else {
				logger.info("GATool contributed a security filter chain for " + paths.endpoint() + " and "
						+ paths.metadataPath() + ", an OAuth 2.1 resource server for the issuer at "
						+ environment.getProperty(McpEndpointPaths.ISSUER_PROPERTY)
						+ ", beside a default chain for every other path of this application." + REPLACES_BOTH);
			}
			return http.securityMatcher(paths.endpoint(), paths.metadataPath())
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

		/**
		 * The chain Boot's own auto-configuration would have contributed, kept alive
		 * beside the MCP chain, at the order and in the shape Boot gives it.
		 * @param http the builder
		 * @param anonymousPaths the actuator paths the chain opens, present with the
		 * actuator
		 * @param beanFactory the bean factory, for the decoder bean and the beans the
		 * CORS support needs
		 * @return the default chain
		 */
		// With the MCP chain alone, Boot's default chain and its management chain back
		// off, and a request outside the endpoint would run without any security filter,
		// so GET /nope would answer 404 where a secured application answers 401. With a
		// JwtDecoder bean, Boot's OAuth2ResourceServerWebSecurityAutoConfiguration
		// contributes a chain that reads bearer tokens, and its generated user backs off,
		// so a form or basic login here would lack a user store: every password would
		// fail, and a valid token would be refused with a Basic challenge on every path
		// outside the endpoint. Without a decoder the generated user exists, and this
		// chain keeps the shape of Boot's plain default chain. The decoder is read from
		// the bean factory at build time, because this configuration is ordered ahead of
		// the one that registers Boot's decoder, and a bean condition here would read the
		// factory before that definition exists.
		@Bean
		@Lazy
		@Order(SecurityFilterProperties.BASIC_AUTH_ORDER)
		SecurityFilterChain gaToolDefaultSecurityFilterChain(HttpSecurity http,
				ObjectProvider<AnonymousPaths> anonymousPaths, ConfigurableListableBeanFactory beanFactory) {
			AnonymousPaths open = anonymousPaths.getIfAvailable();
			boolean bearer = decoderDeclared(beanFactory);
			http.authorizeHttpRequests((requests) -> {
				// Boot 4.1.1 dropped ErrorPageSecurityFilter, so the error dispatch of a
				// request Spring MVC refused, such as a method the endpoint leaves
				// unrouted, is decided by this chain on the /error path. Without this
				// rule an anonymous request the MCP chain permitted would read a 401 with
				// a login challenge in place of Boot's error body.
				requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				if (open != null) {
					requests.requestMatchers(open.matchers().toArray(RequestMatcher[]::new)).permitAll();
				}
				requests.anyRequest().authenticated();
			});
			// Boot's management chain applies cors() and its plain default chain leaves
			// it out, so the actuator decides here as well. It is applied under the
			// condition Spring Security itself needs for it, a CorsConfigurationSource
			// bean or Spring MVC's introspector, because http.cors() without either
			// stops the build.
			if (actuatorPresent() && corsSupportAvailable(beanFactory)) {
				http.cors(Customizer.withDefaults());
			}
			String healthOpen = (open != null) ? " and the actuator's health endpoint open" : "";
			if (!bearer) {
				http.formLogin(Customizer.withDefaults());
				http.httpBasic(Customizer.withDefaults());
				logger.info("GATool contributed a default security filter chain that keeps every other path of this "
						+ "application behind Spring Boot's generated user, with form and basic login" + healthOpen
						+ "." + REPLACES_BOTH);
				return http.build();
			}
			return buildBearerChain(http, healthOpen);
		}

		/**
		 * Builds the default chain in the shape Boot's resource server chain would have:
		 * a bearer token on every request, without a session, a saved request or CSRF
		 * protection.
		 * @param http the builder, with its request rules and CORS support applied
		 * @param healthOpen the clause the log line adds while the actuator's health
		 * endpoint is open
		 * @return the default chain
		 */
		private static SecurityFilterChain buildBearerChain(HttpSecurity http, String healthOpen) {
			http.oauth2ResourceServer((resourceServer) -> resourceServer.jwt(Customizer.withDefaults()));
			// A bearer token arrives on every request, so this shape works without a
			// session or a saved request, the way the MCP chain does. With Boot's
			// defaults the request cache would save each refused request in a new
			// session, so a caller without a token could fill the session store. The form
			// login shape above keeps both, because a login needs them.
			http.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
			http.requestCache(RequestCacheConfigurer::disable);
			// CSRF protection guards a browser whose cookie authenticates it, and this
			// shape authenticates by bearer token alone, so a forged request arrives
			// without the token and is refused as any anonymous request is. Left on, the
			// CSRF token repository would open a session for every anonymous POST,
			// whatever the session policy says. A request with a token is exempt anyway.
			http.csrf(CsrfConfigurer::disable);
			logger.info("GATool contributed a default security filter chain that keeps every other path of this "
					+ "application behind a bearer token for the same issuer, the way Spring Boot's resource "
					+ "server chain would have" + healthOpen + "." + REPLACES_BOTH);
			DefaultSecurityFilterChain chain = http.build();
			// Spring Security's resource server adds a filter that serves a protected
			// resource metadata document for every path under
			// /.well-known/oauth-protected-resource, naming that path as the resource,
			// and the filter's matcher cannot be narrowed. The MCP chain serves the
			// endpoint's document and every challenge names it; a root document would
			// carry the root resource's identifier, which a client has to reject, so this
			// chain is rebuilt without that filter and answers the path like any other.
			List<Filter> filters = chain.getFilters()
				.stream()
				.filter((filter) -> !(filter instanceof OAuth2ProtectedResourceMetadataFilter))
				.toList();
			return new DefaultSecurityFilterChain(chain.getRequestMatcher(), filters);
		}

		// Boot's management chain exists with the actuator's web endpoint support, which
		// is the class its condition names.
		private static boolean actuatorPresent() {
			return ClassUtils.isPresent(WEB_ENDPOINT_AUTO_CONFIGURATION,
					GAToolMcpSecurityAutoConfiguration.class.getClassLoader());
		}

		// Boot's own jwt chain is conditional on a JwtDecoder bean of any name, and the
		// class is absent from an application without the resource server module.
		private static boolean decoderDeclared(ConfigurableListableBeanFactory beanFactory) {
			if (!ClassUtils.isPresent(JWT_DECODER, null)) {
				return false;
			}
			Class<?> decoderType = ClassUtils.resolveClassName(JWT_DECODER, null);
			return beanFactory.getBeanNamesForType(decoderType, true, false).length > 0;
		}

		// Spring Security's cors() reads a CorsConfigurationSource bean, and falls back
		// to Spring MVC's HandlerMappingIntrospector bean, which WebMvcAutoConfiguration
		// registers under this name; without either it throws at build time.
		private static boolean corsSupportAvailable(ConfigurableListableBeanFactory beanFactory) {
			if (!ClassUtils.isPresent(DISPATCHER_SERVLET, GAToolMcpSecurityAutoConfiguration.class.getClassLoader())) {
				return false;
			}
			return beanFactory.containsBean(MVC_HANDLER_MAPPING_INTROSPECTOR)
					|| beanFactory.getBeanNamesForType(CorsConfigurationSource.class, true, false).length > 0;
		}

		/**
		 * The actuator paths the default chain opens to anonymous callers: the health
		 * endpoint and its additional paths, the ones Boot's management chain opens.
		 */
		// The class condition keeps EndpointRequest, which reads actuator types, out of
		// an application without the actuator. The endpoint is named by its id, so the
		// health module stays off this module's compile path; the condition still asks
		// for its class, the way Boot's management chain does, because the endpoint
		// exists only with it.
		@Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(name = { WEB_ENDPOINT_AUTO_CONFIGURATION, HEALTH_ENDPOINT })
		static class HealthEndpointRules {

			@Bean
			AnonymousPaths gaToolHealthEndpointPaths() {
				return new AnonymousPaths(List.of(EndpointRequest.to(HEALTH_ENDPOINT_ID),
						EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HEALTH_ENDPOINT_ID)));
			}

		}

	}

	/**
	 * Paths the default chain permits without authentication.
	 *
	 * @param matchers the matchers of those paths
	 */
	record AnonymousPaths(List<RequestMatcher> matchers) {
	}

	/**
	 * A bean whose creation runs the settings check.
	 */
	static final class ResourceServerSettingsCheck {

	}

}
