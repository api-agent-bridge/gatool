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
import java.time.Duration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.internal.execution.BoundedResponseRequestFactory;
import io.gatool.boot.internal.execution.ExplicitNulls;
import io.gatool.boot.internal.execution.RemoteGraphQlExecutor;
import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.boot.internal.schema.SchemaUrlReader;

/**
 * Checks the settings of remote mode and builds the pieces of its client: the URL, the
 * HTTP clients, the response cap and the mapper that keeps an explicit null.
 *
 * <p>
 * The methods log under the name of {@link GAToolAutoConfiguration}, so a logging level
 * an operator set for it covers these lines.
 *
 * @author Željko Kozina
 */
final class RemoteApiChecks {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	private RemoteApiChecks() {
	}

	/**
	 * Returns the read deadline of the client behind the executor, or {@code null} where
	 * GATool did not build that client.
	 * @param executor the executor that sends the documents
	 * @param httpClients the clients GATool built, where the remote configuration is
	 * active
	 * @return the read deadline, or {@code null} where GATool did not build the
	 * executor's client
	 */
	// The deadline is GATool's to name for the executor it built on RemoteHttpClients.
	// An application's own GraphQlExecutor bean sets a deadline of its own, and an
	// embedded call runs without one, so both leave the sentence without a number.
	static @Nullable Duration readTimeoutBehind(GraphQlExecutor executor,
			ObjectProvider<RemoteHttpClients> httpClients) {
		if (!(executor instanceof RemoteGraphQlExecutor)) {
			return null;
		}
		RemoteHttpClients clients = httpClients.getIfAvailable();
		return (clients != null) ? clients.appliedReadTimeout() : null;
	}

	/**
	 * Returns the clients, and stops startup naming the property that needs them where
	 * the nested configuration backed off.
	 * @param httpClients the provider of the clients GATool built
	 * @param property the property whose value needs the clients
	 * @param value the value of that property
	 * @return the clients
	 */
	static RemoteHttpClients requireHttpClients(ObjectProvider<RemoteHttpClients> httpClients, String property,
			String value) {
		RemoteHttpClients clients = httpClients.getIfAvailable();
		if (clients == null) {
			throw new InvalidConfigurationPropertyValueException(property, value,
					"GATool sends this request through an HTTP client built from Spring Boot's HttpClientSettings, "
							+ "and org.springframework.boot:spring-boot-http-client is missing from the classpath. "
							+ "Both starters bring it; a project that depends on gatool-spring-boot directly adds "
							+ "org.springframework.boot:spring-boot-restclient.");
		}
		return clients;
	}

	/**
	 * Fails startup when {@code gatool.api.url} is not an absolute http or https URL.
	 *
	 * <p>
	 * A value such as {@code movies.example.com/graphql} reads like a URL and is not one.
	 * Unchecked, the application would start, publish its tools, and fail on every call,
	 * where the model reads a transport failure and cannot act on it. The same shape of
	 * check guards {@code gatool.mcp.security.resource}.
	 * @param url the configured value
	 */
	static void requireAbsoluteApiUrl(String url) {
		String reason = null;
		try {
			URI uri = new URI(url.trim());
			if (!uri.isAbsolute() || uri.getHost() == null
					|| (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) {
				reason = "it has to be an absolute http or https URL, such as https://movies.example.com/graphql";
			}
		}
		catch (URISyntaxException ex) {
			reason = "it cannot be read as a URL: " + ex.getReason();
		}
		if (reason != null) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.url", SchemaUrlReader.printable(url),
					"GATool sends every tool call to this URL, and " + reason + ". Leave the property unset to run "
							+ "each document inside this application through Spring for GraphQL.");
		}
	}

	/**
	 * Returns the copy of the application's mapper that keeps an explicit null in the
	 * variables GATool sends, and reads the bodies the executor parses itself.
	 * @param mapper the application's mapper
	 * @return the copy that keeps an explicit null
	 */
	// Startup names the divergence when an application's Jackson configuration would
	// have dropped the null, because that setting is about the objects the application
	// serialises while a GraphQL variable carries what the model asked for.
	static JsonMapper nullKeepingMapper(JsonMapper mapper) {
		if (ExplicitNulls.dropsNulls(mapper)) {
			logger.info("GATool writes the variables it sends with a mapper of its own, because this application's "
					+ "Jackson configuration leaves a null out, usually through "
					+ "spring.jackson.default-property-inclusion. A variable set to null clears a value, where a "
					+ "variable GATool leaves out applies the default the operation declares. Every other HTTP client "
					+ "in this application keeps the mapper it had.");
		}
		return ExplicitNulls.mapperKeepingNulls(mapper);
	}

	/**
	 * Returns the builder with a JSON converter that writes with the null-keeping mapper.
	 * @param builder the builder of GATool's client
	 * @param nullKeepingMapper the mapper that keeps an explicit null
	 * @return the builder with the converter installed
	 */
	// The converter applies to GATool's own client alone, so every other HTTP client in
	// the application writes with the mapper it had.
	static RestClient.Builder withNullKeepingConverter(RestClient.Builder builder, JsonMapper nullKeepingMapper) {
		return builder.configureMessageConverters((converters) -> converters.configureMessageConvertersList((list) -> {
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) instanceof JacksonJsonHttpMessageConverter existing) {
					JacksonJsonHttpMessageConverter replacement = new JacksonJsonHttpMessageConverter(
							nullKeepingMapper);
					// The media types come from the converter being replaced, because
					// Spring
					// for GraphQL reads application/graphql-response+json through it.
					replacement.setSupportedMediaTypes(existing.getSupportedMediaTypes());
					list.set(i, replacement);
				}
			}
		}));
	}

	/**
	 * Returns the largest response GATool accepts, and fails startup where that size is
	 * zero or less.
	 * @param maxResponseSize the configured size
	 * @return the same size, once it is above zero
	 */
	// The size reaches an array index in the end, and a value at or below zero would
	// refuse every response, so both are settled here with the property named.
	static DataSize requirePositiveResponseSize(DataSize maxResponseSize) {
		if (maxResponseSize.toBytes() <= 0) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.max-response-size", maxResponseSize,
					"GATool reads the response of every tool call, so the largest one it accepts is a positive size, "
							+ "such as 1MB.");
		}
		return maxResponseSize;
	}

	/**
	 * Warns where the response cap in bytes is below the result limit in characters, so
	 * the cap refuses a large result before the result limit applies.
	 * @param maxResponseSize the response cap in bytes
	 * @param maxCharacters the result limit in characters
	 */
	// A result of the full result limit needs at least that many bytes from the API,
	// because one character is at least one byte of UTF-8, so the smaller of the two is
	// the one an operator meets. The cap applies to a remote API, and an embedded call
	// runs under the result limit alone, so the line is written for a remote API only.
	static void warnWhenTheResponseCapIsBelowTheResultLimit(DataSize maxResponseSize, int maxCharacters) {
		if (maxResponseSize.toBytes() >= maxCharacters) {
			return;
		}
		logger.warn("gatool.api.max-response-size is " + BoundedResponseRequestFactory.describeLimit(maxResponseSize)
				+ ", and gatool.results.max-characters is " + maxCharacters + ". A result of " + maxCharacters
				+ " characters needs at least that many bytes from the API, so the response cap decides every "
				+ "refusal and the result limit does not apply. Raise gatool.api.max-response-size above "
				+ maxCharacters + " bytes, or lower gatool.results.max-characters.");
	}

}
