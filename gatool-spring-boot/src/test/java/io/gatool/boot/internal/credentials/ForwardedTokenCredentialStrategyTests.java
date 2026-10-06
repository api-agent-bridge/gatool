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

package io.gatool.boot.internal.credentials;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The forwarded token: the caller's bearer token goes out as it is, and a thread without
 * one sends the request without a header.
 */
class ForwardedTokenCredentialStrategyTests {

	private final ForwardedTokenCredentialStrategy credential = new ForwardedTokenCredentialStrategy(
			SecurityContextHolder.getContextHolderStrategy());

	@AfterEach
	void clearTheContextAndTheStrategyAndTheRequest() {
		SecurityContextHolder.clearContext();
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void intercept_withAJwtCaller_shouldForwardItsTokenValue() throws IOException {
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));

		HttpRequest sent = send();

		assertThat(sent.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer the-token");
	}

	@Test
	void intercept_withoutACaller_shouldFailAheadOfABareCallAsACredentialTheStrategyCouldNotSupply() {
		// A bare call would reach the API and be refused in its own words; the failure
		// here names the switch and where such a call can come from, and its type is
		// what the runner answers as a credential failure instead of a transport one.
		assertThatExceptionOfType(CredentialUnavailableException.class).isThrownBy(this::send)
			.withMessageContaining("forward-client-tokens")
			.withMessageContaining("without the caller's token")
			.satisfies((failure) -> assertThat(failure.strategy()).isEqualTo("forward-client-tokens"));
	}

	@Test
	void intercept_withACallerThatIsNotABearerToken_shouldFailAheadOfABareCall() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ana", "n/a"));

		assertThatExceptionOfType(CredentialUnavailableException.class).isThrownBy(this::send);
	}

	@Test
	void intercept_underTheInheritableStrategyOffTheRequestThread_shouldRefuseSayingWhoseTokenIsUnknown() {
		// A pooled thread created during one request keeps that request's context under
		// this strategy, so a token found here may belong to an earlier caller.
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
		ForwardedTokenCredentialStrategy underTheStrategy = new ForwardedTokenCredentialStrategy(
				SecurityContextHolder.getContextHolderStrategy());
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));

		assertThatExceptionOfType(CredentialUnavailableException.class).isThrownBy(() -> send(underTheStrategy))
			.withMessageContaining("MODE_INHERITABLETHREADLOCAL")
			.withMessageContaining("GATool cannot tell whether the token on this thread belongs to this caller")
			.withMessageContaining("spring.reactor.context-propagation=auto");
	}

	@Test
	void intercept_underTheGlobalStrategyOffTheRequestThread_shouldRefuseSayingWhoseTokenIsUnknown() {
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);
		ForwardedTokenCredentialStrategy underTheStrategy = new ForwardedTokenCredentialStrategy(
				SecurityContextHolder.getContextHolderStrategy());
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));

		assertThatExceptionOfType(CredentialUnavailableException.class).isThrownBy(() -> send(underTheStrategy))
			.withMessageContaining("MODE_GLOBAL");
	}

	@Test
	void intercept_underTheInheritableStrategyOnAThreadServingARequest_shouldForwardTheToken() throws IOException {
		// A servlet request bound to the thread says the thread is serving this call, so
		// the context on it was bound for this caller.
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
		ForwardedTokenCredentialStrategy underTheStrategy = new ForwardedTokenCredentialStrategy(
				SecurityContextHolder.getContextHolderStrategy());
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));
		RequestContextHolder.setRequestAttributes(new BoundRequest());

		HttpRequest sent = send(underTheStrategy);

		assertThat(sent.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer the-token");
	}

	@Test
	void intercept_underTheDefaultStrategyOffTheRequestThread_shouldForwardTheToken() throws IOException {
		// The default strategy is what context propagation restores per task, so a token
		// on the thread was put there for the work the thread is doing.
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));

		HttpRequest sent = send(this.credential);

		assertThat(sent.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer the-token");
	}

	private static Jwt jwt() {
		return Jwt.withTokenValue("the-token")
			.header("alg", "RS256")
			.subject("agent")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
	}

	private HttpRequest send() throws IOException {
		return send(this.credential);
	}

	private static HttpRequest send(ForwardedTokenCredentialStrategy credential) throws IOException {
		MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, "http://api/graphql");
		ClientHttpRequestExecution execution = (sent, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);
		ClientHttpResponse response = credential.intercept(request, new byte[0], execution);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return request;
	}

}
