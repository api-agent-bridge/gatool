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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import graphql.introspection.Introspection.DirectiveLocation;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

/**
 * The types an operation can name, walking out from the query and mutation roots.
 *
 * <p>
 * The search tools offer a model coordinates and type declarations, and everything they
 * offer has to be something a tool call can actually use. A schema holds types that no
 * query or mutation reaches: the fields of a subscription root, and every type reachable
 * only through one. Indexed, those would come back from {@code searchSchema} looking like
 * any other coordinate, because {@code SchemaPaths} seeds its roots from the query and
 * mutation types alone and so leaves them without a path, which is how a root field
 * prints. The model would then write an operation against a field that cannot be
 * selected.
 *
 * <p>
 * {@code SchemaPaths} answers a different question and cannot serve as this one. It
 * records the way to a type so a model can write the selection that reaches it. A type
 * without such a way still has selectable fields. An interface that no field returns
 * declares the fields a model reads on the objects implementing it, and an object that no
 * field returns is still named by {@code ... on Movie} under a field returning an
 * interface it implements. Both are missing from the path map and both belong here, so
 * this walk adds the two edges that one leaves out:
 *
 * <ul>
 * <li>an object reaches the interfaces it implements,</li>
 * <li>an interface reaches the objects implementing it.</li>
 * </ul>
 *
 * <p>
 * The walk follows arguments as well as fields, because {@code introspectType} is how a
 * model reads an input object or an enum before it writes a value, and those types are
 * reached through an argument. A directive an operation may write carries arguments for
 * the same reason, so those are seeded too, and only for a directive GraphQL allows in an
 * operation. A directive declared {@code on FIELD_DEFINITION} belongs to the schema, and
 * an operation cannot pass it anything.
 *
 * @author Željko Kozina
 */
public final class ReachableTypes {

	// The locations a query document GATool runs can write a directive in. Every other
	// location belongs to the schema document, where a model does not write anything.
	//
	// SUBSCRIPTION is an executable location in GraphQL and stays out of this set,
	// because GATool refuses a subscription everywhere: a tool call returns a single
	// result. A document GATool runs cannot write a directive only a subscription can
	// carry, and seeding from its arguments readmits the subscription subgraph this class
	// exists to keep out. A directive valid on a subscription and somewhere else still
	// qualifies through that other location. MUTATION joins the set only while mutations
	// are allowed, for the same reason.
	private static final Set<DirectiveLocation> QUERY_LOCATIONS = Set.of(DirectiveLocation.QUERY,
			DirectiveLocation.FIELD, DirectiveLocation.FRAGMENT_DEFINITION, DirectiveLocation.FRAGMENT_SPREAD,
			DirectiveLocation.INLINE_FRAGMENT, DirectiveLocation.VARIABLE_DEFINITION);

	private static final Set<DirectiveLocation> QUERY_AND_MUTATION_LOCATIONS = Set.of(DirectiveLocation.QUERY,
			DirectiveLocation.MUTATION, DirectiveLocation.FIELD, DirectiveLocation.FRAGMENT_DEFINITION,
			DirectiveLocation.FRAGMENT_SPREAD, DirectiveLocation.INLINE_FRAGMENT,
			DirectiveLocation.VARIABLE_DEFINITION);

	private ReachableTypes() {
	}

	/**
	 * Returns every type a query or, while mutations are allowed, a mutation can name.
	 * @param schema the schema GATool validates against
	 * @param includeDeprecated whether a deprecated field is a way through to the type it
	 * returns, from
	 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
	 * @param allowMutations whether the mutation root and everything behind it is
	 * reached, from {@code gatool.dev.experimental.dynamic-operations.allow-mutations}
	 * @return the type names, which hold the built-in scalars of {@code @skip} and
	 * {@code @include} even for a schema without a query or a mutation root
	 */
	// The mutation root follows the switch, because executeGraphql refuses a mutation
	// while it is off: a mutation coordinate the search offers would be one the model
	// writes an operation against and then reads a refusal for.
	public static Set<String> of(GraphQLSchema schema, boolean includeDeprecated, boolean allowMutations) {
		Set<String> reached = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		GraphQLObjectType[] roots = allowMutations
				? new GraphQLObjectType[] { schema.getQueryType(), schema.getMutationType() }
				: new GraphQLObjectType[] { schema.getQueryType() };
		for (GraphQLObjectType root : roots) {
			if (root != null) {
				reach(root.getName(), reached, queue);
			}
		}
		for (String argumentType : collectExecutableDirectiveArgumentTypes(schema, allowMutations)) {
			reach(argumentType, reached, queue);
		}
		while (!queue.isEmpty()) {
			GraphQLType type = schema.getType(queue.removeFirst());
			// Every name in the queue came off a type the schema holds, so this lookup
			// answers. A name the schema does not know cannot reach anything either way.
			if (type == null) {
				continue;
			}
			for (String next : collectTypesReachedFrom(schema, type, includeDeprecated)) {
				reach(next, reached, queue);
			}
		}
		addUnionsOverAReachedMember(schema, reached);
		return Set.copyOf(reached);
	}

	// Marks the type as reached and queues it for its own walk, once.
	private static void reach(String typeName, Set<String> reached, Deque<String> queue) {
		if (reached.add(typeName)) {
			queue.add(typeName);
		}
	}

	// Adds every union that has a member the walk already reached.
	//
	// The union half of the back-edge the walk adds for interfaces. GraphQL lets "... on
	// ThatUnion" be spread wherever the union overlaps the type in hand, so one reachable
	// member makes the union itself nameable. Without this, introspectType would answer
	// that a union no field returns does not exist.
	//
	// This adds the union alone. A member nothing else reaches cannot be the runtime type
	// of a value the operation selected, so its fields would promise data that does not
	// arrive: the spread validates and answers {} every time. A union some field does
	// return is reached by the walk above, and there its members are genuinely selectable
	// and do come with it. One pass is enough, because a union holds objects alone and
	// adding its name cannot open anything further.
	private static void addUnionsOverAReachedMember(GraphQLSchema schema, Set<String> reached) {
		for (GraphQLNamedType type : schema.getAllTypesAsList()) {
			if (type instanceof GraphQLUnionType union && !type.getName().startsWith("__")
					&& union.getTypes().stream().anyMatch((member) -> reached.contains(member.getName()))) {
				reached.add(union.getName());
			}
		}
	}

	// Returns the argument types of every directive an operation can write, which seed
	// the walk.
	//
	// A model writing a document may write any directive the schema declares for an
	// operation, and it needs the argument types to fill one in. Nothing in the field
	// graph leads to them, so they are seeds of their own: without them @filter(by:
	// FilterInput!) would leave FilterInput out of the corpus, and introspectType would
	// answer that it does not exist while executeGraphql accepts a document passing one.
	private static Set<String> collectExecutableDirectiveArgumentTypes(GraphQLSchema schema, boolean allowMutations) {
		Set<DirectiveLocation> locations = allowMutations ? QUERY_AND_MUTATION_LOCATIONS : QUERY_LOCATIONS;
		Set<String> seeds = new LinkedHashSet<>();
		for (GraphQLDirective directive : schema.getDirectives()) {
			if (directive.validLocations().stream().anyMatch(locations::contains)) {
				directive.getArguments()
					.forEach((argument) -> addUnlessReserved(seeds, GraphQLTypeUtil.unwrapAll(argument.getType())));
			}
		}
		return seeds;
	}

	// Returns the type names one step out from a type: the interfaces an object
	// implements, the objects implementing an interface, a union's members, and the types
	// its fields and their arguments name. An enum or a scalar ends the walk.
	private static Set<String> collectTypesReachedFrom(GraphQLSchema schema, GraphQLType type,
			boolean includeDeprecated) {
		Set<String> next = new LinkedHashSet<>();
		switch (type) {
			case GraphQLObjectType object -> {
				object.getInterfaces().forEach((each) -> addUnlessReserved(next, each));
				addFieldTypes(object, next, includeDeprecated);
			}
			case GraphQLInterfaceType intf -> {
				// A model selects a member's own fields through an inline fragment, so
				// every implementation is as reachable as the interface itself.
				schema.getImplementations(intf).forEach((each) -> addUnlessReserved(next, each));
				addFieldTypes(intf, next, includeDeprecated);
			}
			case GraphQLUnionType union -> union.getTypes().forEach((each) -> addUnlessReserved(next, each));
			case GraphQLInputObjectType inputObject -> {
				for (GraphQLInputObjectField field : inputObject.getFieldDefinitions()) {
					addUnlessReserved(next, GraphQLTypeUtil.unwrapAll(field.getType()));
				}
			}
			default -> {
				// An enum or a scalar ends the walk.
			}
		}
		return next;
	}

	// Adds the type each field of the container returns and the types of its arguments,
	// passing over a deprecated field while includeDeprecated is false.
	//
	// A deprecated field the corpus leaves out does not open a way through either: the
	// model does not see it, so a type only that field returns is one it cannot reach.
	private static void addFieldTypes(GraphQLFieldsContainer container, Set<String> next, boolean includeDeprecated) {
		for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
			if (field.isDeprecated() && !includeDeprecated) {
				continue;
			}
			addUnlessReserved(next, GraphQLTypeUtil.unwrapAll(field.getType()));
			for (GraphQLArgument argument : field.getArguments()) {
				addUnlessReserved(next, GraphQLTypeUtil.unwrapAll(argument.getType()));
			}
		}
	}

	// Introspection types are reserved by the specification and left out of everything
	// the search tools publish, so the walk stops at them the way the corpus does.
	private static void addUnlessReserved(Set<String> next, GraphQLType type) {
		if (type instanceof GraphQLNamedType named && !named.getName().startsWith("__")) {
			next.add(named.getName());
		}
	}

}
