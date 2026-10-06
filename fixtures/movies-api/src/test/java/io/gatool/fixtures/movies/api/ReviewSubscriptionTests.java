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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureGraphQlTester
class ReviewSubscriptionTests {

	@Autowired
	private ExecutionGraphQlServiceTester graphQlTester;

	@Autowired
	private MovieCatalog catalog;

	@Test
	void reviewAdded_reviewForTheMovie_shouldSendTheReview() throws Exception {
		CompletableFuture<Integer> firstScore = this.graphQlTester.document("""
				subscription ReviewAdded {
				  reviewAdded(movieId: "movie-5") { score }
				}
				""").executeSubscription().toFlux("reviewAdded.score", Integer.class).next().toFuture();

		this.catalog.addReview("movie-5", 6, "Quiet, but it stays with you.");

		assertThat(firstScore.get(5, TimeUnit.SECONDS)).isEqualTo(6);
	}

}
