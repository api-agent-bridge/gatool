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

import graphql.language.AstPrinter;
import graphql.language.Value;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.InputValueWithState;
import org.jspecify.annotations.Nullable;

/**
 * Writes the pieces of SDL that more than one renderer of this package needs: an argument
 * list, a deprecation marker and a declared default.
 *
 * <p>
 * {@code io.gatool.core.search.SchemaCorpus} writes a field for the search index,
 * {@link SchemaPaths} writes the steps of a path and {@link TypeDetail} writes a whole
 * type, and the three share these pieces, so one rendering serves them all. The marker
 * writes the reason as an escaped string, so a reason holding a quotation mark stays
 * inside its quotes.
 *
 * <p>
 * The marker is written for a field, an argument, an enum value and an input field, which
 * are the four places GraphQL takes the deprecation directive. Written bare, a deprecated
 * argument or enum value reads as current and a model uses it.
 *
 * <p>
 * Public, because {@code SchemaCorpus} calls {@link #arguments} and
 * {@link #deprecationMarker(GraphQLFieldDefinition)} from the public search package.
 *
 * @author Željko Kozina
 */
public final class SdlWriter {

	private SdlWriter() {
	}

	/**
	 * Writes an argument list in brackets, each argument as a name and a type the way SDL
	 * declares it.
	 * @param arguments the arguments of one field
	 * @param withDefaults whether an argument's declared default follows its type, which
	 * a type read in full shows and a path or a corpus entry leaves out
	 * @param includeDeprecated whether a deprecated argument is written, with its marker,
	 * from {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields};
	 * while off, the argument is left out the way a deprecated field is
	 * @return the list, or an empty string for a field without arguments to write
	 */
	// GraphQL forbids @deprecated on a required argument, so an argument left out here
	// is one the model may leave out of its operation.
	public static String arguments(List<GraphQLArgument> arguments, boolean withDefaults, boolean includeDeprecated) {
		StringBuilder text = new StringBuilder("(");
		for (GraphQLArgument argument : arguments) {
			if (argument.isDeprecated() && !includeDeprecated) {
				continue;
			}
			if (text.length() > 1) {
				text.append(", ");
			}
			text.append(argument.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(argument.getType()));
			String declaredDefault = withDefaults ? defaultOf(argument.getArgumentDefaultValue()) : null;
			if (declaredDefault != null) {
				text.append(" = ").append(declaredDefault);
			}
			text.append(deprecationMarker(argument));
		}
		return (text.length() == 1) ? "" : text.append(')').toString();
	}

	/**
	 * Writes the deprecation directive of a field, with the reason the schema gives.
	 * @param field the field
	 * @return the directive with a leading space, or an empty string for a field that is
	 * current
	 */
	// The reason usually names the replacement, which is what sends a model to it.
	// Written as a string literal, because the reason is the schema author's text and
	// can hold a quotation mark, a backslash or a line break.
	public static String deprecationMarker(GraphQLFieldDefinition field) {
		return deprecationMarker(field.isDeprecated(), field.getDeprecationReason());
	}

	/**
	 * Writes the deprecation directive of an argument, with the reason the schema gives.
	 * @param argument the argument
	 * @return the directive with a leading space, or an empty string for an argument that
	 * is current
	 */
	static String deprecationMarker(GraphQLArgument argument) {
		return deprecationMarker(argument.isDeprecated(), argument.getDeprecationReason());
	}

	/**
	 * Writes the deprecation directive of an enum value, with the reason the schema
	 * gives.
	 * @param value the enum value
	 * @return the directive with a leading space, or an empty string for a value that is
	 * current
	 */
	static String deprecationMarker(GraphQLEnumValueDefinition value) {
		return deprecationMarker(value.isDeprecated(), value.getDeprecationReason());
	}

	/**
	 * Writes the deprecation directive of an input field, with the reason the schema
	 * gives.
	 * @param field the input field
	 * @return the directive with a leading space, or an empty string for a field that is
	 * current
	 */
	static String deprecationMarker(GraphQLInputObjectField field) {
		return deprecationMarker(field.isDeprecated(), field.getDeprecationReason());
	}

	// GraphQL declares @deprecated on all four locations, and the four types carry the
	// flag and the reason without a type they share, so each overload above reads its
	// own and this writes the marker.
	private static String deprecationMarker(boolean deprecated, @Nullable String reason) {
		if (!deprecated) {
			return "";
		}
		return (reason == null || reason.isBlank()) ? " @deprecated"
				: " @deprecated(reason: " + stringLiteral(reason.strip()) + ")";
	}

	/**
	 * Returns a declared default printed as SDL.
	 * @param defaultValue the default of an argument or an input field
	 * @return the default as GraphQL text, or {@code null} where the default is absent or
	 * programmatic
	 */
	// A default written in SDL is held as a literal, which prints back as the text it
	// came from. A schema built in code holds a Java value instead, and printing one
	// faithfully is the input schema writer's job, so a default of that kind is left
	// out and the API applies its own. RootFieldOperations reads an argument default
	// the same way for a generated operation's variable declarations.
	static @Nullable String defaultOf(InputValueWithState defaultValue) {
		if (!defaultValue.isSet()) {
			return null;
		}
		Object value = defaultValue.getValue();
		return (defaultValue.isLiteral() && value instanceof Value<?> literal) ? AstPrinter.printAstCompact(literal)
				: null;
	}

	/**
	 * Writes text as the quoted string a GraphQL parser reads back unchanged.
	 * @param text the text
	 * @return the literal, quotes included
	 */
	// A string in the grammar excludes a bare quotation mark, a bare backslash and a
	// line break, and of the control characters it allows a tab alone. Each one
	// becomes the escape the grammar names for it, so the parser reads the text back
	// as the schema holds it.
	static String stringLiteral(String text) {
		StringBuilder literal = new StringBuilder(text.length() + 2).append('"');
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			switch (character) {
				case '"' -> literal.append("\\\"");
				case '\\' -> literal.append("\\\\");
				case '\n' -> literal.append("\\n");
				case '\r' -> literal.append("\\r");
				default -> {
					if (character < ' ' && character != '\t') {
						literal.append(String.format("\\u%04X", (int) character));
					}
					else {
						literal.append(character);
					}
				}
			}
		}
		return literal.append('"').toString();
	}

}
