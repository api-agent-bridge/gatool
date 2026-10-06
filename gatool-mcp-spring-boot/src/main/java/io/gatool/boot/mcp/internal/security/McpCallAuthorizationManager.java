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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;

/**
 * Decides whether one request to the MCP endpoint carries the scopes it needs.
 *
 * <p>
 * Every call needs the baseline scopes, and a {@code tools/call} needs the named tool's
 * scopes as well. The tool name sits in the JSON-RPC body, which the compliance filter
 * has already buffered and read, so this manager reads it from the request attribute that
 * filter leaves, and the body is read once. The scopes a token carries arrive as
 * {@code SCOPE_} authorities, the way Spring Security's JWT converter writes the
 * {@code scope} and {@code scp} claims.
 *
 * <p>
 * The decision fails closed. A caller without an authenticated token is refused whatever
 * the scopes say. A POST the compliance filter did not read is refused as well, because
 * the tool name is then unknown and the baseline alone would let a scoped tool run. That
 * happens when the filter is missing from the chain or was registered for other
 * dispatches.
 *
 * <p>
 * The result carries what was required and what was held, so the access denied handler
 * can name both in the 403 challenge and in its log line.
 *
 * @author Željko Kozina
 */
public final class McpCallAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

	private static final Log logger = LogFactory.getLog(McpCallAuthorizationManager.class);

	private static final String SCOPE_PREFIX = "SCOPE_";

	private final McpScopes scopes;

	/**
	 * Creates the manager.
	 * @param scopes the scopes of this server
	 */
	public McpCallAuthorizationManager(McpScopes scopes) {
		this.scopes = scopes;
	}

	@Override
	public @Nullable AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
			RequestAuthorizationContext context) {
		Authentication caller = authentication.get();
		if (!McpCallers.isSignedIn(caller)) {
			return new RefusalAheadOfScopes("The call needs a bearer token.");
		}
		HttpServletRequest request = context.getRequest();
		if ("POST".equalsIgnoreCase(request.getMethod())
				&& request.getAttribute(McpComplianceFilter.METHOD_ATTRIBUTE) == null) {
			logger.error("GATool refused a POST to the MCP endpoint that the MCP compliance filter did not read, so "
					+ "the tool it names is unknown. That filter runs ahead of Spring Security for every "
					+ "dispatch; check that its registration was left to gatool.");
			return new RefusalAheadOfScopes("The request was not read by the MCP compliance filter.");
		}
		String toolName = toolNameOf(context);
		List<String> required = this.scopes.requiredFor(toolName);
		Set<String> held = heldScopes(caller);
		List<String> missing = new ArrayList<>();
		for (String scope : required) {
			if (!held.contains(scope)) {
				missing.add(scope);
			}
		}
		return new ScopeDecision(toolName, required, this.scopes.knownAmong(held), missing);
	}

	/**
	 * Returns the tool a {@code tools/call} names, as the compliance filter read it.
	 * @param context the request
	 * @return the tool name, or {@code null} for every other call
	 */
	public static @Nullable String toolNameOf(RequestAuthorizationContext context) {
		return McpCallers.toolNameOf(context.getRequest());
	}

	private static Set<String> heldScopes(Authentication authentication) {
		Set<String> held = new TreeSet<>();
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			String name = authority.getAuthority();
			if (name != null && name.startsWith(SCOPE_PREFIX)) {
				held.add(name.substring(SCOPE_PREFIX.length()));
			}
		}
		return held;
	}

	/**
	 * The decision for one request, with the scopes it names.
	 *
	 * @param toolName the tool a {@code tools/call} named, or {@code null}
	 * @param requiredScopes the scopes the call needed
	 * @param heldScopes the scopes the token carried, limited to the ones this server
	 * knows, so a challenge names the caller's scopes without echoing a claim
	 * @param missingScopes the required scopes the token lacked, empty when granted
	 */
	public record ScopeDecision(@Nullable String toolName, List<String> requiredScopes, List<String> heldScopes,
			List<String> missingScopes) implements AuthorizationResult {

		private static final long serialVersionUID = 1L;

		public ScopeDecision {
			requiredScopes = List.copyOf(requiredScopes);
			heldScopes = List.copyOf(heldScopes);
			missingScopes = List.copyOf(missingScopes);
		}

		@Override
		public boolean isGranted() {
			return this.missingScopes.isEmpty();
		}
	}

	/**
	 * A refusal for a reason other than scopes, with the sentence the challenge carries.
	 *
	 * @param reason what the challenge says
	 */
	public record RefusalAheadOfScopes(String reason) implements AuthorizationResult {

		private static final long serialVersionUID = 1L;

		@Override
		public boolean isGranted() {
			return false;
		}
	}

}
