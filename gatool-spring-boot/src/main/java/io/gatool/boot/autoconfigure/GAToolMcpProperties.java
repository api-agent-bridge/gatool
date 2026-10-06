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
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

import io.gatool.boot.autoconfigure.GAToolProperties.Operations;

/**
 * The MCP server: its operation files, rate limit, transport, security, sessions and
 * stdio settings.
 *
 * @author Željko Kozina
 */
public class GAToolMcpProperties {

	/**
	 * Creates the group under {@code gatool.mcp} with the default of each property.
	 */
	public GAToolMcpProperties() {
		// Each property keeps the default its field declares.
	}

	private final Operations operations = Operations.at("optional:classpath*:gatool/mcp/");

	private final RateLimit rateLimit = new RateLimit();

	private final Transport transport = new Transport();

	private final McpSecurity security = new McpSecurity();

	private final Sessions sessions = new Sessions();

	private final Stdio stdio = new Stdio();

	/**
	 * Returns the group of properties under {@code gatool.mcp.sessions}.
	 * @return the group
	 */
	public Sessions getSessions() {
		return this.sessions;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp.stdio}.
	 * @return the group
	 */
	public Stdio getStdio() {
		return this.stdio;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp.operations}.
	 * @return the group
	 */
	public Operations getOperations() {
		return this.operations;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp.rate-limit}.
	 * @return the group
	 */
	public RateLimit getRateLimit() {
		return this.rateLimit;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp.transport}.
	 * @return the group
	 */
	public Transport getTransport() {
		return this.transport;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp.security}.
	 * @return the group
	 */
	public McpSecurity getSecurity() {
		return this.security;
	}

	/**
	 * What the stdio transport takes from its environment, under
	 * spring.ai.mcp.server.stdio=true.
	 */
	public static class Stdio {

		/**
		 * Creates the group under {@code gatool.mcp.stdio} with the default of each
		 * property.
		 */
		public Stdio() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Scopes the process that started this server holds, which satisfy tools that
		 * require scopes. Startup stops when such tools exist without this list, and a
		 * call to a tool needing a scope outside it comes back as a tool error.
		 */
		private @Nullable List<String> grantedScopes;

		/**
		 * Whether the server writes its log to stderr, the stream MCP leaves to a stdio
		 * server for its logging. stderr carries WARN and above until
		 * logging.threshold.console sets another threshold, and the lines are written
		 * without making the server wait, so a host that leaves stderr unread loses lines
		 * while the server keeps answering, and a WARN line says how many once lines get
		 * through again. It applies on Logback, while the application leaves
		 * logging.console.enabled unset and the logging configuration to Spring Boot's
		 * defaults. On Log4j2 startup says in one line on stderr that the log is lost,
		 * while the application leaves the log file and logging.config unset. The console
		 * settings for the pattern, the charset and the structured format apply to stderr
		 * as well.
		 */
		// The logging system starts before this class is bound, so the listener that
		// attaches the appender reads the key from the Environment. The field declares
		// the key for the configuration metadata and has the binder check its value.
		private boolean logToStderr = true;

		/**
		 * Returns the scopes the process that started this server holds, which satisfy
		 * tools that require scopes.
		 * @return the value of {@code gatool.mcp.stdio.granted-scopes}
		 */
		public @Nullable List<String> getGrantedScopes() {
			return this.grantedScopes;
		}

		/**
		 * Sets the scopes the process that started this server holds, which satisfy tools
		 * that require scopes.
		 * @param grantedScopes the value of {@code gatool.mcp.stdio.granted-scopes}
		 */
		public void setGrantedScopes(@Nullable List<String> grantedScopes) {
			this.grantedScopes = grantedScopes;
		}

		/**
		 * Returns whether the server writes its log to stderr, the stream MCP leaves to a
		 * stdio server for its logging.
		 * @return the value of {@code gatool.mcp.stdio.log-to-stderr}
		 */
		public boolean isLogToStderr() {
			return this.logToStderr;
		}

		/**
		 * Sets whether the server writes its log to stderr, the stream MCP leaves to a
		 * stdio server for its logging.
		 * @param logToStderr the value of {@code gatool.mcp.stdio.log-to-stderr}
		 */
		public void setLogToStderr(boolean logToStderr) {
			this.logToStderr = logToStderr;
		}

	}

	/**
	 * The sessions a stateful Streamable HTTP server keeps, under
	 * spring.ai.mcp.server.protocol=STREAMABLE.
	 */
	public static class Sessions {

		/**
		 * Creates the group under {@code gatool.mcp.sessions} with the default of each
		 * property.
		 */
		public Sessions() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Most sessions the server keeps at once. A client that initializes past the cap
		 * gets 503 until a session ends.
		 */
		private int maxCount = 1000;

		/**
		 * Most sessions one caller may hold at once. A caller that initializes past their
		 * own cap gets 429 and the wait. The default of 100 holds one caller to a tenth
		 * of max-count. Zero or below turns the per-caller cap off, and max-count alone
		 * then decides, which one caller can fill on their own.
		 */
		private int maxCountPerCaller = 100;

		/**
		 * How long a session may stay idle before the server evicts it.
		 */
		private Duration idleTimeout = Duration.ofMinutes(30);

		/**
		 * Returns the most sessions the server keeps at once.
		 * @return the value of {@code gatool.mcp.sessions.max-count}
		 */
		public int getMaxCount() {
			return this.maxCount;
		}

		/**
		 * Sets the most sessions the server keeps at once.
		 * @param maxCount the value of {@code gatool.mcp.sessions.max-count}
		 */
		public void setMaxCount(int maxCount) {
			this.maxCount = maxCount;
		}

		/**
		 * Returns the most sessions one caller may hold at once.
		 * @return the value of {@code gatool.mcp.sessions.max-count-per-caller}
		 */
		public int getMaxCountPerCaller() {
			return this.maxCountPerCaller;
		}

		/**
		 * Sets the most sessions one caller may hold at once.
		 * @param maxCountPerCaller the value of
		 * {@code gatool.mcp.sessions.max-count-per-caller}
		 */
		public void setMaxCountPerCaller(int maxCountPerCaller) {
			this.maxCountPerCaller = maxCountPerCaller;
		}

		/**
		 * Returns how long a session may stay idle before the server evicts it.
		 * @return the value of {@code gatool.mcp.sessions.idle-timeout}
		 */
		public Duration getIdleTimeout() {
			return this.idleTimeout;
		}

		/**
		 * Sets how long a session may stay idle before the server evicts it.
		 * @param idleTimeout the value of {@code gatool.mcp.sessions.idle-timeout}
		 */
		public void setIdleTimeout(Duration idleTimeout) {
			this.idleTimeout = idleTimeout;
		}

	}

	/**
	 * What the OAuth 2.1 resource server on the MCP endpoint requires, read once Spring
	 * Security is on the classpath and the unsafe switch is off. The issuer and the
	 * audience come from Spring Boot's own spring.security.oauth2.resourceserver.jwt
	 * properties.
	 */
	public static class McpSecurity {

		/**
		 * Creates the group under {@code gatool.mcp.security} with the default of each
		 * property.
		 */
		public McpSecurity() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Scopes every MCP call needs, all of them. They are published in the protected
		 * resource metadata and in every 401 challenge, so a client asks for them at
		 * sign-in.
		 */
		private List<String> baselineScopes = List.of();

		/**
		 * Canonical URI of this resource, as the protected resource metadata publishes
		 * it. Unset publishes the URL the request arrived at.
		 */
		private @Nullable String resource;

		/**
		 * Returns the scopes every MCP call needs, all of them.
		 * @return the value of {@code gatool.mcp.security.baseline-scopes}
		 */
		public List<String> getBaselineScopes() {
			return this.baselineScopes;
		}

		/**
		 * Sets the scopes every MCP call needs, all of them.
		 * @param baselineScopes the value of {@code gatool.mcp.security.baseline-scopes}
		 */
		public void setBaselineScopes(List<String> baselineScopes) {
			this.baselineScopes = baselineScopes;
		}

		/**
		 * Returns the canonical URI of this resource, as the protected resource metadata
		 * publishes it.
		 * @return the value of {@code gatool.mcp.security.resource}
		 */
		public @Nullable String getResource() {
			return this.resource;
		}

		/**
		 * Sets the canonical URI of this resource, as the protected resource metadata
		 * publishes it.
		 * @param resource the value of {@code gatool.mcp.security.resource}
		 */
		public void setResource(@Nullable String resource) {
			this.resource = resource;
		}

		private final McpSecurity.Unsafe unsafe = new McpSecurity.Unsafe();

		/**
		 * Returns the group of properties under {@code gatool.mcp.security.unsafe}.
		 * @return the group
		 */
		public McpSecurity.Unsafe getUnsafe() {
			return this.unsafe;
		}

		/**
		 * Switches that weaken a control of the MCP endpoint. Each one is off, and
		 * startup warns while any is on.
		 */
		public static class Unsafe {

			/**
			 * Creates the group under {@code gatool.mcp.security.unsafe} with the default
			 * of each property.
			 */
			public Unsafe() {
				// Each property keeps the default its field declares.
			}

			/**
			 * Whether the MCP endpoint serves callers that have not authenticated.
			 */
			private boolean allowMcpCallsWithoutAuthentication;

			/**
			 * Returns whether the MCP endpoint serves callers that have not
			 * authenticated.
			 * @return the value of
			 * {@code gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication}
			 */
			public boolean isAllowMcpCallsWithoutAuthentication() {
				return this.allowMcpCallsWithoutAuthentication;
			}

			/**
			 * Sets whether the MCP endpoint serves callers that have not authenticated.
			 * @param allowMcpCallsWithoutAuthentication the value of
			 * {@code gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication}
			 */
			public void setAllowMcpCallsWithoutAuthentication(boolean allowMcpCallsWithoutAuthentication) {
				this.allowMcpCallsWithoutAuthentication = allowMcpCallsWithoutAuthentication;
			}

			/**
			 * Whether the protected resource metadata and every 401 publish every scope
			 * this server knows, in place of the baseline scopes and the called tool's
			 * own. It serves clients whose step-up fails, and MCP's security best
			 * practices call publishing every scope a common mistake.
			 */
			private boolean requestEveryScopeAtSignIn;

			/**
			 * Returns whether the protected resource metadata and every 401 publish every
			 * scope this server knows, in place of the baseline scopes and the called
			 * tool's own.
			 * @return the value of
			 * {@code gatool.mcp.security.unsafe.request-every-scope-at-sign-in}
			 */
			public boolean isRequestEveryScopeAtSignIn() {
				return this.requestEveryScopeAtSignIn;
			}

			/**
			 * Sets whether the protected resource metadata and every 401 publish every
			 * scope this server knows, in place of the baseline scopes and the called
			 * tool's own.
			 * @param requestEveryScopeAtSignIn the value of
			 * {@code gatool.mcp.security.unsafe.request-every-scope-at-sign-in}
			 */
			public void setRequestEveryScopeAtSignIn(boolean requestEveryScopeAtSignIn) {
				this.requestEveryScopeAtSignIn = requestEveryScopeAtSignIn;
			}

			/**
			 * Whether the rate limiter is switched off.
			 */
			private boolean allowUnlimitedToolCalls;

			/**
			 * Returns whether the rate limiter is switched off.
			 * @return the value of
			 * {@code gatool.mcp.security.unsafe.allow-unlimited-tool-calls}
			 */
			public boolean isAllowUnlimitedToolCalls() {
				return this.allowUnlimitedToolCalls;
			}

			/**
			 * Sets whether the rate limiter is switched off.
			 * @param allowUnlimitedToolCalls the value of
			 * {@code gatool.mcp.security.unsafe.allow-unlimited-tool-calls}
			 */
			public void setAllowUnlimitedToolCalls(boolean allowUnlimitedToolCalls) {
				this.allowUnlimitedToolCalls = allowUnlimitedToolCalls;
			}

			/**
			 * Whether the MCP endpoint accepts revisions 2024-11-05 and 2025-03-26.
			 */
			private boolean allowSupersededMcpRevisions;

			/**
			 * Returns whether the MCP endpoint accepts revisions 2024-11-05 and
			 * 2025-03-26.
			 * @return the value of
			 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}
			 */
			public boolean isAllowSupersededMcpRevisions() {
				return this.allowSupersededMcpRevisions;
			}

			/**
			 * Sets whether the MCP endpoint accepts revisions 2024-11-05 and 2025-03-26.
			 * @param allowSupersededMcpRevisions the value of
			 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}
			 */
			public void setAllowSupersededMcpRevisions(boolean allowSupersededMcpRevisions) {
				this.allowSupersededMcpRevisions = allowSupersededMcpRevisions;
			}

		}

	}

	/**
	 * What the MCP endpoint accepts. A filter checks these ahead of Spring AI's own
	 * transports.
	 */
	public static class Transport {

		/**
		 * Creates the group under {@code gatool.mcp.transport} with the default of each
		 * property.
		 */
		public Transport() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Origins allowed to call the MCP endpoint. A request without an Origin header is
		 * allowed.
		 */
		private List<String> allowedOrigins = List.of();

		/**
		 * Largest request body the MCP endpoint reads.
		 */
		private DataSize maxRequestBodySize = DataSize.ofKilobytes(256);

		/**
		 * Returns the origins allowed to call the MCP endpoint.
		 * @return the value of {@code gatool.mcp.transport.allowed-origins}
		 */
		public List<String> getAllowedOrigins() {
			return this.allowedOrigins;
		}

		/**
		 * Sets the origins allowed to call the MCP endpoint.
		 * @param allowedOrigins the value of {@code gatool.mcp.transport.allowed-origins}
		 */
		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}

		/**
		 * Returns the largest request body the MCP endpoint reads.
		 * @return the value of {@code gatool.mcp.transport.max-request-body-size}
		 */
		public DataSize getMaxRequestBodySize() {
			return this.maxRequestBodySize;
		}

		/**
		 * Sets the largest request body the MCP endpoint reads.
		 * @param maxRequestBodySize the value of
		 * {@code gatool.mcp.transport.max-request-body-size}
		 */
		public void setMaxRequestBodySize(DataSize maxRequestBodySize) {
			this.maxRequestBodySize = maxRequestBodySize;
		}

	}

	/**
	 * How often one caller calls one tool.
	 */
	public static class RateLimit {

		/**
		 * Creates the group under {@code gatool.mcp.rate-limit} with the default of each
		 * property.
		 */
		public RateLimit() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Calls one caller may make to one tool each minute.
		 */
		private int callsPerMinute = 60;

		/**
		 * Most caller and tool pairs the limiter counts at once. Each pair holds a token
		 * bucket of a few hundred bytes for a minute after its last call. Above this
		 * number the limiter drops the bucket of a live pair for each new one, and the
		 * dropped caller starts its next call with a fresh allowance: the counter
		 * gatool.rate-limit.evictions counts those drops, and the log warns once a
		 * minute. Set it above the number of pairs active within a minute.
		 */
		private int maxTrackedPairs = 100_000;

		/**
		 * Returns how many calls one caller may make to one tool each minute.
		 * @return the value of {@code gatool.mcp.rate-limit.calls-per-minute}
		 */
		public int getCallsPerMinute() {
			return this.callsPerMinute;
		}

		/**
		 * Sets how many calls one caller may make to one tool each minute.
		 * @param callsPerMinute the value of
		 * {@code gatool.mcp.rate-limit.calls-per-minute}
		 */
		public void setCallsPerMinute(int callsPerMinute) {
			this.callsPerMinute = callsPerMinute;
		}

		/**
		 * Returns the most caller and tool pairs the limiter counts at once.
		 * @return the value of {@code gatool.mcp.rate-limit.max-tracked-pairs}
		 */
		public int getMaxTrackedPairs() {
			return this.maxTrackedPairs;
		}

		/**
		 * Sets the most caller and tool pairs the limiter counts at once.
		 * @param maxTrackedPairs the value of
		 * {@code gatool.mcp.rate-limit.max-tracked-pairs}
		 */
		public void setMaxTrackedPairs(int maxTrackedPairs) {
			this.maxTrackedPairs = maxTrackedPairs;
		}

	}

}
