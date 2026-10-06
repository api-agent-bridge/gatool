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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import org.jspecify.annotations.Nullable;

/**
 * Reads {@code @gatool} from an operation and takes it back out of the document.
 *
 * <p>
 * GATool declares the directive on {@code QUERY | MUTATION}, so it sits on the operation
 * itself and the tool's settings sit in the same diff as the query. Its arguments arrive
 * across releases. This release reads {@code name}, which wins over the naming strategy,
 * {@code title}, which reaches the MCP tool definition, {@code scopes}, which the MCP
 * endpoint checks on every call, {@code openWorld} and {@code outputSchema}.
 *
 * <p>
 * The directive leaves the document before anything else sees it. A GraphQL API declares
 * its own directives alone, so a document carrying this one fails validation with
 * {@code UnknownDirective}, and {@code AstPrinter} would send it to the API. Stripping it
 * in the parser means the validator and the executor both read the document a GraphQL API
 * accepts.
 *
 * <p>
 * The arguments take literal values only, so a variable or any other expression leaves
 * the value unread and startup reports it. Every problem names the line and column of the
 * node it is about, which the parser puts on each node.
 *
 * @author Željko Kozina
 */
public final class ToolDirective {

	static final String NAME = "gatool";

	private final @Nullable String toolName;

	private final @Nullable String title;

	private final @Nullable List<String> scopes;

	private final @Nullable Boolean openWorld;

	private final @Nullable Boolean outputSchema;

	private ToolDirective(@Nullable String toolName, @Nullable String title, @Nullable List<String> scopes,
			@Nullable Boolean openWorld, @Nullable Boolean outputSchema) {
		this.toolName = toolName;
		this.title = title;
		this.scopes = scopes;
		this.openWorld = openWorld;
		this.outputSchema = outputSchema;
	}

	/**
	 * Reads the directive of one operation.
	 * @param operation the operation as the file wrote it
	 * @param location the operation file, for any problem
	 * @param diagnostics receives a problem for a value that fails to read
	 * @return the settings the directive carries, which are empty where the file leaves
	 * the directive out
	 */
	public static ToolDirective read(OperationDefinition operation, String location, OperationDiagnostics diagnostics) {
		List<Directive> directives = operation.getDirectives(NAME);
		if (directives.isEmpty()) {
			return new ToolDirective(null, null, null, null, null);
		}
		if (directives.size() > 1) {
			diagnostics.problem(location,
					"carries @" + NAME + " " + directives.size()
							+ " times, and one file makes one tool, so the directive belongs on the operation once"
							+ at(directives.get(1)));
			return new ToolDirective(null, null, null, null, null);
		}
		Directive directive = directives.getFirst();
		// graphql-java's UniqueArgumentNames rule would catch a repeated argument, and it
		// runs after the parser has taken the directive out, so the repeat is checked
		// here. Each of the readers below takes the first value of a name, which is why a
		// second one has to be a problem: the file said two things and one of them won.
		Set<String> seen = new LinkedHashSet<>();
		Set<String> repeated = new LinkedHashSet<>();
		for (Argument argument : directive.getArguments()) {
			if (!Arguments.RECOGNISED.contains(argument.getName())) {
				diagnostics.problem(location,
						describeArgument(argument.getName()) + ", and this release reads " + Arguments.NAME + ", "
								+ Arguments.TITLE + ", " + Arguments.SCOPES + ", " + Arguments.OPEN_WORLD + " and "
								+ Arguments.OUTPUT_SCHEMA + at(argument));
			}
			if (!seen.add(argument.getName()) && repeated.add(argument.getName())) {
				diagnostics.problem(location,
						describeArgument(argument.getName())
								+ " more than once, and an argument of that directive is written once; keep the value "
								+ "you mean" + at(argument));
			}
		}
		return new ToolDirective(readStringArgument(directive, Arguments.NAME, location, diagnostics),
				readStringArgument(directive, Arguments.TITLE, location, diagnostics),
				readScopesArgument(directive, location, diagnostics),
				readBooleanArgument(directive, Arguments.OPEN_WORLD, location, diagnostics),
				readBooleanArgument(directive, Arguments.OUTPUT_SCHEMA, location, diagnostics));
	}

	/**
	 * Reports every {@code @gatool} written somewhere other than on an operation.
	 *
	 * <p>
	 * The directive is declared on {@code QUERY | MUTATION}, and the parser takes it off
	 * the operation alone. One written on a field or a fragment stays in the document and
	 * reaches validation as {@code Unknown directive 'gatool'}, a message that reads as
	 * if the directive were foreign to GATool. The document is checked here, before
	 * validation, and the problem says where the directive belongs.
	 * @param document the parsed file, as the file wrote it, whether it holds an
	 * operation or fragments alone
	 * @param location the file, for any problem
	 * @param diagnostics receives a problem for each misplaced directive
	 */
	// Any operation counts as the right place, because a file holding two operations is
	// already refused for that, and a second problem about the directive on the second
	// operation would only repeat it.
	public static void reportMisplaced(Document document, String location, OperationDiagnostics diagnostics) {
		// preOrder, because depthFirst hands every node to the visitor twice, on the way
		// in and on the way out, and each directive would be reported twice.
		new NodeTraverser().preOrder(new NodeVisitorStub() {

			// graphql-java declares the visitor's context with a raw Node, so overriding
			// it faithfully means repeating the raw type under -Werror.
			@Override
			@SuppressWarnings("rawtypes")
			public TraversalControl visitDirective(Directive directive, TraverserContext<Node> context) {
				Node<?> parent = context.getParentNode();
				if (NAME.equals(directive.getName()) && !(parent instanceof OperationDefinition)) {
					diagnostics.problem(location, "carries @" + NAME + " on " + describe(parent) + ", and the "
							+ "directive belongs on the operation alone, between its variables and its selection "
							+ "set, as in query Name($first: Int) @" + NAME + "(name: \"x\") { ... }" + at(directive));
				}
				return TraversalControl.CONTINUE;
			}
		}, document);
	}

	// Names the node a misplaced directive sits on, for the problem that reports it.
	private static String describe(@Nullable Node<?> parent) {
		return switch (parent) {
			case Field field -> "the field " + field.getName();
			case FragmentDefinition fragment -> "the fragment " + fragment.getName();
			case FragmentSpread spread -> "the spread ..." + spread.getName();
			case InlineFragment inline -> "an inline fragment";
			case VariableDefinition variable -> "the variable $" + variable.getName();
			case null, default -> "a node other than the operation";
		};
	}

	/**
	 * Returns the document with the directive taken out.
	 *
	 * <p>
	 * The operation is rebuilt without {@code @gatool} and swapped into the document, so
	 * the fragments and every other definition stay as the file wrote them.
	 * @param document the parsed file
	 * @param operation the operation inside it
	 * @return the document a GraphQL API accepts
	 */
	// The identity comparison below is the point of the loop: the one definition that is
	// this operation gets the stripped copy. A value comparison would replace every
	// definition equal to it, and a file may hold two operations written the same way.
	@SuppressWarnings("ReferenceEquality")
	public static Document withoutDirective(Document document, OperationDefinition operation) {
		if (operation.getDirectives(NAME).isEmpty()) {
			return document;
		}
		return replace(document, operation, withoutDirective(operation));
	}

	/**
	 * Returns the document with one operation swapped for another.
	 * @param document the parsed file
	 * @param operation the operation to replace
	 * @param with the operation to put in its place
	 * @return the document carrying the replacement
	 */
	// The comparison is identity on purpose: the caller holds the one node to swap, and
	// two operations in a document can be equal without being the same definition.
	@SuppressWarnings("ReferenceEquality")
	static Document replace(Document document, OperationDefinition operation, OperationDefinition with) {
		// graphql-java declares Definition with a raw bound of its own, and both
		// getDefinitions() and Builder.definitions(...) traffic in the raw list, so a
		// list of this method's own would draw a raw type warning under -Werror. Adding
		// the definitions one at a time keeps every type named.
		return document.transform((builder) -> {
			builder.definitions(List.of());
			document.getDefinitions()
				.forEach((definition) -> builder.definition((definition == operation) ? with : definition));
		});
	}

	/**
	 * Returns the operation as the directive rebuilt it.
	 * @param operation the operation as the file wrote it
	 * @return the operation without {@code @gatool}
	 */
	public static OperationDefinition withoutDirective(OperationDefinition operation) {
		List<Directive> kept = operation.getDirectives()
			.stream()
			.filter((directive) -> !NAME.equals(directive.getName()))
			.toList();
		return operation.transform((builder) -> builder.directives(kept));
	}

	/**
	 * Returns the explicit tool name.
	 * @return the name the directive sets, or {@code null} where the file leaves that
	 * argument out
	 */
	public @Nullable String toolName() {
		return this.toolName;
	}

	/**
	 * Returns the title for the MCP tool definition.
	 * @return the title the directive sets, or {@code null} where the file leaves that
	 * argument out
	 */
	public @Nullable String title() {
		return this.title;
	}

	/**
	 * Returns the scopes the tool needs, all of them.
	 *
	 * <p>
	 * The list is flat on purpose: every listed scope is required, and a rule such as one
	 * scope or another belongs in the schema's own authorization directives. An empty
	 * list is a statement of its own, that every caller with the baseline scopes may use
	 * this tool, which is how a public catalog is marked where the outbound credential is
	 * shared.
	 * @return the scopes the directive lists, empty where the list is empty, or
	 * {@code null} where the file leaves the argument out
	 */
	public @Nullable List<String> scopes() {
		return this.scopes;
	}

	/**
	 * Returns the open world hint for the MCP tool definition.
	 *
	 * <p>
	 * The hint says whether the tool reaches an open-ended set of entities. The operation
	 * file knows what sits behind the GraphQL API and the starter does not, so the hint
	 * travels from the file and stays out of the tool definition while a file leaves it
	 * unsaid.
	 * @return the hint the directive sets, or {@code null} where the file leaves that
	 * argument out
	 */
	public @Nullable Boolean openWorld() {
		return this.openWorld;
	}

	/**
	 * Returns whether this operation publishes an output schema.
	 *
	 * <p>
	 * The property {@code gatool.results.publish-output-schema} sets the default for
	 * every tool, and this argument overrides it either way: a file says {@code true} to
	 * publish where the default is off, and {@code false} to hold back where the default
	 * is on. MCP binds a server to a schema it publishes, so the file that knows how
	 * awkward its own response shape is has the last word.
	 * @return what the directive set, or {@code null} where the file leaves the property
	 * to decide
	 */
	public @Nullable Boolean outputSchema() {
		return this.outputSchema;
	}

	// Every problem this class reports opens by naming the argument of the directive that
	// caused it, so the message points at one argument before it says what was wrong.
	private static String describeArgument(String argumentName) {
		return "sets @" + NAME + "(" + argumentName + ":)";
	}

	// Reads one argument whose value is a literal true or false, and reports a problem
	// where the file wrote anything else. The value is null where the directive leaves
	// the argument out.
	private static @Nullable Boolean readBooleanArgument(Directive directive, String argumentName, String location,
			OperationDiagnostics diagnostics) {
		Argument argument = directive.getArgument(argumentName);
		if (argument == null) {
			return null;
		}
		Value<?> value = argument.getValue();
		if (value instanceof BooleanValue flag) {
			return flag.isValue();
		}
		diagnostics.problem(location, describeArgument(argumentName)
				+ " to something other than true or false, and the arguments of that directive take literal values"
				+ at(argument));
		return null;
	}

	// Reads the scopes argument, a list of string literals, and reports a problem where
	// the file wrote anything else. The value is null where the directive leaves the
	// argument out.
	//
	// A single string is refused as well, although GraphQL's input coercion would
	// accept one for a list type. A scope list is the one argument whose shape carries
	// meaning, and a file that means one scope writes a list of one.
	private static @Nullable List<String> readScopesArgument(Directive directive, String location,
			OperationDiagnostics diagnostics) {
		Argument argument = directive.getArgument(Arguments.SCOPES);
		if (argument == null) {
			return null;
		}
		Value<?> value = argument.getValue();
		if (value instanceof ArrayValue list) {
			List<String> scopes = new ArrayList<>();
			for (Value<?> element : list.getValues()) {
				String scope = (element instanceof StringValue string) ? string.getValue() : null;
				if (scope == null) {
					diagnostics.problem(location,
							describeArgument(Arguments.SCOPES)
									+ " with an element other than a scope name, and every element is a string literal "
									+ "naming one scope" + at(element));
					return null;
				}
				String problem = ScopeNames.problemWith(scope);
				if (problem != null) {
					diagnostics.problem(location, describeArgument(Arguments.SCOPES) + " with a scope that " + problem
							+ ", and a scope is printable ASCII without a space, a quote or a backslash" + at(element));
					return null;
				}
				scopes.add(scope);
			}
			return List.copyOf(scopes);
		}
		diagnostics.problem(location, describeArgument(Arguments.SCOPES)
				+ " to something other than a list of strings, and the arguments of that directive take literal "
				+ "values" + at(argument));
		return null;
	}

	// Reads one argument whose value is a string literal holding text, and reports a
	// problem where the file wrote anything else. The value is null where the directive
	// leaves the argument out.
	//
	// A blank string is refused as well: a blank title would reach the tool definition as
	// an empty string, which a client shows in place of the name. Blank means without a
	// character a reader can see, which is wider than String.isBlank(), because a title
	// of no-break spaces passes that one and renders empty. VisibleText holds the rule,
	// which the description of a tool takes as well.
	private static @Nullable String readStringArgument(Directive directive, String argumentName, String location,
			OperationDiagnostics diagnostics) {
		Argument argument = directive.getArgument(argumentName);
		if (argument == null) {
			return null;
		}
		Value<?> value = argument.getValue();
		if (value instanceof StringValue string) {
			String text = string.getValue();
			if (text == null || VisibleText.rendersEmpty(text)) {
				diagnostics.problem(location,
						describeArgument(argumentName) + " to a blank string" + describeInvisibleCharacters(text)
								+ ", and the argument takes text a client reads; "
								+ "write it, or leave the argument out" + at(argument));
				return null;
			}
			return text;
		}
		diagnostics.problem(location,
				describeArgument(argumentName)
						+ " to something other than a string, and the arguments of that directive take literal values"
						+ at(argument));
		return null;
	}

	// Names the characters of a blank string that Java's whitespace leaves out, as in
	// ", holding U+00A0 and U+200B", or returns the empty string for a string of ordinary
	// whitespace.
	//
	// The file shows such a string as a pair of quotes around a space, or around
	// what looks like an empty string, so the problem says what the quotes hold.
	// Ordinary whitespace beside them is left unnamed, because the file shows it.
	private static String describeInvisibleCharacters(@Nullable String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		Set<String> codePoints = new LinkedHashSet<>();
		text.codePoints()
			.filter((codePoint) -> !Character.isWhitespace(codePoint))
			.forEach((codePoint) -> codePoints.add(String.format(Locale.ROOT, "U+%04X", codePoint)));
		return ", holding " + String.join(" and ", codePoints);
	}

	// Writes the line and column of a node, for the problem that names it, or the empty
	// string for a node whose location is unknown.
	//
	// The parser puts a location on every node it builds, so the empty string is for
	// a node built in code, which a test may hand in.
	private static String at(Node<?> node) {
		SourceLocation location = node.getSourceLocation();
		return (location != null) ? " (line " + location.getLine() + ", column " + location.getColumn() + ")" : "";
	}

	/**
	 * The arguments the directive takes, held apart from the directive's own
	 * {@link #NAME} and from the fields of the enclosing class that carry their values.
	 * Reading {@code Arguments.TITLE} beside a field called {@code title} says which of
	 * the two a line means, where a constant called {@code TITLE} would leave that to the
	 * capitalisation.
	 */
	private static final class Arguments {

		static final String NAME = "name";

		static final String TITLE = "title";

		static final String SCOPES = "scopes";

		static final String OPEN_WORLD = "openWorld";

		static final String OUTPUT_SCHEMA = "outputSchema";

		// An argument outside this set stops startup. graphql-java validates a document
		// against the API's own schema, which declares its own directives alone, so a
		// misspelled argument reaches this class unchallenged. The problem reported here
		// says that @gatool(nmae:) left the tool as it was.
		static final Set<String> RECOGNISED = Set.of(NAME, TITLE, SCOPES, OPEN_WORLD, OUTPUT_SCHEMA);

		private Arguments() {
		}

	}

}
