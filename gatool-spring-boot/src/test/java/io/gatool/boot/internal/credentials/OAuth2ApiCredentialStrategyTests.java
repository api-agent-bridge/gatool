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

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Whose token token exchange sends.
 *
 * <p>
 * The subject token is the caller's own JWT, read from the security context of the thread
 * the tool call runs on. Under a strategy that lets threads inherit or share a context, a
 * thread can hold a token that belongs to an earlier caller. The exchange would then mint
 * a token for that caller and send it to the API. These tests hold the check that refuses
 * such a call, and the two cases that still pass.
 */
class OAuth2ApiCredentialStrategyTests {

	@AfterEach
	void clearTheContextAndTheStrategyAndTheRequest() {
		SecurityContextHolder.clearContext();
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void requireFingerprintNamed_underTheInheritableStrategyOffTheRequestThread_shouldRefuseSayingWhoseTokenIsUnknown() {
		SecurityContextHolderStrategy strategy = strategyNamed(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);

		assertThatExceptionOfType(CredentialUnavailableException.class)
			.isThrownBy(() -> OAuth2ApiCredentialStrategy.requireFingerprintNamed(strategy))
			.withMessageContaining("gatool.api.credentials.strategy is token-exchange")
			.withMessageContaining("MODE_INHERITABLETHREADLOCAL")
			.withMessageContaining("GATool cannot tell whether the token on this thread belongs to this caller")
			.withMessageContaining("spring.reactor.context-propagation=auto")
			.satisfies((failure) -> assertThat(failure.strategy()).isEqualTo("token-exchange"));
	}

	@Test
	void requireFingerprintNamed_underTheGlobalStrategyOffTheRequestThread_shouldRefuseSayingWhoseTokenIsUnknown() {
		SecurityContextHolderStrategy strategy = strategyNamed(SecurityContextHolder.MODE_GLOBAL);

		assertThatExceptionOfType(CredentialUnavailableException.class)
			.isThrownBy(() -> OAuth2ApiCredentialStrategy.requireFingerprintNamed(strategy))
			.withMessageContaining("MODE_GLOBAL");
	}

	@Test
	void requireFingerprintNamed_underTheInheritableStrategyOnAThreadServingARequest_shouldNameTheTokensFingerprint() {
		SecurityContextHolderStrategy strategy = strategyNamed(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
		RequestContextHolder.setRequestAttributes(new BoundRequest());

		Authentication named = OAuth2ApiCredentialStrategy.requireFingerprintNamed(strategy);

		assertThat(named.getName()).isEqualTo(TokenFingerprint.of("the-token"));
	}

	@Test
	void requireFingerprintNamed_underTheDefaultStrategyOffTheRequestThread_shouldNameTheTokensFingerprint() {
		SecurityContextHolderStrategy strategy = strategyNamed(SecurityContextHolder.MODE_THREADLOCAL);

		Authentication named = OAuth2ApiCredentialStrategy.requireFingerprintNamed(strategy);

		assertThat(named.getName()).isEqualTo(TokenFingerprint.of("the-token"));
	}

	@Test
	void requireFingerprintNamed_onAThreadWithoutABearerToken_shouldSayTheSubjectTokenIsMissing() {
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
		SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

		assertThatExceptionOfType(CredentialUnavailableException.class)
			.isThrownBy(() -> OAuth2ApiCredentialStrategy.requireFingerprintNamed(strategy))
			.withMessageContaining("the subject token to exchange is missing");
	}

	// The strategy instance is the one the auto-configuration would hand the credential,
	// and the caller's token is put on it the way Spring Security's filter does.
	private static SecurityContextHolderStrategy strategyNamed(String mode) {
		SecurityContextHolder.setStrategyName(mode);
		SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
		Jwt jwt = Jwt.withTokenValue("the-token")
			.header("alg", "RS256")
			.subject("agent")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
		strategy.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		return strategy;
	}

}
