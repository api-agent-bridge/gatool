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

import java.util.Collections;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps a {@code null} in the JSON GATool writes, on both exposure types of a tool call,
 * and every digit of a decimal it reads.
 *
 * <p>
 * GraphQL gives a {@code null} a meaning of its own. A variable set to {@code null}
 * clears a value, and the GraphQL specification's section 6.1.2 makes that a different
 * request from one leaving the variable out, which applies the variable's default. Going
 * the other way, a field the API answered as {@code null} is a different answer from a
 * field the operation left unselected. So every {@code null} GATool holds reaches the
 * text it writes.
 *
 * <p>
 * Two mappers would drop them, for two different reasons.
 *
 * <p>
 * Spring AI's own {@code mcpServerJsonMapper} sets value inclusion and content inclusion
 * to {@code NON_NULL} in its bean definition, so a result written with it loses a null
 * whatever the application configures. GATool declares that bean with content inclusion
 * kept, so that a null argument survives the transport's read, and an application may
 * declare its own. The result side guards against a mapper that drops nulls whichever
 * bean is in place.
 *
 * <p>
 * An application setting {@code spring.jackson.default-property-inclusion} to a value
 * that drops nulls reaches Boot's own mapper, which is the one the outbound request would
 * otherwise use. Boot applies that property to content inclusion as well as value
 * inclusion, and content inclusion governs a null map value. Jackson keeps a null map
 * value by default, so the request side is only exposed once an application asks for
 * that. GATool writes its variables with a mapper of its own there, and startup says so,
 * because that property is about the objects the application serialises while a GraphQL
 * variable is a request the model composed.
 *
 * @author Željko Kozina
 */
public final class ExplicitNulls {

	private static final String PROBE = "probe";

	private ExplicitNulls() {
	}

	/**
	 * Returns a copy of one mapper that writes every null and reads a float as a
	 * {@code BigDecimal}.
	 *
	 * <p>
	 * The change is made to a copy, so the mapper an application configured stays as it
	 * is for everything else it serialises. Both inclusions are set, because content
	 * inclusion is the one that governs a null map value and value inclusion is the one
	 * that governs a null field.
	 *
	 * <p>
	 * The copy reads a JSON float as a {@code BigDecimal}, because the API's decimals
	 * arrive through it and a float read into a double loses everything past the
	 * seventeenth significant digit, so a {@code Decimal} scalar the schema describes to
	 * the model would reach it rounded. Jackson writes a {@code BigDecimal} with
	 * {@code toString()}, so {@code 1E+2} passes through as the API sent it.
	 * @param mapper the mapper to copy, which contributes every other setting
	 * @return the copy, which writes a null wherever it finds one and keeps every digit
	 * it reads
	 */
	public static JsonMapper mapperKeepingNulls(JsonMapper mapper) {
		return mapper.rebuild()
			.changeDefaultPropertyInclusion(
					(inclusion) -> JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();
	}

	/**
	 * Whether one mapper leaves a null out of a map it writes.
	 *
	 * <p>
	 * This asks the mapper instead of reading the property, because a
	 * {@code JsonMapperBuilderCustomizer} bean sets the same inclusion, which the
	 * property does not record.
	 * @param mapper the mapper to ask
	 * @return true when a map holding one null value writes as an empty object
	 */
	public static boolean dropsNulls(JsonMapper mapper) {
		String written = mapper.writeValueAsString(Collections.singletonMap(PROBE, null));
		return !written.contains(PROBE);
	}

}
