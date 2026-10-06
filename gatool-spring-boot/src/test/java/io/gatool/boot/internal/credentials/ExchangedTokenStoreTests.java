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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store of exchanged tokens: one entry per caller and registration, and every expired
 * entry gone the next time one is saved.
 */
class ExchangedTokenStoreTests {

	private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

	private final ClientRegistration movies = ClientRegistration.withRegistrationId("movies")
		.clientId("gatool-server")
		.authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
		.tokenUri("http://127.0.0.1:1/token")
		.build();

	private final OAuth2ApiCredentialStrategy.ExchangedTokenStore store = new OAuth2ApiCredentialStrategy.ExchangedTokenStore(
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void saveAndLoad_perCallerAndRegistration_shouldKeepOneEntryEach() {
		this.store.saveAuthorizedClient(client("first", NOW.plusSeconds(300)), caller("hash-a"));
		this.store.saveAuthorizedClient(client("second", NOW.plusSeconds(300)), caller("hash-b"));
		this.store.saveAuthorizedClient(client("third", NOW.plusSeconds(300)), caller("hash-a"));

		OAuth2AuthorizedClient loaded = this.store.loadAuthorizedClient("movies", "hash-a");

		assertThat(loaded).isNotNull();
		assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("third");
		assertThat(this.store.size()).isEqualTo(2);
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-c")).isNull();
	}

	@Test
	void save_withExpiredEntriesPresent_shouldDropThem() {
		this.store.saveAuthorizedClient(client("stale", NOW.minusSeconds(1)), caller("hash-a"));
		this.store.saveAuthorizedClient(client("atTheEdge", NOW), caller("hash-b"));
		this.store.saveAuthorizedClient(client("fresh", NOW.plusSeconds(60)), caller("hash-c"));

		// The save of a fresh entry sweeps the two that have expired, so the store holds
		// what is still usable and grows with the live callers alone.
		assertThat(this.store.size()).isEqualTo(1);
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-a")).isNull();
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-c")).isNotNull();
	}

	@Test
	void save_withAnEntryWithoutAnExpiry_shouldSweepItAsExpired() {
		// Spring gives every token response an expiry, so an entry without one is
		// outside what the provider builds; the store still cannot hold one forever.
		this.store.saveAuthorizedClient(client("undated", null), caller("hash-a"));
		this.store.saveAuthorizedClient(client("fresh", NOW.plusSeconds(60)), caller("hash-b"));

		assertThat(this.store.size()).isEqualTo(1);
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-a")).isNull();
	}

	@Test
	void save_pastTheCap_shouldDropTheEntriesNearestTheirExpiry() {
		for (int index = 0; index < OAuth2ApiCredentialStrategy.ExchangedTokenStore.MAX_ENTRIES; index++) {
			this.store.saveAuthorizedClient(client("t" + index, NOW.plusSeconds(100 + index)), caller("hash-" + index));
		}

		this.store.saveAuthorizedClient(client("late", NOW.plusSeconds(999_999)), caller("hash-late"));

		assertThat(this.store.size()).isEqualTo(OAuth2ApiCredentialStrategy.ExchangedTokenStore.MAX_ENTRIES);
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-0")).isNull();
		assertThat(this.store.<OAuth2AuthorizedClient>loadAuthorizedClient("movies", "hash-late")).isNotNull();
	}

	@Test
	void remove_anEntry_shouldForgetIt() {
		this.store.saveAuthorizedClient(client("fresh", NOW.plusSeconds(60)), caller("hash-a"));

		this.store.removeAuthorizedClient("movies", "hash-a");

		assertThat(this.store.size()).isZero();
	}

	private OAuth2AuthorizedClient client(String tokenValue, Instant expiresAt) {
		return new OAuth2AuthorizedClient(this.movies, "GATool",
				new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue, NOW.minusSeconds(60), expiresAt));
	}

	private static TestingAuthenticationToken caller(String name) {
		return new TestingAuthenticationToken(name, "n/a");
	}

}
