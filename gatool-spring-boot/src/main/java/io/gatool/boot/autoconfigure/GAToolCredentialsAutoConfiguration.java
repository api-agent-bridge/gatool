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

package io.gatool.boot.autoconfigure;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import io.gatool.boot.autoconfigure.GAToolApiProperties.Credentials;
import io.gatool.boot.autoconfigure.GAToolApiProperties.Credentials.CredentialStrategy;
import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.internal.credentials.ForwardedTokenCredentialStrategy;
import io.gatool.boot.internal.credentials.OAuth2ApiCredentialStrategy;
import io.gatool.boot.internal.execution.RemoteHttpClients;

/**
 * The outbound credential strategies that need Spring Security on the classpath: client
 * credentials, token exchange, and the caller's own token forwarded behind an unsafe
 * switch.
 *
 * <p>
 * Each strategy is a bean of {@link ApiCredentialStrategy}, which the remote executor
 * picks up, and the static header strategy stays in {@link GAToolAutoConfiguration},
 * because Spring Web is all it needs. An application's own bean of that type wins over
 * every built-in one, which is also how a team exchanges tokens with an actor token, or
 * in any other shape the built-in strategy leaves out.
 *
 * <p>
 * The OAuth2 strategies build on Spring Boot's client registration properties,
 * {@code spring.security.oauth2.client.registration.<id>}, so the token endpoint, the
 * client secret and the client authentication are the ones every Spring application
 * configures the same way. Client credentials keeps its one token in the
 * {@code OAuth2AuthorizedClientService} Boot defines beside the registrations, and token
 * exchange keeps one exchanged token per inbound token in a store of its own. Everything
 * else the strategies need is built inside them and stays out of the context, so Spring
 * Security's own authorized client manager, which collects every provider bean it finds,
 * is left as the application configured it. Startup stops, naming the property, when the
 * registration is missing or carries another grant type than the strategy asks for.
 *
 * @author Željko Kozina
 */
@AutoConfiguration(after = GAToolAutoConfiguration.class,
		afterName = "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration")
@ConditionalOnBean(GAToolProperties.class)
public final class GAToolCredentialsAutoConfiguration {

	static final String STRATEGY_PROPERTY = "gatool.api.credentials.strategy";

	static final String CLIENT_REGISTRATION_ID_PROPERTY = "gatool.api.credentials.client-registration-id";

	static final String AUDIENCE_PROPERTY = "gatool.api.credentials.audience";

	static final String RESOURCE_PROPERTY = "gatool.api.credentials.resource";

	static final String FORWARD_TOKENS_PROPERTY = "gatool.api.credentials.unsafe.forward-client-tokens";

	static final String OAUTH2_CLIENT_STARTER = "spring-boot-starter-security-oauth2-client";

	private static final Log logger = LogFactory.getLog(GAToolCredentialsAutoConfiguration.class);

	/**
	 * Creates the auto-configuration, which Spring Boot instantiates at startup.
	 */
	public GAToolCredentialsAutoConfiguration() {
		// The beans come from the methods below; Spring Boot instantiates the class.
	}

	/**
	 * Stops startup when the forwarding switch is on beside a strategy, whichever bean
	 * would have been created first.
	 * @param properties the GATool properties, which hold the switch and the strategy
	 */
	static void stopWhenForwardingMeetsAStrategy(GAToolProperties properties) {
		CredentialStrategy strategy = properties.getApi().getCredentials().getStrategy();
		if (properties.getApi().getCredentials().getUnsafe().isForwardClientTokens() && strategy != null) {
			throw new InvalidConfigurationPropertyValueException(FORWARD_TOKENS_PROPERTY, "true",
					"This switch forwards the caller's own token, and " + STRATEGY_PROPERTY + " is "
							+ strategy.propertyValue() + ", which sends a credential of its own. Keep one of the "
							+ "two.");
		}
	}

	// Both strategies read the caller's token from the security context, which exists
	// over HTTP with security on alone: stdio runs without an inbound token, and the
	// unsafe switch leaves every caller anonymous. The stop names the property that
	// asked for the caller's token, because that setting is the one to change.
	static void stopWithoutAnAuthenticatedCaller(GAToolProperties properties, Environment environment, String property,
			String value, String feature) {
		if (SpringAiMcpKeys.servesStdio(environment)) {
			throw new InvalidConfigurationPropertyValueException(property, value,
					"Over stdio the caller is the process that started this server and does not send a token, so "
							+ feature + " is left without a token. Use client credentials or a static header there.");
		}
		if (properties.getMcp().getSecurity().getUnsafe().isAllowMcpCallsWithoutAuthentication()) {
			throw new InvalidConfigurationPropertyValueException(property, value,
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication is true, so the MCP caller "
							+ "arrives without a token, which " + feature + " needs. Secure the "
							+ "endpoint, or choose client credentials or a static header.");
		}
	}

	/**
	 * The two strategies that make GATool an OAuth2 client of the GraphQL API.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager")
	static class OAuth2Client {

		/**
		 * Builds the strategy the property selects.
		 * @param properties the GATool properties
		 * @param environment the environment, for the transport and the security switch
		 * @param registrationsProvider spring Boot's registrations, absent until one is
		 * configured
		 * @param tokenStoreProvider spring Boot's token store
		 * @param restClientBuilder spring Boot's builder, for an application without
		 * {@code spring-boot-http-client}
		 * @param contextHolderStrategy the strategy the application declares as a bean
		 * @param httpClients the clients of GATool, which bring the deadlines of the
		 * token request
		 * @return the strategy
		 */
		@Bean
		@Conditional(OAuth2StrategySelected.class)
		@ConditionalOnMissingBean(ApiCredentialStrategy.class)
		ApiCredentialStrategy gaToolOAuth2CredentialStrategy(GAToolProperties properties, Environment environment,
				ObjectProvider<ClientRegistrationRepository> registrationsProvider,
				ObjectProvider<OAuth2AuthorizedClientService> tokenStoreProvider,
				ObjectProvider<RestClient.Builder> restClientBuilder,
				ObjectProvider<SecurityContextHolderStrategy> contextHolderStrategy,
				ObjectProvider<RemoteHttpClients> httpClients) {
			Credentials credentials = properties.getApi().getCredentials();
			CredentialStrategy strategy = credentials.getStrategy();
			String registrationId = requireRegistrationId(credentials, strategy);
			ClientRegistrationRepository registrations = requireRegistrations(registrationsProvider, registrationId);
			requireRegistrationWithTheGrantType(registrations, registrationId, strategy);
			// The token request waits under the deadlines of the call to the API. Where
			// spring-boot-http-client is absent GATool's clients are absent too, and
			// Boot's builder is used as it comes.
			RemoteHttpClients clients = httpClients.getIfAvailable();
			RestClient.Builder builder = (clients != null) ? clients.tokenEndpointClientBuilder()
					: restClientBuilder.getIfAvailable();
			if (strategy == CredentialStrategy.TOKEN_EXCHANGE) {
				stopWithoutAnAuthenticatedCaller(properties, environment, STRATEGY_PROPERTY,
						CredentialStrategy.TOKEN_EXCHANGE.propertyValue(), "token exchange");
				String audience = blankToNull(credentials.getAudience());
				String resource = requireResourceIndicator(blankToNull(credentials.getResource()));
				if (audience == null && resource == null) {
					throw new InvalidConfigurationPropertyValueException(AUDIENCE_PROPERTY, null,
							"Token exchange asks the issuer for a token the GraphQL API accepts, so set this "
									+ "property to the audience that API validates, which the exchange sends as "
									+ "the audience parameter, or set " + RESOURCE_PROPERTY + " to the API's "
									+ "resource indicator, or both.");
				}
				logger.info("GATool calls the GraphQL API with a token exchanged for each caller's own, through the "
						+ "registration " + registrationId + ", so the API sees the real user. Each inbound "
						+ "token's exchange is kept in memory until it expires.");
				return OAuth2ApiCredentialStrategy.tokenExchange(registrationId, registrations, audience, resource,
						builder, contextHolderStrategy.getIfUnique(SecurityContextHolder::getContextHolderStrategy));
			}
			OAuth2AuthorizedClientService tokenStore = tokenStoreProvider.getIfAvailable();
			if (tokenStore == null) {
				// A property exception, so Boot's analyzer prints the property and the
				// action where a bare IllegalStateException prints a stack trace.
				throw new InvalidConfigurationPropertyValueException(STRATEGY_PROPERTY, nameOf(strategy),
						"Client credentials keep GATool's token in the OAuth2AuthorizedClientService that Spring "
								+ "Boot defines beside its client registrations, and this context is without one. "
								+ "Declare an OAuth2AuthorizedClientService bean, or leave Boot's "
								+ "OAuth2ClientAutoConfiguration in.");
			}
			logger.info("GATool calls the GraphQL API with client credentials through the registration "
					+ registrationId + ", so the API sees GATool as the caller of every tool.");
			return OAuth2ApiCredentialStrategy.clientCredentials(registrationId, registrations, tokenStore, builder);
		}

		// Returns the id of the client registration the strategy authenticates through,
		// and stops startup where the property is unset.
		private static String requireRegistrationId(Credentials credentials, @Nullable CredentialStrategy strategy) {
			String registrationId = credentials.getClientRegistrationId();
			if (registrationId == null || registrationId.isBlank()) {
				throw new InvalidConfigurationPropertyValueException(CLIENT_REGISTRATION_ID_PROPERTY, null,
						"The " + nameOf(strategy) + " strategy authenticates through a Spring Boot client "
								+ "registration, so set this property to the id under "
								+ "spring.security.oauth2.client.registration that names the token endpoint, the "
								+ "client id and the secret.");
			}
			return registrationId;
		}

		// Returns Spring Boot's client registrations, and stops startup where the
		// application does not declare any.
		private static ClientRegistrationRepository requireRegistrations(
				ObjectProvider<ClientRegistrationRepository> registrationsProvider, String registrationId) {
			ClientRegistrationRepository registrations = registrationsProvider.getIfAvailable();
			if (registrations == null) {
				throw new InvalidConfigurationPropertyValueException(CLIENT_REGISTRATION_ID_PROPERTY, registrationId,
						"Spring Boot does not hold any client registration. Declare one under "
								+ "spring.security.oauth2.client.registration." + registrationId
								+ " with the client id, the secret and the provider's token-uri.");
			}
			return registrations;
		}

		// Stops startup where the registration is missing or carries another grant type
		// than the strategy asks for.
		private static void requireRegistrationWithTheGrantType(ClientRegistrationRepository registrations,
				String registrationId, @Nullable CredentialStrategy strategy) {
			ClientRegistration registration = registrations.findByRegistrationId(registrationId);
			if (registration == null) {
				throw new InvalidConfigurationPropertyValueException(CLIENT_REGISTRATION_ID_PROPERTY, registrationId,
						"Spring Boot does not hold a client registration by that id. Declare it under "
								+ "spring.security.oauth2.client.registration." + registrationId
								+ " with the client id, the secret and the provider's token-uri.");
			}
			AuthorizationGrantType expectedGrantType = (strategy == CredentialStrategy.TOKEN_EXCHANGE)
					? AuthorizationGrantType.TOKEN_EXCHANGE : AuthorizationGrantType.CLIENT_CREDENTIALS;
			if (!expectedGrantType.equals(registration.getAuthorizationGrantType())) {
				throw new InvalidConfigurationPropertyValueException(CLIENT_REGISTRATION_ID_PROPERTY, registrationId,
						"The " + nameOf(strategy) + " strategy needs a registration whose authorization-grant-type "
								+ "is " + expectedGrantType.getValue() + ", and this one carries "
								+ registration.getAuthorizationGrantType().getValue() + ".");
			}
		}

		// RFC 8693 asks for an absolute URI without a fragment, and an issuer refuses
		// anything else at call time, where a stop at startup names the property.
		private static @Nullable String requireResourceIndicator(@Nullable String resource) {
			if (resource == null) {
				return null;
			}
			try {
				URI uri = new URI(resource);
				if (!uri.isAbsolute() || uri.getFragment() != null) {
					throw new InvalidConfigurationPropertyValueException(RESOURCE_PROPERTY, resource,
							"A resource indicator is an absolute URI without a fragment, such as "
									+ "https://api.example.com/graphql.");
				}
			}
			catch (URISyntaxException ex) {
				throw new InvalidConfigurationPropertyValueException(RESOURCE_PROPERTY, resource,
						"A resource indicator is an absolute URI without a fragment, such as "
								+ "https://api.example.com/graphql, and this value fails to parse as a URI.");
			}
			return resource;
		}

		private static @Nullable String blankToNull(@Nullable String value) {
			return (value == null || value.isBlank()) ? null : value;
		}

		private static String nameOf(@Nullable CredentialStrategy strategy) {
			return (strategy != null) ? strategy.propertyValue() : "unset";
		}

	}

	/**
	 * The caller's own token, forwarded to the API behind the unsafe switch.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = { "org.springframework.security.core.context.SecurityContextHolder",
			"org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken" })
	static class ForwardedToken {

		@Bean
		@Conditional(ForwardingSelected.class)
		@ConditionalOnMissingBean(ApiCredentialStrategy.class)
		ApiCredentialStrategy gaToolForwardedTokenCredentialStrategy(GAToolProperties properties,
				Environment environment, ObjectProvider<SecurityContextHolderStrategy> contextHolderStrategy) {
			stopWhenForwardingMeetsAStrategy(properties);
			stopWithoutAnAuthenticatedCaller(properties, environment, FORWARD_TOKENS_PROPERTY, "true",
					"forwarding the caller's token");
			logger
				.warn(FORWARD_TOKENS_PROPERTY + " is true, so every call to the GraphQL API carries the token the MCP "
						+ "caller sent. MCP says a server must not pass that token through: the API cannot tell GATool "
						+ "from the caller, a token minted for this server reaches another audience, and a server "
						+ "with this switch on is outside the MCP specification.");
			return new ForwardedTokenCredentialStrategy(
					contextHolderStrategy.getIfUnique(SecurityContextHolder::getContextHolderStrategy));
		}

	}

	/**
	 * Matches while the strategy is one of the two OAuth2 ones, bound the way Boot binds
	 * the enum, so every spelling Boot accepts for the value selects the bean.
	 */
	static class OAuth2StrategySelected extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			ConditionMessage.Builder message = ConditionMessage.forCondition("GATool credential strategy");
			CredentialStrategy strategy;
			try {
				strategy = Binder.get(context.getEnvironment())
					.bind(STRATEGY_PROPERTY, CredentialStrategy.class)
					.orElse(null);
			}
			catch (BindException ex) {
				// A value outside the enum is reported where the properties bind, with
				// Boot's own message naming the property and the values it takes.
				return ConditionOutcome
					.noMatch(message.because("the value fails to bind, which the properties " + "report"));
			}
			if (strategy == null) {
				return ConditionOutcome.noMatch(message.didNotFind("an OAuth2 strategy").atAll());
			}
			return switch (strategy) {
				case CLIENT_CREDENTIALS, TOKEN_EXCHANGE ->
					ConditionOutcome.match(message.found(STRATEGY_PROPERTY).items(strategy.propertyValue()));
				default -> ConditionOutcome.noMatch(message.didNotFind("an OAuth2 strategy").atAll());
			};
		}

	}

	/**
	 * Matches while the forwarding switch is on, bound the way Boot binds a boolean, so
	 * {@code yes} and {@code on} select the bean as {@code true} does.
	 */
	static class ForwardingSelected extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			ConditionMessage.Builder message = ConditionMessage.forCondition("GATool forwarded token");
			boolean on;
			try {
				on = Binder.get(context.getEnvironment())
					.bind(FORWARD_TOKENS_PROPERTY, Boolean.class)
					.orElseGet(() -> false);
			}
			catch (BindException ex) {
				return ConditionOutcome
					.noMatch(message.because("the value fails to bind, which the properties " + "report"));
			}
			return on ? ConditionOutcome.match(message.found(FORWARD_TOKENS_PROPERTY).items("true"))
					: ConditionOutcome.noMatch(message.didNotFind(FORWARD_TOKENS_PROPERTY).atAll());
		}

	}

}
