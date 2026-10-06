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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.gatool.boot.internal.credentials.TokenFingerprint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the rate limiter counts against: the authenticated principal once a caller is
 * signed in, the fingerprint of a bearer token without a principal name, the client
 * address for an anonymous caller, and the one stdio caller where the call arrived
 * without a servlet request; read through the static holder, or through the strategy an
 * application declares.
 */
class RequestCallerTests {

	private final RequestCaller caller = RequestCaller.throughStaticHolder();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void current_authenticatedCaller_shouldBeThePrincipalWhateverTheAddress() {
		bindRequest("10.9.0.1");
		signIn("ana");

		assertThat(this.caller.get()).isEqualTo("principal:ana");

		// The same caller from another address, which a forwarded header can name,
		// keeps its key.
		bindRequest("10.9.0.2");
		assertThat(this.caller.get()).isEqualTo("principal:ana");
	}

	@Test
	void current_twoAuthenticatedCallersFromOneAddress_shouldHaveKeysOfTheirOwn() {
		bindRequest("10.9.0.1");
		signIn("ana");
		String first = this.caller.get();
		signIn("ben");

		assertThat(this.caller.get()).isEqualTo("principal:ben").isNotEqualTo(first);
	}

	@Test
	void current_anonymousCaller_shouldBeTheClientAddress() {
		bindRequest("10.9.0.1");
		SecurityContextHolder.getContext()
			.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(this.caller.get()).isEqualTo("address:10.9.0.1");
	}

	@Test
	void current_withoutAnAuthentication_shouldBeTheClientAddress() {
		bindRequest("10.9.0.1");

		assertThat(this.caller.get()).isEqualTo("address:10.9.0.1");
	}

	@Test
	void current_authenticatedCallerWithoutANameOrAToken_shouldFallBackToTheClientAddress() {
		// An authentication without a name and without a token leaves the address as
		// the one thing that names the caller.
		bindRequest("10.9.0.1");
		signIn("");

		assertThat(this.caller.get()).isEqualTo("address:10.9.0.1");
	}

	@Test
	void current_bearerTokenWithoutASubject_shouldBeTheTokensFingerprint() {
		// A JwtAuthenticationConverter of the application's own may leave the name
		// blank, and the session binding then identifies the caller by the token's
		// fingerprint, so the limiter counts under the same key instead of the
		// address, which a forwarded header can rotate.
		bindRequest("10.9.0.1");
		Jwt jwt = Jwt.withTokenValue("the-token").header("alg", "none").claim("scope", "mcp:tools").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

		assertThat(this.caller.get()).isEqualTo("token:" + TokenFingerprint.of("the-token"));
	}

	@Test
	void current_principalSpellingAnAddress_shouldStayApartFromAClientAtThatAddress() {
		bindRequest("10.9.0.1");
		signIn("10.9.0.1");

		assertThat(this.caller.get()).isEqualTo("principal:10.9.0.1").isNotEqualTo("address:10.9.0.1");
	}

	@Test
	void current_withoutAServletRequest_shouldBeTheStdioCaller() {
		assertThat(this.caller.get()).isEqualTo("stdio");
	}

	@Test
	void through_strategyOfTheApplicationsOwn_shouldReadTheCallerThereAndLeaveTheStaticHolderUnread() {
		// Spring Security's filters write through the strategy bean an application
		// declares and leave the static holder untouched, so the caller has to read
		// the same strategy.
		bindRequest("10.9.0.1");
		SecurityContextHolderStrategy own = new OwnStrategy();
		own.getContext().setAuthentication(new TestingAuthenticationToken("ana", "n/a", "SCOPE_mcp:tools"));
		signIn("someone-else");

		assertThat(RequestCaller.through(own).get()).isEqualTo("principal:ana");
		assertThat(this.caller.get()).isEqualTo("principal:someone-else");
	}

	private static void signIn(String name) {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken(name, "n/a", "SCOPE_mcp:tools"));
	}

	private static void bindRequest(String remoteAddress) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setRemoteAddr(remoteAddress);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	// A strategy with one context of its own, apart from the static holder's.
	private static final class OwnStrategy implements SecurityContextHolderStrategy {

		private SecurityContext context = new SecurityContextImpl();

		@Override
		public void clearContext() {
			this.context = new SecurityContextImpl();
		}

		@Override
		public SecurityContext getContext() {
			return this.context;
		}

		@Override
		public void setContext(SecurityContext context) {
			this.context = context;
		}

		@Override
		public SecurityContext createEmptyContext() {
			return new SecurityContextImpl();
		}

	}

}
