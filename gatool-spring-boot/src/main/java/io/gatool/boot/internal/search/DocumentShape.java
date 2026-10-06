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

package io.gatool.boot.internal.search;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import org.jspecify.annotations.Nullable;

/**
 * The size of a document a model wrote: how deep it nests, how many fields it selects,
 * how many aliases it uses, and whether it asks for introspection.
 *
 * <p>
 * The counts come from a walk of the AST, with every fragment spread expanded where it is
 * spread, so a fragment used twice counts twice, which is what the API would resolve. The
 * graphql-java class {@code ExecutableNormalizedOperationFactory} offers a depth and a
 * field limit of its own. Its entry points coerce the variables through the schema, and
 * on a schema built from SDL a custom scalar's {@code parseValue} throws an exception,
 * while a field under {@code @include(if: $flag)} throws once the coerced map leaves the
 * flag out. The walk here reads the document alone, counts a conditional field as
 * selected, and leaves the API's own limits as the final word.
 *
 * <p>
 * Expanding spreads where they are spread is also what makes a walk without a ceiling
 * dangerous: a chain of thirty fragments that each spread the next one twice is a two
 * kilobyte document with billions of leaves. The walk therefore stops as soon as the
 * field count passes the ceiling the caller gives, and the shape says so, since anything
 * past that point is refused either way.
 *
 * @param depth the deepest nesting of fields, where a root field is at depth one
 * @param fields how many field selections the document makes, spreads expanded, or the
 * ceiling plus one where the walk stopped. The refusal a model reads names the ceiling
 * alone, because past it the count is a lower bound. The count stays here because
 * {@code truncated} is derived from it, and so a test or a log line can read what the
 * walk measured whole
 * @param aliases how many of those selections carry an alias
 * @param introspectionFields the introspection fields the document selects at any depth,
 * {@code __schema} or {@code __type}, which the schema tools answer instead
 * @param truncated whether the walk stopped at the ceiling, so every count is a lower
 * bound
 * @author Željko Kozina
 */
record DocumentShape(int depth, int fields, int aliases, Set<String> introspectionFields, boolean truncated) {

	// __typename is left out of the set, because a result needs it to name the member
	// of a union or an interface.
	private static final Set<String> INTROSPECTION_FIELDS = Set.of("__schema", "__type");

	/**
	 * Measures one operation of a document.
	 * @param document the parsed document, which holds the fragments the operation
	 * spreads
	 * @param operation the operation to measure
	 * @param fieldCeiling the field count past which the walk stops
	 * @return the shape
	 */
	static DocumentShape of(Document document, OperationDefinition operation, int fieldCeiling) {
		Map<String, FragmentDefinition> fragments = new LinkedHashMap<>();
		document.getDefinitionsOfType(FragmentDefinition.class)
			.forEach((fragment) -> fragments.put(fragment.getName(), fragment));
		Counter counter = new Counter(fragments, fieldCeiling);
		counter.walk(operation.getSelectionSet(), 1, new LinkedHashSet<>());
		return new DocumentShape(counter.depth, counter.fields, counter.aliases, counter.introspectionFields,
				counter.fields > fieldCeiling);
	}

	private static final class Counter {

		private final Map<String, FragmentDefinition> fragments;

		private final int fieldCeiling;

		private int depth;

		private int fields;

		private int aliases;

		private final Set<String> introspectionFields = new LinkedHashSet<>();

		Counter(Map<String, FragmentDefinition> fragments, int fieldCeiling) {
			this.fragments = fragments;
			this.fieldCeiling = fieldCeiling;
		}

		// A spread already on the path is a cycle, which validation refuses before this
		// runs, and the set guards the walk against it either way.
		private void walk(@Nullable SelectionSet selectionSet, int level, Set<String> spreadsOnPath) {
			if (selectionSet == null) {
				return;
			}
			for (Selection<?> selection : selectionSet.getSelections()) {
				if (this.fields > this.fieldCeiling) {
					return;
				}
				switch (selection) {
					case Field field -> visitField(field, level, spreadsOnPath);
					case InlineFragment inline -> walk(inline.getSelectionSet(), level, spreadsOnPath);
					case FragmentSpread spread -> visitSpread(spread, level, spreadsOnPath);
					default -> {
						// Field, InlineFragment and FragmentSpread are every selection a
						// document holds, and Selection is not sealed, so javac asks for
						// a default arm, which stays empty.
					}
				}
			}
		}

		private void visitField(Field field, int level, Set<String> spreadsOnPath) {
			this.fields++;
			this.depth = Math.max(this.depth, level);
			if (field.getAlias() != null) {
				this.aliases++;
			}
			// Recorded at every level. GraphQL reserves the names that start with two
			// underscores for introspection, so a field of either name is introspection
			// wherever it stands, and an API that exposes its query root again under a
			// field, as GitHub does with Query.relay, answers it there. The name is read,
			// so an alias does not hide the field.
			if (INTROSPECTION_FIELDS.contains(field.getName())) {
				this.introspectionFields.add(field.getName());
			}
			walk(field.getSelectionSet(), level + 1, spreadsOnPath);
		}

		// A spread already on the path is a cycle, which validation refuses ahead of
		// this walk; the set keeps the walk finite either way.
		private void visitSpread(FragmentSpread spread, int level, Set<String> spreadsOnPath) {
			FragmentDefinition fragment = this.fragments.get(spread.getName());
			if (fragment != null && spreadsOnPath.add(spread.getName())) {
				walk(fragment.getSelectionSet(), level, spreadsOnPath);
				spreadsOnPath.remove(spread.getName());
			}
		}

	}
}
