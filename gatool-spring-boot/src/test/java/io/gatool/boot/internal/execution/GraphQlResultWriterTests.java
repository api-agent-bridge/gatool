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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.ResponseField;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQlResultWriterTests {

	private static final String DOCUMENT = "query Movie { movie(by: { id: \"movie-5\" }) { id title rating } }";

	// Boot's mapper and Spring AI's mcpServerJsonMapper both leave nulls out, so the
	// writer's own copy has to put them back.
	private final GraphQlResultWriter writer = new GraphQlResultWriter(JsonMapper.builder()
		.changeDefaultPropertyInclusion(
				(inclusion) -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
		.build());

	@Test
	void write_fieldTheApiReturnedAsNull_shouldKeepTheNullInTheText() {
		Map<String, Object> movie = new LinkedHashMap<>();
		movie.put("id", "movie-5");
		movie.put("title", "Northern Echoes");
		movie.put("rating", null);

		String text = this.writer
			.write(response(ExecutionResultImpl.newExecutionResult().data(Map.of("movie", movie)).build()));

		assertThat(text).contains("\"rating\":null");
	}

	@Test
	void write_applicationMapperThatIndentsOutput_shouldStillWriteCompactText() {
		// gatool.results.max-characters counts characters, and an application with
		// spring.jackson.serialization.indent-output would otherwise spend that limit on
		// line breaks and indentation.
		GraphQlResultWriter indentingWriter = new GraphQlResultWriter(
				JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build());

		String text = indentingWriter.write(response(
				ExecutionResultImpl.newExecutionResult().data(Map.of("movie", Map.of("id", "movie-5"))).build()));

		assertThat(text).isEqualTo("{\"data\":{\"movie\":{\"id\":\"movie-5\"}}}");
	}

	@Test
	void write_responseWithExtensions_shouldLeaveTheExtensionsEntryOut() {
		String text = this.writer.write(response(ExecutionResultImpl.newExecutionResult()
			.data(Map.of("movie", Map.of("id", "movie-5")))
			.extensions(Map.of("cost", 3))
			.build()));

		assertThat(text).contains("\"data\"").doesNotContain("extensions");
	}

	@Test
	void envelope_responseWithAKeyOutsideTheSpecification_shouldKeepDataAndErrorsAlone() {
		// GraphQL limits the top level of a response to data, errors and extensions. A
		// proxy that adds a key of its own would otherwise hand it to the model as part
		// of the result, and to the structured content a tool's output schema describes.
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("data", Map.of("movie", Map.of("id", "movie-5")));
		map.put("errors", List.of());
		map.put("extensions", Map.of("cost", 3));
		map.put("requestId", "r-17");

		Map<String, Object> envelope = this.writer.envelope(responseOf(map));

		assertThat(envelope).containsOnlyKeys("data", "errors");
	}

	// A response whose map is whatever the API sent, which the executor's own type
	// reproduces for a body it parsed itself.
	private static GraphQlResponse responseOf(Map<String, Object> map) {
		return new GraphQlResponse() {

			@Override
			public boolean isValid() {
				return map.get("data") != null;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> T getData() {
				return (T) map.get("data");
			}

			@Override
			public List<ResponseError> getErrors() {
				return List.of();
			}

			@Override
			public ResponseField field(String path) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Map<Object, Object> getExtensions() {
				return Map.of();
			}

			@Override
			public Map<String, Object> toMap() {
				return map;
			}
		};
	}

	@Test
	void readBack_textWithADecimalPastBinary64AndAnIntegerPastALong_shouldKeepEveryDigitAndEveryNull() {
		// The structured content of a tool with an output schema is read back from the
		// text, so it carries what the text carries: a float as a BigDecimal and an
		// integer as a BigInteger, which a mapper writes digit for digit, and a null.
		String text = "{\"data\":{\"price\":{\"value\":1234567890.123456789,"
				+ "\"big\":123456789012345678901234567890,\"note\":null}}}";

		Map<String, Object> envelope = this.writer.readBack(text);

		@SuppressWarnings("unchecked")
		Map<String, Object> price = (Map<String, Object>) ((Map<String, Object>) envelope.get("data")).get("price");
		assertThat(price).containsEntry("value", new BigDecimal("1234567890.123456789"))
			.containsEntry("big", new BigInteger("123456789012345678901234567890"))
			.containsKey("note");
		assertThat(price.get("note")).isNull();
		assertThat(this.writer.write(envelope)).isEqualTo(text);
	}

	@Test
	void holdsErrors_responseWithDataAndErrors_shouldReportTrue() {
		GraphQlResponse response = response(ExecutionResultImpl.newExecutionResult()
			.data(Map.of("movie", Map.of("id", "movie-5")))
			.addError(GraphqlErrorBuilder.newError().message("The community rating service is unavailable.").build())
			.build());

		assertThat(this.writer.holdsErrors(response)).isTrue();
		assertThat(this.writer.write(response)).contains("The community rating service is unavailable.");
	}

	@Test
	void holdsErrors_responseWithoutData_shouldReportTrue() {
		GraphQlResponse response = response(ExecutionResultImpl.newExecutionResult()
			.data(null)
			.addError(GraphqlErrorBuilder.newError().message("Validation error").build())
			.build());

		assertThat(response.isValid()).isFalse();
		assertThat(this.writer.holdsErrors(response)).isTrue();
	}

	@Test
	void holdsErrors_responseWithDataAlone_shouldReportFalse() {
		GraphQlResponse response = response(ExecutionResultImpl.newExecutionResult()
			.data(Map.of("movie", Map.of("id", "movie-5")))
			.errors(List.of())
			.build());

		assertThat(this.writer.holdsErrors(response)).isFalse();
	}

	private static GraphQlResponse response(ExecutionResult result) {
		return new DefaultExecutionGraphQlResponse(ExecutionInput.newExecutionInput(DOCUMENT).build(), result);
	}

}
