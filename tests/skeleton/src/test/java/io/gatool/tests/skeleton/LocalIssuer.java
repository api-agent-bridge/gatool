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

package io.gatool.tests.skeleton;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An OAuth 2.1 issuer on a local port, enough for Spring Boot's {@code issuer-uri}
 * decoder and for Spring Security's OAuth2 client: the discovery document, the JWK set, a
 * way to mint tokens signed with the key the set publishes, and a token endpoint that
 * answers the client credentials and token exchange grants, recording what it was asked.
 */
final class LocalIssuer {

	private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

	private final AtomicReference<Map<String, String>> lastTokenRequest = new AtomicReference<>(Map.of());

	private final AtomicInteger tokenRequests = new AtomicInteger();

	private static final String KEY_ID = "test-key";

	private static final Object LOCK = new Object();

	private static LocalIssuer instance;

	private final HttpServer server;

	private final RSAKey key;

	private LocalIssuer() throws IOException, NoSuchAlgorithmException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		this.key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
			.keyID(KEY_ID)
			.build();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/.well-known/openid-configuration",
				(exchange) -> answer(exchange, "{\"issuer\":\"" + url() + "\",\"jwks_uri\":\"" + url() + "/jwks\"}"));
		this.server.createContext("/jwks",
				(exchange) -> answer(exchange, new JWKSet(this.key.toPublicJWK()).toString()));
		this.server.createContext("/token", this::issue);
		this.server.start();
	}

	/**
	 * Returns the token endpoint, for a Spring Boot client registration's
	 * {@code token-uri}.
	 */
	String tokenUrl() {
		return url() + "/token";
	}

	/**
	 * Returns the form the token endpoint last received, with the client id that
	 * authenticated under the key {@code client_id}.
	 */
	Map<String, String> lastTokenRequest() {
		return this.lastTokenRequest.get();
	}

	/**
	 * Returns how many times the token endpoint was called.
	 */
	int tokenRequests() {
		return this.tokenRequests.get();
	}

	// The two grants Spring Security's client sends here. The client authenticates with
	// client_secret_basic, which is Spring's default, and the token names that client,
	// the audience the form asked for, and under token exchange the subject of the
	// token that was handed in, so a test can read who the API is told is calling.
	private void issue(HttpExchange exchange) throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		for (String pair : body.split("&")) {
			int at = pair.indexOf('=');
			if (at > 0) {
				form.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
			}
		}
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization != null && authorization.startsWith("Basic ")) {
			String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)),
					StandardCharsets.UTF_8);
			form.put("client_id",
					URLDecoder.decode(credentials.substring(0, credentials.indexOf(':')), StandardCharsets.UTF_8));
		}
		this.lastTokenRequest.set(Map.copyOf(form));
		this.tokenRequests.incrementAndGet();
		String grant = form.getOrDefault("grant_type", "");
		String subject;
		if (TOKEN_EXCHANGE.equals(grant)) {
			try {
				subject = SignedJWT.parse(form.getOrDefault("subject_token", "")).getJWTClaimsSet().getSubject();
			}
			catch (ParseException ex) {
				subject = "unparsed";
			}
		}
		else {
			subject = form.getOrDefault("client_id", "unknown");
		}
		String audience = form.getOrDefault("audience", "graphql-api");
		String scope = form.getOrDefault("scope", "");
		String token = token(url(), subject, audience, List.of(scope.split(" ")), Instant.now().plusSeconds(300),
				this.key);
		answer(exchange,
				"{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300," + "\"scope\":\""
						+ scope + "\",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}");
	}

	/**
	 * Returns the issuer, started on first use, so a {@code @DynamicPropertySource} can
	 * name its URL before any test runs.
	 */
	static LocalIssuer get() {
		synchronized (LOCK) {
			if (instance == null) {
				try {
					instance = new LocalIssuer();
				}
				catch (IOException | NoSuchAlgorithmException ex) {
					throw new IllegalStateException("The local issuer could not start", ex);
				}
			}
			return instance;
		}
	}

	String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/**
	 * Mints a token this issuer signed.
	 * @param audience the audience claim
	 * @param scopes the scope claim, space separated by the caller
	 * @return the serialized JWT
	 */
	String token(String audience, List<String> scopes) {
		return token(url(), "agent", audience, scopes, Instant.now().plusSeconds(300), this.key);
	}

	/**
	 * Mints a token for another subject.
	 */
	String tokenFor(String subject, String audience, List<String> scopes) {
		return token(url(), subject, audience, scopes, Instant.now().plusSeconds(300), this.key);
	}

	/**
	 * Mints a token that names another issuer.
	 */
	String tokenFromAnotherIssuer(String audience, List<String> scopes) {
		return token("https://issuer.example.org", "agent", audience, scopes, Instant.now().plusSeconds(300), this.key);
	}

	/**
	 * Mints a token that expired a minute ago.
	 */
	String expiredToken(String audience, List<String> scopes) {
		return token(url(), "agent", audience, scopes, Instant.now().minusSeconds(60), this.key);
	}

	/**
	 * Mints a token signed with a key the JWK set leaves out.
	 */
	String tokenSignedWithAnUnknownKey(String audience, List<String> scopes) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();
			RSAKey unknown = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
				.privateKey((RSAPrivateKey) pair.getPrivate())
				.keyID(KEY_ID)
				.build();
			return token(url(), "agent", audience, scopes, Instant.now().plusSeconds(300), unknown);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String token(String issuer, String subject, String audience, List<String> scopes, Instant expiry,
			RSAKey key) {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer)
			.subject(subject)
			.audience(audience)
			.issueTime(Date.from(Instant.now().minusSeconds(120)))
			.expirationTime(Date.from(expiry))
			.claim("scope", String.join(" ", scopes))
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
		try {
			jwt.sign(new RSASSASigner(key));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("The token could not be signed", ex);
		}
		return jwt.serialize();
	}

	/**
	 * The claims of a token this issuer signed, read from an {@code Authorization} header
	 * value, so a test can assert what reached the API.
	 */
	static JWTClaimsSet claimsOf(String authorization) throws ParseException {
		assertThat(authorization).startsWith("Bearer ");
		return SignedJWT.parse(authorization.substring("Bearer ".length())).getJWTClaimsSet();
	}

	private static void answer(HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
