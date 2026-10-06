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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.graphql.GraphQlResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the JSON text of a tool result, which both adapters return, so that the text is
 * identical on MCP and in process.
 *
 * <p>
 * The writer keeps its own copy of Boot's Jackson 3 mapper, from
 * {@link ExplicitNulls#mapperKeepingNulls}. Spring AI's {@code mcpServerJsonMapper}
 * writes with {@code NON_NULL}, so a result written with it loses a field the API
 * answered as {@code null}, and the MCP text would differ from the in-process text. An
 * application setting {@code spring.jackson.default-property-inclusion} reaches this text
 * by the same route, and the copy covers that as well. Jackson itself keeps a null map
 * value, so the setting is what a plain application changes here.
 *
 * <p>
 * The copy writes compact JSON whatever the application configured, because
 * {@code gatool.results.max-characters} counts characters and an application with
 * {@code spring.jackson.serialization.indent-output} would otherwise spend that limit on
 * line breaks and indentation that the model skips past.
 *
 * <p>
 * The envelope holds {@code data} and {@code errors} alone. The response level
 * {@code extensions} entry stays out, because GraphQL leaves its size and content to each
 * implementation, and any other key stays out because GraphQL limits the top level of a
 * response to those three: a key a proxy adds would otherwise reach the model as part of
 * the result, and the structured content a tool's output schema describes.
 *
 * @author Željko Kozina
 */
public final class GraphQlResultWriter {

	private static final List<String> ENVELOPE_KEYS = List.of("data", "errors");

	// Reads a result text back with every digit kept: a float as a BigDecimal and an
	// integer past a long as a BigInteger, which any mapper then writes digit for digit.
	private static final JsonMapper READ_BACK = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
		.build();

	private static final TypeReference<Map<String, @Nullable Object>> ENVELOPE_TYPE = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper;

	/**
	 * Creates the writer.
	 * @param jsonMapper boot's mapper, which this writer copies
	 */
	public GraphQlResultWriter(JsonMapper jsonMapper) {
		this.jsonMapper = ExplicitNulls.mapperKeepingNulls(jsonMapper)
			.rebuild()
			.disable(SerializationFeature.INDENT_OUTPUT)
			.build();
	}

	/**
	 * Writes one response as the text of a tool result.
	 * @param response the response the executor returned
	 * @return the JSON text, holding {@code data} and {@code errors}
	 */
	public String write(GraphQlResponse response) {
		return write(envelope(response));
	}

	/**
	 * Returns the response as the map the text is written from.
	 *
	 * <p>
	 * A tool that publishes an output schema returns this map as its structured content,
	 * so the structured result and the text are one value written twice, and the two
	 * cannot disagree.
	 * @param response the response the executor returned
	 * @return the envelope, holding {@code data} and {@code errors}
	 */
	// The value type admits null, because a failed call answers with data: null, and
	// the structured content a tool publishes carries that null as it is.
	public Map<String, @Nullable Object> envelope(GraphQlResponse response) {
		Map<String, Object> map = response.toMap();
		Map<String, @Nullable Object> envelope = new LinkedHashMap<>();
		for (String key : ENVELOPE_KEYS) {
			if (map.containsKey(key)) {
				envelope.put(key, map.get(key));
			}
		}
		return envelope;
	}

	/**
	 * Writes one envelope as the text of a tool result.
	 * @param envelope the envelope from {@link #envelope(GraphQlResponse)}
	 * @return the JSON text
	 */
	public String write(Map<String, @Nullable Object> envelope) {
		return this.jsonMapper.writeValueAsString(envelope);
	}

	/**
	 * Reads a result text back as the envelope it was written from.
	 *
	 * <p>
	 * A tool that publishes an output schema returns this map as its structured content.
	 * The text is what the application's own Jackson modules wrote, and a value read from
	 * it is a map, a list, a string, a {@code BigDecimal}, a {@code BigInteger}, a
	 * boolean or {@code null}, all of which the transport's mapper writes the same way,
	 * so the structured half carries every digit, every string and every null the text
	 * carries.
	 * @param text the text {@link #write(Map)} produced
	 * @return the envelope, holding {@code data} and {@code errors}
	 */
	public Map<String, @Nullable Object> readBack(String text) {
		Map<String, @Nullable Object> envelope = READ_BACK.readValue(text, ENVELOPE_TYPE);
		return (envelope != null) ? envelope : new LinkedHashMap<>();
	}

	/**
	 * Says whether the result counts as an error.
	 * @param response the response the executor returned
	 * @return {@code true} when the response holds errors, or when {@code data} is
	 * missing or {@code null}
	 */
	public boolean holdsErrors(GraphQlResponse response) {
		return !response.getErrors().isEmpty() || !response.isValid();
	}

}
