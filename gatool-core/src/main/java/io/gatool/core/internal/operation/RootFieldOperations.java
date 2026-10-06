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

package io.gatool.core.internal.operation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.language.AstPrinter;
import graphql.language.Value;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

/**
 * Writes one operation per root field of the schema, so a developer gets tools without
 * writing an operation file.
 *
 * <p>
 * This is a development switch, off by default and named
 * {@code gatool.dev.experimental.generate-tools}, because it publishes the API's whole
 * root to a model. The trusted documents are the product: an operations folder states
 * which operations exist, and a pull request is where each one is reviewed. What this
 * switch buys is the first ten minutes, where a developer points GATool at a schema and
 * sees tools before deciding which ones to keep.
 *
 * <p>
 * The generated text goes through the same parser, the same validator and the same
 * catalog as a written operation file, so a generated tool is built the way a written one
 * is. A mistake here fails at startup, before the first call. A file wins over the
 * generator: a root field an operation file already selects is left alone, because the
 * operation file says how that field is called.
 *
 * <p>
 * The selection set goes one level deep by default, taking the leaves of the type the
 * field returns. Depth is the number of object levels expanded, and it stops at
 * {@value #MAX_DEPTH}, because a selection set grows with the shape of the graph. A
 * schema whose types reference each other widely produces a tool too large to read and a
 * prompt too costly to send. A document past {@value #MAX_DOCUMENT_CHARACTERS} characters
 * is left out with a warning for that reason.
 *
 * <p>
 * The operation takes the field's name exactly, so {@code topRatedMovies} becomes
 * {@code query topRatedMovies}. GraphQL allows a lower-case operation name, and taking
 * the name unchanged is what makes every naming strategy give a tool name matching the
 * field: capitalising it first would leave the {@code as-written} strategy publishing
 * {@code TopRatedMovies} for a field called {@code topRatedMovies}.
 *
 * <p>
 * Three kinds of field are left out, each because only an operation file can answer for
 * it. A field with a required argument, since a value invented here would be a guess. A
 * field whose type expands to an empty selection within the depth, since an empty
 * selection set is invalid GraphQL. And a field that would reach a type already on the
 * path, since the walk would not end.
 *
 * <p>
 * Two further exclusions can be switched on, and both are off by default. Setting
 * {@code gatool.dev.experimental.generate-tools-for-deprecated-root-fields} to false
 * leaves a deprecated root field out, and quietly, because leaving it out is what the
 * setting asked for. Setting
 * {@code gatool.dev.experimental.generated-operations-include-deprecated-fields} to false
 * leaves a deprecated field out of a selection, judged by the declaration of the type
 * being selected. An interface field deprecated on the interface goes, and one deprecated
 * only on an implementing object stays, because a selection through the interface reads
 * the interface's own contract.
 *
 * <p>
 * A root field whose type offers deprecated fields alone then has an empty selection set,
 * and the generator leaves it without a tool. The warning for it says which of the two
 * settings reaches it, the depth or the property, or that only both together do. Naming
 * one where the other is the remedy points at a setting that leaves the selection just as
 * empty.
 *
 * @author Željko Kozina
 */
public final class RootFieldOperations {

	/**
	 * The deepest selection this generates, whatever the property asks for.
	 */
	public static final int MAX_DEPTH = 5;

	/**
	 * What the location of a generated operation starts with, so the catalog can tell one
	 * from a written operation file. A generated operation exists only in memory, and the
	 * location is what every message about it names.
	 */
	public static final String LOCATION_PREFIX = "generated:";

	// A selection set grows with the shape of the graph, so a wide graph at depth 5 can
	// produce a document of over 170,000 characters. That document parses and validates,
	// so nothing downstream refuses it, and the schemas derived from it travel in every
	// tools/list. A real schema at depth 1 or 2 writes a few hundred to a few thousand
	// characters, so this bound leaves every ordinary case alone and turns the unusable
	// ones into a warning.
	private static final int MAX_DOCUMENT_CHARACTERS = 20_000;

	private static final String PROPERTY = "gatool.dev.experimental.generated-operations-include-deprecated-fields";

	private static final String DEPTH_PROPERTY = "gatool.dev.experimental.generated-selection-depth";

	/**
	 * Whether an operation at this location was written by the generator.
	 * @param location the location of the operation
	 * @return true when the generator wrote it
	 */
	public static boolean isGenerated(String location) {
		return location.startsWith(LOCATION_PREFIX);
	}

	/**
	 * Returns the root field a generated location names, as {@code "query/name"} or
	 * {@code "mutation/name"}.
	 * @param location the location of a generated operation
	 * @return the root, and the field name after it
	 */
	public static String rootFieldOf(String location) {
		return location.substring(LOCATION_PREFIX.length());
	}

	private RootFieldOperations() {
	}

	/**
	 * Writes an operation for every root field the operation files leave uncovered.
	 * @param schema the schema every operation validates against
	 * @param generation which roots to walk, how far, and what to do with a deprecated
	 * field. The caller has already read
	 * {@link OperationCatalogFactory.Generation#enabled()}, so this writes whenever it is
	 * called
	 * @param covered the root fields the operation files already select, each as
	 * {@code "query/name"} or {@code "mutation/name"}, because one schema can declare the
	 * same field name on both roots
	 * @param diagnostics receives a warning for each root field the generator cannot
	 * answer for, which leaves out the deprecated root fields {@code generation} drops
	 * @return one source per generated operation, in schema order
	 */
	public static List<OperationSource> write(GraphQLSchema schema, OperationCatalogFactory.Generation generation,
			Set<String> covered, OperationDiagnostics diagnostics) {
		OperationCatalogFactory.Generation bounded = withBoundedDepth(generation);
		List<OperationSource> sources = new ArrayList<>();
		write(schema.getQueryType(), "query", bounded, covered, sources, diagnostics);
		if (bounded.includeMutations()) {
			write(schema.getMutationType(), "mutation", bounded, covered, sources, diagnostics);
		}
		return List.copyOf(sources);
	}

	// Returns the generation record with its depth held between 1 and MAX_DEPTH.
	//
	// The depth is bounded once here, so every walk below and every message about one
	// reads the depth this run actually used. A caller building the record itself gets
	// the same bound the property gets at startup.
	private static OperationCatalogFactory.Generation withBoundedDepth(OperationCatalogFactory.Generation generation) {
		int bounded = Math.clamp(generation.depth(), 1, MAX_DEPTH);
		return (bounded == generation.depth()) ? generation
				: new OperationCatalogFactory.Generation(generation.enabled(), generation.includeMutations(),
						generation.includeDeprecatedRootFields(), generation.includeDeprecatedFields(), bounded);
	}

	private static void write(@Nullable GraphQLObjectType root, String keyword,
			OperationCatalogFactory.Generation generation, Set<String> covered, List<OperationSource> sources,
			OperationDiagnostics diagnostics) {
		if (root == null) {
			return;
		}
		int depth = generation.depth();
		for (GraphQLFieldDefinition field : root.getFieldDefinitions()) {
			if (covered.contains(keyword + "/" + field.getName())) {
				continue;
			}
			// A deprecated root field leaves quietly when the property asks for that: an
			// application set the property, and the tool list shows what it did.
			if (field.isDeprecated() && !generation.includeDeprecatedRootFields()) {
				continue;
			}
			String selection = selectionOf(field.getType(), depth, new LinkedHashSet<>(), "  ",
					generation.includeDeprecatedFields());
			if (selection == null) {
				diagnostics.warning(OperationWarning.THE_SCHEMA, explainEmptySelection(field, generation));
				continue;
			}
			String documentText = writeDocument(keyword, field, selection);
			if (documentText.length() > MAX_DOCUMENT_CHARACTERS) {
				diagnostics.warning(OperationWarning.THE_SCHEMA,
						"has the root field '" + field.getName() + "', whose generated operation is "
								+ documentText.length() + " characters at a depth of " + depth + ", past the "
								+ MAX_DOCUMENT_CHARACTERS + " GATool publishes. A selection set grows with the "
								+ "shape of the graph, so lower " + DEPTH_PROPERTY
								+ ", or write an operation file selecting the fields you want.");
				continue;
			}
			// Every exposure type to begin with, and the caller narrows this to the ones
			// whose operation files leave this root field uncovered.
			sources.add(new OperationSource(LOCATION_PREFIX + keyword + "/" + field.getName(), documentText,
					EnumSet.allOf(ToolExposureType.class)));
		}
	}

	// Builds the warning for a root field whose selection set comes out empty, naming the
	// setting that would reach it.
	//
	// The depth and the deprecation switch can each empty a selection set, and either one
	// alone, or only the two together, can be the setting that has to change. So the walk
	// runs again three times, once with the depth raised, once with the deprecated fields
	// put back, and once with both, and the message names whichever of them reaches the
	// field. Naming one when the other is the remedy points at a setting that leaves the
	// selection just as empty.
	private static String explainEmptySelection(GraphQLFieldDefinition field,
			OperationCatalogFactory.Generation generation) {
		int depth = generation.depth();
		boolean excludesDeprecatedFields = !generation.includeDeprecatedFields();
		boolean couldBeDeeper = depth < MAX_DEPTH;
		boolean deprecationAlone = excludesDeprecatedFields && hasSelectionAt(field, depth, true);
		boolean depthAlone = couldBeDeeper && hasSelectionAt(field, MAX_DEPTH, generation.includeDeprecatedFields());
		boolean bothTogether = excludesDeprecatedFields && couldBeDeeper && hasSelectionAt(field, MAX_DEPTH, true);
		String left = "has the root field '" + field.getName() + "', which GATool leaves without a generated tool: ";
		String writeOneAdvice = "or write an operation file for this root field, selecting the fields you want.";
		if (deprecationAlone) {
			return left + PROPERTY + " is false, and leaving the deprecated fields out empties its selection "
					+ "set. Set that property to true for this schema, " + (depthAlone ? "or raise " + DEPTH_PROPERTY
							+ " to " + MAX_DEPTH + ", which reaches it with that " + "property as it is, " : "")
					+ writeOneAdvice;
		}
		if (bothTogether && !depthAlone) {
			return left + "nothing under it can be selected within a depth of " + depth + ", and " + PROPERTY
					+ " is false, so the deprecated fields a deeper walk would reach are left out. A depth of "
					+ MAX_DEPTH + " with that property set to true reaches it, " + writeOneAdvice;
		}
		// Whether a deeper setting would reach it. At depth 5, and where every field
		// under it demands an argument, raising the depth leaves the selection just as
		// empty, and saying so heads off that attempt.
		return left + "nothing under it can be selected within a depth of " + depth
				+ " without inventing an argument for a field that demands one. "
				+ (depthAlone ? "A depth of " + MAX_DEPTH + " reaches it, or write an operation file for it."
						: "No depth reaches it, so write an operation file for it, selecting the fields "
								+ "you want with the arguments they need.");
	}

	private static boolean hasSelectionAt(GraphQLFieldDefinition field, int depth, boolean includeDeprecatedFields) {
		return selectionOf(field.getType(), depth, new LinkedHashSet<>(), "  ", includeDeprecatedFields) != null;
	}

	private static String writeDocument(String keyword, GraphQLFieldDefinition field, String selection) {
		List<GraphQLArgument> arguments = field.getArguments();
		return writeDeprecationComment(field) + keyword + " " + field.getName() + writeVariables(arguments) + " {\n  "
				+ field.getName() + writeArgumentList(arguments) + selection + "\n}\n";
	}

	// Writes the comment lines that mark a deprecated root field, keeping the field's own
	// description and the reason the schema gives.
	//
	// A deprecated root field still becomes a tool by default, because an application may
	// still want it, and the model is told so where it reads the description. Setting
	// generate-tools-for-deprecated-root-fields to false leaves the field out instead,
	// and this comment then goes with it. The comment is the first description source, so
	// this reaches the tool without another code path, and the field's own words go with
	// it, which keeps everything the field said.
	//
	// Every line gets its own #, because strip() keeps the line breaks inside a
	// description, and a second line outside the comment fails to parse.
	// DescriptionResolver joins the comment lines with a line break again.
	//
	// A description or a reason without a character a reader can see is left out, by the
	// rule DescriptionResolver reads a description with. isBlank() lets a no-break space
	// through.
	//
	// DescriptionResolver writes the sentence about the deprecation, because the tool
	// of a written operation that selects a deprecated root field ends with it as well.
	private static String writeDeprecationComment(GraphQLFieldDefinition field) {
		if (!field.isDeprecated()) {
			return "";
		}
		String description = field.getDescription();
		String comment = ((description != null && !VisibleText.rendersEmpty(description)) ? description.strip() + " "
				: "") + DescriptionResolver.describeDeprecation(field.getDeprecationReason());
		return comment.lines().map((line) -> "# " + line + "\n").collect(Collectors.joining());
	}

	// Writes the variable declarations of the operation, one per argument of the root
	// field.
	private static String writeVariables(List<GraphQLArgument> arguments) {
		return arguments.isEmpty() ? ""
				: arguments.stream()
					.map(RootFieldOperations::writeVariableDeclaration)
					.collect(Collectors.joining(", ", "(", ")"));
	}

	// Writes one variable declaration: the name, the type, and the argument's default
	// where it has one.
	//
	// The argument's own default becomes the variable's, so the published schema carries
	// it and a model reads what it gets by leaving the argument out. Without it the
	// default stays in the schema, where the model cannot see it.
	private static String writeVariableDeclaration(GraphQLArgument argument) {
		String defaultValue = defaultOf(argument);
		return "$" + argument.getName() + ": " + GraphQLTypeUtil.simplePrint(argument.getType())
				+ ((defaultValue != null) ? " = " + defaultValue : "");
	}

	// Writes the argument list the field is called with, each argument passing the
	// variable declared for it.
	private static String writeArgumentList(List<GraphQLArgument> arguments) {
		return arguments.isEmpty() ? ""
				: arguments.stream()
					.map(GraphQLArgument::getName)
					.map((name) -> name + ": $" + name)
					.collect(Collectors.joining(", ", "(", ")"));
	}

	// Returns the argument's default value as GraphQL text, or null where the argument
	// lacks a default, or the schema holds it as a Java value.
	//
	// graphql-java holds a default written in SDL as a literal, which prints back as the
	// GraphQL text it came from. A schema built in code holds a Java value instead, and
	// printing that faithfully is the job DefaultValues does for the JSON schema, so the
	// variable simply goes without one and the API applies its own.
	private static @Nullable String defaultOf(GraphQLArgument argument) {
		if (!argument.hasSetDefaultValue()) {
			return null;
		}
		Object value = argument.getArgumentDefaultValue().getValue();
		return (argument.getArgumentDefaultValue().isLiteral() && value instanceof Value<?> literal)
				? AstPrinter.printAstCompact(literal) : null;
	}

	// Returns the selection set for one field's type, the empty string for a leaf, or
	// null where nothing under the type can be selected.
	private static @Nullable String selectionOf(GraphQLType type, int depth, Set<String> ancestors, String indent,
			boolean includeDeprecatedFields) {
		return selectionOf(type, depth, ancestors, indent, Set.of(), includeDeprecatedFields);
	}

	private static @Nullable String selectionOf(GraphQLType type, int depth, Set<String> ancestors, String indent,
			Set<String> excludedFieldNames, boolean includeDeprecatedFields) {
		GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(type);
		if (unwrapped instanceof GraphQLScalarType || unwrapped instanceof GraphQLEnumType) {
			return "";
		}
		if (depth < 1 || !(unwrapped instanceof GraphQLNamedType namedType) || !ancestors.add(namedType.getName())) {
			return null;
		}
		try {
			String body = switch (unwrapped) {
				case GraphQLUnionType union -> membersOf(union, depth, ancestors, indent, includeDeprecatedFields);
				case GraphQLInterfaceType interfaceType -> fieldsOf(interfaceType, depth, ancestors, indent, true,
						excludedFieldNames, includeDeprecatedFields);
				case GraphQLObjectType objectType ->
					fieldsOf(objectType, depth, ancestors, indent, false, excludedFieldNames, includeDeprecatedFields);
				default -> null;
			};
			return (body != null) ? " {\n" + body + indent + "}" : null;
		}
		finally {
			ancestors.remove(namedType.getName());
		}
	}

	private static @Nullable String fieldsOf(GraphQLFieldsContainer container, int depth, Set<String> ancestors,
			String indent, boolean isAbstract, Set<String> excludedFieldNames, boolean includeDeprecatedFields) {
		StringBuilder body = new StringBuilder();
		// An abstract type carries __typename, because the branches of an output schema
		// name the members by it and a model reading a result tells them apart by it.
		if (isAbstract) {
			body.append(indent).append("  __typename\n");
		}
		boolean selected = false;
		for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
			if (requiresAnArgument(field) || excludedFieldNames.contains(field.getName())
					|| (field.isDeprecated() && !includeDeprecatedFields)) {
				continue;
			}
			String selection = selectionOf(field.getType(), depth - 1, ancestors, indent + "  ",
					includeDeprecatedFields);
			if (selection != null) {
				body.append(indent).append("  ").append(field.getName()).append(selection).append('\n');
				selected = true;
			}
		}
		return selected ? body.toString() : null;
	}

	// Writes the selection set of a union, as __typename and one inline fragment per
	// object member, or null where no member contributed a selection.
	//
	// Only the members of a union declare fields, so every member contributes an inline
	// fragment. __typename is what a model switches on, and the output schema writer
	// tells its branches apart by it.
	private static @Nullable String membersOf(GraphQLUnionType union, int depth, Set<String> ancestors, String indent,
			boolean includeDeprecatedFields) {
		Set<String> conflicting = findConflictingFields(union);
		StringBuilder body = new StringBuilder(indent).append("  __typename\n");
		boolean selected = false;
		for (GraphQLNamedType member : union.getTypes()) {
			if (!(member instanceof GraphQLObjectType objectType)) {
				continue;
			}
			String selection = selectionOf(objectType, depth, ancestors, indent + "  ", conflicting,
					includeDeprecatedFields);
			if (selection != null) {
				body.append(indent).append("  ... on ").append(objectType.getName()).append(selection).append('\n');
				selected = true;
			}
		}
		return selected ? body.toString() : null;
	}

	// Returns the field names two members of this union declare with different shapes.
	//
	// GraphQL's FieldsInSetCanMerge rule reads every pair of fields sharing a response
	// name in one selection set, across the inline fragments too, and requires the same
	// response shape. The GraphQL specification puts it as "Given each pair of members
	// fieldA and fieldB in fieldsForName: SameResponseShape(fieldA, fieldB) must be
	// true."
	//
	// So a success type and a failure type that each carry their own code, one Int and
	// one String, give a document the validator refuses. Those names are left out of
	// every member, and the rest of each member still reaches the model. Printed types
	// are compared, so a difference in the leaf, in nullability or in list wrapping all
	// count, which is stricter than the rule and correct in every case.
	private static Set<String> findConflictingFields(GraphQLUnionType union) {
		Map<String, String> shapeByFieldName = new LinkedHashMap<>();
		Set<String> conflicting = new LinkedHashSet<>();
		for (GraphQLNamedType member : union.getTypes()) {
			if (!(member instanceof GraphQLObjectType objectType)) {
				continue;
			}
			for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
				String shape = GraphQLTypeUtil.simplePrint(field.getType());
				String earlierShape = shapeByFieldName.putIfAbsent(field.getName(), shape);
				if (earlierShape != null && !earlierShape.equals(shape)) {
					conflicting.add(field.getName());
				}
			}
		}
		return conflicting;
	}

	// Returns whether this field has an argument GATool would have to invent a value for.
	//
	// A Non-Null argument with a default is satisfied by the API, so the caller can leave
	// it out.
	private static boolean requiresAnArgument(GraphQLFieldDefinition field) {
		return field.getArguments()
			.stream()
			.anyMatch((argument) -> argument.getType() instanceof GraphQLNonNull && !argument.hasSetDefaultValue());
	}

}
