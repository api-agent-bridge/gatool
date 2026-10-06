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

package io.gatool.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.search.ReachableTypes;
import io.gatool.core.internal.search.SchemaPaths;
import io.gatool.core.internal.search.SdlDescriptions;
import io.gatool.core.internal.search.SdlWriter;

/**
 * Every field of a schema, rendered as the text a search index holds.
 *
 * <p>
 * One entry per coordinate, where a coordinate is a type name and a field name written
 * {@code Type.field} and points at exactly one field. A large schema turns into a few
 * thousand short entries, which is what makes searching it cheap.
 *
 * <p>
 * Interfaces are walked beside object types, because a schema declares many of its
 * most-used fields on an interface such as {@code Node}, and a corpus without them cannot
 * answer a question about one. Input objects, enums and unions are left out, because none
 * of them holds a field a selection set can name. The model reaches those with
 * {@code introspectType}, which is the step it takes once search has told it which
 * coordinates matter.
 *
 * <p>
 * Types reserved by the specification, whose names begin with {@code __}, stay out. So do
 * the introspection meta-fields, because {@code getFieldDefinitions()} leaves them out
 * already.
 *
 * <p>
 * Types come out sorted by name and fields in the order the schema declares them, so the
 * corpus of one schema is always the same list. A caller whose cache uses this text as a
 * key depends on that.
 *
 * @author Željko Kozina
 */
public final class SchemaCorpus {

	// A literal newline instead of the platform's, because this text is published: it
	// reaches a model, and anything using it as a key has to hash the same on every
	// machine.
	private static final String NEW_LINE = "\n";

	private SchemaCorpus() {
	}

	/**
	 * Builds the corpus of one schema.
	 * @param schema the schema GATool validates against
	 * @param format how each field is rendered
	 * @param includeDeprecated whether a deprecated field is indexed and a deprecated
	 * argument written, each with its marker, from
	 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
	 * @param allowMutations whether the mutation root and the types only it reaches are
	 * indexed, from {@code gatool.dev.experimental.dynamic-operations.allow-mutations}
	 * @return one entry per field coordinate, in a stable order
	 */
	public static List<CorpusEntry> of(GraphQLSchema schema, CorpusFormat format, boolean includeDeprecated,
			boolean allowMutations) {
		List<CorpusEntry> entries = new ArrayList<>();
		Map<String, String> paths = SchemaPaths.of(schema, includeDeprecated, allowMutations);
		// Every coordinate here is one a model is invited to write an operation against,
		// so the corpus holds what a query or an allowed mutation reaches. The case that
		// turns up in practice is the subscription root and everything behind it: a tool
		// call returns a single result, and indexed those fields would come back looking
		// like root fields. The mutation root does the same while the switch is off: the
		// model would write the mutation the search offers and read the refusal.
		Set<String> reachable = ReachableTypes.of(schema, includeDeprecated, allowMutations);
		List<GraphQLNamedType> types = new ArrayList<>(schema.getAllTypesAsList());
		types.sort(Comparator.comparing(GraphQLNamedType::getName));
		for (GraphQLNamedType type : types) {
			if (type.getName().startsWith("__") || !reachable.contains(type.getName())
					|| !(type instanceof GraphQLFieldsContainer container)) {
				continue;
			}
			for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
				// A large schema carries many, and each one spends search budget on a
				// field the API asks callers to stop using.
				if (field.isDeprecated() && !includeDeprecated) {
					continue;
				}
				String coordinate = container.getName() + "." + field.getName();
				entries.add(CorpusEntry.of(coordinate,
						render(format, container.getName(), field, coordinate, includeDeprecated),
						pathTo(schema, container, paths)));
			}
		}
		return List.copyOf(entries);
	}

	// Returns the way to a field's owner type: the path the walk found, or, for an
	// interface without a path of its own, the way to an implementation with a sentence
	// saying that the field is selected there.
	//
	// An interface without a path is one that only its implementations reach, and a hit
	// on its field would print the way a root field does, so the model would write "{ id
	// }" at the root. Its fields are read on an implementation, so the hint routes to the
	// first implementation by name that a root reaches and says why. A root implementing
	// the interface is a route as well: the field sits on the root itself.
	private static @Nullable String pathTo(GraphQLSchema schema, GraphQLFieldsContainer container,
			Map<String, String> paths) {
		String path = SchemaPaths.to(paths, container.getName());
		if (path != null || !(container instanceof GraphQLInterfaceType intf) || paths.containsKey(intf.getName())) {
			return path;
		}
		List<String> implementations = schema.getImplementations(intf)
			.stream()
			.map(GraphQLObjectType::getName)
			.sorted()
			.toList();
		for (String implementation : implementations) {
			String route = paths.get(implementation);
			if (route != null) {
				return (route.isEmpty() ? implementation : route) + " (declared on interface " + intf.getName()
						+ "; select it on an implementation such as " + implementation + ")";
			}
		}
		return null;
	}

	private static String render(CorpusFormat format, String ownerTypeName, GraphQLFieldDefinition field,
			String coordinate, boolean includeDeprecated) {
		String returnType = GraphQLTypeUtil.simplePrint(field.getType());
		String description = stripDescription(field.getDescription());
		return switch (format) {
			case RAW -> coordinate;
			case GLOSS -> writeGloss(coordinate, ownerTypeName, returnType, description);
			case SDL -> writeSdl(ownerTypeName, field, returnType, description, includeDeprecated);
		};
	}

	// Renders one field as a sentence naming its coordinate, owner type, return type and
	// description.
	//
	// A sentence carries the same facts as SDL and costs about a quarter more to hold,
	// which is why SDL is the default.
	private static String writeGloss(String coordinate, String ownerTypeName, String returnType, String description) {
		StringBuilder text = new StringBuilder("GraphQL field ").append(coordinate)
			.append(". Owner type: ")
			.append(ownerTypeName)
			.append(". Returns: ")
			.append(returnType)
			.append('.');
		if (!description.isEmpty()) {
			text.append(' ').append(description);
		}
		return text.toString();
	}

	// Renders one field as SDL, with its description above it and its owner type in a
	// trailing comment.
	//
	// The field as the schema declares it, with the owner named in a trailing comment so
	// that one entry read on its own says which type it belongs to. The layout is fixed,
	// two spaces before the comment included, because a corpus whose text shifts is a
	// corpus whose token counts and cache keys shift with it.
	private static String writeSdl(String ownerTypeName, GraphQLFieldDefinition field, String returnType,
			String description, boolean includeDeprecated) {
		StringBuilder text = new StringBuilder();
		if (!description.isEmpty()) {
			text.append(SdlDescriptions.blockString(description)).append(NEW_LINE);
		}
		// The argument names and their types, which is what makes SDL the only format
		// that tells a model an argument exists. What an input object holds, and what an
		// argument defaults to, stay for introspectType, because a coordinate is one
		// field and an input object is a type. A deprecated argument follows the setting
		// the way a deprecated field does.
		text.append(field.getName())
			.append(SdlWriter.arguments(field.getArguments(), false, includeDeprecated))
			.append(": ")
			.append(returnType)
			.append(SdlWriter.deprecationMarker(field))
			.append("  # on type ")
			.append(ownerTypeName);
		return text.toString();
	}

	// Stripped and otherwise left as the schema wrote it, including its own line breaks.
	// Collapsing them would change every token count and with it the number of hits a
	// budget admits.
	private static String stripDescription(@Nullable String description) {
		return (description != null) ? description.strip() : "";
	}

}
