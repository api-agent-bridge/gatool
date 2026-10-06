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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import graphql.schema.GraphQLSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.util.unit.DataSize;

import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.boot.internal.schema.SchemaUrlReader;
import io.gatool.core.internal.schema.SdlSchemaFactory;

/**
 * Reads the schema every operation file is validated against: from the SDL file, from a
 * URL, or from the application's own {@link GraphQlSource}.
 *
 * <p>
 * The methods log under the name of {@link GAToolAutoConfiguration}, so a logging level
 * an operator set for it covers these lines.
 *
 * @author Željko Kozina
 */
final class SchemaLoading {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	private SchemaLoading() {
	}

	/**
	 * Returns the schema that every operation file is validated against, read from the
	 * SDL file or from the application's own {@link GraphQlSource}.
	 * @param properties the GATool properties, which name the SDL file or the URL
	 * @param graphQlSource the application's own GraphQL source, where it serves one
	 * @param httpClients the clients GATool built, which fetch a schema from a URL
	 * @return the schema, with the pending cache write where a URL supplied it
	 */
	// The schema arrives from the SDL file, from a URL, or from the running application,
	// with its applied directives. The file wins, so an application that serves one
	// GraphQL API and exposes another reads the one it was pointed at.
	static LoadedSchema readSchema(GAToolProperties properties, ObjectProvider<GraphQlSource> graphQlSource,
			ObjectProvider<RemoteHttpClients> httpClients) {
		GAToolApiProperties.Schema schemaProperties = properties.getApi().getSchema();
		Resource location = schemaProperties.getLocation();
		String schemaUrl = (location != null) ? httpUrlOf(location) : null;
		requireCompleteSchemaHeader(schemaProperties, schemaUrl);
		if (schemaUrl != null) {
			// A registry serves the SDL with a key in a header, which Spring's
			// UrlResource cannot send, so an http or https location is fetched by
			// GATool's own reader. The URL is printed without its userinfo and its query,
			// because a registry key can travel in either, and Boot's failure analysis
			// prints the value it is handed.
			String printableUrl = SchemaUrlReader.printable(schemaUrl);
			logger.info("GATool validates every operation file against the schema at " + printableUrl + ".");
			SchemaUrlReader reader = buildSchemaUrlReader(properties, httpClients, printableUrl);
			try {
				SchemaUrlReader.FetchedSchema fetched = reader.read(schemaUrl, schemaProperties.getHeaderName(),
						schemaProperties.getHeaderValue());
				return new LoadedSchema(SdlSchemaFactory.schemaFrom(fetched.text(), printableUrl), fetched);
			}
			catch (SchemaUrlReader.SchemaFetchException ex) {
				throw new InvalidConfigurationPropertyValueException("gatool.api.schema.location", printableUrl,
						ex.getMessage(), ex.getCause());
			}
		}
		if (location != null) {
			// The schema every operation validated against is the tool contract, so
			// startup names it. Without this line, the mismatch below is invisible.
			logger.info("GATool validates every operation file against " + location.getDescription() + ".");
			return LoadedSchema.of(SdlSchemaFactory.schemaFrom(readSdl(location), location.getDescription()));
		}
		GraphQlSource source = graphQlSource.getIfAvailable();
		if (source != null) {
			// An application that serves GraphQL and bridges a second API is an ordinary
			// gateway. Falling back to the local schema there would validate every
			// operation against the wrong API: the tools would be published with full
			// input schemas, and every call would fail at runtime.
			String url = properties.getApi().getUrl();
			if (url != null) {
				throw new InvalidConfigurationPropertyValueException("gatool.api.schema.location", null,
						"GATool sends every document to " + SchemaUrlReader.printable(url)
								+ " and this application serves a GraphQL schema of "
								+ "its own, so GATool cannot tell which one the operation files describe. Set this "
								+ "property to the schema of the API at that URL, which is the contract the tools "
								+ "carry.");
			}
			logger.info("GATool validates every operation file against the schema this application serves.");
			return LoadedSchema.of(source.schema());
		}
		throw new InvalidConfigurationPropertyValueException("gatool.api.schema.location", null,
				"GATool validates every operation file against the schema of the API. Set this property to the "
						+ "schema definition language file of that API, or add Spring for GraphQL to this "
						+ "application, and GATool reads the schema it serves.");
	}

	// Returns the URL where the location is an http or https one, which GATool fetches
	// itself, or null where Spring's resource loader reads it.
	private static @Nullable String httpUrlOf(Resource location) {
		if (!(location instanceof UrlResource)) {
			return null;
		}
		try {
			String protocol = location.getURL().getProtocol();
			return ("http".equals(protocol) || "https".equals(protocol)) ? location.getURL().toString() : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	// Both halves of the header are needed to send it. A header set beside a location
	// that is read without a request would sit unused, which is worth a line at startup
	// because the team that set it expects it to reach a registry.
	private static void requireCompleteSchemaHeader(GAToolApiProperties.Schema schemaProperties, @Nullable String url) {
		String name = schemaProperties.getHeaderName();
		String value = schemaProperties.getHeaderValue();
		if (name != null && (value == null || value.isBlank())) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.header-value", null,
					"The schema request carries the header " + name + ", so set its value, and keep that value "
							+ "in the environment, out of any file a repository holds.");
		}
		// The JDK refuses a header it cannot send and names the whole value in its
		// message, so both halves are checked here, by the JDK's own rule. The exception
		// for the value names the header name's property, because Boot's failure
		// analysis prints the live value of the property it is given, and the value is
		// a registry key: a secret read from a file often ends in a line break.
		if (name != null && !CredentialChecks.isHeaderName(name)) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.header-name", name,
					"A header name is letters, digits and the characters " + CredentialChecks.TOKEN_PUNCTUATION
							+ ", and this one holds a character a header name cannot carry. Set a name such as "
							+ "X-Hive-CDN-Key.");
		}
		String valueProblem = (value != null) ? CredentialChecks.headerValueProblem(value) : null;
		if (valueProblem != null) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.header-name", name,
					"The value of gatool.api.schema.header-value " + valueProblem + ". A secret read from a file "
							+ "often ends in a line break; strip it.");
		}
		if (value != null && name == null) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.header-name", null,
					"The schema request carries a header value, so set the name of that header, such as "
							+ "X-Hive-CDN-Key.");
		}
		if (name != null && url == null) {
			logger.warn("gatool.api.schema.header-name is set, and gatool.api.schema.location is read without a "
					+ "request, so the header goes unsent. A header reaches a schema served at an http or https "
					+ "URL.");
		}
	}

	// Returns the reader that fetches a schema URL with the application's own HTTP client
	// settings and caches the copy under gatool.api.schema.cache-directory.
	//
	// The client is built from the application's settings for the reason the executor's
	// is: the SSL bundle, the deadlines and the address filter reach this request the
	// way they reach every other client in the application. gatool.api.schema.max-size
	// bounds the body, so a schema of a few megabytes is fetched while the response cap
	// of a tool call stays where the deployment set it. Startup reports the deadlines
	// for this request as it does for the API, because a registry that stops answering
	// holds startup until the socket closes.
	private static SchemaUrlReader buildSchemaUrlReader(GAToolProperties properties,
			ObjectProvider<RemoteHttpClients> httpClients, String printableUrl) {
		RemoteHttpClients clients = RemoteApiChecks.requireHttpClients(httpClients, "gatool.api.schema.location",
				printableUrl);
		StartupLines.reportDeadlines("fetches the schema at " + printableUrl, clients);
		DataSize maxSize = requirePositiveSchemaSize(properties.getApi().getSchema().getMaxSize());
		Path cacheDirectory = CacheDirectories.resolve(properties.getApi().getSchema().getCacheDirectory(),
				GAToolProperties.underTemporaryDirectory("schema"), "gatool.api.schema.cache-directory",
				"the schema is fetched at every startup, and a startup while the registry is down stops");
		return new SchemaUrlReader(clients.schemaClient(maxSize), maxSize, cacheDirectory);
	}

	// Returns the largest schema GATool fetches from a URL, and fails startup where that
	// size is zero or less.
	//
	// The schema has a bound of its own, because a public schema reaches a few megabytes
	// as SDL while a tool call's response cap is a memory setting of the deployment.
	private static DataSize requirePositiveSchemaSize(DataSize maxSize) {
		if (maxSize.toBytes() <= 0) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.max-size", maxSize,
					"GATool reads the schema at startup, so the largest one it accepts is a positive size, "
							+ "such as 10MB.");
		}
		return maxSize;
	}

	private static String readSdl(Resource location) {
		try {
			return location.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new InvalidConfigurationPropertyValueException("gatool.api.schema.location",
					location.getDescription(), "The schema file cannot be read: " + ex.getMessage());
		}
	}

	/**
	 * The schema every operation file validates against, and the cache write a fetched
	 * one holds back until validation passed.
	 *
	 * @param schema the schema
	 * @param fetched the fetch the schema came from, or {@code null} where it came from a
	 * file or from the application itself
	 */
	record LoadedSchema(GraphQLSchema schema, SchemaUrlReader.@Nullable FetchedSchema fetched) {

		static LoadedSchema of(GraphQLSchema schema) {
			return new LoadedSchema(schema, null);
		}

		void commit() {
			if (this.fetched != null) {
				this.fetched.commit();
			}
		}
	}

}
