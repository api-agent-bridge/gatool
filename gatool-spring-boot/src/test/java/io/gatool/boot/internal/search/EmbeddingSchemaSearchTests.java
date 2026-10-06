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
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.SearchHit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Ranking by meaning, and the cache that stops a schema being embedded twice.
 *
 * <p>
 * The model here is a stand-in that turns text into a vector of word counts, so a
 * question and a field that share words point the same way. That is enough to test what
 * this class owns: the order hits come back in, the counting of calls, and what the cache
 * does on a second start. How well a real model ranks a real schema is measured with that
 * model, outside a unit test.
 */
class EmbeddingSchemaSearchTests {

	private static final List<CorpusEntry> CORPUS = List.of(
			CorpusEntry.of("Query.movies", "movies films catalog titles", null),
			CorpusEntry.of("Query.actors", "actors people cast names", null),
			CorpusEntry.of("Movie.rating", "rating score stars", "Query.movies"));

	private static final List<String> WORDS = List.of("movies", "films", "catalog", "titles", "actors", "people",
			"cast", "names", "rating", "score", "stars");

	@Test
	void search_aQuestion_shouldRankTheFieldThatSharesItsMeaningFirst(@TempDir Path cache) {
		List<SearchHit> hits = search(cache).search("films catalog", 10);

		assertThat(hits.getFirst().coordinate()).isEqualTo("Query.movies");
	}

	@Test
	void search_hits_shouldComeBackBestFirst(@TempDir Path cache) {
		List<SearchHit> hits = search(cache).search("rating score", 10);

		assertThat(hits).isSortedAccordingTo((left, right) -> Double.compare(right.score(), left.score()));
	}

	@Test
	void search_everyHit_shouldCarryTheTextThePathAndTheCount(@TempDir Path cache) {
		SearchHit hit = search(cache).search("rating stars", 1).getFirst();

		assertThat(hit.coordinate()).isEqualTo("Movie.rating");
		assertThat(hit.text()).isEqualTo("rating score stars");
		assertThat(hit.path()).isEqualTo("Query.movies");
		assertThat(hit.tokens()).isEqualTo(CORPUS.getLast().tokens());
	}

	@Test
	void search_maxHits_shouldBoundWhatComesBack(@TempDir Path cache) {
		assertThat(search(cache).search("movies actors rating", 2)).hasSize(2);
	}

	@Test
	void search_aQuestionOfWordsTheSchemaLacks_shouldStillAnswer(@TempDir Path cache) {
		// Cosine scores every entry, so every question comes back with hits. The caller's
		// budget is what stops a model reading the whole schema.
		assertThat(search(cache).search("kubernetes ingress", 3)).hasSize(3);
	}

	@Test
	void search_aBlankQuestion_shouldAnswerWithNoHits(@TempDir Path cache) {
		assertThat(search(cache).search("  ", 10)).isEmpty();
	}

	@Test
	void construct_theFirstTime_shouldEmbedEveryFieldOnceAndProbeTheModelOnce(@TempDir Path cache) {
		CountingModel model = new CountingModel();

		new EmbeddingSchemaSearch(CORPUS, model, "", "", 128, cache);

		// Three fields in one batch, plus the probe that identifies the model.
		assertThat(model.texts).hasSize(CORPUS.size() + 1).contains(EmbeddingSchemaSearch.PROBE);
	}

	@Test
	void construct_aSecondTime_shouldReadTheVectorsBackInsteadOfPayingAgain(@TempDir Path cache) {
		new EmbeddingSchemaSearch(CORPUS, new CountingModel(), "", "", 128, cache);
		CountingModel second = new CountingModel();

		new EmbeddingSchemaSearch(CORPUS, second, "", "", 128, cache);

		// The probe alone: the fields came off the disk.
		assertThat(second.texts).containsExactly(EmbeddingSchemaSearch.PROBE);
	}

	@Test
	void construct_aChangedCorpus_shouldEmbedAgain(@TempDir Path cache) {
		new EmbeddingSchemaSearch(CORPUS, new CountingModel(), "", "", 128, cache);
		List<CorpusEntry> changed = new ArrayList<>(CORPUS);
		changed.add(CorpusEntry.of("Query.studios", "studios", null));
		CountingModel second = new CountingModel();

		new EmbeddingSchemaSearch(changed, second, "", "", 128, cache);

		assertThat(second.texts).hasSize(changed.size() + 1);
	}

	@Test
	void construct_aDifferentModel_shouldEmbedAgain(@TempDir Path cache) {
		new EmbeddingSchemaSearch(CORPUS, new CountingModel(), "", "", 128, cache);
		// GATool cannot read a name from a model, so the key holds what it answered for
		// one fixed sentence. This one answers differently, and the cache says so.
		CountingModel other = new CountingModel() {
			@Override
			public float[] embed(String text) {
				float[] vector = super.embed(text);
				vector[0] += 1;
				return vector;
			}
		};

		new EmbeddingSchemaSearch(CORPUS, other, "", "", 128, cache);

		assertThat(other.texts).hasSize(CORPUS.size() + 1);
	}

	@Test
	void construct_withoutACacheDirectory_shouldEmbedAtEveryStart(@TempDir Path cache) {
		new EmbeddingSchemaSearch(CORPUS, new CountingModel(), "", "", 128, null);
		CountingModel second = new CountingModel();

		new EmbeddingSchemaSearch(CORPUS, second, "", "", 128, null);

		assertThat(second.texts).hasSize(CORPUS.size() + 1);
		assertThat(cache).isEmptyDirectory();
	}

	@Test
	void construct_aBatchSizeBelowTheCorpus_shouldStillEmbedEveryField(@TempDir Path cache) {
		CountingModel model = new CountingModel();

		new EmbeddingSchemaSearch(CORPUS, model, "", "", 2, cache);

		assertThat(model.texts).hasSize(CORPUS.size() + 1);
		assertThat(model.batches).isEqualTo(2);
	}

	@Test
	void construct_prefixes_shouldReachTheModel(@TempDir Path cache) {
		CountingModel model = new CountingModel();

		new EmbeddingSchemaSearch(CORPUS, model, "question: ", "passage: ", 128, cache).search("films", 1);

		assertThat(model.texts).contains("passage: movies films catalog titles").contains("question: films");
	}

	@Test
	void construct_anEmptyCorpus_shouldAskTheModelForNothing(@TempDir Path cache) {
		CountingModel model = new CountingModel();

		assertThat(new EmbeddingSchemaSearch(List.of(), model, "", "", 128, cache).search("films", 10)).isEmpty();
		assertThat(model.texts).isEmpty();
	}

	@Test
	void construct_aModelReturningFewerVectorsThanTexts_shouldFailNamingTheModel(@TempDir Path cache) {
		// The vectors are matched to the corpus by position, so one missing vector shifts
		// every entry after it onto the wrong text, and search would then rank nonsense
		// without an error. Startup stops instead, and the cache stays empty.
		DroppingModel model = new DroppingModel();

		assertThatIllegalStateException().isThrownBy(() -> new EmbeddingSchemaSearch(CORPUS, model, "", "", 128, cache))
			.withMessageContaining(DroppingModel.class.getName())
			.withMessageContaining("2 vectors for a batch of 3")
			.withMessageContaining("embed-batch-size");
		assertThat(cache).isEmptyDirectory();
	}

	private static EmbeddingSchemaSearch search(Path cache) {
		return new EmbeddingSchemaSearch(CORPUS, new CountingModel(), "", "", 128, cache);
	}

	/**
	 * A model that answers a batch with one vector too few, which is what a provider that
	 * silently caps a batch does.
	 */
	private static final class DroppingModel extends CountingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> results = super.call(request).getResults();
			return new EmbeddingResponse(results.subList(1, results.size()));
		}

	}

	/**
	 * An embedding model that counts words, so a question and a field sharing words point
	 * the same way, and that records what it was asked to embed.
	 */
	private static class CountingModel implements EmbeddingModel {

		private final List<String> texts = new ArrayList<>();

		private int batches;

		@Override
		public float[] embed(String text) {
			this.texts.add(text);
			return vectorOf(text);
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		// EmbeddingModel.embed(List) is a default method that routes through call, so a
		// stand-in that overrode the list method would leave the path GATool actually
		// takes untested.
		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			this.batches++;
			List<Embedding> results = new ArrayList<>();
			for (String text : request.getInstructions()) {
				this.texts.add(text);
				results.add(new Embedding(vectorOf(text), results.size()));
			}
			return new EmbeddingResponse(results);
		}

		@Override
		public int dimensions() {
			return WORDS.size();
		}

		private static float[] vectorOf(String text) {
			float[] vector = new float[WORDS.size()];
			String lower = text.toLowerCase(Locale.ROOT);
			for (int index = 0; index < WORDS.size(); index++) {
				vector[index] = lower.contains(WORDS.get(index)) ? 1 : 0;
			}
			return vector;
		}

	}

}
