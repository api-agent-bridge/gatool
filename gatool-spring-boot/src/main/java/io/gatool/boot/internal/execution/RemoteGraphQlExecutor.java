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

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.MediaTypes;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.GraphQlExecutionRequest;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.internal.CauseChain;
import io.gatool.boot.internal.credentials.CredentialUnavailableException;

/**
 * Sends each document to the GraphQL API with Spring for GraphQL's own client.
 *
 * <p>
 * The client is built from Spring Boot's auto-configured {@link RestClient.Builder}, so
 * Boot's observations and HTTP client settings apply to every call. A {@code RestClient}
 * called directly would put a REST client on a GraphQL endpoint while Spring for GraphQL
 * ships a client for it.
 *
 * <p>
 * The deadline arrives on the builder: {@code GAToolAutoConfiguration} gives it a request
 * factory built from the application's own {@code HttpClientSettings}, which carry
 * {@code spring.http.clients.connect-timeout} and
 * {@code spring.http.clients.read-timeout} along with the SSL bundle and the cookie
 * policy. Spring Boot leaves both deadlines unset, so GATool fills whichever of the two
 * the application left unset, 3 seconds to connect and 15 for an answer, and startup
 * names the applied values at INFO. With the JDK client the read deadline bounds the
 * whole answer, headers and body together; Apache and Jetty apply it to each wait for
 * data, so there a body that arrives slowly in many pieces can outlast it.
 * {@code blockingTimeout} looks like the lever for the whole call and applies to
 * resolving the document alone.
 *
 * <p>
 * The transport parses a 200, and a 4xx under {@code application/graphql-response+json}.
 * Every other 2xx under a JSON content type, such as the {@code 294 Partial Success} the
 * GraphQL over HTTP draft asks for when both {@code data} and {@code errors} are present,
 * is parsed here with the same mapper, so the partial result rules and the output schema
 * apply to it as they apply to a 200.
 *
 * <p>
 * An answer whose {@code errors} have another shape than the one the GraphQL
 * specification gives, such as an entry that is a plain string, is read here as well,
 * because the transport's own response stops on it. The model reads the member as the API
 * wrote it.
 *
 * @author Željko Kozina
 */
public final class RemoteGraphQlExecutor implements GraphQlExecutor {

	// By name, because Spring Security is optional in this module, and an
	// application's own strategy built on Spring Security's interceptor raises it
	// where GATool's own strategy already turns it into a credential failure.
	private static final String OAUTH2_AUTHORIZATION_EXCEPTION = "org.springframework.security.oauth2.core.OAuth2AuthorizationException";

	// What names the credential strategy where the application configured none.
	private static final String UNSET = "unset";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final HttpSyncGraphQlClient client;

	private final JsonMapper jsonMapper;

	private final @Nullable ClientHttpRequestInterceptor credential;

	// The URL a relative Location is resolved against, for the operator's log line.
	private final URI url;

	/**
	 * Creates the executor with a mapper of its own for the bodies it parses itself.
	 * @param restClientBuilder boot's auto-configured builder
	 * @param url the value of {@code gatool.api.url}
	 * @param credential the strategy that authenticates each call, or {@code null} where
	 * the API takes calls without one
	 */
	public RemoteGraphQlExecutor(RestClient.Builder restClientBuilder, String url,
			@Nullable ClientHttpRequestInterceptor credential) {
		this(restClientBuilder, url, credential, JsonMapper.builder().build());
	}

	/**
	 * Creates the executor.
	 *
	 * <p>
	 * The builder is cloned before the credential is added, so the header reaches
	 * GATool's own client and leaves every other HTTP client in the application as it
	 * was, whether the builder is Boot's prototype or an application's own singleton.
	 * @param restClientBuilder boot's auto-configured builder
	 * @param url the value of {@code gatool.api.url}
	 * @param credential the strategy that authenticates each call, or {@code null} where
	 * the API takes calls without one
	 * @param jsonMapper the mapper for a 2xx body the transport left unparsed, which is
	 * the null-keeping mapper the builder's converter writes with
	 */
	public RemoteGraphQlExecutor(RestClient.Builder restClientBuilder, String url,
			@Nullable ClientHttpRequestInterceptor credential, JsonMapper jsonMapper) {
		RestClient.Builder builder = restClientBuilder.clone();
		if (credential != null) {
			builder = builder.requestInterceptor(credential);
		}
		// Added after the credential, so the peek reads the response that the
		// credential's own execution returned.
		builder = builder.requestInterceptor(new EmptyBodyRefusal());
		// The list arrives here with the converter the auto-configuration put in it, so
		// the replacement reads with that converter's mapper and its media types.
		builder = builder.configureMessageConverters((converters) -> converters.configureMessageConvertersList(
				(list) -> list.replaceAll((converter) -> (converter instanceof JacksonJsonHttpMessageConverter json)
						? new ErrorsOfAnyShapeConverter(json) : converter)));
		this.client = HttpSyncGraphQlClient.builder(builder).url(url).build();
		this.jsonMapper = jsonMapper;
		this.credential = credential;
		this.url = URI.create(url);
	}

	@Override
	public GraphQlResponse execute(GraphQlExecutionRequest request) {
		try {
			GraphQlResponse response = this.client.document(request.document())
				// An empty name is an anonymous operation, and the GraphQL over HTTP
				// specification treats an empty string like an absent parameter, where
				// a server that reads it literally answers "Unknown operation named".
				.operationName(request.operationName().isEmpty() ? null : request.operationName())
				.variables(request.variables())
				.executeSync();
			return requireGraphQlResponse(response, HttpStatus.OK);
		}
		catch (ErrorsOfAnotherShapeException ex) {
			// The API answered with a GraphQL response, and the transport's own response
			// cannot read its errors, so the map the converter parsed is wrapped in the
			// response that reads every shape. The map holds errors, which is what
			// requireGraphQlResponse asks of it.
			return new MapGraphQlResponse(ex.response());
		}
		catch (RestClientResponseException ex) {
			return answerFor(ex);
		}
		catch (UnknownContentTypeException ex) {
			// A successful status whose body no converter can read. This type extends
			// RestClientException beside RestClientResponseException instead of under it,
			// so the catch above does not see it. An authenticating proxy answering 200
			// text/html is the case in the field: the call did run, the answer does not
			// carry any GraphQL, and the API's own page is what tells a reader why.
			throw new ApiRefusedException(ex.getStatusCode(), ex.getResponseBodyAsString(),
					String.valueOf(ex.getContentType()), ex);
		}
		catch (RestClientException ex) {
			throw refusalWhereTheBodyWasUnreadable(ex);
		}
		catch (RuntimeException ex) {
			throw credentialFailureWhereTheServerRefused(ex);
		}
	}

	// Returns the response for a 2xx the transport left unparsed, and raises the refusal
	// for every other status the transport stopped on.
	//
	// Spring for GraphQL parses a 4xx that carries
	// application/graphql-response+json, so a GraphQL error already reaches the
	// model. Everything else arrives here, and what the API said is the best
	// information the model has for correcting the call.
	private GraphQlResponse answerFor(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
		if (status.is2xxSuccessful()) {
			return readSuccessfulAnswer(status, body, ex);
		}
		if (status.is3xxRedirection()) {
			throw ApiRefusedException.redirect(status, body, locationOf(ex.getResponseHeaders()), ex);
		}
		throw new ApiRefusedException(status, body, ex);
	}

	// Returns the refusal for a successful status whose body the reader could not parse,
	// and the exception itself for every other failure, which the caller throws on to the
	// runner.
	//
	// A successful status under a JSON content type whose body the reader cannot parse.
	// RestClient wraps the reader's HttpMessageNotReadableException in a plain
	// RestClientException, the same base type a transport failure leaves as
	// ResourceAccessException. The call ran, and the same call gets the same body, so it
	// is a refusal. Every other RestClientException passes through to the runner's
	// transport sentences, and so does a parse the size cap stopped. The counting
	// stream's refusal arrives inside the reader's failure, and the runner walks the
	// chain for it and answers with the cap's own sentence. A body the reader could not
	// finish because the connection dropped or the read timed out arrives the same way,
	// with the I/O failure as a cause, and passes through as well: the runner reads that
	// cause, and tells a query whether a later call may succeed and a mutation that it
	// may have been applied. One shape stays a refusal: a body the server delimits by
	// closing the connection, without a length or chunking, and cuts short reaches the
	// reader as a clean end of input, so the chain carries a parse failure alone.
	// HTTP/1.1 servers send a length or chunk, so it is left as it is.
	private static RuntimeException refusalWhereTheBodyWasUnreadable(RestClientException ex) {
		if (ex.getCause() instanceof HttpMessageNotReadableException unreadable && !carriesResponseTooLarge(ex)
				&& !carriesTransportFailure(unreadable)) {
			return ApiRefusedException.unreadableBody(statusOf(unreadable), String.valueOf(unreadable.getMessage()),
					ex);
		}
		return ex;
	}

	// Returns the credential failure for an OAuth2 exception an application's own
	// strategy left in the chain, and the exception itself for every other failure, which
	// the caller throws on.
	//
	// An application's own strategy built on Spring Security's interceptor leaves
	// it as the OAuth2 exception, which is the credential failing, and the runner
	// answers a credential failure without inviting a retry.
	private RuntimeException credentialFailureWhereTheServerRefused(RuntimeException ex) {
		if (isOAuth2AuthorizationFailure(ex)) {
			String strategy = (this.credential != null) ? this.credential.getClass().getName() : UNSET;
			return new CredentialUnavailableException(strategy, "The ApiCredentialStrategy bean " + strategy
					+ " asked the authorization server for a token, and the server refused: " + ex.getMessage() + ".",
					ex);
		}
		return ex;
	}

	// Returns the response for a 2xx the transport left unparsed, or raises the refusal a
	// 2xx without a GraphQL response gets.
	//
	// A content type outside JSON takes the same shape a 200 text/html takes, because
	// the answer is the same: a page in front of the API. A JSON body is parsed with
	// the mapper the client writes with, so the map holds what the API sent, nulls
	// included.
	private GraphQlResponse readSuccessfulAnswer(HttpStatusCode status, String body,
			RestClientResponseException cause) {
		String contentType = contentTypeOf(cause.getResponseHeaders());
		if (!isJson(contentType)) {
			throw new ApiRefusedException(status, body, contentType, cause);
		}
		Map<String, Object> map;
		try {
			map = this.jsonMapper.readValue(body, MAP_TYPE);
		}
		catch (JacksonException ex) {
			throw ApiRefusedException.unreadableBody(status, String.valueOf(ex.getOriginalMessage()), ex);
		}
		if (map == null) {
			throw ApiRefusedException.unreadableBody(status, "the body is the JSON value null", cause);
		}
		return requireGraphQlResponse(new MapGraphQlResponse(map), status);
	}

	// A relative Location is resolved against the API URL, so the log line names the
	// URL to set. A Location that cannot be read as a URL is left out of the line.
	private @Nullable String locationOf(@Nullable HttpHeaders headers) {
		if (headers == null) {
			return null;
		}
		try {
			URI location = headers.getLocation();
			return (location != null) ? this.url.resolve(location).toString() : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	// The header is read as text where it does not parse, because a malformed content
	// type is still what tells a reader what answered.
	private static String contentTypeOf(@Nullable HttpHeaders headers) {
		if (headers == null) {
			return UNSET;
		}
		try {
			MediaType contentType = headers.getContentType();
			return (contentType != null) ? contentType.toString() : UNSET;
		}
		catch (InvalidMediaTypeException ex) {
			return String.valueOf(headers.getFirst(HttpHeaders.CONTENT_TYPE));
		}
	}

	private static boolean isJson(String contentType) {
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
					|| MediaTypes.APPLICATION_GRAPHQL_RESPONSE.isCompatibleWith(mediaType);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	// Returns the response where its map is a GraphQL response, and raises the refusal a
	// JSON object without data and errors gets.
	//
	// GraphQL limits the top level of a response to data, errors and extensions. A JSON
	// object without the first two is a REST endpoint or a proxy answering under the
	// API's content type.
	private static GraphQlResponse requireGraphQlResponse(GraphQlResponse response, HttpStatusCode status) {
		Map<String, Object> map = response.toMap();
		if (!map.containsKey("data") && !map.containsKey("errors")) {
			throw ApiRefusedException.unreadableBody(status, "the JSON object lacks both data and errors", null);
		}
		return response;
	}

	private static boolean carriesResponseTooLarge(Throwable failure) {
		return CauseChain.first(failure, ResponseTooLargeException.class) != null;
	}

	// The reader wraps the stream's failure in its own, so a connection that dropped
	// or a read that timed out mid-body arrives as an unreadable body with the
	// IOException, or the JDK client's TimeoutException, further down the chain. A
	// body that arrived whole and is malformed carries the parser's failure alone.
	private static boolean carriesTransportFailure(Throwable failure) {
		return CauseChain.any(failure, (cause) -> cause instanceof IOException || cause instanceof TimeoutException);
	}

	// Each exception in the chain is matched by the names of its class and its
	// superclasses, so ClientAuthorizationException matches through the type it extends.
	private static boolean isOAuth2AuthorizationFailure(Throwable failure) {
		return CauseChain.any(failure, (cause) -> {
			for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
				if (OAUTH2_AUTHORIZATION_EXCEPTION.equals(type.getName())) {
					return true;
				}
			}
			return false;
		});
	}

	// Returns the status of the response whose body the reader could not parse.
	//
	// The converter reads the body through RestClient's own response wrapper, which is
	// the input message the exception carries, and the wrapper answers its status after
	// the body is closed. The fallback is the status the transport hands a body to the
	// reader on in every case but a 4xx that declares a GraphQL response.
	private static HttpStatusCode statusOf(HttpMessageNotReadableException unreadable) {
		try {
			if (unreadable.getHttpInputMessage() instanceof ClientHttpResponse response) {
				return response.getStatusCode();
			}
		}
		catch (IOException | IllegalStateException ex) {
			// The accessor asserts that a message was attached, and the status read
			// declares an IOException; both leave the fallback in place.
		}
		return HttpStatus.OK;
	}

	/**
	 * Reads a body as the JSON converter it replaces does, and hands a GraphQL response
	 * whose {@code errors} have another shape than the specified one to {@link #execute}.
	 */
	// Spring for GraphQL's response casts errors to a list of maps in its constructor. A
	// gateway that answers "errors": ["Rate limit exceeded"], or writes one object where
	// the list belongs, ends that constructor in a ClassCastException, and a null entry
	// ends it in an IllegalArgumentException. The runner would read either as a call that
	// failed in transport, and what the API said would be lost with the parsed body.
	//
	// The converter is the one place that holds the parsed body before that constructor
	// runs. It raises an exception carrying the map, and execute() wraps the map in
	// MapGraphQlResponse, the way it wraps a 2xx the transport left unparsed. A transport
	// of GATool's own would read every answer itself, and take over the status rules and
	// the converter setup that Spring for GraphQL's client builder keeps to itself.
	// Rewriting the member into a list of objects would let Spring's response read it,
	// and the model would then read errors the API did not write. Catching the
	// ClassCastException comes after the body is consumed, so the answer would carry a
	// sentence of GATool's and leave out what the API said.
	//
	// The exception leaves RestClient.exchange(), so the observation Spring Boot keeps
	// of the HTTP call records it as that call's error, as it records the exception the
	// transport raises for a 294.
	private static final class ErrorsOfAnyShapeConverter extends JacksonJsonHttpMessageConverter {

		ErrorsOfAnyShapeConverter(JacksonJsonHttpMessageConverter replaced) {
			super(replaced.getMapper());
			setSupportedMediaTypes(replaced.getSupportedMediaTypes());
		}

		// The check runs on the body of an HTTP response alone. Spring for GraphQL's
		// client decodes a field of a response through this converter as well, and a
		// field named errors inside data is the API's own data.
		@Override
		@SuppressWarnings("unchecked")
		public Object read(ResolvableType type, HttpInputMessage inputMessage, @Nullable Map<String, Object> hints)
				throws IOException {
			Object body = super.read(type, inputMessage, hints);
			if (inputMessage instanceof ClientHttpResponse && body instanceof Map<?, ?> map
					&& !MapGraphQlResponse.holdsSpecifiedErrors(map)) {
				throw new ErrorsOfAnotherShapeException((Map<String, Object>) map);
			}
			return body;
		}

	}

	/**
	 * Carries a parsed GraphQL response from the converter to {@link #execute}, past the
	 * transport's own response, which cannot read its {@code errors}.
	 */
	private static final class ErrorsOfAnotherShapeException extends RuntimeException {

		// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
		// Serializable class for this field.
		private static final long serialVersionUID = 1L;

		// Transient, because the map holds whatever the API sent, and the exception is
		// caught in the method that started the call.
		private final transient Map<String, Object> response;

		// The stack trace is left out, because the exception carries a value to a catch
		// inside this class and its trace does not reach a log.
		ErrorsOfAnotherShapeException(Map<String, Object> response) {
			super("The GraphQL API answered with errors in another shape than the GraphQL specification gives", null,
					false, false);
			this.response = response;
		}

		Map<String, Object> response() {
			return this.response;
		}

	}

	/**
	 * Turns a successful response without a body into a refusal that names the status.
	 */
	// A 2xx without a body reaches the transport as a null body, which it maps to an
	// empty response map, and the runner would then write "{}" as the tool error, without
	// the status and without a sentence. The status is known here alone, so the check
	// runs here: the first byte is read and pushed back, which is what RestClient's own
	// introspecting wrapper does to tell an empty body apart. A response with a status
	// outside 2xx passes through, because the transport reads its body as text for the
	// refusal the runner describes.
	private static final class EmptyBodyRefusal implements ClientHttpRequestInterceptor {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			ClientHttpResponse response = execution.execute(request, body);
			if (!response.getStatusCode().is2xxSuccessful()) {
				return response;
			}
			PushbackInputStream peeked = new PushbackInputStream(response.getBody(), 1);
			int first = peeked.read();
			if (first == -1) {
				response.close();
				throw ApiRefusedException.emptyBody(response.getStatusCode());
			}
			peeked.unread(first);
			return new PeekedResponse(response, peeked);
		}

	}

	/**
	 * A response whose body is the stream the peek pushed the first byte back into.
	 */
	// A record, because every method but getBody() delegates and the two components
	// are what the delegation needs.
	private record PeekedResponse(ClientHttpResponse response, InputStream body) implements ClientHttpResponse {

		@Override
		public InputStream getBody() {
			return this.body;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return this.response.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return this.response.getStatusText();
		}

		@Override
		public void close() {
			this.response.close();
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.response.getHeaders();
		}
	}

}
