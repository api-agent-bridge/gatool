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

package io.gatool.core.internal.search;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "reach it from" hint a search hit carries: the shortest way from a root to the type
 * that owns the hit, written as the steps an operation selects.
 *
 * <p>
 * Every case is a schema shape where a plain walk gets the hint wrong: a deprecated
 * shortcut the model cannot see, an object behind an interface or a union that needs an
 * inline fragment, a mutation the model cannot run.
 */
class SchemaPathsTests {

	@Test
	void of_aRootType_shouldMapToAnEmptyPathThatToReadsAsNone() {
		Map<String, String> paths = paths("""
				type Query { movie: Movie }
				type Movie { title: String }
				""", false, true);

		// A root field is where an operation starts, so a hit on one comes without a
		// hint.
		assertThat(paths).containsEntry("Query", "");
		assertThat(SchemaPaths.to(paths, "Query")).isNull();
		assertThat(SchemaPaths.to(paths, "Movie")).isEqualTo("Query.movie");
	}

	@Test
	void of_aTypeReachedTwoWays_shouldTakeTheFewestSteps() {
		Map<String, String> paths = paths("""
				type Query { movie: Movie studio: Studio }
				type Movie { studio: Studio }
				type Studio { name: String }
				""", false, true);

		assertThat(SchemaPaths.to(paths, "Studio")).isEqualTo("Query.studio");
	}

	@Test
	void of_aStepWithArguments_shouldWriteThemWithoutTheirDefaults() {
		// What the model has to supply on the way, and introspectType is where the
		// defaults are read.
		Map<String, String> paths = paths("""
				type Query { movies(first: Int = 10, genre: Genre): [Movie!]! }
				type Movie { title: String }
				enum Genre { DRAMA }
				""", false, true);

		assertThat(SchemaPaths.to(paths, "Movie")).isEqualTo("Query.movies(first: Int, genre: Genre)");
	}

	@Test
	void of_aTypeReachedThroughAnInterfaceField_shouldEndInAnInlineFragmentStep() {
		// Nothing returns Movie itself. A walk that stopped at the interface would leave
		// Movie out of the map, and it would print like a root field.
		Map<String, String> paths = paths("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! studio: Studio }
				type Studio { name: String }
				type Query { node(id: ID!): Node }
				""", false, true);

		assertThat(SchemaPaths.to(paths, "Node")).isEqualTo("Query.node(id: ID!)");
		assertThat(SchemaPaths.to(paths, "Movie")).isEqualTo("Query.node(id: ID!) -> ... on Movie");
		assertThat(SchemaPaths.to(paths, "Studio")).isEqualTo("Query.node(id: ID!) -> ... on Movie -> Movie.studio");
	}

	@Test
	void of_aUnionMember_shouldEndInAnInlineFragmentStep() {
		// The selection an operation writes is the same as for an interface, and a path
		// that stopped at the field would read as though the member's fields sat under
		// it.
		Map<String, String> paths = paths("""
				type Movie { title: String }
				type Person { name: String }
				union SearchResult = Movie | Person
				type Query { search(text: String!): [SearchResult!]! }
				""", false, true);

		assertThat(SchemaPaths.to(paths, "Movie")).isEqualTo("Query.search(text: String!) -> ... on Movie");
		assertThat(SchemaPaths.to(paths, "Person")).isEqualTo("Query.search(text: String!) -> ... on Person");
		// A hit names a field of an object or an interface, so the union itself stays
		// out of the map.
		assertThat(paths).doesNotContainKey("SearchResult");
	}

	@Test
	void of_aTypeASiblingFieldReturnsOutright_shouldTakeThatFieldWhateverTheDeclarationOrder() {
		// node is declared first, and a walk in declaration order would reach Movie
		// through it, which asks the model for an id it lacks when movies would list
		// them.
		Map<String, String> paths = paths("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! }
				union Anything = Movie
				type Query { node(id: ID!): Node anything: Anything movies: [Movie!]! }
				""", false, true);

		assertThat(SchemaPaths.to(paths, "Movie")).isEqualTo("Query.movies");
	}

	@Test
	void of_aDeprecatedShortcut_shouldBeAStepOnlyWhileDeprecatedFieldsAreShown() {
		// The corpus hides the field by default, so a hint through it would name a
		// field the model has not read.
		String sdl = """
				type Query {
				  latest: Movie @deprecated(reason: "Use movies.")
				  movies: [MovieEdge!]!
				}
				type MovieEdge { node: Movie! }
				type Movie { title: String! }
				""";

		assertThat(SchemaPaths.to(paths(sdl, false, true), "Movie")).isEqualTo("Query.movies -> MovieEdge.node");
		assertThat(SchemaPaths.to(paths(sdl, true, true), "Movie")).isEqualTo("Query.latest");
	}

	@Test
	void of_aTypeReachableOnlyThroughADeprecatedField_shouldHaveNoPathByDefault() {
		Map<String, String> paths = paths("""
				type Query { legacy: LegacyReport @deprecated(reason: "Gone.") }
				type LegacyReport { rows: Int! }
				""", false, true);

		assertThat(paths).doesNotContainKey("LegacyReport");
	}

	@Test
	void of_theMutationRoot_shouldBeARootOnlyWhileMutationsAreAllowed() {
		// A path through a mutation is one the model cannot run while the switch is off,
		// so the walk starts from the query root alone and takes the longer way.
		String sdl = """
				type Query { movies: [MovieEdge!]! }
				type MovieEdge { node: Movie! }
				type Movie { title: String! }
				type Mutation { addMovie(title: String!): Movie }
				""";

		assertThat(SchemaPaths.to(paths(sdl, false, true), "Movie")).isEqualTo("Mutation.addMovie(title: String!)");
		assertThat(SchemaPaths.to(paths(sdl, false, false), "Movie")).isEqualTo("Query.movies -> MovieEdge.node");
		assertThat(paths(sdl, false, false)).doesNotContainKey("Mutation");
	}

	@Test
	void of_aTypeUsedInAnArgumentAlone_shouldHaveNoPath() {
		Map<String, String> paths = paths("""
				input MovieFilter { genre: String }
				type Query { movies(filter: MovieFilter): [Movie!]! }
				type Movie { title: String }
				""", false, true);

		assertThat(paths).doesNotContainKey("MovieFilter");
	}

	@Test
	void of_anySchema_shouldLeaveTheTypesTheSpecificationReservesOut() {
		Map<String, String> paths = paths("type Query { ping: String }", false, true);

		assertThat(paths.keySet()).noneMatch((name) -> name.startsWith("__"));
	}

	private static Map<String, String> paths(String sdl, boolean includeDeprecated, boolean allowMutations) {
		return SchemaPaths.of(SdlSchemaFactory.schemaFrom(sdl, "schema-paths-test.graphqls"), includeDeprecated,
				allowMutations);
	}

}
