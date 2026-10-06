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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;

/**
 * The {@code WWW-Authenticate} challenges the MCP endpoint sends.
 *
 * <p>
 * MCP's authorization rules want two things in every challenge that Spring Security's own
 * entry point and access denied handler leave out. The 401 has to point
 * {@code resource_metadata} at this endpoint's own document, the path-inserted one, where
 * Spring's default resolver names the root document. It should also carry {@code scope}
 * with the baseline scopes, which Spring adds only for a token error that names one. The
 * 403 has to carry {@code insufficient_scope} with {@code scope} and
 * {@code resource_metadata}, where Spring's handler writes {@code realm} and the error
 * alone. So both challenges are written here, from the parts RFC 6750 defines.
 *
 * <p>
 * A challenge that answers a {@code tools/call} names that tool's scopes beside the
 * baseline, so a client can step up for the one tool it wanted. The 403's {@code scope}
 * keeps the scopes the token already holds beside the ones it lacks, which is the
 * inclusion strategy MCP recommends so that a client signing in again keeps what it had.
 * Its description says which of this server's scopes the token held and which it lacked.
 * The log line for the refusal carries a correlation id, which stays out of the
 * challenge: the trace id where Micrometer Tracing fills it, and an id of GATool's own
 * otherwise.
 *
 * <p>
 * Every parameter value is checked against the characters RFC 6750 allows in a quoted
 * string before it is written, so a description or a URI that an application's own code
 * put into an error cannot break the header.
 *
 * @author Željko Kozina
 */
public final class McpBearerChallenges {

	private static final Log logger = LogFactory.getLog(McpBearerChallenges.class);

	private static final String ERROR_DESCRIPTION = "error_description";

	private static final String TRACE_ID_MDC_KEY = "traceId";

	private static final String FALLBACK_DESCRIPTION = "The token was refused.";

	private static final int INTERNAL_ERROR = -32603;

	private final McpEndpointPaths paths;

	private final McpScopes scopes;

	private final @Nullable String resource;

	private final SecurityContextHolderStrategy contextHolderStrategy;

	/**
	 * Creates the challenges of one endpoint.
	 * @param paths where the endpoint and its metadata live
	 * @param scopes the scopes every call and each tool needs
	 * @param resource the canonical resource URI, or {@code null} to name the metadata
	 * document from the request
	 * @param contextHolderStrategy where the chain keeps the caller, which is the
	 * strategy bean of an application that declares one
	 */
	public McpBearerChallenges(McpEndpointPaths paths, McpScopes scopes, @Nullable String resource,
			SecurityContextHolderStrategy contextHolderStrategy) {
		this.paths = paths;
		this.scopes = scopes;
		this.resource = resource;
		this.contextHolderStrategy = contextHolderStrategy;
	}

	/**
	 * Returns the entry point that answers a request without a usable token.
	 * @return the 401 challenge
	 */
	public AuthenticationEntryPoint entryPoint() {
		return this::commence;
	}

	/**
	 * Returns the handler that answers a token lacking a scope.
	 * @return the 403 challenge
	 */
	public AccessDeniedHandler accessDeniedHandler() {
		return this::deny;
	}

	private void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
		Map<String, String> parameters = new LinkedHashMap<>();
		HttpStatus status = HttpStatus.UNAUTHORIZED;
		// A token error says what was wrong with the token the client sent. A request
		// without a token arrives as an InsufficientAuthenticationException, and its
		// challenge carries the two parameters that tell the client where to sign in.
		if (exception instanceof OAuth2AuthenticationException oauth2) {
			OAuth2Error error = oauth2.getError();
			putIfHeaderSafe(parameters, "error", error.getErrorCode());
			putIfHeaderSafe(parameters, ERROR_DESCRIPTION, error.getDescription());
			putIfHeaderSafe(parameters, "error_uri", error.getUri());
			if (error instanceof BearerTokenError bearer) {
				status = bearer.getHttpStatus();
			}
		}
		write(request, response, status, this.scopes.publishedAtSignIn(McpCallers.toolNameOf(request)), parameters);
	}

	private void deny(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		// Spring's own translation sends an anonymous caller to the entry point ahead of
		// this handler, and the authorization filter refuses an empty context before the
		// manager runs. This branch is a safety net: whatever reaches it without an
		// authenticated caller reads the sign-in challenge.
		Authentication caller = this.contextHolderStrategy.getContext().getAuthentication();
		if (!McpCallers.isSignedIn(caller)) {
			write(request, response, HttpStatus.UNAUTHORIZED,
					this.scopes.publishedAtSignIn(McpCallers.toolNameOf(request)), new LinkedHashMap<>());
			return;
		}
		if (exception instanceof AuthorizationDeniedException denied && denied
			.getAuthorizationResult() instanceof McpCallAuthorizationManager.RefusalAheadOfScopes refusal) {
			// A refusal is this server's own misconfiguration, so it is answered as one,
			// without a scope the client could ask for, in the JSON-RPC shape every other
			// refusal on this endpoint has.
			response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			response.getWriter()
				.write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + INTERNAL_ERROR + ",\"message\":\""
						+ escapeJson(refusal.reason()) + "\"}}");
			return;
		}
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("error", BearerTokenErrorCodes.INSUFFICIENT_SCOPE);
		List<String> challengeScopes = this.scopes.requiredFor(McpCallers.toolNameOf(request));
		String description = "The token lacks a scope this endpoint requires.";
		if (exception instanceof AuthorizationDeniedException denied
				&& denied.getAuthorizationResult() instanceof McpCallAuthorizationManager.ScopeDecision decision) {
			String correlationId = newCorrelationId();
			challengeScopes = union(decision.requiredScopes(), decision.heldScopes());
			// The correlation id stays in the log. An MCP client compares one challenge
			// with the last to tell a step-up that can succeed from one that cannot, and
			// a fresh id in the description would make every pair differ. The log line
			// carries the tool and the scopes, which is what support needs.
			description = descriptionOf(decision);
			logger.info("GATool answered 403 insufficient_scope to " + describeCall(decision) + ": the token "
					+ describeWhatTheTokenHolds(decision) + ". Correlation id " + correlationId
					+ (isTraceId(correlationId) ? " (the trace id)."
							: " (generated by GATool, because tracing is off)."));
		}
		putIfHeaderSafe(parameters, ERROR_DESCRIPTION, description);
		write(request, response, HttpStatus.FORBIDDEN, challengeScopes, parameters);
	}

	// A tool is named only where this server declares it, so a name the caller made up
	// stays out of the header and the log; a call of any other name needs the
	// baseline alone and is described as such.
	private String describeCall(McpCallAuthorizationManager.ScopeDecision decision) {
		String toolName = decision.toolName();
		if (toolName == null) {
			return "an MCP request";
		}
		return this.scopes.hasScopeListFor(toolName) ? "a call of " + toolName : "a call of a tool this server lacks";
	}

	// The scopes the call needs first, then the ones the token already holds, once
	// each, so a client that follows the challenge asks for everything it had and
	// everything it lacks.
	private static List<String> union(List<String> required, List<String> held) {
		Set<String> scopes = new LinkedHashSet<>(required);
		scopes.addAll(held);
		return List.copyOf(scopes);
	}

	// The tool's scopes come first in the sentence, because that is what the client
	// asks for next, and the held scopes are the server's own list alone, so the
	// challenge only repeats what this server itself defined.
	private String descriptionOf(McpCallAuthorizationManager.ScopeDecision decision) {
		String toolName = decision.toolName();
		String sentenceStart = (toolName != null && this.scopes.hasScopeListFor(toolName)) ? toolName + " needs "
				: "This endpoint needs ";
		return sentenceStart + String.join(" ", decision.requiredScopes()) + ". The token "
				+ describeWhatTheTokenHolds(decision) + ".";
	}

	// A token without one of this server's scopes lacks each scope the call needs. The
	// sentence before this one has named them, so the list is written once.
	private static String describeWhatTheTokenHolds(McpCallAuthorizationManager.ScopeDecision decision) {
		if (decision.heldScopes().isEmpty()) {
			return "does not hold any of them";
		}
		return "holds " + String.join(" ", decision.heldScopes()) + " and lacks "
				+ String.join(" ", decision.missingScopes());
	}

	// The reason is a fixed sentence of this server's own, so the escape covers the two
	// characters JSON quotes and the control characters, which is all a string needs.
	private static String escapeJson(String text) {
		StringBuilder escaped = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (character == '"' || character == '\\') {
				escaped.append('\\').append(character);
			}
			else if (character < 0x20) {
				escaped.append(String.format("\\u%04x", (int) character));
			}
			else {
				escaped.append(character);
			}
		}
		return escaped.toString();
	}

	// Boot's LogCorrelationEnvironmentPostProcessor puts the trace id into every log
	// line once Micrometer Tracing runs, under this MDC key; an id of GATool's own fills
	// the gap where tracing is absent, and the log line says which it is.
	private static String newCorrelationId() {
		String traceId = MDC.get(TRACE_ID_MDC_KEY);
		return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
	}

	private static boolean isTraceId(String correlationId) {
		return correlationId.equals(MDC.get(TRACE_ID_MDC_KEY));
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			List<String> challengeScopes, Map<String, String> parameters) {
		putIfHeaderSafe(parameters, "scope", String.join(" ", challengeScopes));
		putIfHeaderSafe(parameters, "resource_metadata", this.paths.metadataUrl(request, this.resource));
		String challenge = parameters.entrySet()
			.stream()
			.map((parameter) -> parameter.getKey() + "=\"" + parameter.getValue() + "\"")
			.collect(Collectors.joining(", ", "Bearer ", ""));
		response.setStatus(status.value());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
	}

	// A value outside the characters RFC 6750 allows in a parameter is dropped, or
	// replaced by a fixed sentence where the parameter is the description, the way
	// Spring's own BearerTokenErrors falls back. Spring validates every
	// BearerTokenError it builds, so this covers an error an application's own code
	// built without that check.
	private static void putIfHeaderSafe(Map<String, String> parameters, String name, @Nullable String value) {
		if (!StringUtils.hasText(value)) {
			return;
		}
		boolean uri = "error_uri".equals(name) || "resource_metadata".equals(name);
		if (isHeaderSafe(value, uri)) {
			parameters.put(name, value);
		}
		else if (ERROR_DESCRIPTION.equals(name)) {
			parameters.put(name, FALLBACK_DESCRIPTION);
		}
	}

	// RFC 6750 section 3: %x20-21 / %x23-5B / %x5D-7E, which is printable ASCII
	// without the double quote and the backslash, and a URI parameter is without spaces.
	private static boolean isHeaderSafe(String value, boolean uri) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (isOutsideTheHeaderCharset(character) || (uri && character == ' ')) {
				return false;
			}
		}
		return true;
	}

	private static boolean isOutsideTheHeaderCharset(char character) {
		return character < 0x20 || character > 0x7E || character == '"' || character == '\\';
	}

}
