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

import java.util.List;

import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;
import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.CorpusFormat;
import io.gatool.core.search.SchemaCorpus;
import io.gatool.core.search.SearchHit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What BM25 does with a question, on a corpus small enough to reason about.
 *
 * <p>
 * What belongs here is the behaviour a caller depends on: that the words of a question
 * find the entry carrying them, on a schema with descriptions and on one without, that a
 * hit carries the text and the token count the corpus measured, and that the awkward
 * inputs answer without an exception.
 */
class LuceneSchemaSearchTests {

	private static final List<CorpusEntry> CORPUS = List.of(CorpusEntry.of("Query.movies", """
			\"""Finds movies by title or by release year.\"""
			movies(filter: MovieFilter): [Movie!]!
			# on type Query""", null), CorpusEntry.of("Movie.rating", """
			\"""The average viewer rating.\"""
			rating: Float
			# on type Movie""", "Query.movies"), CorpusEntry.of("Query.actors", """
			\"""Finds actors by name.\"""
			actors(name: String): [Actor!]!
			# on type Query""", null), CorpusEntry.of("Mutation.addReview", """
			\"""Adds a review to a movie.\"""
			addReview(input: AddReviewInput!): AddReviewPayload
			# on type Mutation""", null));

	// A schema with a mutation root, for the two cases the mutation switch decides.
	private static final String SDL = """
			type Movie { id: ID! title: String! reviews: [Review!]! }
			type Review { id: ID! score: Int! }
			input AddReviewInput { movieId: ID! score: Int! }
			type AddReviewPayload { review: Review }
			type Query {
			  \"""Finds movies by title or by release year.\"""
			  movies(title: String, year: Int): [Movie!]!
			}
			type Mutation {
			  \"""Adds a review to a movie.\"""
			  addReview(input: AddReviewInput!): AddReviewPayload
			}
			""";

	@Test
	void search_aQuestionNamingAField_shouldRankThatFieldFirst() {
		List<SearchHit> hits = search("how do I find movies by their release year", 10);

		assertThat(hits).isNotEmpty();
		assertThat(hits.getFirst().coordinate()).isEqualTo("Query.movies");
	}

	@Test
	void search_aQuestionAboutWritingWithMutationsOn_shouldRankTheMutationFirst() {
		List<SearchHit> hits = search(corpusOf(true), "add a review", 10);

		assertThat(hits.getFirst().coordinate()).isEqualTo("Mutation.addReview");
	}

	@Test
	void search_aQuestionAboutWritingWithMutationsOff_shouldOfferNoMutationCoordinate() {
		// The corpus follows the switch, so the ranking cannot see the mutation root or
		// the payload only it reaches, and the model cannot be offered an operation
		// executeGraphql would refuse.
		List<SearchHit> hits = search(corpusOf(false), "add a review", 10);

		assertThat(hits).isNotEmpty();
		assertThat(hits).extracting(SearchHit::coordinate)
			.doesNotContain("Mutation.addReview", "AddReviewPayload.review");
	}

	@Test
	void search_aQuestionOfMoreDistinctWordsThanLuceneAllowsClauses_shouldRankWithoutAnException() {
		// Lucene refuses a query of more than 1,024 clauses, and one clause per word
		// would fail a question of that length with a stack trace in the log.
		StringBuilder question = new StringBuilder("movies");
		for (int index = 0; index < 1_200; index++) {
			question.append(" word").append(index);
		}

		List<SearchHit> hits = search(question.toString(), 10);

		assertThat(hits).extracting(SearchHit::coordinate).contains("Query.movies");
	}

	@Test
	void search_aWordRepeatedMoreTimesThanLuceneAllowsClauses_shouldRankItOnce() {
		List<SearchHit> hits = search("movies ".repeat(1_100), 10);

		assertThat(hits.getFirst().coordinate()).isEqualTo("Query.movies");
	}

	@Test
	void close_shouldReleaseTheIndexSoASearchAfterItFails() {
		// The container calls this when the application stops. Lucene's own exception
		// is the observable proof the reader is closed.
		LuceneSchemaSearch search = new LuceneSchemaSearch(CORPUS);

		search.close();

		assertThatExceptionOfType(AlreadyClosedException.class).isThrownBy(() -> search.search("movies", 10));
	}

	@Test
	void close_aSecondTime_shouldBeHarmless() {
		LuceneSchemaSearch search = new LuceneSchemaSearch(CORPUS);
		search.close();

		assertThatCode(search::close).doesNotThrowAnyException();
	}

	@Test
	void search_everyHit_shouldCarryTheTextAndTheCountTheCorpusMeasured() {
		SearchHit hit = search("rating", 10).getFirst();

		// The hit is what the model reads, so it carries the corpus text, which saves the
		// caller looking the coordinate up again, and the count the budget walk spends.
		CorpusEntry rating = CORPUS.stream()
			.filter((entry) -> entry.coordinate().equals("Movie.rating"))
			.findFirst()
			.orElseThrow();
		assertThat(hit.coordinate()).isEqualTo("Movie.rating");
		assertThat(hit.text()).isEqualTo(rating.text());
		assertThat(hit.tokens()).isEqualTo(rating.tokens());
		assertThat(hit.score()).isPositive();
	}

	@Test
	void search_hits_shouldComeBackBestFirst() {
		List<SearchHit> hits = search("find movies and actors", 10);

		assertThat(hits).isSortedAccordingTo((left, right) -> Double.compare(right.score(), left.score()));
	}

	@Test
	void search_maxHits_shouldBoundWhatComesBack() {
		assertThat(search("find movies and actors and reviews and ratings", 2)).hasSize(2);
	}

	@Test
	void search_aQuestionMatchingNothing_shouldAnswerWithNoHits() {
		assertThat(search("kubernetes ingress", 10)).isEmpty();
	}

	@Test
	void search_aQuestionOfCommonWordsAlone_shouldMatchWeaklyInsteadOfRefusing() {
		// The analyzer keeps every stop word, so "the and of" analyses to exactly those
		// three terms and matches any entry carrying one. BM25 is what keeps such a
		// question from swamping the ranking: a word in most entries earns a score close
		// to zero, and a question of common words alone still answers.
		List<SearchHit> hits = search("the and of", 10);

		assertThat(hits).isNotEmpty().allSatisfy((hit) -> assertThat(hit.score()).isLessThan(1.0));
	}

	@Test
	void search_aQuestionCarryingQuerySyntax_shouldReadItAsWords() {
		// Lucene's own query parser would read this as a field query and a boolean
		// operator. GATool analyses the question into terms, so the punctuation is just
		// punctuation and no escaping is needed.
		List<SearchHit> hits = search("title:\"movies\" AND (year OR rating) -actors", 10);

		assertThat(hits).isNotEmpty();
		assertThat(hits).extracting(SearchHit::coordinate).contains("Query.movies");
	}

	@Test
	void search_plainWordsAgainstACamelCaseIdentifier_shouldRankThatFieldFirst() {
		// The schema below holds identifiers alone, so they are all the index holds, and
		// StandardAnalyzer would keep topRatedMovies as one term that only the identifier
		// itself could match.
		assertThat(search(bareCorpus(), "top rated movies", 10)).extracting(SearchHit::coordinate)
			.first()
			.isEqualTo("Query.topRatedMovies");
	}

	@Test
	void search_plainWordsAgainstAFieldOfANestedType_shouldRankThatFieldFirst() {
		assertThat(search(bareCorpus(), "release year", 10)).extracting(SearchHit::coordinate)
			.first()
			.isEqualTo("Movie.releaseYear");
	}

	@Test
	void search_aPluralNounAlone_shouldRankTheFieldReturningTheList() {
		// Every entry of this corpus names Movie somewhere, and the one that spells the
		// plural the question wrote ranks above the ones that merely stem to it.
		assertThat(search(bareCorpus(), "movies", 10)).extracting(SearchHit::coordinate)
			.first()
			.isEqualTo("Query.topRatedMovies");
	}

	@Test
	void search_plainWordsAgainstASnakeCaseIdentifier_shouldRankThatFieldFirst() {
		assertThat(search(bareCorpus(), "movie by id", 10)).extracting(SearchHit::coordinate)
			.first()
			.isEqualTo("Query.movie_by_id");
	}

	@Test
	void search_theSameQuestionTwice_shouldAnswerInTheSameOrder() {
		// Several entries tie on a one-word question, and a model reading two answers
		// to the same question in two orders would take the schema to have changed.
		try (LuceneSchemaSearch search = new LuceneSchemaSearch(bareCorpus());
				LuceneSchemaSearch again = new LuceneSchemaSearch(bareCorpus())) {
			List<SearchHit> first = search.search("movie id", 10);

			assertThat(first).hasSizeGreaterThan(1);
			assertThat(search.search("movie id", 10)).isEqualTo(first);
			assertThat(again.search("movie id", 10)).isEqualTo(first);
		}
	}

	@Test
	void search_maxHitsBelowOne_shouldAnswerWithNoHits() {
		assertThat(search("movies", 0)).isEmpty();
	}

	@Test
	void search_anEmptyCorpus_shouldAnswerWithNoHits() {
		assertThat(search(List.of(), "movies", 10)).isEmpty();
	}

	private static List<SearchHit> search(String question, int maxHits) {
		return search(CORPUS, question, maxHits);
	}

	private static List<SearchHit> search(List<CorpusEntry> corpus, String question, int maxHits) {
		try (LuceneSchemaSearch search = new LuceneSchemaSearch(corpus)) {
			return search.search(question, maxHits);
		}
	}

	// A schema without descriptions, rendered as SDL, so a question can match the
	// identifiers alone.
	private static List<CorpusEntry> bareCorpus() {
		return SchemaCorpus.of(SdlSchemaFactory.schemaFrom("""
				type Movie { id: ID! releaseYear: Int }
				type Query { topRatedMovies(first: Int): [Movie!]! movie_by_id(id: ID!): Movie }
				""", "lucene-search-test.graphqls"), CorpusFormat.SDL, false, false);
	}

	private static List<CorpusEntry> corpusOf(boolean allowMutations) {
		return SchemaCorpus.of(SdlSchemaFactory.schemaFrom(SDL, "lucene-search-test.graphqls"), CorpusFormat.SDL, false,
				allowMutations);
	}

}
