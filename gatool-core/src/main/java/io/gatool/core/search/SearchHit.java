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

package io.gatool.core.search;

import org.jspecify.annotations.Nullable;

/**
 * One ranked coordinate, as {@code searchSchema} returns it.
 *
 * <p>
 * The text, the path and the token count come straight from the corpus entry, so what a
 * ranking returns is what the model reads and what the budget charged for it.
 *
 * @param coordinate the {@code Type.field} coordinate
 * @param score how well the entry matched, where a higher number ranks earlier. The scale
 * belongs to the ranking, so two implementations do not compare
 * @param text the rendering of that coordinate, which {@link CorpusFormat} chose
 * @param path how an operation reaches this field's owner type from a root, or
 * {@code null} for a root field and for a type beyond the reach of every root
 * @param tokens what reading the text and the path costs, which the budget walk spends
 * @author Željko Kozina
 */
public record SearchHit(String coordinate, double score, String text, @Nullable String path, int tokens) {
}
