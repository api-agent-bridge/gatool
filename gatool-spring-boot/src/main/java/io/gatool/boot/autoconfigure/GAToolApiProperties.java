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

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

/**
 * The GraphQL API that GATool sends every document to.
 *
 * @author Željko Kozina
 */
public class GAToolApiProperties {

	/**
	 * Creates the group under {@code gatool.api} with the default of each property.
	 */
	public GAToolApiProperties() {
		// Each property keeps the default its field declares.
	}

	/**
	 * URL of the GraphQL API, such as https://movies.example.com/graphql.
	 */
	private @Nullable String url;

	/**
	 * Largest response read from the GraphQL API. Applies to a remote API only. The
	 * schema fetched from a URL has a bound of its own,
	 * {@code gatool.api.schema.max-size}.
	 */
	private DataSize maxResponseSize = DataSize.ofMegabytes(1);

	private final Schema schema = new Schema();

	private final Credentials credentials = new Credentials();

	private final RequestLimits requestLimits = new RequestLimits();

	/**
	 * Returns the URL of the GraphQL API, such as https://movies.example.com/graphql.
	 * @return the value of {@code gatool.api.url}
	 */
	public @Nullable String getUrl() {
		return this.url;
	}

	/**
	 * Sets the URL of the GraphQL API, such as https://movies.example.com/graphql.
	 * @param url the value of {@code gatool.api.url}
	 */
	public void setUrl(@Nullable String url) {
		this.url = url;
	}

	/**
	 * Returns the largest response read from the GraphQL API.
	 * @return the value of {@code gatool.api.max-response-size}
	 */
	public DataSize getMaxResponseSize() {
		return this.maxResponseSize;
	}

	/**
	 * Sets the largest response read from the GraphQL API.
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 */
	public void setMaxResponseSize(DataSize maxResponseSize) {
		this.maxResponseSize = maxResponseSize;
	}

	/**
	 * Returns the group of properties under {@code gatool.api.schema}.
	 * @return the group
	 */
	public Schema getSchema() {
		return this.schema;
	}

	/**
	 * Returns the group of properties under {@code gatool.api.credentials}.
	 * @return the group
	 */
	public Credentials getCredentials() {
		return this.credentials;
	}

	/**
	 * Returns the group of properties under {@code gatool.api.request-limits}.
	 * @return the group
	 */
	public RequestLimits getRequestLimits() {
		return this.requestLimits;
	}

	/**
	 * The limits you expect the GraphQL API to apply to a request. GATool reads an
	 * operation file however long it is, and the API refuses a call whose document passes
	 * its own limits, which GraphQL's introspection leaves out, so GATool cannot read
	 * them from the API. Startup and the build check warn about a tool whose document
	 * passes one of these, and the tool is served all the same.
	 */
	// The defaults are what graphql-java reads of a request, so they hold for an API
	// built on it that left its parser options alone. They are written here as
	// literals, because the configuration processor reads a field initialiser and
	// publishes it as the default an IDE shows.
	public static class RequestLimits {

		/**
		 * Creates the group under {@code gatool.api.request-limits} with the default of
		 * each property.
		 */
		public RequestLimits() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Characters the API reads of a document. GATool warns about a tool whose
		 * document holds more. A value below 1 leaves the characters unchecked.
		 */
		private int maxCharacters = 1_048_576;

		/**
		 * Tokens the API reads of a document. GATool warns about a tool whose document
		 * holds more. A value below 1 leaves the tokens unchecked.
		 */
		private int maxTokens = 15_000;

		/**
		 * Whitespace tokens the API reads of a document, which the indentation of the
		 * printed document adds up to. GATool warns about a tool whose document holds
		 * more. A value below 1 leaves them unchecked.
		 */
		private int maxWhitespaceTokens = 200_000;

		/**
		 * Returns how many characters the API reads of a document.
		 * @return the value of {@code gatool.api.request-limits.max-characters}
		 */
		public int getMaxCharacters() {
			return this.maxCharacters;
		}

		/**
		 * Sets how many characters the API reads of a document.
		 * @param maxCharacters the value of
		 * {@code gatool.api.request-limits.max-characters}
		 */
		public void setMaxCharacters(int maxCharacters) {
			this.maxCharacters = maxCharacters;
		}

		/**
		 * Returns how many tokens the API reads of a document.
		 * @return the value of {@code gatool.api.request-limits.max-tokens}
		 */
		public int getMaxTokens() {
			return this.maxTokens;
		}

		/**
		 * Sets how many tokens the API reads of a document.
		 * @param maxTokens the value of {@code gatool.api.request-limits.max-tokens}
		 */
		public void setMaxTokens(int maxTokens) {
			this.maxTokens = maxTokens;
		}

		/**
		 * Returns how many whitespace tokens the API reads of a document, which the
		 * indentation of the printed document adds up to.
		 * @return the value of {@code gatool.api.request-limits.max-whitespace-tokens}
		 */
		public int getMaxWhitespaceTokens() {
			return this.maxWhitespaceTokens;
		}

		/**
		 * Sets how many whitespace tokens the API reads of a document, which the
		 * indentation of the printed document adds up to.
		 * @param maxWhitespaceTokens the value of
		 * {@code gatool.api.request-limits.max-whitespace-tokens}
		 */
		public void setMaxWhitespaceTokens(int maxWhitespaceTokens) {
			this.maxWhitespaceTokens = maxWhitespaceTokens;
		}

	}

	/**
	 * How GATool authenticates to the GraphQL API in remote mode: a static header, client
	 * credentials or token exchange, or a strategy bean of the application's own.
	 */
	public static class Credentials {

		/**
		 * Creates the group under {@code gatool.api.credentials} with the default of each
		 * property.
		 */
		public Credentials() {
			// Each property keeps the default its field declares.
		}

		private final Credentials.Unsafe unsafe = new Credentials.Unsafe();

		/**
		 * Returns the group of properties under {@code gatool.api.credentials.unsafe}.
		 * @return the group
		 */
		public Credentials.Unsafe getUnsafe() {
			return this.unsafe;
		}

		/**
		 * How GATool authenticates to the GraphQL API. A credential strategy bean
		 * replaces the built-in ones. Unset sends the request without a credential.
		 */
		private @Nullable CredentialStrategy strategy;

		/**
		 * Name of the header that carries the credential, such as Authorization or
		 * X-API-Key.
		 */
		private @Nullable String headerName;

		/**
		 * Value of that header, such as a bearer token.
		 */
		private @Nullable String headerValue;

		/**
		 * The Spring Boot client registration the client-credentials and token-exchange
		 * strategies authenticate through, an id under
		 * spring.security.oauth2.client.registration.
		 */
		private @Nullable String clientRegistrationId;

		/**
		 * The audience the token-exchange strategy asks the issuer for, which is the
		 * audience the GraphQL API validates.
		 */
		private @Nullable String audience;

		/**
		 * The resource the token-exchange strategy names to the issuer, sent as the
		 * resource parameter when set.
		 */
		private @Nullable String resource;

		/**
		 * Returns how GATool authenticates to the GraphQL API.
		 * @return the value of {@code gatool.api.credentials.strategy}
		 */
		public @Nullable CredentialStrategy getStrategy() {
			return this.strategy;
		}

		/**
		 * Sets how GATool authenticates to the GraphQL API.
		 * @param strategy the value of {@code gatool.api.credentials.strategy}
		 */
		public void setStrategy(@Nullable CredentialStrategy strategy) {
			this.strategy = strategy;
		}

		/**
		 * Returns the name of the header that carries the credential, such as
		 * Authorization or X-API-Key.
		 * @return the value of {@code gatool.api.credentials.header-name}
		 */
		public @Nullable String getHeaderName() {
			return this.headerName;
		}

		/**
		 * Sets the name of the header that carries the credential, such as Authorization
		 * or X-API-Key.
		 * @param headerName the value of {@code gatool.api.credentials.header-name}
		 */
		public void setHeaderName(@Nullable String headerName) {
			this.headerName = headerName;
		}

		/**
		 * Returns the value of that header, such as a bearer token.
		 * @return the value of {@code gatool.api.credentials.header-value}
		 */
		public @Nullable String getHeaderValue() {
			return this.headerValue;
		}

		/**
		 * Sets the value of that header, such as a bearer token.
		 * @param headerValue the value of {@code gatool.api.credentials.header-value}
		 */
		public void setHeaderValue(@Nullable String headerValue) {
			this.headerValue = headerValue;
		}

		/**
		 * Returns the Spring Boot client registration the client-credentials and
		 * token-exchange strategies authenticate through, an id under
		 * spring.security.oauth2.client.registration.
		 * @return the value of {@code gatool.api.credentials.client-registration-id}
		 */
		public @Nullable String getClientRegistrationId() {
			return this.clientRegistrationId;
		}

		/**
		 * Sets the Spring Boot client registration the client-credentials and
		 * token-exchange strategies authenticate through, an id under
		 * spring.security.oauth2.client.registration.
		 * @param clientRegistrationId the value of
		 * {@code gatool.api.credentials.client-registration-id}
		 */
		public void setClientRegistrationId(@Nullable String clientRegistrationId) {
			this.clientRegistrationId = clientRegistrationId;
		}

		/**
		 * Returns the audience the token-exchange strategy asks the issuer for, which is
		 * the audience the GraphQL API validates.
		 * @return the value of {@code gatool.api.credentials.audience}
		 */
		public @Nullable String getAudience() {
			return this.audience;
		}

		/**
		 * Sets the audience the token-exchange strategy asks the issuer for, which is the
		 * audience the GraphQL API validates.
		 * @param audience the value of {@code gatool.api.credentials.audience}
		 */
		public void setAudience(@Nullable String audience) {
			this.audience = audience;
		}

		/**
		 * Returns the resource the token-exchange strategy names to the issuer, sent as
		 * the resource parameter when set.
		 * @return the value of {@code gatool.api.credentials.resource}
		 */
		public @Nullable String getResource() {
			return this.resource;
		}

		/**
		 * Sets the resource the token-exchange strategy names to the issuer, sent as the
		 * resource parameter when set.
		 * @param resource the value of {@code gatool.api.credentials.resource}
		 */
		public void setResource(@Nullable String resource) {
			this.resource = resource;
		}

		/**
		 * Switches that weaken how GATool authenticates to the GraphQL API.
		 */
		public static class Unsafe {

			/**
			 * Creates the group under {@code gatool.api.credentials.unsafe} with the
			 * default of each property.
			 */
			public Unsafe() {
				// Each property keeps the default its field declares.
			}

			/**
			 * Whether every call to the GraphQL API carries the token the MCP client
			 * sent. MCP says a server must not pass that token through, because the API
			 * cannot tell GATool from the caller.
			 */
			private boolean forwardClientTokens;

			/**
			 * Returns whether every call to the GraphQL API carries the token the MCP
			 * client sent.
			 * @return the value of
			 * {@code gatool.api.credentials.unsafe.forward-client-tokens}
			 */
			public boolean isForwardClientTokens() {
				return this.forwardClientTokens;
			}

			/**
			 * Sets whether every call to the GraphQL API carries the token the MCP client
			 * sent.
			 * @param forwardClientTokens the value of
			 * {@code gatool.api.credentials.unsafe.forward-client-tokens}
			 */
			public void setForwardClientTokens(boolean forwardClientTokens) {
				this.forwardClientTokens = forwardClientTokens;
			}

		}

		/**
		 * The built-in credential strategies.
		 */
		public enum CredentialStrategy {

			/**
			 * Sends a fixed header on every call, such as an API key. This suits an
			 * internal API, development and stdio.
			 */
			STATIC_HEADER("static-header", true),

			/**
			 * Authenticates as GATool itself with the client credentials grant, through a
			 * Spring Boot client registration. The API sees GATool as the caller of every
			 * tool.
			 */
			CLIENT_CREDENTIALS("client-credentials", true),

			/**
			 * Exchanges the caller's token for one issued for the API, through a Spring
			 * Boot client registration with the token-exchange grant. The API sees the
			 * real user.
			 */
			TOKEN_EXCHANGE("token-exchange", false);

			private final String propertyValue;

			private final boolean sharesOneIdentity;

			CredentialStrategy(String propertyValue, boolean sharesOneIdentity) {
				this.propertyValue = propertyValue;
				this.sharesOneIdentity = sharesOneIdentity;
			}

			/**
			 * Returns the value as a property file writes it.
			 * @return the kebab-case name
			 */
			public String propertyValue() {
				return this.propertyValue;
			}

			/**
			 * Returns whether the GraphQL API sees GATool, and every MCP caller through
			 * it, as one caller.
			 * @return true for a credential without a user behind it
			 */
			public boolean sharesOneIdentity() {
				return this.sharesOneIdentity;
			}

		}

	}

	/**
	 * Where the schema of the API comes from.
	 */
	public static class Schema {

		/**
		 * Creates the group under {@code gatool.api.schema} with the default of each
		 * property.
		 */
		public Schema() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Location of the schema definition language file of the API: a classpath: or
		 * file: resource, an https:// URL such as a schema registry's, or a protocol a
		 * Spring Cloud module registers, such as s3://.
		 */
		private @Nullable Resource location;

		/**
		 * Name of the header that carries the registry key when the location is an http
		 * or https URL, such as X-Hive-CDN-Key.
		 */
		private @Nullable String headerName;

		/**
		 * Value of that header. Keep it in the environment, out of any file a repository
		 * holds.
		 */
		private @Nullable String headerValue;

		/**
		 * Where a schema fetched from a URL is cached with its ETag, so the next startup
		 * sends If-None-Match and a registry that is down starts the application on the
		 * copy. The default is schema, inside a folder named gatool- and the account the
		 * JVM runs as, under the JVM's temporary directory, so the copy lives as long as
		 * that directory does. GATool makes the default folder for its owner alone and
		 * uses it only while that account owns it and group and others cannot write to
		 * it. A team that wants the copy to survive, or to live on a volume of its own,
		 * sets this property, and GATool uses that directory as it is. Blank fetches at
		 * every startup.
		 */
		private String cacheDirectory = GAToolProperties.underTemporaryDirectory("schema");

		/**
		 * Largest schema read from a URL. A public schema reaches a few megabytes as SDL,
		 * and this bound is the schema's own, so a schema larger than
		 * {@code gatool.api.max-response-size} is fetched while that cap stays where it
		 * is. A schema above this size stops startup.
		 */
		private DataSize maxSize = DataSize.ofMegabytes(10);

		/**
		 * Returns the location of the schema definition language file of the API: a
		 * classpath: or file: resource, an https:// URL such as a schema registry's, or a
		 * protocol a Spring Cloud module registers, such as s3://.
		 * @return the value of {@code gatool.api.schema.location}
		 */
		public @Nullable Resource getLocation() {
			return this.location;
		}

		/**
		 * Sets the location of the schema definition language file of the API: a
		 * classpath: or file: resource, an https:// URL such as a schema registry's, or a
		 * protocol a Spring Cloud module registers, such as s3://.
		 * @param location the value of {@code gatool.api.schema.location}
		 */
		public void setLocation(@Nullable Resource location) {
			this.location = location;
		}

		/**
		 * Returns the name of the header that carries the registry key when the location
		 * is an http or https URL, such as X-Hive-CDN-Key.
		 * @return the value of {@code gatool.api.schema.header-name}
		 */
		public @Nullable String getHeaderName() {
			return this.headerName;
		}

		/**
		 * Sets the name of the header that carries the registry key when the location is
		 * an http or https URL, such as X-Hive-CDN-Key.
		 * @param headerName the value of {@code gatool.api.schema.header-name}
		 */
		public void setHeaderName(@Nullable String headerName) {
			this.headerName = headerName;
		}

		/**
		 * Returns the value of that header.
		 * @return the value of {@code gatool.api.schema.header-value}
		 */
		public @Nullable String getHeaderValue() {
			return this.headerValue;
		}

		/**
		 * Sets the value of that header.
		 * @param headerValue the value of {@code gatool.api.schema.header-value}
		 */
		public void setHeaderValue(@Nullable String headerValue) {
			this.headerValue = headerValue;
		}

		/**
		 * Returns where a schema fetched from a URL is cached with its ETag, so the next
		 * startup sends If-None-Match and a registry that is down starts the application
		 * on the copy.
		 * @return the value of {@code gatool.api.schema.cache-directory}
		 */
		public String getCacheDirectory() {
			return this.cacheDirectory;
		}

		/**
		 * Sets where a schema fetched from a URL is cached with its ETag, so the next
		 * startup sends If-None-Match and a registry that is down starts the application
		 * on the copy.
		 * @param cacheDirectory the value of {@code gatool.api.schema.cache-directory}
		 */
		public void setCacheDirectory(String cacheDirectory) {
			this.cacheDirectory = cacheDirectory;
		}

		/**
		 * Returns the largest schema read from a URL.
		 * @return the value of {@code gatool.api.schema.max-size}
		 */
		public DataSize getMaxSize() {
			return this.maxSize;
		}

		/**
		 * Sets the largest schema read from a URL.
		 * @param maxSize the value of {@code gatool.api.schema.max-size}
		 */
		public void setMaxSize(DataSize maxSize) {
			this.maxSize = maxSize;
		}

	}

}
