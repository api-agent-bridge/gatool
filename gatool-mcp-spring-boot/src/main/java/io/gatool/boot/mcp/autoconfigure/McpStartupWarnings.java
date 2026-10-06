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
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.Environment;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolMcpProperties;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.internal.SpringAiMcpKeys;

/**
 * The warnings {@link GAToolMcpAutoConfiguration} writes at startup about a setting that
 * leaves the MCP endpoint more open than its default.
 *
 * <p>
 * The class holds the warnings so that the auto-configuration keeps its bean methods and
 * little else. The log lines keep the auto-configuration's category, so a level set on
 * that class still covers them.
 *
 * @author Željko Kozina
 */
final class McpStartupWarnings {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	private McpStartupWarnings() {
	}

	/**
	 * Logs a warning for every switch under {@code gatool.mcp.security.unsafe} while it
	 * is on.
	 * @param properties the GATool properties, which carry the switches
	 */
	// At every startup, so the log of a run with one of them on says so.
	static void warnOnUnsafeSwitches(GAToolProperties properties) {
		GAToolMcpProperties.McpSecurity.Unsafe unsafe = properties.getMcp().getSecurity().getUnsafe();
		if (unsafe.isAllowMcpCallsWithoutAuthentication()) {
			logger.warn("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication is true, so your MCP "
					+ "server can be reached without authentication.");
		}
		if (unsafe.isRequestEveryScopeAtSignIn()) {
			logger.warn("gatool.mcp.security.unsafe.request-every-scope-at-sign-in is true, so the protected resource "
					+ "metadata and every 401 name every scope this server knows, and a client asks for all of "
					+ "them at sign-in. MCP's security best practices call that a common mistake, and a token "
					+ "that holds every scope reaches every tool.");
		}
		if (unsafe.isAllowUnlimitedToolCalls()) {
			logger.warn("gatool.mcp.security.unsafe.allow-unlimited-tool-calls is true, so every caller makes as many "
					+ "tool calls as it likes, and MCP asks a server to rate limit them. Cover the limit at a "
					+ "gateway in front of this application.");
		}
		if (unsafe.isAllowSupersededMcpRevisions()) {
			logger.warn("gatool.mcp.security.unsafe.allow-superseded-mcp-revisions is true, so the MCP endpoint serves "
					+ "the revisions 2024-11-05 and 2025-03-26 as well. 2025-03-26 asks a server to read JSON-RPC "
					+ "batches, which MCP Java SDK 2.0.0 cannot, so a client on that revision may fail on a "
					+ "batched request.");
		}
	}

	/**
	 * Warns while the MCP endpoint runs unauthenticated on every network interface.
	 * @param properties the GATool properties, which carry the authentication switch
	 * @param environment carries {@code server.address} and the stdio flag
	 */
	// MCP's transport security warning says a local server SHOULD bind to loopback, and
	// Spring Boot listens on every interface while server.address is unset. Spring AI's
	// MCP starter leaves that property alone, and so does GATool, because the property
	// binds the whole application and a starter that changed it over one endpoint would
	// surprise a team serving other endpoints. The warning is the whole of what GATool
	// does, and the team decides. Stdio runs without a port, so it is left out.
	static void warnWhenOpenOnEveryInterface(GAToolProperties properties, Environment environment) {
		if (SpringAiMcpKeys.servesStdio(environment)
				|| !properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		String address = environment.getProperty("server.address");
		if (address == null || address.isBlank() || bindsEveryInterface(address)) {
			boolean addressUnset = (address == null || address.isBlank());
			logger.warn("server.address is " + (addressUnset ? "unset, so it defaults to 0.0.0.0" : address) + ", and "
					+ "the MCP endpoint runs without authentication, so any machine that can reach this one on the "
					+ "network can call every tool it serves. MCP asks a local server to bind to loopback: set "
					+ "server.address=127.0.0.1, or put the endpoint behind a gateway that authenticates for it.");
		}
	}

	// The wildcard has several spellings, 0.0.0.0, ::, [::] and 0:0:0:0:0:0:0:0 among
	// them, and the JDK knows them all. A value it cannot parse is left to Boot, which
	// refuses it when the server starts.
	private static boolean bindsEveryInterface(String address) {
		try {
			return InetAddress.getByName(address).isAnyLocalAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

	/**
	 * Warns that tools requiring scopes run open while the endpoint runs without
	 * authentication.
	 * @param catalog the tools this application publishes
	 * @param properties the GATool properties, which carry the authentication switch
	 */
	// The switch turns the scope check off with the rest of security, and a file that
	// listed scopes meant to close a tool, so each such tool is named.
	static void warnWhenScopedToolsRunOpen(GAToolCatalog catalog, GAToolProperties properties) {
		if (!properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		List<String> toolsRequiringScopes = catalog.mcpTools()
			.stream()
			.filter((tool) -> tool.scopes() != null && !tool.scopes().isEmpty())
			.map((tool) -> tool.name() + " (" + String.join(" ", Objects.requireNonNull(tool.scopes())) + ")")
			.toList();
		if (!toolsRequiringScopes.isEmpty()) {
			logger.warn("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication is true, so these tools, "
					+ "whose operation files require scopes, are open to any unauthenticated caller: "
					+ String.join(", ", toolsRequiringScopes) + ".");
		}
	}

	// The list is read over stdio alone, and a value set on an HTTP server would sit
	// unread, where an operator may believe it grants something.
	static void warnWhenStdioScopesAreSetOverHttp(GAToolProperties properties) {
		if (properties.getMcp().getStdio().getGrantedScopes() != null) {
			logger.info(
					"gatool.mcp.stdio.granted-scopes is set, and this server runs over HTTP, where the caller's token "
							+ "carries the scopes, so the property stays unread.");
		}
	}

	/**
	 * Warns that a stateful server without authentication lets any caller fill the
	 * session cap.
	 * @param properties the GATool properties, which carry the session settings
	 */
	// The cap and the idle timeout bound what one server holds, and with
	// authentication off they are also the whole of what stops a caller from
	// holding it. A caller that opens sessions up to the cap leaves every other
	// caller with 503 until the timeout frees them.
	static void warnOnUnauthenticatedSessions(GAToolProperties properties) {
		if (!properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		GAToolMcpProperties.Sessions sessions = properties.getMcp().getSessions();
		logger.warn("GATool serves stateful Streamable HTTP without authentication, so any caller that reaches "
				+ "the port can open sessions up to gatool.mcp.sessions.max-count (" + sessions.getMaxCount()
				+ ") and hold them for gatool.mcp.sessions.idle-timeout (" + sessions.getIdleTimeout()
				+ "), which leaves every other caller with 503 until they are evicted. Keep the server on a "
				+ "loopback address or behind a gateway that authenticates for it, or leave "
				+ GAToolEnvironmentPostProcessor.PROTOCOL + " unset for stateless Streamable HTTP, which does "
				+ "not hold a session.");
	}

	/**
	 * Warns that a secured stateful server leaves one caller free to fill the whole
	 * session cap, because the per-caller cap is off or does not sit below max-count.
	 * @param properties the GATool properties, which carry the session settings
	 */
	// Authentication tells the server who a caller is, and the per-caller cap is
	// what turns that identity into a limit. Without a cap below max-count, one
	// authenticated caller can still open every session the server keeps and leave
	// every other caller with 503 until sessions expire.
	static void warnOnMissingPerCallerSessionCap(GAToolProperties properties) {
		if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			return;
		}
		GAToolMcpProperties.Sessions sessions = properties.getMcp().getSessions();
		int perCaller = sessions.getMaxCountPerCaller();
		int maxCount = sessions.getMaxCount();
		if (perCaller > 0 && perCaller < maxCount) {
			return;
		}
		logger.warn("GATool serves stateful Streamable HTTP with authentication, and "
				+ "gatool.mcp.sessions.max-count-per-caller (" + perCaller + ") leaves one caller free to open "
				+ "every session gatool.mcp.sessions.max-count (" + maxCount + ") allows. That caller can then "
				+ "leave every other caller with 503 until sessions expire. Set "
				+ "gatool.mcp.sessions.max-count-per-caller to a number below gatool.mcp.sessions.max-count.");
	}

}
