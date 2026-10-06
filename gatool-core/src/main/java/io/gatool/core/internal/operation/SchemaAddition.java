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

package io.gatool.core.internal.operation;

import java.util.List;

/**
 * The characters the adapter of one exposure type adds to the text of each schema it
 * publishes, which the token estimate of a tool counts.
 *
 * <p>
 * The catalog holds each schema as its writer produced it, and an adapter may publish
 * more than that text. The MCP adapter names every schema with a keyword of its own, and
 * {@code tools/list} carries the keyword with the schema. The adapter states the size of
 * what it adds here, so the estimate counts what an agent reads, and this module stays
 * free of the adapter's own format.
 *
 * @param toolExposureType the exposure type the adapter serves
 * @param characters what the adapter adds to each schema, in characters
 * @author Željko Kozina
 */
public record SchemaAddition(ToolExposureType toolExposureType, int characters) {

	/**
	 * Returns what the adapter of one exposure type adds to each schema it publishes.
	 * @param toolExposureType the exposure type an agent reads the tool through
	 * @param additions what each adapter stated
	 * @return the characters, which are zero for an exposure type whose adapter left them
	 * unstated
	 */
	public static int charactersFor(ToolExposureType toolExposureType, List<SchemaAddition> additions) {
		return additions.stream()
			.filter((addition) -> addition.toolExposureType() == toolExposureType)
			.mapToInt(SchemaAddition::characters)
			.sum();
	}
}
