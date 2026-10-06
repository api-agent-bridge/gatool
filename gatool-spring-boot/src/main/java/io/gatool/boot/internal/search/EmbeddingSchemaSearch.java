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

package io.gatool.boot.internal.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;

import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.SchemaSearch;
import io.gatool.core.search.SearchHit;

/**
 * Ranks schema coordinates by meaning, using the {@code EmbeddingModel} bean an
 * application publishes.
 *
 * <p>
 * Where the default backend finds a field whose declaration carries the words a question
 * used, this one finds a field that answers the question in other words. A question about
 * "films" reaches a schema that says "movie", which word statistics cannot do.
 *
 * <p>
 * The vectors live in memory as arrays and cosine runs over them directly. A schema is a
 * few thousand short entries, so a vector store would add a dependency and a service to
 * sort a list.
 *
 * <p>
 * Embedding happens once, at startup, and {@link VectorCache} keeps the result so the
 * next start reads it instead of paying for it again. What a model charges for is one
 * call per field.
 *
 * <p>
 * Some embedding models are trained to read a short instruction before the text and
 * answer better for it, and they want a different one for a question than for a document.
 * Those are the two prefix settings, and they are empty unless an application sets them,
 * because a model that was not trained that way reads them as noise.
 *
 * @author Željko Kozina
 */
public final class EmbeddingSchemaSearch implements SchemaSearch {

	/**
	 * What the cache key embeds to tell one model from another. Any fixed sentence does;
	 * this one is fixed forever, because changing it would invalidate every cache file in
	 * existence.
	 */
	static final String PROBE = "GATool schema navigation probe";

	private static final Log logger = LogFactory.getLog(EmbeddingSchemaSearch.class);

	private final List<CorpusEntry> corpus;

	private final List<float[]> vectors;

	private final EmbeddingModel model;

	private final String queryPrefix;

	/**
	 * Embeds one corpus.
	 * @param corpus every field of the schema
	 * @param model the application's embedding model
	 * @param queryPrefix what a question carries before it is embedded
	 * @param documentPrefix what a field carries before it is embedded
	 * @param batchSize how many fields go to the model in one call
	 * @param cacheDirectory where embedded fields are kept, or {@code null} to embed at
	 * every startup
	 */
	public EmbeddingSchemaSearch(List<CorpusEntry> corpus, EmbeddingModel model, String queryPrefix,
			String documentPrefix, int batchSize, @Nullable Path cacheDirectory) {
		this.corpus = List.copyOf(corpus);
		this.model = model;
		this.queryPrefix = queryPrefix;
		this.vectors = embedCorpus(this.corpus, model, queryPrefix, documentPrefix, batchSize, cacheDirectory);
	}

	// Returns one vector per corpus entry. A cached run reads them from the cache;
	// otherwise the model embeds the corpus in batches and the result is cached.
	private static List<float[]> embedCorpus(List<CorpusEntry> corpus, EmbeddingModel model, String queryPrefix,
			String documentPrefix, int batchSize, @Nullable Path cacheDirectory) {
		if (corpus.isEmpty()) {
			return List.of();
		}
		List<String> texts = corpus.stream().map(CorpusEntry::text).toList();
		VectorCache cache = new VectorCache(cacheDirectory);
		// One call, and it identifies the model by what it answers instead of by a name
		// no provider-neutral API exposes.
		String key = VectorCache.keyOf(texts, queryPrefix, documentPrefix, model.embed(PROBE));
		List<float[]> cached = cache.read(key, texts.size());
		if (cached != null) {
			logger.info("GATool read " + cached.size() + " embedded schema coordinates from its cache.");
			return cached;
		}
		logger.info("GATool is embedding " + texts.size() + " schema coordinates, which is one call to your "
				+ "embedding model for each of them. The result is cached, so later starts read it.");
		List<float[]> embedded = new ArrayList<>(texts.size());
		for (int start = 0; start < texts.size(); start += batchSize) {
			List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()))
				.stream()
				.map((text) -> documentPrefix + text)
				.toList();
			List<float[]> vectors = model.embed(batch);
			if (vectors.size() != batch.size()) {
				// The vectors are matched to the corpus by position, so one missing
				// vector shifts every entry after it onto the wrong text, and search then
				// ranks nonsense without an error. A model that drops an input, or caps a
				// batch below the configured size, is caught here at startup, before
				// anything is cached.
				throw new IllegalStateException("The embedding model " + model.getClass().getName() + " returned "
						+ vectors.size() + " vectors for a batch of " + batch.size() + " schema coordinates, and "
						+ "searchSchema needs one vector per coordinate. Check the model's batch limit, or lower "
						+ "gatool.dev.experimental.dynamic-operations.embed-batch-size.");
			}
			embedded.addAll(vectors);
		}
		Path written = cache.write(key, embedded);
		if (written != null) {
			logger.info("GATool cached them in " + written + ".");
		}
		return List.copyOf(embedded);
	}

	// Every hit comes back, because cosine gives every entry a score and a threshold
	// that is right for every schema does not exist. Ranking is this class's job and the
	// budget is the caller's, so the cut happens once, there.
	@Override
	public List<SearchHit> search(String question, int maxHits) {
		if (question.isBlank() || maxHits < 1 || this.vectors.isEmpty()) {
			return List.of();
		}
		float[] questionVector = this.model.embed(this.queryPrefix + question);
		List<SearchHit> hits = new ArrayList<>(this.corpus.size());
		for (int index = 0; index < this.corpus.size(); index++) {
			CorpusEntry entry = this.corpus.get(index);
			double score = cosine(questionVector, this.vectors.get(index));
			hits.add(new SearchHit(entry.coordinate(), score, entry.text(), entry.path(), entry.tokens()));
		}
		hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
		return List.copyOf(hits.subList(0, Math.min(maxHits, hits.size())));
	}

	// Returns the cosine similarity of two vectors, and 0 where their lengths differ or
	// either vector is all zeros.
	private static double cosine(float[] left, float[] right) {
		if (left.length != right.length) {
			// A cache file that survived a model change, or two models behind one bean.
			// Scoring it would rank nonsense, so the entry scores zero and ranks below
			// every entry the vectors match.
			return 0;
		}
		double dot = 0;
		double leftLength = 0;
		double rightLength = 0;
		for (int index = 0; index < left.length; index++) {
			dot += (double) left[index] * right[index];
			leftLength += (double) left[index] * left[index];
			rightLength += (double) right[index] * right[index];
		}
		// A zero vector has no direction, so its similarity to anything is 0, and the
		// division is skipped for it.
		double lengths = Math.sqrt(leftLength) * Math.sqrt(rightLength);
		return (lengths > 0) ? dot / lengths : 0;
	}

}
