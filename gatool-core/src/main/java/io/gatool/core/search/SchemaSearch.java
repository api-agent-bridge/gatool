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

import java.util.List;

/**
 * Ranks the coordinates of a schema against a question, which is what the
 * {@code searchSchema} tool of the dynamic layer calls.
 *
 * <p>
 * Two implementations ship. BM25 over a Lucene index is the default, and it works without
 * a service, a key or a model. The other embeds every entry through the
 * {@code EmbeddingModel} bean an application publishes and ranks by cosine similarity,
 * which finds a field that answers a question in words the question did not use.
 *
 * <p>
 * An application publishes its own bean to replace either, the way it replaces
 * {@code GraphQlExecutor} or a {@code ToolNamingStrategy}, which is why this interface
 * and the corpus it reads are public while the two implementations are not.
 *
 * <p>
 * Ranking a few thousand short entries is a list and a sort, so an implementation holds
 * its index in memory. A vector store would add a dependency and a service for a corpus
 * this size.
 *
 * <p>
 * Ranking is all this does. The token budget that decides how much of the ranking a model
 * actually reads is spent by the caller, so the two implementations cannot disagree about
 * it and a third one cannot forget it.
 *
 * @author Željko Kozina
 */
public interface SchemaSearch {

	/**
	 * Ranks the corpus against one question.
	 * @param question what to look for, in the words the caller used. It is natural
	 * language, so an implementation takes the words as they arrive
	 * @param maxHits how many hits to rank at most, which bounds the work instead of what
	 * a model reads
	 * @return the hits, best first, which is empty where nothing matched
	 */
	List<SearchHit> search(String question, int maxHits);

}
