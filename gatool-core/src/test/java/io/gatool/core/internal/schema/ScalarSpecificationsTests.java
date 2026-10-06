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

package io.gatool.core.internal.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spellings one specification arrives under.
 *
 * <p>
 * Under an exact string match, a schema citing RFC 4122 at the datatracker host would
 * read as a page GATool has yet to see, although it is the page {@code tools.ietf.org}
 * redirects to: {@code https://tools.ietf.org/html/rfc4122} answers 301 to
 * {@code https://datatracker.ietf.org/doc/html/rfc4122}, and
 * {@code scalars.graphql.org/chillicream/long} answers 200 with and without the
 * {@code .html} suffix, while a trailing slash answers 404.
 */
class ScalarSpecificationsTests {

	@Test
	void of_theThreeSpellingsOfAnRfc_shouldFindTheSameSpecification() {
		assertThat(ScalarSpecifications.of("https://tools.ietf.org/html/rfc4122")).isNotNull();
		assertThat(ScalarSpecifications.of("https://datatracker.ietf.org/doc/html/rfc4122")).isNotNull();
		assertThat(ScalarSpecifications.of("https://www.rfc-editor.org/rfc/rfc4122")).isNotNull();
		assertThat(ScalarSpecifications.of("http://tools.ietf.org/html/rfc4122")).isNotNull();
	}

	@Test
	void of_aRegistryUrlWithOrWithoutItsSuffix_shouldFindTheSameSpecification() {
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time")).isNotNull();
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time.html")).isNotNull();
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time/")).isNotNull();
	}

	@Test
	void of_aHostInAnotherCase_shouldFindTheSameSpecification() {
		assertThat(ScalarSpecifications.of("HTTPS://Scalars.GraphQL.org/andimarek/date-time")).isNotNull();
	}

	@Test
	void of_aPathInAnotherCase_shouldFindNothing() {
		// The path keeps its case, because the registry serves AlexandreCarlton and
		// answers 404 for alexandrecarlton, so two spellings are two pages there.
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/ANDIMAREK/date-time")).isNull();
	}

	@Test
	void of_aPageGAToolHasYetToRead_shouldFindNothing() {
		assertThat(ScalarSpecifications.of("https://example.com/our-own-timestamp")).isNull();
		assertThat(ScalarSpecifications.of(null)).isNull();
	}

	@Test
	void of_anotherRfc_shouldFindNothing() {
		// The alias brings the spellings of one RFC together, and leaves each RFC its
		// own.
		assertThat(ScalarSpecifications.of("https://tools.ietf.org/html/rfc9562")).isNull();
	}

	@Test
	void of_anRfcCitedBySection_shouldFindTheSpecification() {
		// A schema citing the section it means is citing the same page.
		assertThat(ScalarSpecifications.of("https://datatracker.ietf.org/doc/html/rfc4122#section-4.1.2")).isNotNull();
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time.html#sec-Input"))
			.isNotNull();
	}

	@Test
	void of_aUrlCarryingAQueryString_shouldFindTheSpecification() {
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time?utm_source=schema"))
			.isNotNull();
	}

	@Test
	void of_theSuffixInAnotherCase_shouldFindTheSpecification() {
		assertThat(ScalarSpecifications.of("https://scalars.graphql.org/andimarek/date-time.HTML")).isNotNull();
	}

	@Test
	void of_theSamePageOverHttp_shouldFindTheSpecification() {
		// The table is written with https, and a schema written before the registry
		// served it cites the same page.
		assertThat(ScalarSpecifications.of("http://scalars.graphql.org/andimarek/date-time")).isNotNull();
	}

	@Test
	void normalise_aUrlWithSurroundingSpace_shouldStillMatch() {
		assertThat(ScalarSpecifications.normalise("  https://tools.ietf.org/html/rfc4122  "))
			.isEqualTo(ScalarSpecifications.normalise("https://tools.ietf.org/html/rfc4122"));
	}

}
