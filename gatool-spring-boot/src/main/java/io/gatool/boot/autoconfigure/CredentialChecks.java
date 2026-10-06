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

import java.util.function.UnaryOperator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.internal.credentials.BuiltInCredentialStrategy;
import io.gatool.boot.internal.execution.StaticHeaderCredentialStrategy;

/**
 * Chooses the credential strategy of remote mode and checks the static header and the
 * header of the schema request against what the JDK's client sends.
 *
 * <p>
 * The methods log under the name of {@link GAToolAutoConfiguration}, so a logging level
 * an operator set for it covers these lines.
 *
 * @author Željko Kozina
 */
final class CredentialChecks {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	// The characters RFC 9110 admits in a field name beside letters and digits.
	static final String TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~";

	private CredentialChecks() {
	}

	/**
	 * Returns the function that blanks the configured static header value in a body the
	 * API answered with.
	 * @param properties the GATool properties, which hold the static header value
	 * @return the function that blanks that value, or the identity where the value is
	 * unset or blank
	 */
	// The runner blanks a bearer token by its shape. A static header value is whatever
	// the application configured, an API key as often as a token, so its shape is
	// unknown and the value itself is what gets blanked. An API or a proxy that echoes
	// the request into its error page would otherwise hand the key to the model. The
	// value is blanked whenever one is configured, whichever strategy is selected,
	// because blanking a value the call did not send costs a string search and keeps
	// the answer as safe either way.
	static UnaryOperator<String> credentialRedaction(GAToolProperties properties) {
		String value = properties.getApi().getCredentials().getHeaderValue();
		if (value == null || value.isBlank()) {
			return UnaryOperator.identity();
		}
		return (text) -> text.replace(value, "[redacted]");
	}

	/**
	 * Returns the strategy that authenticates every remote call: the application's own
	 * bean, the static header built here, or null where the strategy stays unset.
	 * @param properties the GATool properties, which name the strategy
	 * @param credential the application's own strategy bean, where it declares one
	 * @return the interceptor that authenticates each call, or {@code null} where the
	 * strategy stays unset
	 */
	// A bean of the public type wins, so an application replaces the built-in
	// strategies with one line. The OAuth2 strategies and the forwarded token are beans
	// of the credentials auto-configuration, which needs Spring Security, so a
	// strategy that selects one without such a bean is missing that dependency. An
	// unset strategy calls the API without a credential, which suits a local API and
	// an internal one behind a gateway.
	static @Nullable ClientHttpRequestInterceptor chooseCredentialStrategy(GAToolProperties properties,
			ObjectProvider<ApiCredentialStrategy> credential) {
		GAToolApiProperties.Credentials credentials = properties.getApi().getCredentials();
		// Checked here first, whichever built-in bean the context created, so the two
		// settings cannot be combined by the order beans happen to be registered in.
		GAToolCredentialsAutoConfiguration.stopWhenForwardingMeetsAStrategy(properties);
		ApiCredentialStrategy applicationStrategy = credential.getIfAvailable();
		if (applicationStrategy != null) {
			boolean builtIn = applicationStrategy instanceof BuiltInCredentialStrategy;
			if (!builtIn && (credentials.getStrategy() != null
					|| properties.getApi().getCredentials().getUnsafe().isForwardClientTokens())) {
				logger.info("GATool authenticates to the GraphQL API with the ApiCredentialStrategy bean "
						+ applicationStrategy.getClass().getName() + ", which replaces the built-in strategy.");
			}
			return applicationStrategy;
		}
		if (properties.getApi().getCredentials().getUnsafe().isForwardClientTokens()) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.unsafe.forward-client-tokens",
					"true", "Forwarding the caller's token reads it from Spring Security, which is missing from this "
							+ "application. Add spring-boot-starter-security-oauth2-resource-server.");
		}
		GAToolApiProperties.Credentials.CredentialStrategy strategy = credentials.getStrategy();
		if (strategy == null) {
			return null;
		}
		if (strategy != GAToolApiProperties.Credentials.CredentialStrategy.STATIC_HEADER) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.strategy",
					strategy.propertyValue(),
					"This strategy makes GATool an OAuth2 client of the GraphQL API "
							+ "through Spring Security, which is missing from this application. Add "
							+ "spring-boot-starter-security-oauth2-client.");
		}
		return buildStaticHeader(credentials);
	}

	// Returns the static header, stopping startup while either half is unset or cannot be
	// sent.
	//
	// The JDK refuses a header it cannot send while it builds the request, on every
	// call, and names the whole value in its message, so both halves are checked here,
	// the way the registry key's are. The exception for the value names the header
	// name's property, because Boot's failure analysis prints the live value of the
	// property it is given, and the value is the credential.
	private static StaticHeaderCredentialStrategy buildStaticHeader(GAToolApiProperties.Credentials credentials) {
		String name = credentials.getHeaderName();
		if (name == null || name.isBlank()) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.header-name", null,
					"The static-header strategy sends one header on every call to the GraphQL API, so set the "
							+ "name of that header, such as Authorization or X-API-Key.");
		}
		if (!isHeaderName(name)) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.header-name", name,
					"A header name is letters, digits and the characters " + TOKEN_PUNCTUATION + ", and this one "
							+ "holds a character a header name cannot carry. Set a name such as Authorization or "
							+ "X-API-Key.");
		}
		String value = credentials.getHeaderValue();
		if (value == null || value.isBlank()) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.header-value", null,
					"The static-header strategy sends one header on every call to the GraphQL API, so set its "
							+ "value, and keep that value in the environment instead of in a file a repository "
							+ "holds.");
		}
		String problem = headerValueProblem(value);
		if (problem != null) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.credentials.header-name", name,
					"The value of gatool.api.credentials.header-value " + problem + ", which the HTTP client "
							+ "refuses on every call. A secret read from a file often ends in a line break; strip "
							+ "it.");
		}
		return new StaticHeaderCredentialStrategy(name, value);
	}

	/**
	 * Tells whether the name is a field name RFC 9110 admits, which is what the JDK's
	 * client sends.
	 * @param name the configured header name
	 * @return whether the name is a field name RFC 9110 admits
	 */
	static boolean isHeaderName(String name) {
		return !name.isEmpty() && name.chars()
			.allMatch((character) -> character < 0x80
					&& (Character.isLetterOrDigit(character) || TOKEN_PUNCTUATION.indexOf(character) >= 0));
	}

	/**
	 * Returns what keeps the value from being sent as a header value, or {@code null}
	 * where the JDK's client sends it.
	 * @param value the configured header value
	 * @return what keeps the value from being sent, or {@code null} where the client
	 * sends it
	 */
	// The JDK's own rule: a control character other than a tab, a DEL, and a character
	// above 0xFF fail while the request is built. A space or a tab inside the value is
	// legal, and "Bearer <token>" depends on it, so the check admits it. One at either
	// end is a mistake the JDK sends as it is, and the API then refuses the call.
	static @Nullable String headerValueProblem(String value) {
		if (value.chars()
			.anyMatch(
					(character) -> (character < 0x20 && character != '\t') || character == 0x7F || character > 0xFF)) {
			return "holds a character a header cannot carry, such as a line break";
		}
		if (!value.equals(value.strip())) {
			return "starts or ends with a space or a tab";
		}
		return null;
	}

}
