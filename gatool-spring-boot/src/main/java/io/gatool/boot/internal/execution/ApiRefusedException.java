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

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

/**
 * The GraphQL API answered, and what came back lacks a GraphQL response.
 *
 * <p>
 * Spring for GraphQL parses a 4xx that arrives as
 * {@code application/graphql-response+json}, so the model reads the API's own errors.
 * Every other failing status leaves the transport as an exception. For a 400, a 401, a
 * 403 or a 404 the call did reach the API, and the next one fails the same way.
 *
 * <p>
 * A successful status arrives here too when the body is not a GraphQL response, in three
 * shapes that {@link Shape} names. A gateway or a sign-in page answering {@code 200
 * text/html} is the case the first shape is for: the call ran, the answer is a page, and
 * repeating the call unchanged gets the same page. An empty body and a body the JSON
 * reader cannot parse are the other two. A JSON object that lacks both {@code data} and
 * {@code errors} takes the third shape as well, because GraphQL limits the top level of a
 * response to {@code data}, {@code errors} and {@code extensions}.
 *
 * <p>
 * This type carries what the API said, so the model reads the status and the body and can
 * correct the call. The executor raises it, because knowing what an HTTP status means
 * belongs to the side that speaks HTTP.
 *
 * @author Željko Kozina
 */
public final class ApiRefusedException extends RuntimeException {

	// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
	// Serializable class for this field.
	private static final long serialVersionUID = 1L;

	/**
	 * The shapes an answer without a GraphQL response takes.
	 */
	public enum Shape {

		/**
		 * A status the API refused with, and the body it sent beside it.
		 */
		REFUSING_STATUS,

		/**
		 * A successful status under a content type the converters cannot read, such as a
		 * sign-in page.
		 */
		UNREADABLE_CONTENT_TYPE,

		/**
		 * A successful status without a body.
		 */
		EMPTY_BODY,

		/**
		 * A successful status whose body the JSON reader could not parse, or whose JSON
		 * object lacks both {@code data} and {@code errors}.
		 */
		UNREADABLE_BODY

	}

	private final int status;

	private final String body;

	private final Shape shape;

	private final @Nullable String detail;

	private final boolean isRetryable;

	/**
	 * Creates the exception for a status whose answer lacks a GraphQL response.
	 * @param status the HTTP status the API answered with
	 * @param body the body it sent, which is empty for an answer without one
	 * @param cause the failure the transport reported
	 */
	public ApiRefusedException(HttpStatusCode status, String body, Throwable cause) {
		this(status, body, null, cause);
	}

	/**
	 * Creates the exception for a status, or for a successful status under a content type
	 * the converters cannot read.
	 * @param status the HTTP status the API answered with
	 * @param body the body it sent, which is empty for an answer without one
	 * @param unreadableContentType the content type no converter could read, or
	 * {@code null} where the status itself is what the API refused with
	 * @param cause the failure the transport reported
	 */
	public ApiRefusedException(HttpStatusCode status, String body, @Nullable String unreadableContentType,
			Throwable cause) {
		this(status, body, (unreadableContentType != null) ? Shape.UNREADABLE_CONTENT_TYPE : Shape.REFUSING_STATUS,
				unreadableContentType, cause);
	}

	private ApiRefusedException(HttpStatusCode status, String body, Shape shape, @Nullable String detail,
			@Nullable Throwable cause) {
		super("The GraphQL API answered " + status.value(), cause);
		this.status = status.value();
		this.body = body;
		this.shape = shape;
		this.detail = detail;
		// A 5xx passes, and so do the three 4xx statuses that invite the same request
		// again. A 408 says the API gave up waiting for the request (RFC 9110, section
		// 15.5.9), a 425 says it would not risk a request that may be a replay (RFC 8470,
		// section 5.2), and a 429 says the caller is over a rate (RFC 6585, section 4).
		// Every other request that the API refused on its own terms fails the same way
		// until the call changes. A body without a GraphQL response fails the same way
		// too, whatever the status, because the next call gets the same body.
		this.isRetryable = (shape == Shape.REFUSING_STATUS)
				&& (status.is5xxServerError() || invitesTheSameRequestAgain(status.value()));
	}

	private static boolean invitesTheSameRequestAgain(int status) {
		return status == 408 || status == 425 || status == 429;
	}

	/**
	 * Creates the exception for a successful status without a body.
	 * @param status the HTTP status the API answered with
	 * @return the exception, which carries the status and an empty body
	 */
	// Without a cause, because the transport does not raise one: it would read the empty
	// body as an empty response map and the runner would write "{}".
	public static ApiRefusedException emptyBody(HttpStatusCode status) {
		return new ApiRefusedException(status, "", Shape.EMPTY_BODY, null, null);
	}

	/**
	 * Creates the exception for a successful status whose body is not a GraphQL response:
	 * the JSON reader could not parse it, or the JSON object lacks both {@code data} and
	 * {@code errors}.
	 * @param status the HTTP status the API answered with
	 * @param reason what the reader said, which names the token it stopped at, or what
	 * the object lacks
	 * @param cause the failure the transport reported, or {@code null} where the body
	 * parsed and its shape is what is wrong
	 * @return the exception, which carries the reason as its detail
	 */
	// The body itself is gone by the time the reader fails, so the reader's own
	// message stands in for it. The message names the token that broke the parse, which
	// is what tells a reader that a proxy answered with text under a JSON content type.
	public static ApiRefusedException unreadableBody(HttpStatusCode status, String reason, @Nullable Throwable cause) {
		return new ApiRefusedException(status, "", Shape.UNREADABLE_BODY, reason, cause);
	}

	/**
	 * Creates the exception for a redirect, which GATool leaves unfollowed on a call.
	 * @param status the 3xx status the API answered with
	 * @param body the body it sent, which is empty for most redirects
	 * @param location the URL the {@code Location} header names, or {@code null} where
	 * that header is absent
	 * @param cause the failure the transport reported
	 * @return the exception, which carries the location as its detail
	 */
	// The location is for the operator's log line, so it stays out of the text the
	// model reads: the model cannot change gatool.api.url, and the operator can.
	public static ApiRefusedException redirect(HttpStatusCode status, String body, @Nullable String location,
			Throwable cause) {
		return new ApiRefusedException(status, body, Shape.REFUSING_STATUS, location, cause);
	}

	/**
	 * Returns the shape of the answer.
	 * @return which of the four answers this is
	 */
	public Shape shape() {
		return this.shape;
	}

	/**
	 * Returns the content type that no converter could read.
	 * @return the content type, or {@code null} for every other shape
	 */
	public @Nullable String unreadableContentType() {
		return (this.shape == Shape.UNREADABLE_CONTENT_TYPE) ? this.detail : null;
	}

	/**
	 * Returns what the JSON reader said about a body it could not parse.
	 * @return the reader's message, or {@code null} for every other shape
	 */
	public @Nullable String unreadableBodyReason() {
		return (this.shape == Shape.UNREADABLE_BODY) ? this.detail : null;
	}

	/**
	 * Returns the URL a redirect names.
	 * @return the {@code Location} the API answered with, or {@code null} for every other
	 * answer, and for a redirect without one
	 */
	public @Nullable String redirectLocation() {
		return isRedirect() ? this.detail : null;
	}

	/**
	 * Tells whether the status is a redirect.
	 *
	 * <p>
	 * GATool's client leaves a redirect unfollowed on a call, because the JDK turns a
	 * redirected POST into a GET without a body on a 301, 302 or 303, and a credential in
	 * a custom header would travel to whatever host the {@code Location} names. The URL
	 * to call is the operator's to set.
	 * @return {@code true} for a 3xx status
	 */
	public boolean isRedirect() {
		return this.shape == Shape.REFUSING_STATUS && this.status >= 300 && this.status <= 399;
	}

	/**
	 * Returns the status.
	 * @return the HTTP status the API answered with
	 */
	public int status() {
		return this.status;
	}

	/**
	 * Returns the body.
	 * @return what the API sent, which is empty for an answer without a body
	 */
	public String body() {
		return this.body;
	}

	/**
	 * Tells whether the same call may succeed later.
	 * @return {@code true} for a server error, and for 408, 425 and 429, which invite the
	 * same request again
	 */
	public boolean isRetryable() {
		return this.isRetryable;
	}

	/**
	 * Tells whether the status refuses the credential GATool sends.
	 *
	 * <p>
	 * A 401 says the credential is missing or invalid, a 403 says it lacks the access,
	 * and a 407 says a proxy on the way wants one. The arguments of the call are not the
	 * cause of any of the three, so the model reads that, and the operator reads a
	 * warning, because a credential is theirs to fix.
	 * @return {@code true} for 401, 403 and 407
	 */
	public boolean isRefusedCredential() {
		return this.shape == Shape.REFUSING_STATUS && (this.status == 401 || this.status == 403 || this.status == 407);
	}

	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage(), "the constructor requires a message");
	}

}
