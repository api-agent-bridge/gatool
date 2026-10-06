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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import graphql.ErrorClassification;
import graphql.language.SourceLocation;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.support.AbstractGraphQlResponse;

/**
 * A GraphQL response over the map a body parsed into, for an answer Spring for GraphQL's
 * transport left unparsed or could not read.
 *
 * <p>
 * The transport parses a 200, and a 4xx under {@code application/graphql-response+json}.
 * A 2xx other than 200, such as the {@code 294 Partial Success} the GraphQL over HTTP
 * draft asks for when both {@code data} and {@code errors} are present, leaves it as a
 * server error, so {@link RemoteGraphQlExecutor} parses that body itself and wraps it
 * here. The shape mirrors Spring's own response over a map, which is package-private in
 * Spring for GraphQL.
 *
 * <p>
 * Spring's response casts {@code errors} to a list of maps, so an answer it does parse
 * stops there when an API or a gateway in front of it wrote the member in another shape:
 * an entry that is a plain string, or one object where the list belongs. The executor
 * wraps such an answer here as well, and this class reads every shape. The map keeps the
 * member as the API wrote it, which is what the model reads, and {@link #getErrors()} is
 * the view the error flag is read from.
 *
 * @author Željko Kozina
 */
final class MapGraphQlResponse extends AbstractGraphQlResponse {

	private static final String MESSAGE = "message";

	private static final String EXTENSIONS = "extensions";

	private final Map<String, Object> map;

	private final List<ResponseError> errors;

	MapGraphQlResponse(Map<String, Object> map) {
		this.map = map;
		this.errors = errorsOf(map);
	}

	// An entry that is not an object is malformed, and it still counts as an error,
	// because a response whose errors list is not empty is not a clean success. A member
	// that is one object or one string where the list belongs counts as one error for the
	// same reason. A member written as null counts as absent.
	@SuppressWarnings("unchecked")
	private static List<ResponseError> errorsOf(Map<String, Object> map) {
		Object member = map.get("errors");
		if (member == null) {
			return List.of();
		}
		List<?> list = (member instanceof List<?> entries) ? entries : Collections.singletonList(member);
		List<ResponseError> errors = new ArrayList<>(list.size());
		for (Object entry : list) {
			errors.add(new MapResponseError((entry instanceof Map<?, ?> error) ? (Map<String, Object>) error
					: Map.of(MESSAGE, String.valueOf(entry))));
		}
		return List.copyOf(errors);
	}

	/**
	 * Returns whether the {@code errors} member of a parsed body has the shape the
	 * GraphQL specification gives it: absent, or a list of objects whose {@code message},
	 * {@code locations}, {@code path} and {@code extensions} each have the specified
	 * type. A member written as {@code null} counts as absent.
	 * @param map the parsed body
	 * @return whether the {@code errors} member is absent or has the specified shape
	 */
	// This is the shape Spring for GraphQL's own response reads. Its constructor casts
	// the member to a list of maps, and the locations and the path of each entry to
	// lists, and its accessors cast the message and the extensions, so a value of any
	// other type ends in a ClassCastException there.
	static boolean holdsSpecifiedErrors(Map<?, ?> map) {
		Object member = map.get("errors");
		if (member == null) {
			return true;
		}
		if (!(member instanceof List<?> entries)) {
			return false;
		}
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> error) || !isSpecified(error)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isSpecified(Map<?, ?> error) {
		return isAbsentOr(String.class, error.get(MESSAGE)) && isAbsentOr(List.class, error.get("path"))
				&& isAbsentOr(Map.class, error.get(EXTENSIONS)) && holdsSpecifiedLocations(error.get("locations"));
	}

	private static boolean holdsSpecifiedLocations(@Nullable Object member) {
		if (member == null) {
			return true;
		}
		if (!(member instanceof List<?> locations)) {
			return false;
		}
		for (Object entry : locations) {
			if (!(entry instanceof Map<?, ?> location) || !(location.get("line") instanceof Number)
					|| !(location.get("column") instanceof Number)
					|| !isAbsentOr(String.class, location.get("sourceName"))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isAbsentOr(Class<?> type, @Nullable Object value) {
		return value == null || type.isInstance(value);
	}

	@Override
	public boolean isValid() {
		return this.map.get("data") != null;
	}

	// The type parameter is the interface's own, so the unchecked cast comes with it.
	@Override
	@SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
	public <T> @Nullable T getData() {
		return (T) this.map.get("data");
	}

	@Override
	public List<ResponseError> getErrors() {
		return this.errors;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<Object, Object> getExtensions() {
		return (this.map.get(EXTENSIONS) instanceof Map<?, ?> extensions) ? (Map<Object, Object>) extensions : Map.of();
	}

	@Override
	public Map<String, Object> toMap() {
		return this.map;
	}

	@Override
	public String toString() {
		return this.map.toString();
	}

	/**
	 * One entry of the {@code errors} list, read as the specification lays it out: a
	 * message, locations, a path and extensions.
	 */
	private static final class MapResponseError implements ResponseError {

		private final Map<String, Object> error;

		MapResponseError(Map<String, Object> error) {
			this.error = error;
		}

		@Override
		public @Nullable String getMessage() {
			Object message = this.error.get(MESSAGE);
			return (message != null) ? message.toString() : null;
		}

		// The classification travels in the extensions, as graphql-java writes it. A
		// name outside both enums is kept as a classification of its own.
		@Override
		public ErrorClassification getErrorType() {
			String classification = String.valueOf(getExtensions().getOrDefault("classification", ""));
			try {
				return graphql.ErrorType.valueOf(classification);
			}
			catch (IllegalArgumentException ex) {
				try {
					return org.springframework.graphql.execution.ErrorType.valueOf(classification);
				}
				catch (IllegalArgumentException ex2) {
					return ErrorClassification.errorClassification(classification);
				}
			}
		}

		@Override
		public String getPath() {
			StringBuilder path = new StringBuilder();
			for (Object segment : getParsedPath()) {
				if (segment instanceof Integer) {
					path.append('[').append(segment).append(']');
				}
				else {
					path.append(path.isEmpty() ? "" : ".").append(segment);
				}
			}
			return path.toString();
		}

		@Override
		@SuppressWarnings("unchecked")
		public List<Object> getParsedPath() {
			return (this.error.get("path") instanceof List<?> path) ? (List<Object>) path : List.of();
		}

		// A location without a number is malformed, and a zero keeps the error readable
		// where Spring's own reader would stop the whole call.
		@Override
		public List<SourceLocation> getLocations() {
			if (!(this.error.get("locations") instanceof List<?> list)) {
				return List.of();
			}
			List<SourceLocation> locations = new ArrayList<>(list.size());
			for (Object entry : list) {
				if (entry instanceof Map<?, ?> location) {
					Object sourceName = location.get("sourceName");
					locations.add(new SourceLocation(intOf(location.get("line")), intOf(location.get("column")),
							(sourceName != null) ? sourceName.toString() : null));
				}
			}
			return List.copyOf(locations);
		}

		private static int intOf(@Nullable Object value) {
			return (value instanceof Number number) ? number.intValue() : 0;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Map<String, Object> getExtensions() {
			return (this.error.get(EXTENSIONS) instanceof Map<?, ?> extensions) ? (Map<String, Object>) extensions
					: Map.of();
		}

		@Override
		public String toString() {
			return this.error.toString();
		}

	}

}
