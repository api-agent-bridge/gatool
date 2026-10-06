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

package io.gatool.core.internal.schema;

import java.util.List;

/**
 * The input schema of one operation, with the custom scalars that stayed unmapped while
 * it was written.
 *
 * <p>
 * Startup logs one warning that lists every unmapped scalar and the tools that use it.
 * That list spans the whole catalog, so each operation reports its own scalars here and
 * the catalog gathers them.
 *
 * @param json the JSON Schema 2020-12 object, as text
 * @param unmappedScalars the names of the custom scalars this operation uses that reached
 * the schema as {@code {}}, in the order the variables declare them
 * @param refusedDefaults each variable whose declared default failed the property schema
 * beside it, so the default was left out
 * @param refusedFieldDefaults each input field of the API's schema whose default failed
 * the property schema beside it, as {@code <Type>.<field> = <value>}, so the default was
 * left out
 * @param cutTypes each variable and input type where the walk stopped expanding, as
 * {@code <variable> at <Type>}, because the input types of that API reach each other in
 * enough ways to write more than a model reads
 * @author Željko Kozina
 */
public record InputSchema(String json, List<String> unmappedScalars, List<String> refusedDefaults,
		List<String> refusedFieldDefaults, List<String> cutTypes) {

	public InputSchema {
		unmappedScalars = List.copyOf(unmappedScalars);
		refusedDefaults = List.copyOf(refusedDefaults);
		refusedFieldDefaults = List.copyOf(refusedFieldDefaults);
		cutTypes = List.copyOf(cutTypes);
	}
}
