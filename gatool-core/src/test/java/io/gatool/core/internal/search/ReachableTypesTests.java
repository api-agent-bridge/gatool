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

import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which types the search tools may offer a model.
 *
 * <p>
 * The rule is one sentence, and almost every case below is a shape where the obvious
 * reading of it gets the answer wrong. A type belongs here when a valid query or mutation
 * could name it, so a type without a path to it can still belong, and a type the schema
 * plainly declares can still be out of reach. Both mistakes cost the model something
 * real: a type wrongly left out makes {@code introspectType} deny that it exists, and a
 * type wrongly offered spends the search budget on a coordinate that cannot answer.
 */
class ReachableTypesTests {

	@Test
	void of_aTypeReachableOnlyThroughTheSubscriptionRoot_shouldStayOut() {
		assertThat(reachable("""
				type Query { movie: String }
				type ReviewEvent { id: ID! score: Int! }
				type Subscription { reviewAdded: ReviewEvent! }
				""")).contains("Query").doesNotContain("Subscription", "ReviewEvent");
	}

	@Test
	void of_anInterfaceNoFieldReturns_shouldBeReached() {
		// Its fields are the ones a model reads on the objects implementing it, so an
		// interface without a path to it is still a type the model needs.
		assertThat(reachable("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! title: String! }
				type Query { movie: Movie }
				""")).contains("Node");
	}

	@Test
	void of_anImplementationNoFieldReturns_shouldBeReached() {
		// "... on Movie { rating }" under a field returning Node selects it.
		assertThat(reachable("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! rating: Float }
				type Query { anything: Node }
				""")).contains("Movie");
	}

	@Test
	void of_aUnionNoFieldReturns_shouldBeReachedThroughAMemberThatIs() {
		// "... on Promotable" spreads wherever the union overlaps the type in hand, and
		// Article gives it that overlap with Node. A walk that goes forward from a union
		// to its members without going back would leave Promotable out, and
		// introspectType would deny that it exists.
		assertThat(reachable("""
				interface Node { id: ID! }
				type Article implements Node { id: ID! }
				type Advert { slot: String }
				union Promotable = Article | Advert
				type Query { node: Node }
				""")).contains("Promotable");
	}

	@Test
	void of_aUnionReachedThroughOneMember_shouldLeaveItsOtherMembersOut() {
		// The query returns Node, and Advert does not implement it, so a value the
		// operation selected cannot be one at runtime: the spread validates and answers
		// {} every time. Offering Advert.slot would promise data that does not arrive.
		assertThat(reachable("""
				interface Node { id: ID! }
				type Article implements Node { id: ID! }
				type Advert { slot: String budget: Money }
				type Money { amount: Int }
				union Promotable = Article | Advert
				type Query { node: Node }
				""")).doesNotContain("Advert", "Money");
	}

	@Test
	void of_aUnionAFieldReturns_shouldReachEveryMember() {
		// The other way round: here the field hands the model the union itself, so every
		// member really can be the runtime type.
		assertThat(reachable("""
				type Article { id: ID! }
				type Advert { slot: String }
				union Promotable = Article | Advert
				type Query { promoted: Promotable }
				""")).contains("Promotable", "Article", "Advert");
	}

	@Test
	void of_aTypeNamedOnlyByADirectiveAnOperationMayWrite_shouldBeReached() {
		// Nothing in the field graph leads to FilterInput, and a model writing @filter
		// has to know what it holds. graphql-java validates such a document, so denying
		// the type would leave the model unable to fill in a directive the schema offers.
		assertThat(reachable("""
				directive @filter(by: FilterInput!, mode: Mode) on FIELD
				input FilterInput { term: String! }
				enum Mode { LOOSE STRICT }
				type Query { hits: [String!]! }
				""")).contains("FilterInput", "Mode");
	}

	@Test
	void of_aTypeNamedOnlyByASchemaDirective_shouldStayOut() {
		// A directive on FIELD_DEFINITION is written in the schema document, so no
		// operation can pass it anything.
		assertThat(reachable("""
				directive @tag(spec: TagInput!) on FIELD_DEFINITION
				input TagInput { name: String! }
				type Query { hits: [String!]! }
				""")).doesNotContain("TagInput");
	}

	@Test
	void of_aTypeNamedOnlyByASubscriptionDirective_shouldStayOut() {
		// SUBSCRIPTION is an executable location in GraphQL, and GATool does not run a
		// subscription, so a document GATool runs cannot write a directive only a
		// subscription can carry. Counting it would readmit the subscription subgraph.
		assertThat(reachable("""
				directive @live(cfg: LiveInput!) on SUBSCRIPTION
				input LiveInput { every: Int kind: LiveKind }
				enum LiveKind { PUSH POLL }
				type Query { hello: Int }
				type Subscription { ticks: Int }
				""")).doesNotContain("LiveInput", "LiveKind");
	}

	@Test
	void of_aDirectiveValidOnASubscriptionAndAField_shouldBeReachedThroughTheField() {
		// The exclusion above is by location, so this one still qualifies.
		assertThat(reachable("""
				directive @live(cfg: LiveInput!) on SUBSCRIPTION | FIELD
				input LiveInput { every: Int }
				type Query { hello: Int }
				type Subscription { ticks: Int }
				""")).contains("LiveInput");
	}

	@Test
	void of_anInputObjectNestedInAnother_shouldBeReachedThroughIt() {
		// introspectType is how a model reads an input object before it passes one, and
		// the inner type is a call of its own.
		assertThat(reachable("""
				input Paging { sort: Sort }
				input Sort { field: SortField }
				enum SortField { NAME YEAR }
				type Query { movies(paging: Paging): [String!]! }
				""")).contains("Paging", "Sort", "SortField");
	}

	@Test
	void of_aRecursiveInputObject_shouldFinish() {
		assertThat(reachable("""
				input Filter { all: [Filter!] term: String }
				type Query { movies(filter: Filter): [String!]! }
				""")).contains("Filter");
	}

	@Test
	void of_aMutationOnlySchema_shouldWalkFromTheMutationRoot() {
		assertThat(reachable("""
				type Query { unused: String }
				type Receipt { id: ID! }
				type Mutation { pay: Receipt }
				""")).contains("Mutation", "Receipt");
	}

	@Test
	void of_theMutationRootWithMutationsOff_shouldStayOutWithEverythingOnlyItReaches() {
		// executeGraphql refuses a mutation while the switch is off, so the mutation root
		// is as unreachable as the subscription root: a type offered here is one the
		// model writes an operation against.
		String sdl = """
				type Query { movie: Movie }
				type Movie { id: ID! }
				input ReviewInput { score: Int! }
				type ReviewPayload { review: Movie }
				type Mutation { addReview(input: ReviewInput!): ReviewPayload }
				""";

		assertThat(reachable(sdl, false)).contains("Query", "Movie")
			.doesNotContain("Mutation", "ReviewInput", "ReviewPayload");
		assertThat(reachable(sdl, true)).contains("Mutation", "ReviewInput", "ReviewPayload");
	}

	@Test
	void of_aTypeNamedOnlyByAMutationDirective_shouldFollowTheSwitch() {
		// The same rule as the subscription location: with mutations off, a directive
		// only a mutation can carry stays out of every document GATool runs.
		String sdl = """
				directive @audit(tag: AuditTag!) on MUTATION
				input AuditTag { name: String! }
				type Query { hello: Int }
				type Mutation { touch: Int }
				""";

		assertThat(reachable(sdl, false)).doesNotContain("AuditTag");
		assertThat(reachable(sdl, true)).contains("AuditTag");
	}

	private static Iterable<String> reachable(String sdl) {
		return reachable(sdl, true);
	}

	private static Iterable<String> reachable(String sdl, boolean allowMutations) {
		return ReachableTypes.of(SdlSchemaFactory.schemaFrom(sdl, "reachable-types-test.graphqls"), false,
				allowMutations);
	}

}
