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
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.web.client.RestClient;

/**
 * Authenticates to the GraphQL API as an OAuth2 client, with client credentials or with
 * token exchange, through Spring Security's own interceptor.
 *
 * <p>
 * Spring Security's {@link OAuth2ClientHttpRequestInterceptor} obtains, caches and
 * refreshes the access token through an {@code OAuth2AuthorizedClientManager}, and this
 * class only chooses the manager, the store and the two resolvers the interceptor takes.
 * The manager is the service-backed one,
 * {@link AuthorizedClientServiceOAuth2AuthorizedClientManager}, because it works on the
 * request thread and on a stdio server alike, where Spring Security's default manager
 * needs an HTTP session behind an anonymous caller. The manager, the provider and the
 * token response client are built here and stay out of the application context, so Spring
 * Security's own manager, which collects every provider bean it finds, keeps the
 * application's exchanges as they were. The token endpoint is called through Spring
 * Boot's own {@code RestClient.Builder} where the context provides one, so
 * {@code spring.http.client.*} timeouts and SSL bundles apply there too.
 *
 * <p>
 * Under client credentials every call shares one principal, so the application holds one
 * token for the API in the {@code OAuth2AuthorizedClientService} Spring Boot defines,
 * which is what that grant asks for.
 *
 * <p>
 * Under token exchange the subject token is the MCP caller's own JWT, read from the
 * security context of the calling thread. The exchanged token is stored under a hash of
 * that inbound token in a store of GATool's own that drops expired entries. Spring's
 * in-memory service looks an entry up by the principal's name, the JWT subject, and keeps
 * every entry. Stored that way, a later token of the same user, narrower or issued after
 * the first was revoked, would reuse the first exchange until it expired. A server with
 * many users would also hold every user's token for the life of the process.
 *
 * <p>
 * Token exchange reads the caller from the security context of the thread that runs the
 * tool call, so whose thread it is decides whose token goes to the API. On the MCP
 * surface GATool holds the call to the request thread: it stops startup for an MCP server
 * built without {@code immediateExecution(true)} and refuses a call that arrives on
 * another thread. {@link #requireFingerprintNamed} refuses a call under a security
 * context holder strategy that threads inherit or share while the thread is not serving a
 * request. The token on such a thread can belong to an earlier caller, and the in-process
 * surface reaches this check as well. Startup refuses token exchange over stdio and
 * behind the unauthenticated switch, because the inbound token it would exchange is
 * missing there.
 *
 * <p>
 * Client credentials works differently. It holds one principal of its own,
 * {@code GATOOL_PRINCIPAL}, so it runs on every transport without reading the security
 * context, which is why the startup message recommends it for stdio.
 *
 * @author Željko Kozina
 */
public final class OAuth2ApiCredentialStrategy implements BuiltInCredentialStrategy {

	static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

	// One fixed principal for the client credentials grant, so every call shares the
	// one token the manager holds for it.
	private static final Authentication GATOOL_PRINCIPAL = new AnonymousAuthenticationToken("GATool", "GATool",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private final OAuth2ClientHttpRequestInterceptor buildInterceptor;

	// The property value that selects this strategy, which every failure names.
	private final String strategyName;

	private OAuth2ApiCredentialStrategy(OAuth2ClientHttpRequestInterceptor buildInterceptor, String strategyName) {
		this.buildInterceptor = buildInterceptor;
		this.strategyName = strategyName;
	}

	/**
	 * Creates the client credentials strategy.
	 * @param registrationId the Spring Boot client registration to authenticate with
	 * @param registrations the registrations Spring Boot read from its properties
	 * @param tokenStore where the obtained token is kept
	 * @param restClientBuilder spring Boot's builder for the token endpoint call, or
	 * {@code null} for Spring Security's own client
	 * @return the strategy
	 */
	public static OAuth2ApiCredentialStrategy clientCredentials(String registrationId,
			ClientRegistrationRepository registrations, OAuth2AuthorizedClientService tokenStore,
			RestClient.@Nullable Builder restClientBuilder) {
		RestClientClientCredentialsTokenResponseClient responseClient = new RestClientClientCredentialsTokenResponseClient();
		RestClient restClient = tokenEndpointClient(restClientBuilder);
		if (restClient != null) {
			responseClient.setRestClient(restClient);
		}
		OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
			.clientCredentials((grant) -> grant.accessTokenResponseClient(responseClient))
			.build();
		OAuth2ClientHttpRequestInterceptor interceptor = buildInterceptor(registrationId, registrations, tokenStore,
				provider);
		interceptor.setPrincipalResolver((request) -> GATOOL_PRINCIPAL);
		return new OAuth2ApiCredentialStrategy(interceptor, "client-credentials");
	}

	/**
	 * Creates the token exchange strategy.
	 * @param registrationId the Spring Boot client registration whose grant type is token
	 * exchange
	 * @param registrations the registrations Spring Boot read from its properties
	 * @param audience the audience the exchange asks for, or {@code null} where the
	 * resource alone names the target
	 * @param resource the resource the exchange names, or {@code null}
	 * @param restClientBuilder spring Boot's builder for the token endpoint call, or
	 * {@code null} for Spring Security's own client
	 * @param contextHolderStrategy where the chain keeps the caller, which is the
	 * strategy bean of an application that declares one
	 * @return the strategy
	 */
	public static OAuth2ApiCredentialStrategy tokenExchange(String registrationId,
			ClientRegistrationRepository registrations, @Nullable String audience, @Nullable String resource,
			RestClient.@Nullable Builder restClientBuilder, SecurityContextHolderStrategy contextHolderStrategy) {
		RestClientTokenExchangeTokenResponseClient responseClient = new RestClientTokenExchangeTokenResponseClient();
		RestClient restClient = tokenEndpointClient(restClientBuilder);
		if (restClient != null) {
			responseClient.setRestClient(restClient);
		}
		// The subject is the access token the MCP caller sent, and the request says so.
		// Spring writes the JWT token type for a subject it parsed as a JWT, which the
		// standard token exchange of Keycloak 26.7.3 refuses with "supports access tokens
		// only". The access_token type is what RFC 8693 gives an access token whatever
		// its format.
		responseClient.setParametersCustomizer((parameters) -> {
			parameters.set("subject_token_type", ACCESS_TOKEN_TYPE);
			if (audience != null) {
				parameters.set("audience", audience);
			}
			if (resource != null) {
				parameters.set("resource", resource);
			}
		});
		TokenExchangeOAuth2AuthorizedClientProvider tokenExchangeProvider = new TokenExchangeOAuth2AuthorizedClientProvider();
		tokenExchangeProvider.setAccessTokenResponseClient(responseClient);
		OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
			.provider(tokenExchangeProvider)
			.build();
		OAuth2ClientHttpRequestInterceptor interceptor = buildInterceptor(registrationId, registrations,
				new ExchangedTokenStore(Clock.systemUTC()), provider);
		// The caller's own authentication, read on the thread that runs the tool call,
		// under a name that is a digest of the inbound token. Each token then gets its
		// own exchange, while the subject token resolver still finds the JWT as the
		// principal. The digest is what the store holds and what a log line prints, so
		// the token value itself stays out of both.
		interceptor.setPrincipalResolver((request) -> requireFingerprintNamed(contextHolderStrategy));
		return new OAuth2ApiCredentialStrategy(interceptor, "token-exchange");
	}

	// Spring Security's own client for the token endpoint is a bare RestClient.builder()
	// with the OAuth2 converters and error handler, so a client built from Boot's builder
	// carries the same two converters and the same handler. The builder arrives with
	// the request factory the auto-configuration gave it, which waits under the
	// application's deadlines, and under GATool's where the application left them unset.
	// The builder is cloned first, because a builder mutates in place and an
	// application's own singleton builder would otherwise carry these converters into
	// every client it builds afterwards.
	private static @Nullable RestClient tokenEndpointClient(RestClient.@Nullable Builder builder) {
		if (builder == null) {
			return null;
		}
		return builder.clone().configureMessageConverters((converters) -> {
			converters.addCustomConverter(new FormHttpMessageConverter());
			converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
		}).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
	}

	private static OAuth2ClientHttpRequestInterceptor buildInterceptor(String registrationId,
			ClientRegistrationRepository registrations, OAuth2AuthorizedClientService tokenStore,
			OAuth2AuthorizedClientProvider provider) {
		AuthorizedClientServiceOAuth2AuthorizedClientManager manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
				registrations, tokenStore);
		manager.setAuthorizedClientProvider(provider);
		OAuth2ClientHttpRequestInterceptor buildInterceptor = new OAuth2ClientHttpRequestInterceptor(manager);
		buildInterceptor.setClientRegistrationIdResolver((request) -> registrationId);
		buildInterceptor
			.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokenStore));
		return buildInterceptor;
	}

	// Two checks, both on the thread the tool call runs on. The first asks whether the
	// context on this thread belongs to this call at all. Under a strategy that threads
	// inherit or share it can belong to an earlier caller, and the exchange would then
	// mint a token for that caller. Without a bearer token the subject token is
	// missing, and Spring's interceptor would send the call without any credential, so
	// the call fails here too and the tool reads an error naming the strategy.
	static Authentication requireFingerprintNamed(SecurityContextHolderStrategy contextHolderStrategy) {
		InheritedSecurityContexts.requireAThreadWhoseContextIsItsOwn(contextHolderStrategy, "token-exchange",
				"gatool.api.credentials.strategy is token-exchange");
		@Nullable Authentication authentication = contextHolderStrategy.getContext().getAuthentication();
		if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> bearer) {
			return new FingerprintNamedAuthentication(bearer);
		}
		throw new CredentialUnavailableException("token-exchange",
				"gatool.api.credentials.strategy is token-exchange, and this call runs on a thread without the "
						+ "caller's token, so the subject token to exchange is missing. Token exchange serves calls "
						+ "that arrive through the secured MCP endpoint; a call from elsewhere needs client "
						+ "credentials or a strategy bean of its own.",
				null);
	}

	// Spring's interceptor raises OAuth2AuthorizationException when the authorization
	// server refuses the request for a token, with the error code the server sent,
	// such as invalid_client. That is the credential failing, and the runner answers
	// it as one instead of as a transport failure that invites a retry.
	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		try {
			return this.buildInterceptor.intercept(request, body, execution);
		}
		catch (OAuth2AuthorizationException ex) {
			throw new CredentialUnavailableException(this.strategyName,
					"gatool.api.credentials.strategy is " + this.strategyName
							+ ", and the authorization server refused the request for a token: " + ex.getMessage()
							+ ". Check the client registration under "
							+ "spring.security.oauth2.client.registration and the access rules of the issuer.",
					ex);
		}
	}

	/**
	 * The caller's authentication under the name of its token, so the store tells inbound
	 * tokens apart. The principal stays the JWT, which is what the token exchange
	 * provider reads the subject token from.
	 */
	static final class FingerprintNamedAuthentication implements Authentication {

		private static final long serialVersionUID = 1L;

		private final AbstractOAuth2TokenAuthenticationToken<?> delegate;

		private final String name;

		// A digest, so the store's keys and any log line that prints a name carry the
		// token's fingerprint alone.
		FingerprintNamedAuthentication(AbstractOAuth2TokenAuthenticationToken<?> delegate) {
			this.delegate = delegate;
			this.name = TokenFingerprint.of(delegate.getToken().getTokenValue());
		}

		@Override
		public String getName() {
			return this.name;
		}

		@Override
		public Collection<? extends GrantedAuthority> getAuthorities() {
			return this.delegate.getAuthorities();
		}

		@Override
		public @Nullable Object getCredentials() {
			return this.delegate.getCredentials();
		}

		@Override
		public @Nullable Object getDetails() {
			return this.delegate.getDetails();
		}

		@Override
		public @Nullable Object getPrincipal() {
			return this.delegate.getPrincipal();
		}

		@Override
		public boolean isAuthenticated() {
			return this.delegate.isAuthenticated();
		}

		// The interface asks every implementation to take false, and the call leaves this
		// object unchanged: the caller's own authentication is what is read.
		@Override
		public void setAuthenticated(boolean isAuthenticated) {
			if (isAuthenticated) {
				throw new IllegalArgumentException("The caller's authentication is read here and stays as it is");
			}
		}

	}

	/**
	 * The store of exchanged tokens, one per inbound token, which drops every expired
	 * entry each time it saves one, so it holds at most the tokens that are still usable.
	 */
	static final class ExchangedTokenStore implements OAuth2AuthorizedClientService {

		static final int MAX_ENTRIES = 10_000;

		private final Map<String, OAuth2AuthorizedClient> clients = new ConcurrentHashMap<>();

		private final Clock clock;

		ExchangedTokenStore(Clock clock) {
			this.clock = clock;
		}

		// The type parameter is the interface's own, so the unchecked cast comes with it.
		@Override
		@SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
		public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(String clientRegistrationId,
				String principalName) {
			return (T) this.clients.get(clientRegistrationId + ":" + principalName);
		}

		// Above the cap the entries nearest their expiry go first, so a burst of
		// short-lived tokens cannot grow the store past a bound.
		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
			Instant now = this.clock.instant();
			this.clients.values().removeIf((client) -> hasExpired(client.getAccessToken(), now));
			while (this.clients.size() >= MAX_ENTRIES) {
				String earliestExpiringKey = this.clients.entrySet()
					.stream()
					.min(Comparator.comparing((entry) -> expiryOf(entry.getValue())))
					.map(Map.Entry::getKey)
					.orElse(null);
				if (earliestExpiringKey == null) {
					break;
				}
				this.clients.remove(earliestExpiringKey);
			}
			this.clients.put(authorizedClient.getClientRegistration().getRegistrationId() + ":" + principal.getName(),
					authorizedClient);
		}

		private static Instant expiryOf(OAuth2AuthorizedClient client) {
			Instant expiresAt = client.getAccessToken().getExpiresAt();
			return (expiresAt != null) ? expiresAt : Instant.MIN;
		}

		@Override
		public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
			this.clients.remove(clientRegistrationId + ":" + principalName);
		}

		int size() {
			return this.clients.size();
		}

		// Spring gives every token response an expiry, one second after issue where the
		// issuer left it out, and a token without one is treated as expired here, so
		// that every entry in the store can be swept.
		private static boolean hasExpired(OAuth2Token token, Instant now) {
			Instant expiresAt = token.getExpiresAt();
			return expiresAt == null || !expiresAt.isAfter(now);
		}

	}

}
