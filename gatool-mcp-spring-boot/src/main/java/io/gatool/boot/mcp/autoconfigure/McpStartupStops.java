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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolMcpProperties;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.core.model.GATool;

/**
 * The checks that stop startup on a setting {@link GAToolMcpAutoConfiguration} cannot
 * serve, and the warning that belongs beside the reactive stop.
 *
 * <p>
 * The class holds the checks so that the auto-configuration keeps its bean methods and
 * little else. The log lines keep the auto-configuration's category, so a level set on
 * that class still covers them.
 *
 * @author Željko Kozina
 */
final class McpStartupStops {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	private static final Set<String> SERVED_PROTOCOLS = Set.of("STATELESS", "STREAMABLE");

	private static final String SERVER_TYPE_PROPERTY = "spring.ai.mcp.server.type";

	private static final String TOOL_CAPABILITY_PROPERTY = "spring.ai.mcp.server.capabilities.tool";

	static final String UNAUTHENTICATED_SWITCH = "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication";

	private static final String WEB_APPLICATION_TYPE_PROPERTY = "spring.main.web-application-type";

	// The bean types a reactive application declares, by name, because each one lives
	// in a jar this module leaves optional. Each entry pairs the class with the word the
	// warning uses for it, in the order the warning lists them.
	private static final List<Map.Entry<String, String>> REACTIVE_BEAN_TYPES = List.of(
			Map.entry("org.springframework.web.server.WebFilter", "WebFilter"),
			Map.entry("org.springframework.security.web.server.SecurityWebFilterChain", "SecurityWebFilterChain"),
			Map.entry("org.springframework.web.reactive.function.server.RouterFunction", "reactive RouterFunction"));

	private McpStartupStops() {
	}

	/**
	 * Fails startup when one of the three Spring AI settings that select the server
	 * carries whitespace around its value.
	 * @param environment the environment the settings are read from
	 */
	// The stops below trim a value before they compare it, and reading the stdio flag as
	// a Boolean trims it too. Spring's @ConditionalOnProperty compares the value as
	// written. A .properties file keeps a trailing space, so "SYNC " would pass every
	// stop and miss every condition: the application would start without a server and the
	// endpoint would answer 500. With "true " for the stdio flag, GATool's stdio wiring
	// would back off and Spring AI's own transport would serve.
	static void stopOnWhitespaceAroundAServerSetting(Environment environment) {
		for (String property : List.of(GAToolEnvironmentPostProcessor.PROTOCOL, SERVER_TYPE_PROPERTY,
				SpringAiMcpKeys.STDIO)) {
			String value = environment.getProperty(property);
			if (value != null && !value.equals(value.strip())) {
				throw new InvalidConfigurationPropertyValueException(property, value,
						"The value carries whitespace at its start or its end. Spring compares this property as "
								+ "it is written, so the server this value selects would stay unbuilt. Write the "
								+ "value without the whitespace.");
			}
		}
	}

	/**
	 * Fails startup when {@code spring.ai.mcp.server.protocol} carries a value other than
	 * {@code STATELESS} or {@code STREAMABLE}.
	 * @param environment the environment the property is read from
	 */
	// Spring AI also serves SSE, which it deprecates, and GATool publishes tools for the
	// stateless and the stateful servers alone. Without this check, SSE would start a
	// server that answers every request and serves zero GATool tools, and the log would
	// not say so.
	static void stopOnUnsupportedProtocol(Environment environment) {
		String protocol = environment.getProperty(GAToolEnvironmentPostProcessor.PROTOCOL);
		if (protocol == null || SERVED_PROTOCOLS.contains(protocol.trim().toUpperCase(Locale.ROOT))) {
			return;
		}
		throw new InvalidConfigurationPropertyValueException(GAToolEnvironmentPostProcessor.PROTOCOL, protocol,
				"GATool serves STATELESS and STREAMABLE over HTTP, and STREAMABLE for stdio. Leave the property "
						+ "unset, and GATool sets STATELESS for HTTP and STREAMABLE for stdio.");
	}

	/**
	 * Fails startup when {@code spring.ai.mcp.server.type} carries a value other than
	 * {@code SYNC}.
	 * @param environment the environment the property is read from
	 */
	// Spring AI's async server reads AsyncToolSpecification beans alone, so GATool's sync
	// lists would go unread and the endpoint would answer tools/list with an empty array.
	static void stopOnAsyncServer(Environment environment) {
		String type = environment.getProperty(SERVER_TYPE_PROPERTY);
		if (type == null || "SYNC".equalsIgnoreCase(type.trim())) {
			return;
		}
		throw new InvalidConfigurationPropertyValueException(SERVER_TYPE_PROPERTY, type,
				"GATool publishes synchronous tool specifications, and an asynchronous server reads the "
						+ "asynchronous ones alone, so it would serve zero GATool tools. Set this property to SYNC, "
						+ "or leave it unset, which is the same thing.");
	}

	/**
	 * Refuses to start a reactive web application.
	 *
	 * <p>
	 * Every guard in front of the MCP endpoint is a servlet filter carrying the servlet
	 * condition. On WebFlux each one backs off while this auto-configuration publishes
	 * the tools regardless. The endpoint would then come up with the {@code Origin} check
	 * and the {@code MCP-Protocol-Version} check absent, both of which MCP states as
	 * MUST, and with the request body cap, the revision limit and the security chain
	 * absent too. The rate limiter would still run and count every caller as one, because
	 * {@code RequestCaller} answers with the stdio caller where no servlet request is in
	 * reach.
	 *
	 * <p>
	 * This is the judgement {@link #stopOnAsyncServer} already makes. A deployment shape
	 * this release cannot serve safely stops at startup and says why, where backing off
	 * quietly would leave a server answering every caller with the rules switched off and
	 * the log silent about it.
	 *
	 * <p>
	 * The context type comes from Spring Boot's own
	 * {@code ReactiveWebApplicationContext}, so the check works without a WebFlux class
	 * of its own. A nested configuration under
	 * {@code @ConditionalOnWebApplication(REACTIVE)} would work as well, because that
	 * condition probes the WebFlux class by name, and it would need a bean whose creation
	 * throws to say anything. The explicit check stays, because it runs in the one place
	 * the other two startup stops run, and a reader finds the three together.
	 *
	 * <p>
	 * The value passed to the exception is the one the environment holds, null while the
	 * type was inferred from the classpath, so Boot's analyzer prints the origin of a
	 * value that was set.
	 * @param applicationContext the context this auto-configuration runs in
	 */
	static void stopOnReactiveWebApplication(ApplicationContext applicationContext) {
		if (!(applicationContext instanceof ReactiveWebApplicationContext)) {
			return;
		}
		throw new InvalidConfigurationPropertyValueException(WEB_APPLICATION_TYPE_PROPERTY,
				applicationContext.getEnvironment().getProperty(WEB_APPLICATION_TYPE_PROPERTY),
				"GATool does not serve its MCP endpoint over WebFlux yet. Every guard in front of the "
						+ "endpoint is a servlet filter: the Origin check and the MCP-Protocol-Version check, "
						+ "which MCP requires, the request body cap, the revision limit, the per-caller rate limit "
						+ "and the security chain. On WebFlux each one backs off while the tools stay published, "
						+ "so the endpoint would answer every caller with those rules off. Run this application on "
						+ "Spring MVC, or leave the MCP starter out and add "
						+ "gatool-in-process-spring-boot-starter, whose tools run inside the application and do "
						+ "not need these filters.");
	}

	/**
	 * Warns a servlet application that declares beans of WebFlux's kind.
	 *
	 * <p>
	 * A WebFlux application that adds the MCP starter gets Spring MVC and Tomcat with it,
	 * and Boot runs the servlet stack while both are present, so the application starts
	 * on Tomcat and its {@code WebFilter}, {@code SecurityWebFilterChain} and reactive
	 * {@code RouterFunction} beans stay unserved, while GATool serves MCP on the servlet
	 * stack. The stop above reads the context type alone, so that application would
	 * otherwise read a normal startup. A check keyed on {@code spring-webflux} being
	 * present would fire for a Spring MVC application that carries it for
	 * {@code WebClient}, so the beans are the signal. Each type is resolved by name and
	 * skipped while its jar is absent.
	 * @param applicationContext the context this auto-configuration runs in
	 */
	static void warnOnReactiveBeansInAServletApplication(ApplicationContext applicationContext) {
		if (!(applicationContext instanceof WebApplicationContext)) {
			return;
		}
		ClassLoader classLoader = applicationContext.getClassLoader();
		List<String> beans = new ArrayList<>();
		for (Map.Entry<String, String> reactiveType : REACTIVE_BEAN_TYPES) {
			if (!ClassUtils.isPresent(reactiveType.getKey(), classLoader)) {
				continue;
			}
			Class<?> type = ClassUtils.resolveClassName(reactiveType.getKey(), classLoader);
			for (String beanName : applicationContext.getBeanNamesForType(type, true, false)) {
				beans.add(beanName + " (" + reactiveType.getValue() + ")");
			}
		}
		if (beans.isEmpty()) {
			return;
		}
		logger.warn("This application declares beans of WebFlux's kind, " + String.join(", ", beans) + ", and runs "
				+ "on the servlet stack, because the MCP starter brings Spring MVC and Tomcat and Boot chooses "
				+ "the servlet stack while both are present. Those beans stay unserved: Tomcat and Spring MVC "
				+ "answer every request, and GATool serves MCP there. Set " + WEB_APPLICATION_TYPE_PROPERTY
				+ "=reactive to read why GATool cannot serve MCP over WebFlux, or leave the MCP starter out and "
				+ "add gatool-in-process-spring-boot-starter, whose tools run inside the application without a "
				+ "web stack of their own.");
	}

	/**
	 * Fails startup when {@code spring.ai.mcp.server.capabilities.tool} is false while
	 * the catalog holds MCP tools.
	 * @param catalog the tools this application publishes
	 * @param environment the environment the property is read from
	 */
	// Spring AI hands the tool specifications to its server only while the tool
	// capability is on. With it off the server would start without any GATool tool and
	// answer tools/list with "Missing handler", while startup lists the tools as served.
	// An application whose catalog holds zero MCP tools may switch the capability off, so
	// the stop reads the catalog as well as the property.
	static void stopWhenTheToolCapabilityIsOff(GAToolCatalog catalog, Environment environment) {
		if (catalog.mcpTools().isEmpty()
				|| Boolean.TRUE.equals(environment.getProperty(TOOL_CAPABILITY_PROPERTY, Boolean.class, true))) {
			return;
		}
		throw new InvalidConfigurationPropertyValueException(TOOL_CAPABILITY_PROPERTY,
				environment.getProperty(TOOL_CAPABILITY_PROPERTY),
				"Spring AI registers tools only while this property is true, so the server would start without "
						+ "the tools GATool built from the operation files ("
						+ catalog.mcpTools().stream().map(GATool::name).collect(Collectors.joining(", "))
						+ ") and refuse tools/list. Set this property to true, or leave it unset, which is the "
						+ "same thing.");
	}

	/**
	 * Fails startup over HTTP until
	 * {@code gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication} is true.
	 * @param properties the GATool properties, which carry the switch
	 * @param environment says whether the server runs over stdio
	 */
	// The MCP endpoint is secure by default: with Spring Security on the classpath, the
	// security auto-configuration makes it an OAuth 2.1 resource server, and without it
	// an application accepts in writing that every caller reaches every tool. Stdio
	// stays exempt, because the caller is the process that started the server, and the
	// switch turns off a control that only HTTP has. Both protocols over HTTP run this
	// check.
	static void stopWhenCallersArriveUnauthenticated(GAToolProperties properties, Environment environment) {
		if (SpringAiMcpKeys.servesStdio(environment)) {
			return;
		}
		if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		// The same three classes the security auto-configuration requires, so this stop
		// fires exactly where that configuration would be absent. The names live in a
		// class of their own: reading them off the security auto-configuration runs its
		// static initializer, which builds a Security token, so on a classpath without
		// Spring Security the stop would die on a NoClassDefFoundError ahead of its
		// message.
		if (McpSecurityClasses.allPresent(null)) {
			return;
		}
		// The value is the one the environment holds, null while the property is unset,
		// so Boot's analyzer prints the origin of a value that was set and leaves a
		// fabricated origin out of a report about a default.
		throw new InvalidConfigurationPropertyValueException(UNAUTHENTICATED_SWITCH,
				environment.getProperty(UNAUTHENTICATED_SWITCH),
				"GATool secures the MCP endpoint with Spring Security, which is missing from this application, so "
						+ "every caller that reaches the port would call every tool on it. Add "
						+ "spring-boot-starter-security-oauth2-resource-server and set "
						+ "spring.security.oauth2.resourceserver.jwt.issuer-uri, or set this property to true to "
						+ "serve the endpoint without authentication, on a loopback address or behind a gateway "
						+ "that authenticates for it.");
	}

	/**
	 * Fails startup for stdio under the {@code STATELESS} protocol.
	 * @param environment the environment the protocol and the stdio flag are read from
	 */
	// Every MCP server auto-configuration in Spring AI 2.0.1 backs off for stdio with
	// the STATELESS protocol, so the application would start without a server. Boot's
	// own analyzer reports this exception with the property that carries the value,
	// and the value passed is the one the environment holds, so the analyzer prints
	// the origin it came from.
	static void stopOnStatelessStdio(Environment environment) {
		if (SpringAiMcpKeys.servesStdio(environment)) {
			throw new InvalidConfigurationPropertyValueException(GAToolEnvironmentPostProcessor.PROTOCOL,
					environment.getProperty(GAToolEnvironmentPostProcessor.PROTOCOL),
					"Spring AI serves stdio on a stateful session, so its MCP server backs off with this value and "
							+ "the application starts without a server. Leave "
							+ GAToolEnvironmentPostProcessor.PROTOCOL
							+ " unset, and GATool sets STREAMABLE for stdio.");
		}
	}

	// A cap below one turns every client away, and a timeout at or below zero evicts
	// every session as it starts. A keep-alive interval at or above the timeout would
	// evict a session between two pings, which the SDK's builder refuses with an
	// assertion that leaves both properties unnamed; GATool's idle default is 30 minutes,
	// so a keep-alive of one hour, valid on plain Spring AI, would meet that assertion.
	static GAToolMcpProperties.Sessions requireValidSessions(GAToolProperties properties,
			McpServerStreamableHttpProperties streamableHttp) {
		GAToolMcpProperties.Sessions sessions = properties.getMcp().getSessions();
		if (sessions.getMaxCount() < 1) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.sessions.max-count",
					sessions.getMaxCount(), "A stateful server keeps at least one session.");
		}
		if (sessions.getIdleTimeout().isZero() || sessions.getIdleTimeout().isNegative()) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.sessions.idle-timeout",
					sessions.getIdleTimeout(), "A session stays for a positive time before it is evicted.");
		}
		Duration keepAlive = streamableHttp.getKeepAliveInterval();
		if (keepAlive != null && keepAlive.compareTo(sessions.getIdleTimeout()) >= 0) {
			throw new InvalidConfigurationPropertyValueException(
					"spring.ai.mcp.server.streamable-http.keep-alive-interval", keepAlive,
					"A session idle for gatool.mcp.sessions.idle-timeout (" + sessions.getIdleTimeout()
							+ ") is evicted, so the keep-alive interval has to be shorter than that, or a "
							+ "connected client is evicted between two pings. Shorten the interval or "
							+ "raise gatool.mcp.sessions.idle-timeout.");
		}
		return sessions;
	}

}
