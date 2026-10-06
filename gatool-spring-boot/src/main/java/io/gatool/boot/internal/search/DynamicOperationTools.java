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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.normalized.ExecutableNormalizedOperationFactory;
import graphql.parser.InvalidSyntaxException;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.gatool.boot.internal.execution.ToolCallRunner;
import io.gatool.core.internal.operation.IncrementalDelivery;
import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.OperationValidator;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.internal.schema.JsonSchemaKeywords;
import io.gatool.core.internal.search.ReachableTypes;
import io.gatool.core.internal.search.TypeDetail;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;
import io.gatool.core.naming.ToolNamingStrategy;
import io.gatool.core.search.SchemaSearch;
import io.gatool.core.search.SearchHit;

/**
 * The three tools of the dynamic layer: search the schema, read a type, run an operation
 * the model wrote.
 *
 * <p>
 * These are the other shape GATool can take. A trusted document is one tool that came
 * through review; these three leave the operation to the model and publish only the loop
 * that helps it write one. They sit under {@code gatool.dev.experimental} for that
 * reason, and an operations folder keeps working beside them, which is what makes them a
 * fallback.
 *
 * <p>
 * The loop they serve is search, then read the types the search named, then run the
 * operation and read the validation errors. One search rarely returns everything a task
 * needs, so the second and third tools are what close the gap: a type read in full, and a
 * refusal naming what was wrong.
 *
 * <p>
 * Each tool's description carries the guidance the loop needs: the order to work in, and
 * the rule that every name the model writes must have appeared in a tool result first.
 * GATool does not write a system prompt, on either exposure type, so the descriptions are
 * where that can live.
 *
 * @author Željko Kozina
 */
public final class DynamicOperationTools {

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private static final String LOCATION_PREFIX = "dynamic:";

	// A literal newline, because this text is published to a model and reads the same on
	// every machine.
	private static final String NEW_LINE = "\n";

	private static final String DOCUMENT = "document";

	private static final String VARIABLES = "variables";

	private DynamicOperationTools() {
	}

	/**
	 * Builds the three tools.
	 * @param schema the schema GATool validates against
	 * @param search the ranking behind {@code searchSchema}
	 * @param runner the call body every GATool tool shares, so a document the model wrote
	 * meets the same size cap, the same error mapping and the same observation as a
	 * trusted document
	 * @param naming how a tool takes its name
	 * @param settings what the three tools read from the properties
	 * @return the three tools, in the order the loop calls them
	 */
	public static List<GATool> of(GraphQLSchema schema, SchemaSearch search, ToolCallRunner runner,
			ToolNamingStrategy naming, DynamicOperationSettings settings) {
		// Walked once here, because the walk covers every type of the schema and
		// introspectType answers one call.
		Set<String> reachable = ReachableTypes.of(schema, settings.includeDeprecated(), settings.allowMutations());
		// Named once here, because a tool's own messages name the tools: the hint that
		// sends a model to the reading tool, the refusal over the character limit and the
		// failure of a call. Fixed names would give a model under snake-case names the
		// tool list lacks.
		ToolNames names = new ToolNames(naming.toolName("searchSchema"), naming.toolName("introspectType"),
				naming.toolName("executeGraphql"));
		return List.of(searchSchema(search, names, settings, runner.maxCharacters()),
				introspectType(schema, names, settings, reachable, runner.maxCharacters()),
				executeGraphql(schema, runner, names, settings));
	}

	/**
	 * Cuts a schema tool's answer at the configured limit.
	 *
	 * <p>
	 * These two answer from the schema without calling the API, so they do not pass
	 * through {@link ToolCallRunner#run}, where every other tool meets this limit. The
	 * property is documented as the largest result a tool returns, so the limit is
	 * applied here as well.
	 * @param toolName the tool that answered
	 * @param outcome the answer it built
	 * @param maxCharacters the value of {@code gatool.results.max-characters}
	 * @return the answer, or a tool error naming the property
	 */
	private static ToolCallOutcome withinLimit(String toolName, ToolCallOutcome outcome, int maxCharacters) {
		// A refusal this tool wrote is short and says what to do next, so it goes back as
		// it is however the limit is set.
		if (outcome.isError()) {
			return outcome;
		}
		return withinLimit(toolName, outcome.text(), maxCharacters);
	}

	/**
	 * Cuts an answer this tool built as text.
	 * @param toolName the tool that answered
	 * @param text the answer it built
	 * @param maxCharacters the value of {@code gatool.results.max-characters}
	 * @return the answer, or a tool error naming the property
	 */
	private static ToolCallOutcome withinLimit(String toolName, String text, int maxCharacters) {
		if (text.length() <= maxCharacters) {
			return new ToolCallOutcome(text, false);
		}
		return new ToolCallOutcome("The answer of " + toolName + " is " + text.length() + " characters, and "
				+ "gatool.results.max-characters allows " + maxCharacters + ". Ask for one type, or a narrower "
				+ "question, or raise the property.", true);
	}

	private static GATool searchSchema(SchemaSearch search, ToolNames names, DynamicOperationSettings settings,
			int maxCharacters) {
		int tokenBudget = settings.searchTokenBudget();
		int rankedHits = settings.rankedHits();
		String description = orDefault(settings.descriptions().searchSchema(),
				"Finds the fields of the GraphQL schema that answer a question, ranked, as "
						+ "Type.field coordinates with their declarations. A field below the root comes with "
						+ "the path that reaches it from a root field. Start here whenever no other tool "
						+ "already does what you need. Never guess a type or field name: every name you write "
						+ "has to have appeared in a tool result first.");
		return GATool.builder()
			.name(names.search())
			.description(description)
			.inputSchema(writeInputSchema("question", "What you are looking for, in plain words."))
			.readOnly(true)
			.callHandler(
					(arguments) -> withinLimit(names.search(),
							writeHits(search, readText(arguments, "question"), tokenBudget, rankedHits,
									names.introspect()),
							maxCharacters))
			.build();
	}

	private static GATool introspectType(GraphQLSchema schema, ToolNames names, DynamicOperationSettings settings,
			Set<String> reachable, int maxCharacters) {
		String description = orDefault(settings.descriptions().introspectType(),
				"Returns the details of one type that " + names.search() + " returned: its fields with their "
						+ "arguments and return types, an enum's values, or what an input object holds. Call "
						+ "it when a search result names a type you need to look inside: an input type or an "
						+ "enum you pass as an argument, or an object type whose fields you select. "
						+ describeRoots(schema, settings.allowMutations()));
		return GATool.builder()
			.name(names.introspect())
			.description(description)
			.inputSchema(writeInputSchema("name", "The exact type name, as a search result spelled it."))
			.readOnly(true)
			// Stripped, because a model that writes "Movie " would read a refusal naming
			// that as an unknown type and go searching for one it has just seen.
			.callHandler((arguments) -> withinLimit(names.introspect(),
					readType(schema, readText(arguments, "name").strip(), settings, reachable), maxCharacters))
			.build();
	}

	// An application's description replaces the whole text, generated sentences included,
	// so what the property holds is what the model reads.
	private static String orDefault(@Nullable String configured, String written) {
		return (configured != null && !configured.isBlank()) ? configured : written;
	}

	// Writes the sentence naming the root types, which are the one thing a model may
	// write without having read them in a tool result.
	//
	// The mutation root is named only while a mutation can run, because naming it while
	// the switch is off invites the model to read a type introspectType refuses.
	private static String describeRoots(GraphQLSchema schema, boolean allowMutations) {
		String query = (schema.getQueryType() != null) ? schema.getQueryType().getName() : null;
		String mutation = (allowMutations && schema.getMutationType() != null) ? schema.getMutationType().getName()
				: null;
		if (query == null) {
			return (mutation != null) ? "The mutation root is " + mutation + "." : "";
		}
		return "The query root is " + query
				+ ((mutation != null) ? " and the mutation root is " + mutation + "." : ".");
	}

	private static GATool executeGraphql(GraphQLSchema schema, ToolCallRunner runner, ToolNames names,
			DynamicOperationSettings settings) {
		boolean allowMutations = settings.allowMutations();
		// In validate-only mode the tool reads as one that hands the operation to a
		// person, so a model keeps writing the document and stops expecting data.
		String description = orDefault(settings.descriptions().executeGraphql(),
				(settings.validateOnly()
						? "Checks a GraphQL document against the schema and returns it with its variables for a "
								+ "person to run, without calling the API. "
						: "Executes a GraphQL document against the API. ")
						+ "A document that fails validation is refused with the errors, and the fix is to read the "
						+ "types they name before trying again."
						+ (allowMutations ? "" : " Queries only: a mutation is refused.") + " Always call "
						+ names.search() + " first and " + names.execute()
						+ " after that. Where the operation uses a type you have not read, such as a filter or "
						+ "another input type you pass, call " + names.introspect() + " on it before " + names.execute()
						+ ". Do not try to guess a valid operation.");
		return GATool.builder()
			.name(names.execute())
			.description(description)
			.inputSchema(writeExecuteInputSchema())
			.readOnly(!allowMutations || settings.validateOnly())
			.callHandler((arguments) -> run(schema, runner, names.execute(), settings, arguments))
			.build();
	}

	// Writes the ranked hits for one question, taking them until the token budget is
	// spent, and answers a blank or unmatched question with what to ask instead.
	//
	// The budget is spent here instead of inside a backend, so the two rankings cannot
	// disagree about it and a third one cannot forget it. A budget at or below zero takes
	// every ranked hit.
	private static String writeHits(SchemaSearch search, String question, int tokenBudget, int rankedHits,
			String introspectToolName) {
		if (question.isBlank()) {
			return "Ask for something: this tool takes a question in plain words, such as "
					+ "\"the highest rated films\".";
		}
		List<SearchHit> hits = search.search(question, rankedHits);
		StringBuilder text = new StringBuilder();
		int spent = 0;
		for (SearchHit hit : hits) {
			if (tokenBudget > 0 && spent + hit.tokens() > tokenBudget) {
				break;
			}
			spent += hit.tokens();
			text.append(hit.coordinate()).append(':').append(NEW_LINE);
			if (hit.path() != null) {
				// How an operation reaches the owner type, so the model can write one
				// without spending a call on introspectType to work the way down.
				text.append("reach it from ").append(hit.path()).append(NEW_LINE);
			}
			text.append(hit.text()).append(NEW_LINE).append(NEW_LINE);
		}
		if (hits.isEmpty()) {
			return "Nothing in this schema matched \"" + question + "\". Try the words the API would use for it, "
					+ "or a single noun such as \"films\" or \"reviews\".";
		}
		if (text.isEmpty()) {
			// The ranking found something and the first hit alone is over the budget.
			// Told that nothing matched, the model would rephrase a question that had
			// already matched; told which coordinate it was, it can read the type or the
			// operator can raise the budget.
			SearchHit best = hits.getFirst();
			return "The best match, " + best.coordinate() + ", costs " + best.tokens() + " tokens, and "
					+ "gatool.dev.experimental.dynamic-operations.search-token-budget allows " + tokenBudget + ". Read "
					+ ownerOf(best.coordinate()) + " with " + introspectToolName + ", or raise the property.";
		}
		return text.toString().strip();
	}

	// The type a coordinate names before the dot. A SchemaSearch bean of the
	// application's own may answer with a coordinate of another shape, and that comes
	// back whole.
	private static String ownerOf(String coordinate) {
		int dot = coordinate.indexOf('.');
		return (dot > 0) ? coordinate.substring(0, dot) : coordinate;
	}

	private static ToolCallOutcome readType(GraphQLSchema schema, String typeName, DynamicOperationSettings settings,
			Set<String> reachable) {
		String declaration = TypeDetail.of(schema, typeName, settings.includeDeprecated(), reachable);
		if (declaration == null) {
			// An error instead of empty text, because the model asked for something it
			// cannot have and its next move is to search again.
			return new ToolCallOutcome(writeTypeRefusal(schema, typeName, settings.allowMutations()), true);
		}
		return new ToolCallOutcome(declaration, false);
	}

	// Writes the text a model reads when introspectType cannot show the type it asked
	// for.
	//
	// Why introspectType cannot show a declaration, in the words the model reads. Four
	// reasons, most specific first: a type the schema declares is one the model may have
	// read about elsewhere, so telling it the name does not exist would send it searching
	// for something it can already see. The sibling writeRefusal(parsed) below does the
	// same job for a document.
	private static String writeTypeRefusal(GraphQLSchema schema, String typeName, boolean allowMutations) {
		if (schema.getSubscriptionType() != null && typeName.equals(schema.getSubscriptionType().getName())) {
			return "A subscription answers over a stream, and a tool call returns a single result, so these tools "
					+ "leave the " + typeName + " type out. Look for a query that answers the same question.";
		}
		if (!allowMutations && schema.getMutationType() != null
				&& typeName.equals(schema.getMutationType().getName())) {
			// The same refusal executeGraphql gives a mutation, so the model reads the
			// reason here instead of writing the mutation and reading it there.
			return "This server runs queries here, and " + typeName + " is the mutation root, so these tools leave "
					+ "it out. gatool.dev.experimental.dynamic-operations.allow-mutations turns writing on. Look "
					+ "for a query that answers the same question.";
		}
		if (schema.getType(typeName) != null && !typeName.startsWith("__")) {
			return "This schema declares '" + typeName + "', and no query" + (allowMutations ? " or mutation" : "")
					+ " reaches it, so these tools leave it out. Search for what you need: a coordinate names its "
					+ "type before the dot.";
		}
		return "This schema does not declare a type named '" + typeName + "'. Search for what you need: a coordinate "
				+ "names its type before the dot.";
	}

	// Validates the document the model wrote, then refuses three shapes a single result
	// cannot answer, several operations in one document, a subscription and incremental
	// delivery, a fourth the allow-mutations switch withholds, introspection, and a
	// document above the size limits. What is left runs through the shared runner, or
	// comes back as written in validate-only mode.
	private static ToolCallOutcome run(GraphQLSchema schema, ToolCallRunner runner, String toolName,
			DynamicOperationSettings settings, Map<String, @Nullable Object> arguments) {
		boolean allowMutations = settings.allowMutations();
		String document = readText(arguments, DOCUMENT);
		if (document.isBlank()) {
			return new ToolCallOutcome("Send a GraphQL operation as the document argument.", true);
		}
		Map<String, @Nullable Object> variables = readVariables(arguments);
		if (variables == null) {
			// A list or a scalar in the variables slot is refused, because replacing it
			// with an empty object would run the operation with its variables missing,
			// and the model would read an answer to a question it did not ask.
			return new ToolCallOutcome("Send the variables as a JSON object holding each variable by name, such "
					+ "as {\"first\": 10}, or leave the argument out.", true);
		}
		// Parsing is graphql-java's, and validation is the check every operation file
		// passes, OneOf rules included, so the two paths agree about one document. The
		// locale pins graphql-java's syntax messages to English, which is the language of
		// the sentences this tool wraps them in. An ExecutionInput built without one
		// carries the locale of the host JVM.
		ParseAndValidateResult parsed = ParseAndValidate
			.parse(ExecutionInput.newExecutionInput(document).locale(Locale.ROOT).build());
		if (parsed.isFailure()) {
			return new ToolCallOutcome(writeRefusal(parsed.getSyntaxException(), List.of()), true);
		}
		List<GraphQLError> errors = List.copyOf(OperationValidator.validate(schema, parsed.getDocument()));
		if (!errors.isEmpty()) {
			return new ToolCallOutcome(writeRefusal(null, errors), true);
		}
		OperationDefinition operation = findSoleOperation(parsed.getDocument());
		if (operation == null) {
			return new ToolCallOutcome("Send one operation. A document holding several does not say which to run.",
					true);
		}
		ToolCallOutcome refusal = refuseWhatASingleResultCannotAnswer(parsed.getDocument(), operation, allowMutations);
		if (refusal == null) {
			refusal = refuseWhatTheToolsAnswerOrTheLimitsStop(parsed.getDocument(), operation, settings);
		}
		if (refusal != null) {
			return refusal;
		}
		// A mutation, otherwise a query, which the refusal above makes true.
		OperationType type = (operation.getOperation() == OperationDefinition.Operation.MUTATION)
				? OperationType.MUTATION : OperationType.QUERY;
		if (settings.validateOnly()) {
			return writeDocumentAndVariables(document, variables);
		}
		return runner.run(adHocOperation(toolName, operation, type, document), variables);
	}

	// Refuses the three shapes a single result cannot answer, a subscription, incremental
	// delivery and a mutation while the switch withholds it, or returns null where the
	// operation is one that runs.
	private static @Nullable ToolCallOutcome refuseWhatASingleResultCannotAnswer(Document document,
			OperationDefinition operation, boolean allowMutations) {
		if (operation.getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
			// The same refusal OperationFileParser makes for a file, in the words the
			// model reads. Validation in graphql-java accepts a subscription against a
			// schema that declares one, so nothing below this stops it, and the ternary
			// that follows would read it as a query. The model would then get a
			// successful result whose data is the Java bean properties of graphql-java's
			// publisher.
			return new ToolCallOutcome("That document is a subscription, and a tool call returns a single result, "
					+ "so only a query or a mutation runs here. The fields of the subscription type answer over a "
					+ "stream this server does not open.", true);
		}
		List<String> incremental = IncrementalDelivery.directivesIn(document);
		if (!incremental.isEmpty()) {
			// Same rule, the other way of breaking it. graphql-java declares @defer in
			// every schema it builds, so validation passes such a document and only this
			// refuses it.
			return new ToolCallOutcome("That document uses @" + String.join(" and @", incremental)
					+ ", and a tool call returns a single result, so an operation that asks for its answer in "
					+ "pieces cannot run here. Select the fields you need and read them in one answer.", true);
		}
		if (operation.getOperation() == OperationDefinition.Operation.MUTATION && !allowMutations) {
			return new ToolCallOutcome("This server runs queries here, and that document is a mutation. "
					+ "gatool.dev.experimental.dynamic-operations.allow-mutations turns writing on, and it is an "
					+ "unsafe switch because the operation is one nobody reviewed.", true);
		}
		return null;
	}

	// Refuses a document that asks the API for what the schema tools answer, and one
	// above the size limits, or returns null where the document fits.
	private static @Nullable ToolCallOutcome refuseWhatTheToolsAnswerOrTheLimitsStop(Document document,
			OperationDefinition operation, DynamicOperationSettings settings) {
		DocumentShape shape = DocumentShape.of(document, operation, fieldCeiling(settings));
		if (!shape.introspectionFields().isEmpty()) {
			// The two schema tools answer what introspection would, within a budget and
			// without hidden fields, so a document asking the API directly is sent back
			// to them. __typename stays, because a result needs it to name a member.
			return new ToolCallOutcome(
					"That document selects " + String.join(" and ", shape.introspectionFields())
							+ ", and introspection runs through the schema tools here: search the schema for what you "
							+ "need, and read one type in full by its name. __typename inside a selection is fine.",
					true);
		}
		String excess = describeExcess(shape, settings);
		if (excess != null) {
			return new ToolCallOutcome(
					"That document is larger than this server runs: " + excess
							+ ". Select fewer fields, nest less deeply, or split the question into two operations.",
					true);
		}
		return null;
	}

	// Returns the document and the variables as written, which is what validate-only mode
	// answers with.
	//
	// The document passed every check, and the person reading the transcript
	// runs it. The text holds the operation as the model wrote it beside the
	// variables it sent, so what it wrote is what a test or a benchmark reads.
	private static ToolCallOutcome writeDocumentAndVariables(String document, Map<String, @Nullable Object> variables) {
		ObjectNode documentAndVariables = JSON_MAPPER.createObjectNode();
		documentAndVariables.put(DOCUMENT, document);
		documentAndVariables.set(VARIABLES, JSON_MAPPER.valueToTree(variables));
		return new ToolCallOutcome(JSON_MAPPER.writeValueAsString(documentAndVariables), false);
	}

	// Returns the operation the shared runner runs the model's document as.
	//
	// The runner is the call body every GATool tool shares, so this document meets the
	// same response cap, the same refusal text and the same observation as a trusted
	// document. The location says where it came from, for a message that names it. The
	// model wrote this document, so it carries the values it meant, and the null rules do
	// not have anything to protect here, without a variable of its own or an input type
	// to walk. The tool name is this tool's, because every message the runner writes
	// opens with it: named after the operation, a failure would read "The tool Top could
	// not reach the GraphQL API", and "The tool could not reach" for an anonymous
	// document. The operation name stays the document's own, because the executors send
	// it to the API.
	private static ToolOperation adHocOperation(String toolName, OperationDefinition operation, OperationType type,
			String document) {
		return ToolOperation.builder()
			.toolName(toolName)
			.operationName(nameOf(operation))
			.operationType(type)
			.printedDocument(document)
			.inputSchema("{}")
			.location(LOCATION_PREFIX + nameOf(operation))
			.build();
	}

	// Returns which limit the document exceeds, in the words the model reads, or null
	// where it fits.
	//
	// A limit at or below zero is off, so a team measuring its own agents can lift one
	// limit at a time. The depth and alias excesses name the measured value beside the
	// limit, so a model can tell how far it has to shrink the document. The field
	// excess names the ceiling alone, because the walk stops there and leaves the exact
	// count unmeasured. A document past the ceiling is refused whether the field limit
	// is on (the ceiling is the limit) or off (the ceiling is graphql-java's own).
	private static @Nullable String describeExcess(DocumentShape shape, DynamicOperationSettings settings) {
		if (shape.truncated()) {
			return "it selects more fields than the limit of " + fieldCeiling(settings) + ", counting every "
					+ "fragment where it is spread";
		}
		if (settings.maxDepth() > 0 && shape.depth() > settings.maxDepth()) {
			return "it nests fields " + shape.depth() + " levels deep, and the limit is " + settings.maxDepth();
		}
		if (settings.maxAliases() > 0 && shape.aliases() > settings.maxAliases()) {
			return "it uses " + shape.aliases() + " aliases, and the limit is " + settings.maxAliases();
		}
		return null;
	}

	// With the field limit off, the walk still stops at the count graphql-java's own
	// normalised operation refuses by default, so a document built to explode the
	// count is refused before it costs anything.
	private static int fieldCeiling(DynamicOperationSettings settings) {
		return (settings.maxFields() > 0) ? settings.maxFields()
				: ExecutableNormalizedOperationFactory.Options.DEFAULT_MAX_FIELDS_COUNT;
	}

	// Writes the text a model reads for a document that failed to parse or validate:
	// every error, and the move that fixes it.
	//
	// graphql-java reports a syntax error and a validation error through the same result.
	// The hint matters as much as the errors: they name types, and reading those types is
	// the move that fixes the document.
	private static String writeRefusal(@Nullable InvalidSyntaxException syntax, List<GraphQLError> errors) {
		StringBuilder text = new StringBuilder("That document was refused:");
		if (syntax != null) {
			text.append(NEW_LINE).append("  - ").append(syntax.getMessage());
		}
		for (GraphQLError error : errors) {
			text.append(NEW_LINE).append("  - ").append(error.getMessage());
		}
		return text.append(NEW_LINE)
			.append("Read every type the errors name before trying again, and write only names that have "
					+ "appeared in a tool result.")
			.toString();
	}

	private static @Nullable OperationDefinition findSoleOperation(@Nullable Document document) {
		if (document == null) {
			return null;
		}
		List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
		return (operations.size() == 1) ? operations.getFirst() : null;
	}

	// GraphQL asks for an operation name once a document holds several, and this path
	// takes one operation, so an anonymous document is legal and travels without a name.
	// The executors leave the operationName out of the request for an empty one.
	private static String nameOf(OperationDefinition operation) {
		return (operation.getName() != null) ? operation.getName() : "";
	}

	// Returns the variables the call sent, an empty map where it left the argument out,
	// or null where it sent something other than a JSON object.
	@SuppressWarnings("unchecked")
	private static @Nullable Map<String, @Nullable Object> readVariables(Map<String, @Nullable Object> arguments) {
		Object variables = arguments.get(VARIABLES);
		if (variables == null) {
			return Map.of();
		}
		return (variables instanceof Map<?, ?> map) ? (Map<String, @Nullable Object>) map : null;
	}

	private static String readText(Map<String, @Nullable Object> arguments, String name) {
		Object value = arguments.get(name);
		return (value instanceof String string) ? string : "";
	}

	// Writes the input schema of a tool that takes one required string argument.
	//
	// The same shape the input schema writer publishes for an operation, so a tool of
	// this layer reads to a model exactly like one from a trusted document.
	private static String writeInputSchema(String argument, String description) {
		ObjectNode schema = createSchemaRoot();
		ObjectNode property = schema.get(JsonSchemaKeywords.PROPERTIES).withObjectProperty(argument);
		property.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_STRING);
		property.put(JsonSchemaKeywords.DESCRIPTION, description);
		schema.withArrayProperty(JsonSchemaKeywords.REQUIRED).add(argument);
		schema.put(JsonSchemaKeywords.ADDITIONAL_PROPERTIES, false);
		return JSON_MAPPER.writeValueAsString(schema);
	}

	private static String writeExecuteInputSchema() {
		ObjectNode schema = createSchemaRoot();
		ObjectNode properties = (ObjectNode) schema.get(JsonSchemaKeywords.PROPERTIES);
		ObjectNode document = properties.withObjectProperty(DOCUMENT);
		document.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_STRING);
		document.put(JsonSchemaKeywords.DESCRIPTION, "The complete GraphQL operation, as one document.");
		ObjectNode variables = properties.withObjectProperty(VARIABLES);
		variables.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		variables.put(JsonSchemaKeywords.DESCRIPTION, "The variables the operation declares, by name.");
		schema.withArrayProperty(JsonSchemaKeywords.REQUIRED).add(DOCUMENT);
		schema.put(JsonSchemaKeywords.ADDITIONAL_PROPERTIES, false);
		return JSON_MAPPER.writeValueAsString(schema);
	}

	private static ObjectNode createSchemaRoot() {
		ObjectNode schema = JSON_MAPPER.createObjectNode();
		schema.put(JsonSchemaKeywords.SCHEMA, JsonSchemaKeywords.DIALECT);
		schema.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		schema.putObject(JsonSchemaKeywords.PROPERTIES);
		return schema;
	}

	/**
	 * The three tool names as the strategy spells them.
	 *
	 * @param search the search tool
	 * @param introspect the reading tool
	 * @param execute the running tool
	 */
	private record ToolNames(String search, String introspect, String execute) {
	}

}
