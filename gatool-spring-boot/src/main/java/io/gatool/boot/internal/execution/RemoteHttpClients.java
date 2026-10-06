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

package io.gatool.boot.internal.execution;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP clients GATool sends a request from, on the application's own
 * {@link HttpClientSettings} and request factory builder: the client of remote mode, and
 * the client the schema fetch uses.
 *
 * <p>
 * The factory is built from the settings the application configured, so the SSL bundle,
 * the cookie policy, the address filter and both deadlines reach GATool's clients the way
 * they reach every other client in the application. Starting from
 * {@code HttpClientSettings.defaults()} would drop all of that, which would cost an
 * application its mutual TLS on this client alone, without a warning at startup. Two
 * things are GATool's own. Redirects are left unfollowed: on a call, the JDK would turn a
 * redirected POST into a GET without a body on a 301, 302 or 303, and would carry a
 * credential in a custom header to whatever host the {@code Location} names; the schema
 * fetch follows redirects itself under a rule of its own. The body is capped at the size
 * the caller passes, {@code gatool.api.max-response-size} for a tool call and
 * {@code gatool.api.schema.max-size} for the schema fetch, and the cap wraps the body
 * alone.
 *
 * <p>
 * The auto-configuration publishes one of these from a nested configuration that matches
 * while {@code spring-boot-http-client} is on the classpath, so the bean methods that
 * build a client name this class alone and stay loadable when that jar is missing.
 *
 * @author Željko Kozina
 */
public final class RemoteHttpClients {

	/**
	 * The connect deadline GATool applies where the application left it unset.
	 */
	public static final Duration FALLBACK_CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * The read deadline GATool applies where the application left it unset.
	 *
	 * <p>
	 * The MCP Java SDK client and Spring AI's MCP client wait 20 seconds for a tool call
	 * by default, and their clock starts before this one does. Under a deadline of 20
	 * seconds the client gives up first, and the model reads the client's own timeout
	 * message instead of GATool's sentence. After a mutation GATool's sentence is the one
	 * that asks the model to read the current state before it sends the write again. 15
	 * seconds leaves a margin under a client at its default.
	 */
	public static final Duration FALLBACK_READ_TIMEOUT = Duration.ofSeconds(15);

	// Spring Boot leaves both deadlines unset, so a call to an API that stops answering
	// would hold its thread until the socket closes, which can take more than 75 seconds.
	// These two fill that gap alone. Every other field of this instance is null, because
	// HttpClientSettings.defaults() is null throughout, so the merge below leaves the SSL
	// bundle, the cookie policy, the redirect rule and the address filter to the
	// application.
	private static final HttpClientSettings FALLBACK_DEADLINES = HttpClientSettings.defaults()
		.withTimeouts(FALLBACK_CONNECT_TIMEOUT, FALLBACK_READ_TIMEOUT);

	// By name, as TransportFailure reads the JDK client's exceptions: the factory is
	// built on java.net.http, a module of its own, and a comparison of names leaves
	// the class unloaded on a runtime image built without that module.
	private static final String JDK_REQUEST_FACTORY = "org.springframework.http.client.JdkClientHttpRequestFactory";

	private final RestClient.@Nullable Builder bootBuilder;

	private final HttpClientSettings settings;

	private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;

	/**
	 * Creates the clients.
	 * @param bootBuilder spring Boot's auto-configured builder, or {@code null} where the
	 * application lacks one, as a reactive application does
	 * @param settings the settings the application configured under
	 * {@code spring.http.clients}
	 * @param requestFactoryBuilder the application's own request factory builder, or the
	 * one Boot detects for the classpath
	 */
	public RemoteHttpClients(RestClient.@Nullable Builder bootBuilder, HttpClientSettings settings,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder) {
		this.bootBuilder = bootBuilder;
		this.settings = settings;
		this.requestFactoryBuilder = requestFactoryBuilder;
	}

	/**
	 * Returns Boot's builder with GATool's request factory, for the client of remote
	 * mode.
	 *
	 * <p>
	 * Boot's builder is what carries the application's customizers, observations and SSL
	 * to this client. It is cloned, because a builder mutates in place and an
	 * application's own singleton builder would otherwise carry GATool's factory and
	 * converter into every client it builds afterwards.
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 * @return the builder, or {@code null} where Boot's builder bean is absent
	 */
	public RestClient.@Nullable Builder apiClientBuilder(DataSize maxResponseSize) {
		if (this.bootBuilder == null) {
			return null;
		}
		return this.bootBuilder.clone().requestFactory(requestFactory(maxResponseSize));
	}

	/**
	 * Returns the client the schema fetch uses: Boot's builder where the context has one,
	 * so the application's customizers apply, and a plain builder otherwise, both with
	 * GATool's request factory.
	 * @param maxSize the value of {@code gatool.api.schema.max-size}
	 * @return the client
	 */
	public RestClient schemaClient(DataSize maxSize) {
		RestClient.Builder builder = (this.bootBuilder != null) ? this.bootBuilder.clone() : RestClient.builder();
		return builder.requestFactory(requestFactory(maxSize)).build();
	}

	/**
	 * Returns Boot's builder with a request factory under the deadlines of
	 * {@link #deadlinedSettings()}, for the client that asks an issuer for a token.
	 *
	 * <p>
	 * The factory is built from the application's own builder and settings, so the SSL
	 * bundle and the client implementation are the ones every other client of the
	 * application has. The response size cap and the redirect rule of the API client stay
	 * out, because they belong to the answer of a GraphQL API.
	 * @return the builder, or {@code null} where Boot's builder bean is absent
	 */
	// The factory Boot's builder brings is built from the application's settings alone.
	// Boot leaves both deadlines unset, so on that factory an issuer that accepts the
	// request and stays silent would hold the tool call that waits for the token until
	// the socket closes.
	public RestClient.@Nullable Builder tokenEndpointClientBuilder() {
		if (this.bootBuilder == null) {
			return null;
		}
		return this.bootBuilder.clone().requestFactory(this.requestFactoryBuilder.build(deadlinedSettings()));
	}

	/**
	 * Returns the connect deadline the application configured.
	 * @return {@code spring.http.clients.connect-timeout}, or {@code null} where it is
	 * unset
	 */
	public @Nullable Duration connectTimeout() {
		return this.settings.connectTimeout();
	}

	/**
	 * Returns the read deadline the application configured.
	 * @return {@code spring.http.clients.read-timeout}, or {@code null} where it is unset
	 */
	public @Nullable Duration readTimeout() {
		return this.settings.readTimeout();
	}

	/**
	 * Returns the settings this client is built from: the application's own, with
	 * GATool's deadlines filling whichever of the two it left unset.
	 *
	 * <p>
	 * {@link HttpClientSettings#orElse} keeps this instance's value wherever it has one,
	 * so a value on {@code spring.http.clients.connect-timeout} or
	 * {@code spring.http.clients.read-timeout} decides. Spring Boot merges a client's own
	 * settings with the shared ones the same way.
	 * @return the settings, with both deadlines set
	 */
	public HttpClientSettings deadlinedSettings() {
		return this.settings.orElse(FALLBACK_DEADLINES);
	}

	/**
	 * Returns the connect deadline this client applies.
	 * @return {@code spring.http.clients.connect-timeout} where the application set it,
	 * and {@link #FALLBACK_CONNECT_TIMEOUT} where it did not
	 */
	public Duration appliedConnectTimeout() {
		Duration configured = this.settings.connectTimeout();
		return (configured != null) ? configured : FALLBACK_CONNECT_TIMEOUT;
	}

	/**
	 * Returns the read deadline this client applies.
	 * @return {@code spring.http.clients.read-timeout} where the application set it, and
	 * {@link #FALLBACK_READ_TIMEOUT} where it did not
	 */
	public Duration appliedReadTimeout() {
		Duration configured = this.settings.readTimeout();
		return (configured != null) ? configured : FALLBACK_READ_TIMEOUT;
	}

	private ClientHttpRequestFactory requestFactory(DataSize maxSize) {
		ClientHttpRequestFactory factory = buildWithoutRedirects();
		return new BoundedResponseRequestFactory(factory, maxSize,
				boundsTheWholeExchange(factory) ? appliedReadTimeout() : null);
	}

	// A builder made with ClientHttpRequestFactoryBuilder.of(...) for a factory class
	// other than the five Spring Boot has a builder for sets the properties of that class
	// through reflection. It sets the two deadlines and refuses every redirect rule
	// except the default, with "Unable to set redirect follow using reflection", which
	// does not name the builder or a way out. The stop stays, because a factory that
	// follows redirects would carry the credential of a call to whatever host a Location
	// names, and GATool cannot see what such a factory does. Building without the rule
	// and warning would leave that to a line in the log.
	private ClientHttpRequestFactory buildWithoutRedirects() {
		try {
			return this.requestFactoryBuilder.build(deadlinedSettings().withRedirects(HttpRedirects.DONT_FOLLOW));
		}
		catch (IllegalStateException ex) {
			throw new IllegalStateException("GATool asks the application's ClientHttpRequestFactoryBuilder for a "
					+ "request factory that leaves redirects unfollowed, and the builder "
					+ this.requestFactoryBuilder.getClass().getName() + " refused: " + ex.getMessage()
					+ ". A redirect that is followed would carry the credential of a call to the host its "
					+ "Location names. A builder made with ClientHttpRequestFactoryBuilder.of(...) for a factory "
					+ "class of your own sets its properties through reflection and cannot set that rule. Declare the bean with "
					+ "ClientHttpRequestFactoryBuilder.jdk(), httpComponents(), jetty(), reactor() or simple(), "
					+ "or as a lambda that builds the factory itself, applies the timeouts of the settings it "
					+ "receives and leaves redirects unfollowed.", ex);
		}
	}

	// Says whether the client behind a factory applies the read deadline to the whole
	// exchange.
	//
	// Spring's request on the JDK client does: it starts a timer when the request is
	// sent, and closes the response when the timer ends, inside the body as well. It
	// reports a deadline that passed inside the body the way it reports a dropped
	// connection, so the wrapper is given the deadline and names it. The client on
	// HttpURLConnection applies the deadline to each wait for data, as Apache
	// HttpClient and Jetty do, and raises a SocketTimeoutException when one passes. A
	// body that arrives in pieces can outlast the deadline there, so the time an
	// exchange took cannot say what ended it, and the wrapper leaves the failures of
	// those clients as they are. A factory an application wrapped in one of its own
	// is left the same way.
	private static boolean boundsTheWholeExchange(ClientHttpRequestFactory factory) {
		for (Class<?> type = factory.getClass(); type != null; type = type.getSuperclass()) {
			if (JDK_REQUEST_FACTORY.equals(type.getName())) {
				return true;
			}
		}
		return false;
	}

}
