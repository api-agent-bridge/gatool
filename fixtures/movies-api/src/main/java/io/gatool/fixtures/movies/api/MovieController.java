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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

@Controller
public class MovieController {

	private final MovieCatalog catalog;

	public MovieController(MovieCatalog catalog) {
		this.catalog = catalog;
	}

	@QueryMapping
	public List<Movie> topRatedMovies(@Argument int first) {
		return this.catalog.topRated(first);
	}

	@QueryMapping
	public @Nullable Movie movie(@Argument MovieLookupInput by) {
		String id = by.id();
		if (id != null) {
			return this.catalog.movie(id);
		}
		return this.catalog.movieByTitle(Objects.requireNonNull(by.title(), "@oneOf requires an id or a title"));
	}

	/**
	 * Returns one page of the movies the filter matches.
	 * @param filter the fields a movie has to match, or {@code null} to match every movie
	 * @param subrange the cursor and page size Spring for GraphQL read from the field's
	 * arguments
	 * @return the page, with a cursor per movie and whether more movies follow it
	 */
	// Spring for GraphQL turns the returned Window into a MovieConnection. An offset
	// cursor is exclusive, so the page starts one movie after the cursor's offset.
	@QueryMapping
	public Window<Movie> movies(@Argument @Nullable MovieFilterInput filter, ScrollSubrange subrange) {
		List<Movie> matches = this.catalog.movies(filter);
		int start = subrange.position()
			.map(OffsetScrollPosition.class::cast)
			.filter((position) -> !position.isInitial())
			.map((position) -> (int) position.getOffset() + 1)
			.orElse(0);
		int end = Math.min(matches.size(), start + subrange.count().orElse(matches.size()));
		List<Movie> page = matches.subList(Math.min(start, end), end);
		// The cast is on the addition, because ScrollPosition.offset takes a long and int
		// arithmetic would wrap before the widening.
		return Window.from(page, (index) -> ScrollPosition.offset((long) start + index), end < matches.size());
	}

	@QueryMapping
	public List<Object> search(@Argument String text) {
		return this.catalog.search(text);
	}

	@QueryMapping
	public @Nullable Object node(@Argument String id) {
		return this.catalog.node(id);
	}

	@QueryMapping
	public List<Movie> allMovies() {
		return this.catalog.allMovies();
	}

	// The deprecated limit stands in for first while a client still sends it.
	@QueryMapping
	public List<Review> reviews(@Argument @Nullable OffsetDateTime since, @Argument @Nullable String country,
			@Argument @Nullable ReviewFilterInput filter, @Argument @Nullable Integer first,
			@Argument @Nullable Integer limit) {
		int count = (first != null) ? first : (limit != null) ? limit : 20;
		return this.catalog.reviews(since, country, filter, count);
	}

	@SchemaMapping
	public double communityRating(Movie movie) {
		return this.catalog.communityRating(movie.id());
	}

	@SchemaMapping
	public List<Person> directors(Movie movie) {
		return this.catalog.people(movie.directorIds());
	}

	@SchemaMapping
	public List<Review> reviews(Movie movie) {
		return this.catalog.reviewsOf(movie.id());
	}

	@MutationMapping
	public AddReviewResponse addReview(@Argument AddReviewInput input) {
		return new AddReviewResponse(this.catalog.addReview(input.movieId(), input.score(), input.comment()));
	}

	@SubscriptionMapping
	public Flux<Review> reviewAdded(@Argument String movieId) {
		return this.catalog.addedReviews().filter((review) -> review.movieId().equals(movieId));
	}

	@GraphQlExceptionHandler
	public GraphQLError communityRatingUnavailable(CommunityRatingUnavailableException ex,
			DataFetchingEnvironment environment) {
		return GraphqlErrorBuilder.newError(environment)
			.errorType(ErrorType.INTERNAL_ERROR)
			.message(CommunityRatingUnavailableException.MESSAGE)
			.build();
	}

}
