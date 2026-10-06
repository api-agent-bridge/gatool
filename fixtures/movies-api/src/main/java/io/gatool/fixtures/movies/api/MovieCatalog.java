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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The movies, people and reviews that the fixture serves, kept in memory.
 *
 * @author Željko Kozina
 */
@Component
public class MovieCatalog {

	// The ids that more than one row names: a cast list points at a person, and both
	// reviews point at one movie.
	private static final String ANA_ID = "person-1";

	private static final String TOMAS_ID = "person-2";

	private static final String MIRA_ID = "person-3";

	private static final String KEPLER_ID = "movie-1";

	private final Map<String, Movie> movies = new LinkedHashMap<>();

	private final Map<String, Person> people = new LinkedHashMap<>();

	private final Map<String, Double> communityRatings = new HashMap<>();

	private final List<Review> reviews = new CopyOnWriteArrayList<>();

	private final Sinks.Many<Review> addedReviews = Sinks.many().multicast().directBestEffort();

	private final AtomicInteger reviewNumbers = new AtomicInteger();

	public MovieCatalog() {
		addPerson(new Person(ANA_ID, "Ana Novak", "SI"));
		addPerson(new Person(TOMAS_ID, "Tomás Herrera", "MX"));
		addPerson(new Person(MIRA_ID, "Mira Kovač", "HR"));
		addMovie(new Movie(KEPLER_ID, "Signal from Kepler", 2021, Genre.SCIFI, 8.6, List.of(ANA_ID)), 8.2);
		addMovie(new Movie("movie-2", "The Last Lighthouse", 2018, Genre.DRAMA, 7.9, List.of(TOMAS_ID)), 8.0);
		addMovie(new Movie("movie-3", "Midnight Recipe", 2023, Genre.COMEDY, 7.1, List.of(MIRA_ID)), null);
		addMovie(new Movie("movie-4", "Iron Harbor", 2015, Genre.ACTION, 6.4, List.of(TOMAS_ID, MIRA_ID)), 6.9);
		addMovie(new Movie("movie-5", "Northern Echoes", 2024, Genre.DRAMA, null, List.of(ANA_ID)), 7.5);
		this.reviews.add(new Review(nextReviewId(), KEPLER_ID, 9, "A careful, quiet film about listening.",
				OffsetDateTime.parse("2026-01-10T18:30:00Z")));
		this.reviews.add(new Review(nextReviewId(), KEPLER_ID, 7, null, OffsetDateTime.parse("2026-02-02T09:15:00Z")));
	}

	public List<Movie> topRated(int first) {
		return this.movies.values()
			.stream()
			.filter((movie) -> movie.rating() != null)
			.sorted(Comparator.comparing((Movie movie) -> Objects.requireNonNull(movie.rating()))
				.reversed()
				.thenComparing(Movie::id))
			.limit(Math.max(first, 0))
			.toList();
	}

	public @Nullable Movie movie(String id) {
		return this.movies.get(id);
	}

	public @Nullable Movie movieByTitle(String title) {
		return this.movies.values().stream().filter((movie) -> movie.title().equals(title)).findFirst().orElse(null);
	}

	public List<Movie> movies(@Nullable MovieFilterInput filter) {
		return this.movies.values()
			.stream()
			.filter((movie) -> matches(movie, filter))
			.sorted(Comparator.comparing(Movie::title))
			.toList();
	}

	public List<Object> search(String text) {
		String searched = text.toLowerCase(Locale.ROOT);
		List<Object> results = new ArrayList<>();
		this.movies.values()
			.stream()
			.filter((movie) -> movie.title().toLowerCase(Locale.ROOT).contains(searched))
			.forEach(results::add);
		this.people.values()
			.stream()
			.filter((person) -> person.name().toLowerCase(Locale.ROOT).contains(searched))
			.forEach(results::add);
		return results;
	}

	public @Nullable Object node(String id) {
		Movie movie = this.movies.get(id);
		if (movie != null) {
			return movie;
		}
		Person person = this.people.get(id);
		if (person != null) {
			return person;
		}
		return this.reviews.stream().filter((review) -> review.id().equals(id)).findFirst().orElse(null);
	}

	public List<Movie> allMovies() {
		return List.copyOf(this.movies.values());
	}

	public double communityRating(String movieId) {
		Double rating = this.communityRatings.get(movieId);
		if (rating == null) {
			throw new CommunityRatingUnavailableException();
		}
		return rating;
	}

	public List<Person> people(List<String> ids) {
		return ids.stream().map(this.people::get).filter(Objects::nonNull).toList();
	}

	/**
	 * Returns the reviews that match, newest first.
	 * @param since the earliest instant to include, or {@code null} for every review
	 * @param country the country of a director of the reviewed movie, or {@code null}
	 * @param filter the score and genres to match, or {@code null} for every review
	 * @param first how many reviews to return
	 * @return the matching reviews
	 */
	public List<Review> reviews(@Nullable OffsetDateTime since, @Nullable String country,
			@Nullable ReviewFilterInput filter, int first) {
		int minScore = (filter != null && filter.minScore() != null) ? filter.minScore() : 1;
		return this.reviews.stream()
			.filter((review) -> since == null || !review.createdAt().isBefore(since))
			.filter((review) -> review.score() >= minScore)
			.filter((review) -> {
				Movie movie = movie(review.movieId());
				if (movie == null) {
					return false;
				}
				boolean genreMatches = filter == null || filter.genres() == null
						|| filter.genres().contains(movie.genre());
				boolean countryMatches = country == null || people(movie.directorIds()).stream()
					.anyMatch((person) -> country.equals(person.countryCode()));
				return genreMatches && countryMatches;
			})
			.sorted(Comparator.comparing(Review::createdAt).reversed())
			.limit(first)
			.toList();
	}

	public List<Review> reviewsOf(String movieId) {
		return this.reviews.stream().filter((review) -> review.movieId().equals(movieId)).toList();
	}

	public Review addReview(String movieId, int score, @Nullable String comment) {
		if (!this.movies.containsKey(movieId)) {
			throw new IllegalArgumentException("The catalog lacks a movie with id " + movieId);
		}
		Review review = new Review(nextReviewId(), movieId, score, comment,
				OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
		this.reviews.add(review);
		this.addedReviews.tryEmitNext(review);
		return review;
	}

	public Flux<Review> addedReviews() {
		return this.addedReviews.asFlux();
	}

	private void addMovie(Movie movie, @Nullable Double communityRating) {
		this.movies.put(movie.id(), movie);
		if (communityRating != null) {
			this.communityRatings.put(movie.id(), communityRating);
		}
	}

	private void addPerson(Person person) {
		this.people.put(person.id(), person);
	}

	private String nextReviewId() {
		return "review-" + this.reviewNumbers.incrementAndGet();
	}

	private static boolean matches(Movie movie, @Nullable MovieFilterInput filter) {
		if (filter == null) {
			return true;
		}
		Genre genre = filter.genre();
		Double minRating = filter.minRating();
		Double rating = movie.rating();
		return (genre == null || movie.genre() == genre)
				&& (minRating == null || (rating != null && rating >= minRating));
	}

}
