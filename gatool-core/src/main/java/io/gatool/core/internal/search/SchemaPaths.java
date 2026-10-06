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
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

/**
 * The shortest way from a root type to every type the schema can reach.
 *
 * <p>
 * A search hit names a coordinate such as {@code Studio.name} and says what it returns.
 * It does not say how an operation gets there, and an operation that cannot get there
 * cannot be written: a selection set starts at {@code Query}. This walk answers that
 * once, at startup, so a hit carries the answer and saves the model the round trip of
 * working it out.
 *
 * <p>
 * Breadth-first from the query root and then the mutation root, so a type is reached by
 * the fewest fields, and a type reachable from both is reached through the query. With
 * mutations switched off the walk starts from the query root alone, because a path
 * through a mutation is one the model cannot run. Each step is written with its
 * arguments, because an argument the path requires is part of what the model has to
 * supply:
 *
 * <pre>
 * Query.movie(id: ID!) -&gt; Movie.studio
 * </pre>
 *
 * <p>
 * A field returning an interface or a union reaches each implementation or member through
 * an inline fragment step, because that is the selection an operation writes to read the
 * member's own fields:
 *
 * <pre>
 * Query.node(id: ID!) -&gt; ... on Movie
 * </pre>
 *
 * <p>
 * A deprecated field is a step only while the corpus shows deprecated fields, because a
 * path through a field the corpus hides would route the model through a name it has not
 * read. A type no root can reach does not get a path, which is what happens to a type
 * used in an argument position alone.
 *
 * <p>
 * Public inside {@code io.gatool.core.internal.search}, because
 * {@code io.gatool.core.search.SchemaCorpus} calls it from the public search package. An
 * application does not call this class.
 *
 * @author Željko Kozina
 */
public final class SchemaPaths {

	/** What separates one step from the next. */
	static final String STEP = " -> ";

	// The inline fragment that selects one implementation or member, written as an
	// operation writes it.
	private static final String FRAGMENT = "... on ";

	private SchemaPaths() {
	}

	/**
	 * Walks the schema from its roots.
	 * @param schema the schema GATool validates against
	 * @param includeDeprecated whether a deprecated field is a step, from
	 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
	 * @param allowMutations whether the mutation root is a root of the walk, from
	 * {@code gatool.dev.experimental.dynamic-operations.allow-mutations}
	 * @return the path to each reachable type, by type name, where a root type itself
	 * maps to an empty string
	 */
	public static Map<String, String> of(GraphQLSchema schema, boolean includeDeprecated, boolean allowMutations) {
		Map<String, String> paths = new LinkedHashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		for (GraphQLObjectType root : rootsOf(schema, allowMutations)) {
			reach(root.getName(), "", paths, queue);
		}
		while (!queue.isEmpty()) {
			String typeName = queue.removeFirst();
			if (schema.getType(typeName) instanceof GraphQLFieldsContainer container) {
				walkFields(schema, typeName, container, includeDeprecated, paths, queue);
			}
		}
		return Map.copyOf(paths);
	}

	// Two passes over the fields: the types a field returns outright first, then the
	// implementations and members behind an inline fragment. A type a sibling field
	// returns is then reached through that field, whatever the declaration order:
	// Review through Query.reviews instead of Query.node -> ... on Review, which would
	// ask the model for an id it lacks.
	private static void walkFields(GraphQLSchema schema, String typeName, GraphQLFieldsContainer container,
			boolean includeDeprecated, Map<String, String> paths, Deque<String> queue) {
		// A name joins the queue only after its path is recorded, so the path is there.
		// Objects.requireNonNull says that invariant outright.
		String pathSoFar = Objects.requireNonNull(paths.get(typeName), typeName + " reached the queue without a path");
		for (boolean viaFragment : new boolean[] { false, true }) {
			for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
				// The corpus and the type detail leave a deprecated field out under the
				// same setting, so a hint routed through one would name a field the
				// model has not read.
				if (field.isDeprecated() && !includeDeprecated) {
					continue;
				}
				String step = stepOf(pathSoFar, typeName, field, includeDeprecated);
				reachedBy(schema, field, step, viaFragment)
					.forEach((reachedType, path) -> reach(reachedType, path, paths, queue));
			}
		}
	}

	// Records the path to a type the first time it is reached and queues it for its own
	// walk, so the first path found, which is the shortest, is the one kept.
	private static void reach(String typeName, String path, Map<String, String> paths, Deque<String> queue) {
		if (paths.putIfAbsent(typeName, path) == null) {
			queue.add(typeName);
		}
	}

	private static List<GraphQLObjectType> rootsOf(GraphQLSchema schema, boolean allowMutations) {
		List<GraphQLObjectType> roots = new ArrayList<>();
		if (schema.getQueryType() != null) {
			roots.add(schema.getQueryType());
		}
		if (allowMutations && schema.getMutationType() != null) {
			roots.add(schema.getMutationType());
		}
		return roots;
	}

	// Returns the types a field leads on to, each with the path that reaches it. With
	// viaFragment false that is the object or interface the field returns. With it true,
	// that is each implementation of a returned interface and each member of a returned
	// union, behind an inline fragment step.
	//
	// The implementations come with the interface, because an object reached through an
	// interface field alone would otherwise be missing from the map and print like a root
	// field: a Relay Movie behind Query.node would come out as "{ movie { title } }". A
	// union member is written the same way, because the selection an operation makes is
	// the same, and a path that stopped at the field would read as though the member's
	// fields sat directly under it.
	private static Map<String, String> reachedBy(GraphQLSchema schema, GraphQLFieldDefinition field, String step,
			boolean viaFragment) {
		GraphQLType named = GraphQLTypeUtil.unwrapAll(field.getType());
		if (!(named instanceof GraphQLNamedType namedType) || namedType.getName().startsWith("__")) {
			return Map.of();
		}
		Map<String, String> reached = new LinkedHashMap<>();
		switch (schema.getType(namedType.getName())) {
			case GraphQLUnionType union when viaFragment -> union.getTypes()
				.forEach((member) -> reached.put(member.getName(), step + STEP + FRAGMENT + member.getName()));
			case GraphQLInterfaceType intf -> {
				if (viaFragment) {
					schema.getImplementations(intf)
						.forEach((each) -> reached.put(each.getName(), step + STEP + FRAGMENT + each.getName()));
				}
				else {
					reached.put(intf.getName(), step);
				}
			}
			case GraphQLFieldsContainer container when !viaFragment -> reached.put(container.getName(), step);
			case null, default -> {
				// A scalar or an enum ends the walk, and a field cannot return an input
				// object.
			}
		}
		return reached;
	}

	private static String stepOf(String pathSoFar, String typeName, GraphQLFieldDefinition field,
			boolean includeDeprecated) {
		// The defaults stay out, because a path says what the model has to supply on the
		// way and introspectType is where a type is read in full. A deprecated argument
		// follows the setting, the way the corpus and the type detail write it.
		String step = typeName + "." + field.getName()
				+ SdlWriter.arguments(field.getArguments(), false, includeDeprecated);
		return pathSoFar.isEmpty() ? step : pathSoFar + STEP + step;
	}

	/**
	 * Returns the path to one type.
	 * @param paths the walk of this schema
	 * @param typeName the type to reach
	 * @return the path, or {@code null} where the type is a root or no root reaches it,
	 * which are the two cases where a hit leaves the path out
	 */
	public static @Nullable String to(Map<String, String> paths, String typeName) {
		String path = paths.get(typeName);
		return (path == null || path.isEmpty()) ? null : path;
	}

}
