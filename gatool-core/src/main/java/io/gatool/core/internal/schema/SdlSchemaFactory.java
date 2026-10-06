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

package io.gatool.core.internal.schema;

import java.util.List;
import java.util.stream.Collectors;

import graphql.GraphQLError;
import graphql.GraphQLException;
import graphql.language.Document;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;
import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.parser.TrustedText;

/**
 * Turns schema definition language text into a {@link GraphQLSchema}, wired by
 * {@link GAToolWiringFactory}.
 *
 * <p>
 * The schema file source calls this, and the introspection source of a later release
 * reuses it unchanged, because both arrive as SDL text.
 *
 * @author Željko Kozina
 */
public final class SdlSchemaFactory {

	private SdlSchemaFactory() {
	}

	/**
	 * Builds the schema.
	 * @param sdl the schema definition language text
	 * @param location the configured location, which every message about the schema names
	 * @return the executable schema
	 * @throws IllegalStateException if the text is not a valid schema, with every schema
	 * error in the message
	 */
	public static GraphQLSchema schemaFrom(String sdl, String location) {
		return schemaFrom(sdl, location, TrustedText.SCHEMA_OPTIONS);
	}

	/**
	 * Builds the schema under options of the caller's own, which is how a test reaches a
	 * limit with a short text.
	 * @param sdl the schema definition language text
	 * @param location the configured location, which every message about the schema names
	 * @param options the parser options the text is read under
	 * @return the executable schema
	 */
	static GraphQLSchema schemaFrom(String sdl, String location, ParserOptions options) {
		// The text is read with a line feed for every line terminator, wherever it
		// came from, so a description written as a block string is the same text
		// from a file checked out with \r\n and from a registry that serves \n.
		String text = TrustedText.withLineFeeds(sdl);
		Document document = null;
		try {
			// Parsed through an environment of GATool's own, because
			// SchemaParser.parse(String) builds its environment with the JVM's default
			// locale, and a syntax error would then print in German on a German laptop
			// where every other startup line prints in English. The operation parser and
			// the validator are pinned to Locale.ROOT the same way, and the options lift
			// every limit, as the ones SchemaParser would have used do.
			document = TrustedText.parse(text, null, options);
			TypeDefinitionRegistry registry = new SchemaParser().buildRegistry(document);
			RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring().wiringFactory(new GAToolWiringFactory()).build();
			return new SchemaGenerator().makeExecutableSchema(registry, wiring);
		}
		catch (GraphQLException ex) {
			// A limit arrives as the exception of a syntax error, and the parser stopped
			// ahead of the end of the text, so whether the schema is valid stays unsaid.
			String limit = (ex instanceof InvalidSyntaxException syntax) ? TrustedText.describeLimit(syntax, text)
					: null;
			throw new IllegalStateException(
					(limit != null) ? location + " " + limit : describeRefusal(location, ex, document), ex);
		}
	}

	// Returns the message for a schema graphql-java refused: its errors, and where an
	// argument's default differs from its interface's, what that difference means.
	//
	// graphql-java raises one error for each argument it compared and found different, so
	// where the errors are as many as the differing defaults, every error is one of
	// those, and the message stops calling the schema invalid: the specification allows
	// it. Beside another error the schema is invalid all the same, the header stays, and
	// the sentences about the defaults follow the list.
	private static String describeRefusal(String location, GraphQLException ex, @Nullable Document document) {
		List<InterfaceArgumentDefaults.Difference> differences = (document != null && ex instanceof SchemaProblem)
				? InterfaceArgumentDefaults.findIn(document) : List.of();
		if (differences.isEmpty()) {
			return location + " is not a valid GraphQL schema:" + errorsOf(ex);
		}
		boolean defaultsAlone = ex instanceof SchemaProblem problem && problem.getErrors().size() == differences.size();
		return location
				+ (defaultsAlone ? " is refused by graphql-java, which GATool builds the schema with:"
						: " is not a valid GraphQL schema:")
				+ errorsOf(ex) + System.lineSeparator() + InterfaceArgumentDefaults.describe(differences);
	}

	// Returns every error the exception carries, one per line.
	//
	// The registry builder and the generator raise SchemaProblem, which lists its errors,
	// and the schema validation the generator runs last raises InvalidSchemaException,
	// for an input type that holds itself through a Non-Null list among others, and keeps
	// its list package-private. Catching SchemaProblem alone would let the second one
	// escape raw, without the location every other schema error names. Its message is
	// "invalid schema:" followed by one line per error, and those lines are kept. The
	// parser raises InvalidSyntaxException, whose message is the one line to keep.
	private static String errorsOf(GraphQLException ex) {
		List<String> lines = (ex instanceof SchemaProblem problem)
				? problem.getErrors().stream().map(GraphQLError::getMessage).toList()
				: String.valueOf(ex.getMessage())
					.lines()
					.map(String::strip)
					.filter((line) -> !line.isEmpty() && !"invalid schema:".equals(line))
					.toList();
		return lines.stream()
			.collect(Collectors.joining(System.lineSeparator() + "  - ", System.lineSeparator() + "  - ", ""));
	}

}
