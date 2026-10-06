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

package io.gatool.boot.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.util.ClassUtils;

import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.core.internal.operation.SchemaAddition;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.operation.ToolOperation;

/**
 * The lines {@link GAToolAutoConfiguration} writes at startup: the tools it serves, the
 * mode a tool call runs in, the deadlines of a request and the customizers that reach its
 * client.
 *
 * <p>
 * The methods log under the auto-configuration's name, so a logging level an operator set
 * for it covers these lines.
 *
 * @author Željko Kozina
 */
final class StartupLines {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	// By name, because spring-boot-restclient is optional in this module and a reactive
	// application can carry spring-boot-http-client alone.
	private static final String REST_CLIENT_CUSTOMIZER = "org.springframework.boot.restclient.RestClientCustomizer";

	private StartupLines() {
	}

	/**
	 * Names the {@code RestClientCustomizer} beans that reach GATool's client through
	 * Boot's builder, and says what of theirs applies to it.
	 * @param beanFactory the bean factory that holds the customizer beans
	 */
	// A customizer's headers and interceptors travel with the builder. The request
	// factory it installs is replaced by GATool's own, which carries the response size
	// cap and leaves redirects unfollowed, so a proxy, an mTLS context or a pool
	// installed that way is missing from GATool's calls alone.
	static void reportRestClientCustomizers(ConfigurableListableBeanFactory beanFactory) {
		ClassLoader classLoader = beanFactory.getBeanClassLoader();
		if (!ClassUtils.isPresent(REST_CLIENT_CUSTOMIZER, classLoader)) {
			return;
		}
		String[] names = beanFactory
			.getBeanNamesForType(ClassUtils.resolveClassName(REST_CLIENT_CUSTOMIZER, classLoader), true, false);
		if (names.length == 0) {
			return;
		}
		logger.info("GATool builds its HTTP client from Spring Boot's RestClient.Builder, so these "
				+ "RestClientCustomizer beans apply to it: " + String.join(", ", names) + ". The headers and "
				+ "interceptors they set reach every request GATool sends. A request factory they install stays "
				+ "off GATool's client, because GATool builds its own from the ClientHttpRequestFactoryBuilder bean "
				+ "and spring.http.clients.*, and that factory carries the response size cap and leaves redirects "
				+ "unfollowed.");
	}

	/**
	 * Whether this application carries everything the MCP side needs to run.
	 *
	 * <p>
	 * The four names are the class condition on the MCP module's
	 * {@code GAToolMcpAutoConfiguration}, and they arrive together with the MCP starter.
	 * An application that adds the in-process starter alone does not run an MCP server,
	 * so a warning about zero MCP tools would name a property without a server behind it,
	 * at every start.
	 *
	 * <p>
	 * The names are strings instead of class literals because the four frameworks sit on
	 * the compile path of {@code gatool-mcp-spring-boot}, which declares them optional,
	 * and stay off this module's.
	 * @return whether the MCP auto-configuration's classes are all present
	 */
	private static boolean mcpSideCanRun() {
		ClassLoader classLoader = GAToolAutoConfiguration.class.getClassLoader();
		return ClassUtils.isPresent("io.modelcontextprotocol.server.McpStatelessSyncServer", classLoader)
				&& ClassUtils.isPresent(
						"org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties",
						classLoader)
				&& ClassUtils.isPresent("io.github.bucket4j.Bucket", classLoader)
				&& ClassUtils.isPresent("com.github.benmanes.caffeine.cache.Caffeine", classLoader);
	}

	/**
	 * Logs every tool of one exposure type, with the file it came from and what it costs
	 * in tokens.
	 * @param toolExposureType the exposure type whose tools are listed
	 * @param tools the tools of that exposure type
	 * @param locations the operation locations that were read for it
	 * @param baselineScopes the scopes every MCP call needs, which the listing adds to
	 * each tool's own
	 * @param schemaAdditions what the adapters add to each tool's input schema, which
	 * counts in the token estimate
	 */
	// Spring AI's own line prints the count and leaves the names out. This line names
	// which file became which tool, which is the first thing to check when an agent calls
	// something unexpected. The cost is the tool's own estimate for this exposure type,
	// with what the adapter of the exposure type adds to each schema, so the MCP listing
	// counts what tools/list carries and the in-process listing counts what a model
	// provider is sent. The catalog compares the same estimate before it warns about a
	// large tool, and its warning names the exposure type, so the warning and the listing
	// of that exposure type print one number.
	static void logTools(ToolExposureType toolExposureType, List<ToolOperation> tools, List<String> locations,
			List<String> baselineScopes, List<SchemaAddition> schemaAdditions) {
		if (tools.isEmpty()) {
			// GATool keeps the application running with an empty exposure type and says
			// which folders were read, because an MCP server serving zero tools is almost
			// always a location that points somewhere else.
			if (toolExposureType == ToolExposureType.MCP && mcpSideCanRun()) {
				logger.warn("GATool serves zero MCP tools. These locations were read: " + String.join(", ", locations)
						+ ". An operation file there becomes a tool, and gatool.mcp.operations.locations names the "
						+ "folders.");
			}
			return;
		}
		StringBuilder listing = new StringBuilder("GATool serves ").append(tools.size())
			.append(" ")
			.append(toolExposureType.label())
			.append((tools.size() != 1) ? " tools:" : " tool:");
		int total = 0;
		int charactersAddedToEachSchema = SchemaAddition.charactersFor(toolExposureType, schemaAdditions);
		for (ToolOperation tool : tools) {
			int tokens = tool.estimatedTokens(toolExposureType, charactersAddedToEachSchema);
			total += tokens;
			listing.append(System.lineSeparator())
				.append("  ")
				.append(tool.toolName())
				.append(" from ")
				.append(tool.location())
				.append(" (about ")
				.append(tokens)
				.append(" tokens)")
				.append(describeScopes(tool.scopes(), toolExposureType, baselineScopes));
		}
		// The total is per exposure type, as the listing is, because the two exposure
		// types carry their own tool lists and an agent reads one of them.
		listing.append(System.lineSeparator())
			.append("  about ")
			.append(total)
			.append(" tokens in all, which every agent that lists these tools reads before it calls one");
		logger.info(listing.toString());
		// The assembled text is what the API receives, and a spread that resolved to an
		// unexpected shared file is the first thing to check when a tool selects more or
		// less than its file shows.
		if (logger.isDebugEnabled()) {
			for (ToolOperation tool : tools) {
				if (!tool.sharedFragmentFiles().isEmpty()) {
					logger.debug("GATool assembled " + tool.toolName() + " from " + tool.location()
							+ " and the shared fragments of " + String.join(", ", tool.sharedFragmentFiles()) + ":"
							+ System.lineSeparator() + tool.printedDocument());
				}
			}
		}
	}

	// The scopes a call needs are the baseline of gatool.mcp.security.baseline-scopes and
	// the ones the operation file lists, and the MCP endpoint checks both, so the listing
	// prints the whole set: an operator reads in one place what the 401 and 403
	// challenges name. Without a baseline, an empty list is still named, because it is a
	// statement the file made on purpose.
	//
	// The in-process side lacks a caller of its own to check them against: the caller is
	// the application's own ChatClient, and nothing on that path reads the list. So the
	// listing says who checks.
	private static String describeScopes(@Nullable List<String> scopes, ToolExposureType toolExposureType,
			List<String> baselineScopes) {
		if (toolExposureType == ToolExposureType.IN_PROCESS) {
			return (scopes == null || scopes.isEmpty()) ? "" : ", declares the scopes " + String.join(" ", scopes)
					+ ", which this application has to enforce itself";
		}
		Set<String> needed = new LinkedHashSet<>(baselineScopes);
		if (scopes != null) {
			needed.addAll(scopes);
		}
		if (!needed.isEmpty()) {
			return ", needs the scopes " + String.join(" ", needed);
		}
		return (scopes != null) ? ", open to every caller" : "";
	}

	/**
	 * Logs that embedded mode runs each document inside this application, names the
	 * WebGraphQlInterceptor beans that run in front of every tool call, and says which
	 * handler runs them.
	 * @param interceptors the interceptor beans the context holds
	 * @param builtFromService whether the handler is the one built here from an
	 * {@code ExecutionGraphQlService}, or the application's own {@code WebGraphQlHandler}
	 * bean
	 */
	// A web application publishes a WebGraphQlHandler carrying its interceptors, and a
	// non-web application publishes the service alone, so the handler GATool builds from
	// it carries the same beans. Each of them sees every tool call on both paths, and the
	// line says so.
	static void logEmbeddedMode(ObjectProvider<WebGraphQlInterceptor> interceptors, boolean builtFromService) {
		warnThatEmbeddedModeSkipsTheHttpLayer(builtFromService);
		List<String> names = interceptors.orderedStream()
			.map((interceptor) -> interceptor.getClass().getName())
			.toList();
		String handler = builtFromService
				? ", through a WebGraphQlHandler it built from the ExecutionGraphQlService because this "
						+ "application lacks a WebGraphQlHandler bean"
				: "";
		if (names.isEmpty()) {
			logger.info("GATool runs each document inside this application" + handler + ".");
			return;
		}
		logger.info(
				"GATool runs each document inside this application" + handler + ", " + (builtFromService ? "and " : "")
						+ "through these WebGraphQlInterceptor beans: " + String.join(", ", names));
	}

	// Says that a tool call in embedded mode reaches the GraphQL engine without passing
	// the HTTP layer.
	//
	// Embedded mode calls the handler directly, so a rule in authorizeHttpRequests on the
	// GraphQL path and every servlet filter registered there stay out of a tool call. A
	// token holding the baseline scope alone runs an operation the path itself refuses
	// with 403. What still applies is the handler's own interceptors and the security
	// context, so method security on a resolver decides, which is where authorization
	// belongs for this mode.
	//
	// The line is a warning where Spring Security is on the classpath, because a URL rule
	// is then something this application plausibly has, and a statement otherwise. An
	// application that publishes the ExecutionGraphQlService alone serves the engine
	// without an HTTP layer, so every check that applies to a call sits inside the
	// engine, and the line says what decides, at INFO, because it describes the mode.
	private static void warnThatEmbeddedModeSkipsTheHttpLayer(boolean builtFromService) {
		if (builtFromService) {
			logger.info("A tool call in embedded mode reaches the GraphQL engine directly, and this application "
					+ "serves the engine without an HTTP layer in front of it, so the security context that "
					+ "travels with the call, and method security on a resolver, decide what a tool call may run.");
			return;
		}
		String sentence = "A tool call in embedded mode reaches the GraphQL engine directly, so every servlet "
				+ "filter and every authorizeHttpRequests rule on the GraphQL path stays out of it. The security "
				+ "context travels with the call, so method security on a resolver still decides, which is where "
				+ "authorization belongs for this mode.";
		if (ClassUtils.isPresent("org.springframework.security.web.SecurityFilterChain", null)) {
			logger.warn(sentence);
			return;
		}
		logger.info(sentence);
	}

	/**
	 * Logs the connect and read deadlines that bound a request GATool makes, and names
	 * the property to set wherever GATool supplied one.
	 * @param subject what GATool does under these deadlines, as the line's verb phrase
	 * @param clients the clients whose deadlines the line reports
	 */
	// The deadlines belong to the application, and GATool reads the settings Boot built
	// instead of the properties by name. Boot leaves both unset, so a call to an API that
	// stops answering would hold the request thread, and the agent waiting on it, until
	// the socket closes, which can take more than 75 seconds. GATool fills whichever of
	// the two the application left unset.
	//
	// GATool does not introduce a property for them, because a GATool default would sit
	// in front of the Spring property the application set. The merge keeps the
	// application's value wherever it set one, so the Spring property decides whenever it
	// is present. The schema fetch reports the same way, because a registry that stops
	// answering holds startup the way an API holds a call.
	static void reportDeadlines(String subject, RemoteHttpClients clients) {
		@Nullable Duration connectTimeout = clients.connectTimeout();
		@Nullable Duration readTimeout = clients.readTimeout();
		String line = "GATool " + subject + ", waiting up to " + describe(clients.appliedConnectTimeout())
				+ " to connect and " + describe(clients.appliedReadTimeout()) + " for an answer.";
		if (connectTimeout != null && readTimeout != null) {
			logger.info(line);
			return;
		}
		String supplied;
		if (connectTimeout == null && readTimeout == null) {
			supplied = "GATool supplied both deadlines, because spring.http.clients.connect-timeout and "
					+ "spring.http.clients.read-timeout are unset. Set those two properties to decide them, "
					+ "and the values there apply to every client this application builds.";
		}
		else if (connectTimeout == null) {
			supplied = "GATool supplied the connect deadline, because spring.http.clients.connect-timeout is "
					+ "unset. Set that property to decide it, and the value there applies to every client this "
					+ "application builds.";
		}
		else {
			supplied = "GATool supplied the read deadline, because spring.http.clients.read-timeout is unset. "
					+ "Set that property to decide it, and the value there applies to every client this "
					+ "application builds.";
		}
		logger.info(line + " " + supplied);
	}

	private static String describe(Duration timeout) {
		return timeout.toMillis() + "ms";
	}

}
