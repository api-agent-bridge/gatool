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

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

/**
 * One type of the schema, written as the declaration a developer would read.
 *
 * <p>
 * This is what {@code introspectType} answers with, and it is the step between a search
 * hit and a working operation. A hit names {@code Query.movies} and the arguments it
 * takes. What those arguments hold, which fields the returned type has and which values
 * an enum allows are all here, because a corpus entry is one field and each of those is a
 * type.
 *
 * <p>
 * SDL instead of the JSON of an introspection query, for the same reason the corpus
 * renders SDL: it is the syntax a model reads in every schema it has seen, and it says
 * the same thing in fewer tokens.
 *
 * <p>
 * One level deep, without a depth argument. A field's return type is named and left
 * unexpanded, so the model asks for the types it needs and pays for those alone. A tool
 * that can be asked for the whole graph in one call is a tool that can spend a context
 * window in one call.
 *
 * <p>
 * Descriptions become one line each. A schema writes them over as many lines as it likes,
 * and this text is read beside a tool result.
 *
 * <p>
 * An interface names the object types implementing it in a trailing comment, because its
 * fields are selected on one of them and the model has to know what to spread.
 *
 * @author Željko Kozina
 */
public final class TypeDetail {

	// A literal newline instead of the platform's, because this text is published and
	// has to read the same on every machine.
	private static final String NEW_LINE = "\n";

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private static final String INDENT = "  ";

	private TypeDetail() {
	}

	/**
	 * Writes one type of the schema.
	 * @param schema the schema GATool validates against
	 * @param typeName the name to look up, exactly as the schema spells it
	 * @param includeDeprecated whether a deprecated field, argument, enum value or input
	 * field is written, with its marker, from
	 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
	 * @param reachable the types a query or an allowed mutation can name, from
	 * {@link ReachableTypes#of}. The caller walks the schema once and keeps the set,
	 * because this method answers one tool call and the walk covers every type
	 * @return the declaration, or {@code null} where the schema lacks a type of that name
	 * a query or a mutation can reach
	 */
	public static @Nullable String of(GraphQLSchema schema, String typeName, boolean includeDeprecated,
			Set<String> reachable) {
		if (typeName.startsWith("__")) {
			// The specification reserves these for introspection, and the corpus leaves
			// them out, so a model that asks for one asked for something it has not read.
			return null;
		}
		if (!reachable.contains(typeName)) {
			// The corpus offers the same set, so the two tools a model reads the schema
			// through agree about what exists. Reading a type no query or mutation
			// reaches would let a model write an operation against fields that cannot be
			// selected, such as the subscription root and everything behind it. The
			// executeGraphql tool does not apply that filter: it validates against the
			// whole schema, because refusing a document graphql-java accepts would be
			// GATool inventing a rule GraphQL does not have.
			return null;
		}
		GraphQLNamedType type = (GraphQLNamedType) schema.getType(typeName);
		return switch (type) {
			case null -> null;
			case GraphQLObjectType object ->
				writeContainer("type " + object.getName() + writeImplements(object.getInterfaces()) + " {", object,
						includeDeprecated);
			case GraphQLInterfaceType intf ->
				writeContainer("interface " + intf.getName() + writeImplements(intf.getInterfaces())
						+ writeImplementedBy(schema, intf), intf, includeDeprecated);
			case GraphQLEnumType enumType -> writeEnum(enumType, includeDeprecated);
			case GraphQLInputObjectType inputObject -> writeInputObject(inputObject, includeDeprecated);
			case GraphQLUnionType union -> writeDescription(union.getDescription()) + "union " + union.getName() + " = "
					+ String.join(" | ", union.getTypes().stream().map(GraphQLNamedType::getName).toList());
			case GraphQLScalarType scalar -> writeDescription(scalar.getDescription()) + "scalar " + scalar.getName();
			default -> typeName;
		};
	}

	// Writes an object or interface declaration with one line per field, each field
	// carrying its arguments, its return type and its description. The declaration
	// arrives with its opening brace, because an interface carries a comment after it.
	private static String writeContainer(String declaration, GraphQLFieldsContainer container,
			boolean includeDeprecated) {
		StringBuilder text = new StringBuilder(writeDescription(descriptionOf(container))).append(declaration);
		for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
			// The corpus leaves a deprecated field out, so reading the type has to leave
			// it out too. A model that met it here would use it without having seen it in
			// a search result.
			if (field.isDeprecated() && !includeDeprecated) {
				continue;
			}
			// The arguments carry their defaults here, unlike in a corpus entry, because
			// what the API does when the model leaves an argument out is often the reason
			// to leave it out.
			text.append(NEW_LINE)
				.append(indent(writeDescription(field.getDescription())))
				.append(INDENT)
				.append(field.getName())
				.append(SdlWriter.arguments(field.getArguments(), true, includeDeprecated))
				.append(": ")
				.append(GraphQLTypeUtil.simplePrint(field.getType()))
				.append(SdlWriter.deprecationMarker(field));
		}
		return text.append(NEW_LINE).append('}').toString();
	}

	// A deprecated value follows the same rule as a deprecated field: left out while the
	// setting is off, and marked with the schema's own reason while it is on.
	private static String writeEnum(GraphQLEnumType enumType, boolean includeDeprecated) {
		StringBuilder text = new StringBuilder(writeDescription(enumType.getDescription())).append("enum ")
			.append(enumType.getName())
			.append(" {");
		for (GraphQLEnumValueDefinition value : enumType.getValues()) {
			if (value.isDeprecated() && !includeDeprecated) {
				continue;
			}
			// Each value's own words, which the input schema writer publishes for the
			// same reason: they are what stops a model inventing a plausible member.
			text.append(NEW_LINE)
				.append(indent(writeDescription(value.getDescription())))
				.append(INDENT)
				.append(value.getName())
				.append(SdlWriter.deprecationMarker(value));
		}
		return text.append(NEW_LINE).append('}').toString();
	}

	// Writes an input declaration with one line per field, each field carrying the
	// default the schema declares for it.
	//
	// GraphQL forbids @deprecated on a required input field, so a field left out here is
	// one the model may leave out of its value.
	private static String writeInputObject(GraphQLInputObjectType inputObject, boolean includeDeprecated) {
		StringBuilder text = new StringBuilder(writeDescription(inputObject.getDescription())).append("input ")
			.append(inputObject.getName())
			.append(" {");
		for (GraphQLInputObjectField field : inputObject.getFieldDefinitions()) {
			if (field.isDeprecated() && !includeDeprecated) {
				continue;
			}
			text.append(NEW_LINE)
				.append(indent(writeDescription(field.getDescription())))
				.append(INDENT)
				.append(field.getName())
				.append(": ")
				.append(GraphQLTypeUtil.simplePrint(field.getType()));
			String declaredDefault = SdlWriter.defaultOf(field.getInputFieldDefaultValue());
			if (declaredDefault != null) {
				// An input field's default matters as much as an argument's, and the
				// input schema writer publishes it for the same reason: it is what the
				// model gets by leaving the field out.
				text.append(" = ").append(declaredDefault);
			}
			text.append(SdlWriter.deprecationMarker(field));
		}
		return text.append(NEW_LINE).append('}').toString();
	}

	private static String writeImplements(List<GraphQLNamedOutputType> interfaces) {
		if (interfaces.isEmpty()) {
			return "";
		}
		return " implements "
				+ String.join(" & ", interfaces.stream().map(GraphQLNamedType::getName).sorted().toList());
	}

	// Writes the opening brace of an interface with a trailing comment naming the object
	// types implementing it, in name order, or the brace alone for an interface without
	// implementations.
	//
	// An interface's fields are selected on an implementation, through "... on Movie",
	// and a model that reads the interface alone has to guess which type to spread. Every
	// implementation of a reachable interface is reachable, so each name here is one the
	// model can read next.
	private static String writeImplementedBy(GraphQLSchema schema, GraphQLInterfaceType intf) {
		List<String> implementations = schema.getImplementations(intf)
			.stream()
			.map(GraphQLObjectType::getName)
			.sorted()
			.toList();
		if (implementations.isEmpty()) {
			return " {";
		}
		return " {  # implemented by " + String.join(", ", implementations);
	}

	private static @Nullable String descriptionOf(GraphQLFieldsContainer container) {
		return (container instanceof GraphQLObjectType object) ? object.getDescription()
				: ((GraphQLInterfaceType) container).getDescription();
	}

	private static String writeDescription(@Nullable String description) {
		if (description == null || description.isBlank()) {
			return "";
		}
		return SdlDescriptions.blockString(WHITESPACE.matcher(description.strip()).replaceAll(" ")) + NEW_LINE;
	}

	// A description of a field or a value is indented with what it describes, so the
	// declaration reads as a developer would write it.
	private static String indent(String describedLine) {
		return describedLine.isEmpty() ? "" : INDENT + describedLine;
	}

}
