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
import java.util.List;
import java.util.Locale;

import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLSchema;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.validation.AbstractRule;
import graphql.validation.ValidationContext;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorCollector;
import graphql.validation.Validator;
import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.parser.TrustedText;

/**
 * Validates an operation file against the schema, prints the document that the executor
 * sends, and validates the printed document again.
 *
 * <p>
 * The validator is graphql-java's own with one rule beside its 35: the OneOf variable
 * rule of {@link OneOfFieldValueRule}, which 25.0 leaves out.
 *
 * @author Željko Kozina
 */
public final class OperationValidator {

	private final GraphQLSchema schema;

	private final ParserOptions options;

	public OperationValidator(GraphQLSchema schema) {
		this(schema, TrustedText.OPTIONS);
	}

	/**
	 * Creates a validator that reads the printed document back under options of the
	 * caller's own, which is how a test reaches a limit with a short document.
	 * @param schema the schema the operations are validated against
	 * @param options the parser options the printed document is read back under
	 */
	OperationValidator(GraphQLSchema schema, ParserOptions options) {
		this.schema = schema;
		this.options = options;
	}

	/**
	 * Validates one operation file.
	 * @param file the parsed operation file
	 * @param diagnostics receives the validation errors
	 * @return the validated operation, or {@code null} when validation fails
	 */
	public @Nullable ValidatedOperation validate(OperationFile file, OperationDiagnostics diagnostics) {
		String location = file.source().location();
		List<ValidationError> errors = validate(file.document());
		if (!errors.isEmpty()) {
			diagnostics.problem(location, "fails validation against the schema:" + describeErrors(errors, location));
			return null;
		}
		String printedDocument = AstPrinter.printAst(file.document());
		String controlCharacter = findControlCharacter(printedDocument);
		if (controlCharacter != null) {
			diagnostics.problem(location,
					"holds the control character " + controlCharacter + " in the string " + "literal at "
							+ describeStringValueHolding(file, controlCharacter, location)
							+ ", and GraphQL's SourceCharacter grammar leaves that character out, so the API's lexer "
							+ "refuses the document, in which it travels unescaped; write the value without it");
			return null;
		}
		try {
			List<ValidationError> printedErrors = validate(TrustedText.parse(printedDocument, null, this.options));
			if (!printedErrors.isEmpty()) {
				diagnostics.problem(location, "prints a document that fails validation, which is a bug in " + "GATool:"
						+ describeErrors(printedErrors, location));
				return null;
			}
		}
		catch (InvalidSyntaxException ex) {
			// A limit arrives as the same exception a syntax error does. The printed
			// document is the operator's own text, so it is read without the limits of
			// a request, and a limit met anyway is a matter of its size.
			String limit = TrustedText.describeLimit(ex, printedDocument);
			diagnostics.problem(location,
					(limit != null) ? "prints a document that " + limit + describeSharedFragmentFiles(file)
							: "prints a document with invalid syntax, which is a bug in GATool: " + ex.getMessage());
			return null;
		}
		return new ValidatedOperation(file, printedDocument);
	}

	// Names the shared fragment files whose fragments joined the document, which is where
	// a printed document gets the text its operation file does not hold.
	private static String describeSharedFragmentFiles(OperationFile file) {
		return file.sharedFragmentFiles().isEmpty() ? ""
				: " The document holds the shared fragments of " + String.join(", ", file.sharedFragmentFiles()) + ".";
	}

	// ParseAndValidate.validate is this call with graphql-java's rules alone, so the
	// rule set is widened here.
	private List<ValidationError> validate(Document document) {
		return validate(this.schema, document);
	}

	/**
	 * Validates a parsed document with graphql-java's rules and the OneOf rules beside
	 * them, which is the check every operation file passes.
	 * @param schema the schema to validate against
	 * @param document the parsed document
	 * @return the errors, empty when the document is valid
	 */
	public static List<ValidationError> validate(GraphQLSchema schema, Document document) {
		// graphql-java translates these messages, and the default locale would give a
		// German laptop German text where CI prints English. Startup problems are read
		// beside GATool's own English sentences and diffed against another machine's
		// output, and the dynamic path publishes them to a model inside English text.
		WithOneOfRule validator = new WithOneOfRule();
		List<ValidationError> errors = validator.validateDocument(schema, document, Locale.ROOT);
		requireOneOfRulesRan(validator.rulesCreated());
		return errors;
	}

	/**
	 * Stops where graphql-java validated the document without calling the hook that adds
	 * the OneOf rule, since the document would otherwise pass as validated while the
	 * checks every operation file is promised were left out.
	 * @param rulesCreated whether graphql-java called the hook that adds the OneOf rule
	 */
	// graphql-java 25 calls createRules on every validateDocument, ahead of the
	// traversal, so the flag is set for a valid document, one with problems and one
	// without an operation alike. 26 removed the hook, and a validator built on it
	// then runs graphql-java's own rules alone: the document validates, and the
	// OneOf checks are gone without a word. gatool-core's build refuses 26, and a
	// consumer's own override sits outside that build, so this is the guard on
	// their side until the rule is rewritten on public API.
	static void requireOneOfRulesRan(boolean rulesCreated) {
		if (!rulesCreated) {
			throw new IllegalStateException("graphql-java validated the document without GATool's @oneOf "
					+ "checks, because it left out the Validator.createRules hook they are added through. "
					+ "graphql-java 26 removed that hook, so this is what a graphql-java 26 on the classpath "
					+ "does. Stay on the graphql-java version Spring Boot manages, which is 25, until GATool "
					+ "rewrites the @oneOf rule on public API.");
		}
	}

	/**
	 * Formats the validation errors as one indented line each, with the line and column
	 * of every location the error carries, and the shared fragment file where a location
	 * lies in one.
	 * @param errors the validation errors
	 * @param location the operation file the errors belong to
	 * @return the listing, one line per error, each line starting with a line separator
	 */
	// The parser puts a file's location on every source location its nodes carry, and a
	// shared fragment keeps the nodes its own file parsed when it joins an operation's
	// document. So a location whose file differs from the operation file's is one inside
	// a shared fragment, with that file's line numbers, and the line says so. The printed
	// document is parsed without a name, so its locations print plain.
	static String describeErrors(List<ValidationError> errors, String location) {
		StringBuilder listing = new StringBuilder();
		for (ValidationError error : errors) {
			listing.append(System.lineSeparator()).append("  - ").append(error.getDescription());
			for (SourceLocation errorLocation : error.getLocations()) {
				listing.append(" (").append(describe(errorLocation, location)).append(')');
			}
		}
		return listing.toString();
	}

	// Writes one source location as its line and column, naming the shared fragment file
	// where the location lies in one.
	private static String describe(SourceLocation sourceLocation, String location) {
		String file = sourceLocation.getSourceName();
		String position = "line " + sourceLocation.getLine() + ", column " + sourceLocation.getColumn();
		return (file == null || file.equals(location)) ? position : position + " of the shared fragment file " + file;
	}

	// Returns the first character of the printed document outside GraphQL's
	// SourceCharacter set, written as U+0001, or null where every character is one the
	// grammar admits.
	//
	// SourceCharacter is U+0009, U+000A, U+000D and U+0020 upwards, so the characters
	// left out are U+0000 to U+0008, U+000B, U+000C and U+000E to U+001F. AstPrinter
	// escapes the quote, the backslash and the five named controls, and writes every
	// other character as it is, so one of these in a string literal reaches the printed
	// document raw. The lexer of graphql-java reads it, which is why the re-parse below
	// does not catch it, and a lexer written to the grammar refuses the whole document.
	private static @Nullable String findControlCharacter(String printedDocument) {
		for (int index = 0; index < printedDocument.length(); index++) {
			char character = printedDocument.charAt(index);
			if (character < 0x20 && character != '\t' && character != '\n' && character != '\r') {
				return String.format(Locale.ROOT, "U+%04X", (int) character);
			}
		}
		return null;
	}

	// Names the position in the file of the first string literal holding the character,
	// or a placeholder where the walk cannot find one, which the guard above rules out
	// today.
	//
	// The string literal is the one place a character can enter the printed document
	// and leave the file's own text: a name cannot hold one, and comments are dropped.
	// The literal's own location names the file it sits in, which is the shared
	// fragment file where the literal came from one.
	private static String describeStringValueHolding(OperationFile file, String controlCharacter, String location) {
		char character = (char) Integer.parseInt(controlCharacter.substring(2), 16);
		List<SourceLocation> found = new ArrayList<>();
		new NodeTraverser().preOrder(new NodeVisitorStub() {

			// graphql-java declares the visitor's context with a raw Node, so overriding
			// it faithfully means repeating the raw type under -Werror.
			@Override
			@SuppressWarnings("rawtypes")
			public TraversalControl visitStringValue(StringValue node, TraverserContext<Node> context) {
				SourceLocation sourceLocation = node.getSourceLocation();
				String value = node.getValue();
				if (found.isEmpty() && sourceLocation != null && value != null && value.indexOf(character) >= 0) {
					found.add(sourceLocation);
				}
				return TraversalControl.CONTINUE;
			}
		}, file.document());
		return found.isEmpty() ? "an unknown position" : describe(found.getFirst(), location);
	}

	static final class WithOneOfRule extends Validator {

		private boolean rulesCreated;

		@Override
		public List<AbstractRule> createRules(ValidationContext context, ValidationErrorCollector collector) {
			this.rulesCreated = true;
			List<AbstractRule> rules = new ArrayList<>(super.createRules(context, collector));
			rules.add(new OneOfFieldValueRule(context, collector));
			return rules;
		}

		/**
		 * Returns whether graphql-java called the hook while validating, which the
		 * version the build manages does on every document.
		 * @return whether the OneOf rule joined the last validation
		 */
		boolean rulesCreated() {
			return this.rulesCreated;
		}

	}

}
