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

package io.gatool.fixtures.movies.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureHttpGraphQlTester
class MoviesApiTests {

	@Autowired
	private HttpGraphQlTester graphQlTester;

	@Test
	void topRatedMovies_firstTwo_shouldReturnHighestRatedFirst() {
		this.graphQlTester.document("""
				query TopRatedMovies {
				  topRatedMovies(first: 2) { title rating }
				}
				""")
			.execute()
			.path("topRatedMovies[*].title")
			.entityList(String.class)
			.containsExactly("Signal from Kepler", "The Last Lighthouse");
	}

	@Test
	void movie_lookupByTitle_shouldReturnTheMovie() {
		this.graphQlTester.document("""
				query MovieByTitle {
				  movie(by: { title: "Iron Harbor" }) { id genre }
				}
				""")
			.execute()
			.path("movie.id")
			.entity(String.class)
			.isEqualTo("movie-4")
			.path("movie.genre")
			.entity(String.class)
			.isEqualTo("ACTION");
	}

	@Test
	void movies_secondPage_shouldContinueAfterTheCursor() {
		String endCursor = this.graphQlTester.document("""
				query FirstMoviePage {
				  movies(first: 2) {
				    edges { node { title } }
				    pageInfo { hasNextPage endCursor }
				  }
				}
				""")
			.execute()
			.path("movies.edges[*].node.title")
			.entityList(String.class)
			.containsExactly("Iron Harbor", "Midnight Recipe")
			.path("movies.pageInfo.hasNextPage")
			.entity(Boolean.class)
			.isEqualTo(true)
			.path("movies.pageInfo.endCursor")
			.entity(String.class)
			.get();

		this.graphQlTester.document("""
				query NextMoviePage($after: String) {
				  movies(first: 2, after: $after) {
				    edges { node { title } }
				  }
				}
				""")
			.variable("after", endCursor)
			.execute()
			.path("movies.edges[*].node.title")
			.entityList(String.class)
			.containsExactly("Northern Echoes", "Signal from Kepler");
	}

	@Test
	void search_textInTitleAndName_shouldReturnMovieAndPerson() {
		this.graphQlTester.document("""
				query Search {
				  search(text: "no") {
				    __typename
				    ... on Movie { title }
				    ... on Person { name }
				  }
				}
				""").execute().path("search[*].__typename").entityList(String.class).containsExactly("Movie", "Person");
	}

	@Test
	void node_reviewId_shouldResolveTheReviewWithItsDateTime() {
		this.graphQlTester.document("""
				query ReviewNode {
				  node(id: "review-1") {
				    __typename
				    ... on Review { score createdAt }
				  }
				}
				""")
			.execute()
			.path("node.__typename")
			.entity(String.class)
			.isEqualTo("Review")
			.path("node.createdAt")
			.entity(String.class)
			.isEqualTo("2026-01-10T18:30:00.000Z");
	}

	@Test
	void node_personId_shouldSerializeTheCountryCode() {
		this.graphQlTester.document("""
				query PersonNode {
				  node(id: "person-3") { ... on Person { name countryCode } }
				}
				""").execute().path("node.countryCode").entity(String.class).isEqualTo("HR");
	}

	@Test
	void movie_communityRatingUnavailable_shouldReturnPartialResult() {
		this.graphQlTester.document("""
				query MidnightRecipeRatings {
				  movie(by: { id: "movie-3" }) { title communityRating }
				}
				""").execute().errors().satisfy((errors) -> assertThat(errors).singleElement().satisfies((error) -> {
			assertThat(error.getMessage()).isEqualTo("The community rating service is unavailable.");
			assertThat(error.getPath()).isEqualTo("movie.communityRating");
		}))
			.path("movie.title")
			.entity(String.class)
			.isEqualTo("Midnight Recipe")
			.path("movie.communityRating")
			.valueIsNull();
	}

	@Test
	void addReview_validInput_shouldReturnTheStoredReview() {
		this.graphQlTester.document("""
				mutation AddReview {
				  addReview(input: { movieId: "movie-2", score: 8, comment: "Slow and beautiful." }) {
				    review { score comment }
				  }
				}
				""")
			.execute()
			.path("addReview.review.score")
			.entity(Integer.class)
			.isEqualTo(8)
			.path("addReview.review.comment")
			.entity(String.class)
			.isEqualTo("Slow and beautiful.");
	}

	@Test
	void allMovies_deprecatedField_shouldStillReturnEveryMovie() {
		this.graphQlTester.document("""
				query AllMovies {
				  allMovies { id }
				}
				""").execute().path("allMovies[*].id").entityList(String.class).hasSize(5);
	}

}
