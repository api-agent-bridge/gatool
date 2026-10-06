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

package io.gatool.boot.mcp.internal.limit;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.gatool.boot.internal.credentials.TokenFingerprint;

/**
 * Names the caller that the rate limiter counts against.
 *
 * <p>
 * With MCP security on, the caller is the authenticated principal,
 * {@code Authentication.getName()}, which is the JWT subject under Spring's converter and
 * the key the session binding uses as well. The count then follows the token wherever its
 * requests come from. A caller behind a proxy cannot open a fresh count by changing the
 * address a forwarded header reports, and two callers whose requests leave one address
 * each get a count of their own. A principal carries the prefix {@code principal:}, a
 * token the prefix {@code token:} and an address the prefix {@code address:}, so a
 * subject that spells an address cannot share a bucket with a client at that address.
 *
 * <p>
 * A converter of the application's own may leave {@code Authentication.getName()} blank.
 * A bearer token is then named by its SHA-256 fingerprint, the key the session binding
 * falls back to, so the count still follows the token; an authentication without a name
 * and without a token is counted by address.
 *
 * <p>
 * Under the unsafe switch every caller is anonymous, and the client address of the
 * servlet request is what remains. A deployment behind a proxy then sets
 * {@code server.forward-headers-strategy=native} with
 * {@code server.tomcat.remoteip.internal-proxies} naming the proxy, so the container
 * rewrites the address for that proxy alone. The default of that property covers every
 * private address range, and {@code trusted-proxies} adds to it without narrowing it; the
 * {@code framework} strategy trusts every client's forwarded headers, and the count would
 * follow whatever the client wrote.
 *
 * <p>
 * The authentication is read through the {@link SecurityContextHolderStrategy} handed to
 * {@link #through(SecurityContextHolderStrategy)}, which is the bean an application
 * declares, the one Spring Security's filters write. Spring Security 7 leaves the static
 * {@link SecurityContextHolder} untouched by such a bean, so a caller that read the
 * static holder under one would see every request as anonymous.
 * {@link #throughStaticHolder()} serves an application without that bean.
 *
 * <p>
 * What makes this reading right is that the call runs on the thread that served its HTTP
 * request, where {@link RequestContextHolder} carries the request and Spring Security's
 * context holder carries the caller. GATool enforces that instead of assuming it. Startup
 * stops where an MCP server bean was built without {@code immediateExecution(true)}, and
 * a call that reaches the handler on a thread the compliance filter left unmarked is
 * refused before this class is asked. Over HTTP the answer {@code stdio} below is
 * therefore out of reach.
 *
 * <p>
 * The MCP Java SDK offers {@code McpTransportContextExtractor}, which would put the
 * caller into the transport context the handler receives, and Spring AI 2.0.1 builds its
 * WebMVC transports without one. GATool builds both transports itself, for the session
 * cap and the error bodies, so an extractor is one call on each of the two builders, and
 * this release still reads the caller from the thread.
 *
 * <p>
 * A call reaches this point without a bound request when the server runs over stdio,
 * where one process is the only caller, so those calls share one count.
 *
 * <p>
 * Spring Security is optional on this module's classpath, so the lookup of the caller
 * sits in nested classes behind class checks, loaded on first use alone, and the security
 * types stay unresolved in an application without Spring Security. The resource server
 * module is optional as well, so the fingerprint sits in a nested class of its own.
 *
 * @author Željko Kozina
 */
public final class RequestCaller implements Supplier<String> {

	/**
	 * The prefix of a caller named by its authenticated principal.
	 */
	static final String PRINCIPAL_PREFIX = "principal:";

	/**
	 * The prefix of a caller named by the fingerprint of its bearer token.
	 */
	static final String TOKEN_PREFIX = "token:";

	/**
	 * The prefix of a caller named by its client address.
	 */
	static final String ADDRESS_PREFIX = "address:";

	private static final String STDIO = "stdio";

	private static final String UNKNOWN = "unknown";

	private static final boolean SECURITY_PRESENT = ClassUtils.isPresent(
			"org.springframework.security.core.context.SecurityContextHolder", RequestCaller.class.getClassLoader());

	private static final boolean RESOURCE_SERVER_PRESENT = ClassUtils.isPresent(
			"org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
			RequestCaller.class.getClassLoader());

	private final Supplier<@Nullable String> authenticatedKey;

	private RequestCaller(Supplier<@Nullable String> authenticatedKey) {
		this.authenticatedKey = authenticatedKey;
	}

	/**
	 * Names callers through Spring Security's static context holder, and by address alone
	 * in an application without Spring Security.
	 * @return the caller
	 */
	public static RequestCaller throughStaticHolder() {
		return new RequestCaller(SECURITY_PRESENT ? AuthenticatedCaller::fromStaticHolder : () -> null);
	}

	/**
	 * Names callers through the strategy an application declares as a bean, which is
	 * where Spring Security's filters write the authentication.
	 * @param contextHolderStrategy the strategy
	 * @return the caller
	 */
	// The parameter type belongs to Spring Security, and the JVM resolves it on the
	// first call alone, so an application without Spring Security loads this class and
	// calls throughStaticHolder without meeting the type.
	public static RequestCaller through(SecurityContextHolderStrategy contextHolderStrategy) {
		return new RequestCaller(AuthenticatedCaller.through(contextHolderStrategy));
	}

	/**
	 * Returns the caller of the call running on this thread.
	 * @return the authenticated principal, the fingerprint of a bearer token without a
	 * principal name, the client address for an anonymous caller, or a stand-in when the
	 * call arrived without a servlet request
	 */
	@Override
	public String get() {
		String key = this.authenticatedKey.get();
		if (key != null) {
			return key;
		}
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			String address = servletAttributes.getRequest().getRemoteAddr();
			return ADDRESS_PREFIX + ((address != null) ? address : UNKNOWN);
		}
		return STDIO;
	}

	/**
	 * Reads the caller from a Spring Security context holder strategy.
	 */
	private static final class AuthenticatedCaller {

		private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

		private AuthenticatedCaller() {
		}

		// The static holder is read on each call, so a strategy set on it at runtime,
		// the way Spring Security's own reference shows, is the one that answers.
		static @Nullable String fromStaticHolder() {
			return keyOf(SecurityContextHolder.getContextHolderStrategy());
		}

		static Supplier<@Nullable String> through(SecurityContextHolderStrategy contextHolderStrategy) {
			return () -> keyOf(contextHolderStrategy);
		}

		private static @Nullable String keyOf(SecurityContextHolderStrategy contextHolderStrategy) {
			Authentication authentication = contextHolderStrategy.getContext().getAuthentication();
			if (authentication == null || !authentication.isAuthenticated()
					|| TRUST_RESOLVER.isAnonymous(authentication)) {
				return null;
			}
			String name = authentication.getName();
			if (name != null && !name.isBlank()) {
				return PRINCIPAL_PREFIX + name;
			}
			return RESOURCE_SERVER_PRESENT ? BearerCaller.fingerprintOf(authentication) : null;
		}

	}

	/**
	 * Names a bearer token without a principal name by its fingerprint, the digest the
	 * session binding and the token exchange store use as well.
	 */
	private static final class BearerCaller {

		private BearerCaller() {
		}

		static @Nullable String fingerprintOf(Authentication authentication) {
			if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> bearer) {
				return TOKEN_PREFIX + TokenFingerprint.of(bearer.getToken().getTokenValue());
			}
			return null;
		}

	}

}
