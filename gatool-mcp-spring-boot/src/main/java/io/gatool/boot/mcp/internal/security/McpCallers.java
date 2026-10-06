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
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;

import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;

/**
 * What the security classes read about one request's caller: whether an authentication
 * names a signed-in caller, and the tool a {@code tools/call} names, as the compliance
 * filter left it on the request.
 *
 * <p>
 * The challenges, the authorization manager and the session binding each ask both
 * questions, and one place keeps the three answers the same. The trust resolver is
 * stateless, so one instance serves every caller.
 *
 * @author Željko Kozina
 */
final class McpCallers {

	private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

	private McpCallers() {
	}

	/**
	 * Returns whether the authentication names a signed-in caller: present,
	 * authenticated, and other than Spring Security's anonymous token.
	 * @param authentication the authentication the context holds, or {@code null}
	 * @return true for a caller the chain has authenticated
	 */
	static boolean isSignedIn(@Nullable Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& !TRUST_RESOLVER.isAnonymous(authentication);
	}

	/**
	 * Returns the tool a {@code tools/call} names, as the compliance filter read it.
	 * @param request the request
	 * @return the tool name, or {@code null} for every other call and for an attribute of
	 * another type
	 */
	static @Nullable String toolNameOf(HttpServletRequest request) {
		Object name = request.getAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE);
		return (name instanceof String text && !text.isBlank()) ? text : null;
	}

}
