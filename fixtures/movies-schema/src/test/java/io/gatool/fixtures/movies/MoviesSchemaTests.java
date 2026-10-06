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

package io.gatool.fixtures.movies;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Each test names a schema element that the starter's own tests rely on, so a change to
// this fixture that removed one would fail here first.
class MoviesSchemaTests {

	private final GraphQLSchema schema = SchemaGenerator.createdMockedSchema(MoviesSchema.sdl());

	@Test
	void dateTime_customScalar_shouldCarrySpecifiedByUrl() {
		assertThat(this.schema.getType("DateTime")).isInstanceOfSatisfying(GraphQLScalarType.class,
				(scalar) -> assertThat(scalar.getSpecifiedByUrl())
					.isEqualTo("https://scalars.graphql.org/andimarek/date-time"));
	}

	@Test
	void countryCode_customScalar_shouldLackSpecifiedByUrl() {
		assertThat(this.schema.getType("CountryCode")).isInstanceOfSatisfying(GraphQLScalarType.class,
				(scalar) -> assertThat(scalar.getSpecifiedByUrl()).isNull());
	}

	@Test
	void genre_enum_shouldListFourValues() {
		assertThat(this.schema.getType("Genre")).isInstanceOfSatisfying(GraphQLEnumType.class,
				(genre) -> assertThat(genre.getValues()).hasSize(4));
	}

	@Test
	void movieFilterInput_inputObject_shouldBeOrdinaryInput() {
		assertThat(this.schema.getType("MovieFilterInput")).isInstanceOfSatisfying(GraphQLInputObjectType.class,
				(input) -> assertThat(input.isOneOf()).isFalse());
	}

	@Test
	void movieLookupInput_oneOfInput_shouldBeOneOf() {
		assertThat(this.schema.getType("MovieLookupInput")).isInstanceOfSatisfying(GraphQLInputObjectType.class,
				(input) -> assertThat(input.isOneOf()).isTrue());
	}

	@Test
	void searchResult_union_shouldHoldMovieAndPerson() {
		assertThat(this.schema.getType("SearchResult")).isInstanceOfSatisfying(GraphQLUnionType.class,
				(union) -> assertThat(union.getTypes()).extracting(GraphQLNamedType::getName)
					.containsExactly("Movie", "Person"));
	}

	@Test
	void node_interface_shouldHaveThreeImplementations() {
		GraphQLInterfaceType node = (GraphQLInterfaceType) this.schema.getType("Node");
		assertThat(node).isNotNull();
		assertThat(this.schema.getImplementations(node)).extracting(GraphQLObjectType::getName)
			.containsExactlyInAnyOrder("Movie", "Person", "Review");
	}

	@Test
	void movies_relayConnection_shouldTakeFirstAndAfter() {
		var movies = this.schema.getQueryType().getFieldDefinition("movies");
		assertThat(movies.getArgument("first")).isNotNull();
		assertThat(movies.getArgument("after")).isNotNull();
		assertThat(this.schema.getObjectType("PageInfo").getFieldDefinitions()).extracting("name")
			.containsExactly("hasPreviousPage", "hasNextPage", "startCursor", "endCursor");
	}

	@Test
	void allMovies_deprecatedField_shouldBeDeprecated() {
		assertThat(this.schema.getQueryType().getFieldDefinition("allMovies").isDeprecated()).isTrue();
	}

	@Test
	void addReview_mutation_shouldExist() {
		assertThat(this.schema.getMutationType()).isNotNull();
		assertThat(this.schema.getMutationType().getFieldDefinition("addReview")).isNotNull();
	}

	@Test
	void communityRating_partialResultField_shouldBeNullable() {
		var communityRating = this.schema.getObjectType("Movie").getFieldDefinition("communityRating");
		assertThat(communityRating.getType()).isNotInstanceOf(GraphQLNonNull.class);
	}

	@Test
	void reviewAdded_subscription_shouldExist() {
		assertThat(this.schema.getSubscriptionType()).isNotNull();
		assertThat(this.schema.getSubscriptionType().getFieldDefinition("reviewAdded")).isNotNull();
	}

	@Test
	void topRatedMovies_listField_shouldReturnNonNullMovies() {
		var topRatedMovies = this.schema.getQueryType().getFieldDefinition("topRatedMovies");
		assertThat(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(this.schema.getObjectType("Movie")))))
			.hasToString(topRatedMovies.getType().toString());
	}

}
