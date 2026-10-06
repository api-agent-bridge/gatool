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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.gatool.core.internal.operation.OperationDiagnostics;

/**
 * The JSON Schema configured for one custom scalar, checked before any tool is published.
 *
 * <p>
 * A custom scalar's name is only a name: the values its API takes live in coercion the
 * schema leaves out. The schema adds little more, since of the 31 scalars in the
 * community registry three declare a {@code @specifiedBy} URL and the rest declare only a
 * name. GATool cannot know the format, and whoever owns the API can, so
 * {@code gatool.inputs.scalar-schemas.<Name>} is where the format is stated, and GATool
 * writes it into the input schema wherever that scalar appears.
 *
 * <p>
 * The keyword set is closed. A refusal names the client that forces it. A keyword still
 * unmeasured against any client is refused for that reason, because a closed set starts
 * from what has been measured. Six keywords are published: {@code type}, {@code format},
 * {@code pattern}, {@code enum}, {@code description} and {@code anyOf}. The reason for a
 * closed set instead of a check against the JSON Schema meta-schema is that a schema is
 * read by two audiences with narrower rules than the meta-schema has. It is read by LLM
 * clients, whose strict modes refuse keywords a valid schema may carry, and it is
 * asserted by the MCP Java SDK's validator before a tool call runs. Against the Anthropic
 * Messages API, a tool carrying {@code minimum} with {@code strict: true} answers
 * {@code 400 ... For 'integer' type, properties maximum, minimum are not supported}, and
 * {@code oneOf} answers {@code Schema type 'oneOf' is not supported}.
 *
 * <p>
 * Every rule below follows from one division: an annotating keyword can only mislead a
 * model, while an asserting keyword can refuse, inside GATool's own process, a value the
 * GraphQL API would have taken. The keyword {@code format} annotates: the SDK's validator
 * accepts {@code "not-a-date"} under {@code format: date-time}. The keyword
 * {@code pattern} asserts: the same validator refuses {@code "ABC"} under
 * {@code ^[a-z]+$}. So {@code pattern} carries the strictest rules here, and the guidance
 * is to write one that over-accepts and leave the real refusal to the API.
 *
 * <p>
 * The output schema publishes a scalar from the same fragment, reduced by
 * {@link #forResults(ScalarSchemas)} to what holds for a result, so the format is stated
 * once. {@code gatool.results.scalar-schemas.<Name>} holds a fragment for the one scalar
 * that returns another form than it accepts, read by
 * {@link #ofResults(GraphQLSchema, Map, OperationDiagnostics)} under the rules above.
 *
 * @author Željko Kozina
 */
public final class ScalarSchemas {

	/**
	 * The configuration property these schemas come from. Every message names it, so the
	 * message points at the line to change.
	 */
	public static final String PROPERTY = "gatool.inputs.scalar-schemas";

	/**
	 * The configuration property that holds the schema a scalar has in a result, where
	 * that differs from what {@link #PROPERTY} says. A message about one of its entries
	 * names it.
	 */
	public static final String RESULTS_PROPERTY = "gatool.results.scalar-schemas";

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private static final ScalarSchemas NONE = new ScalarSchemas(Map.of());

	// The six keywords GATool publishes. Everything else is refused by name, which is
	// what makes the set closed: a keyword nobody has checked against a client cannot
	// arrive by being merely valid JSON Schema.
	private static final Set<String> PUBLISHED_KEYWORDS = Set.of(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.FORMAT,
			JsonSchemaKeywords.PATTERN, JsonSchemaKeywords.ENUM, JsonSchemaKeywords.DESCRIPTION,
			JsonSchemaKeywords.ANY_OF);

	// object and array are left out because a scalar is one value, and an Input Object is
	// how GraphQL spells a structured argument. null is left out because GATool owns
	// nullability: it writes the null branch from the GraphQL type.
	private static final Set<String> ALLOWED_TYPES = Set.of(JsonSchemaKeywords.TYPE_STRING,
			JsonSchemaKeywords.TYPE_INTEGER, JsonSchemaKeywords.TYPE_NUMBER, JsonSchemaKeywords.TYPE_BOOLEAN);

	// The ten formats Anthropic documents. JSON Schema 2020-12 defines more. This is not
	// an intersection: OpenAI's strict subset leaves uri out, which is why a schema
	// writing it publishes with a warning instead of being refused. An application
	// deploying to Anthropic alone can write it safely.
	private static final Set<String> ALLOWED_FORMATS = Set.of(JsonSchemaKeywords.FORMAT_DATE_TIME,
			JsonSchemaKeywords.FORMAT_TIME, JsonSchemaKeywords.FORMAT_DATE, JsonSchemaKeywords.FORMAT_DURATION,
			JsonSchemaKeywords.FORMAT_EMAIL, JsonSchemaKeywords.FORMAT_HOSTNAME, JsonSchemaKeywords.FORMAT_URI,
			JsonSchemaKeywords.FORMAT_IPV4, JsonSchemaKeywords.FORMAT_IPV6, JsonSchemaKeywords.FORMAT_UUID);

	private static final Set<String> SPECIFIED_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");

	// Anthropic compiles a pattern under strict mode and answers an unsupported construct
	// with 400 Invalid regex in pattern field. Lookaround, a backreference, a named or
	// atomic group, a possessive quantifier and a word boundary are the constructs its
	// documented subset leaves out. An escape counts only where a backslash does not
	// precede it, so \\1 reads as a literal backslash and a digit, and \++ as one or more
	// literal plus signs.
	private static final Pattern UNSUPPORTED_REGEX_CONSTRUCT = Pattern
		.compile("\\(\\?[=!>]|\\(\\?<|(?<!\\\\)\\\\[1-9]|(?<!\\\\)\\\\[bB]|(?<!\\\\)[*+?}]\\+");

	private static final int MAX_PATTERN_LENGTH = 200;

	private static final int MAX_DESCRIPTION_LENGTH = 1024;

	// The schema travels in every tools/list and in every prompt that carries the tool,
	// once per argument that reaches the scalar.
	private static final int MAX_SCALAR_SCHEMA_LENGTH = 2048;

	private static final int MAX_ENUM_VALUES = 250;

	private static final int MIN_BRANCHES = 2;

	private static final int MAX_BRANCHES = 8;

	// The keywords of a fragment that hold for a result as they hold for an argument,
	// in the order a branch is written.
	private static final List<String> RESULT_KEYWORDS = List.of(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.FORMAT);

	private final Map<String, ObjectNode> schemasByScalar;

	private ScalarSchemas(Map<String, ObjectNode> schemasByScalar) {
		this.schemasByScalar = schemasByScalar;
	}

	/**
	 * Which of the two maps a fragment was written in: the property a message names, and
	 * the sentences that differ because one map describes what a model sends and the
	 * other what the API returns.
	 */
	// One set of rules checks both maps, and most messages read the same for both. Three
	// sentences differ, because the input side's wording is untrue of a result: whether
	// the fragment describes what the API takes or what it returns, what an enum that
	// contradicts its type costs, and what removing the entry leaves the scalar with.
	private enum Origin {

		INPUTS(PROPERTY, "takes", "every call carrying this argument is refused before it reaches the API"),

		RESULTS(RESULTS_PROPERTY, "returns",
				"every result carrying this scalar fails the output schema after the API ran the call");

		private final String property;

		private final String whatTheApiDoes;

		private final String whatAnUnsatisfiableEnumCosts;

		Origin(String property, String whatTheApiDoes, String whatAnUnsatisfiableEnumCosts) {
			this.property = property;
			this.whatTheApiDoes = whatTheApiDoes;
			this.whatAnUnsatisfiableEnumCosts = whatAnUnsatisfiableEnumCosts;
		}

		// The sentence every refusal of an empty schema ends with, at the top and inside
		// a branch, because the fix is the same in both places.
		String whatASchemaSays() {
			return "A schema says what the API " + this.whatTheApiDoes + " for this scalar, so give ";
		}

		String whatRemovingTheEntryDoes(String declaredName) {
			return (this == INPUTS) ? "let the scalar keep the empty schema."
					: "let the output schema reuse what " + PROPERTY + "." + declaredName + " says.";
		}

	}

	/**
	 * Returns the schemas for an application whose configuration leaves them out.
	 * @return an empty set of schemas
	 */
	public static ScalarSchemas none() {
		return NONE;
	}

	/**
	 * Reads and checks the configured scalar schemas.
	 *
	 * <p>
	 * Every failure is a problem on the diagnostics, so an application with a bad scalar
	 * schema refuses to start and reads every problem at once, the way it does for a bad
	 * operation file.
	 * @param schema the schema every operation validates against
	 * @param configuredByScalarName the value of {@code gatool.inputs.scalar-schemas}
	 * @param diagnostics collects the problems and warnings
	 * @return the scalar schemas that passed, by scalar name
	 */
	public static ScalarSchemas of(GraphQLSchema schema, Map<String, ?> configuredByScalarName,
			OperationDiagnostics diagnostics) {
		return read(schema, configuredByScalarName, Origin.INPUTS, diagnostics);
	}

	/**
	 * Reads and checks the scalar schemas configured for results, which take the place of
	 * what the output schema would reuse from the input fragments.
	 *
	 * <p>
	 * The rules are the ones {@link #of(GraphQLSchema, Map, OperationDiagnostics)}
	 * applies, and every problem and warning names {@code gatool.results.scalar-schemas}.
	 * @param schema the schema every operation validates against
	 * @param configuredByScalarName the value of {@code gatool.results.scalar-schemas}
	 * @param diagnostics collects the problems and warnings
	 * @return the scalar schemas that passed, by scalar name
	 */
	public static ScalarSchemas ofResults(GraphQLSchema schema, Map<String, ?> configuredByScalarName,
			OperationDiagnostics diagnostics) {
		return read(schema, configuredByScalarName, Origin.RESULTS, diagnostics);
	}

	private static ScalarSchemas read(GraphQLSchema schema, Map<String, ?> configuredByScalarName, Origin origin,
			OperationDiagnostics diagnostics) {
		if (configuredByScalarName.isEmpty()) {
			return NONE;
		}
		Map<String, ObjectNode> schemasByScalar = new LinkedHashMap<>();
		configuredByScalarName.forEach((scalarName, configuredValue) -> {
			String location = "The property " + origin.property + "." + scalarName;
			String declaredName = resolveDeclaredScalar(schema, scalarName, location, diagnostics);
			if (declaredName == null) {
				return;
			}
			JsonNode scalarSchema = withIndexedObjectsAsArrays(JSON_MAPPER.valueToTree(configuredValue));
			if (!(scalarSchema instanceof ObjectNode object)) {
				diagnostics.problem(location,
						"takes a JSON Schema object, and this one is " + describe(scalarSchema) + ".");
				return;
			}
			int problemsBefore = diagnostics.problemCount();
			refuse(object, location, false, origin, diagnostics);
			if (object.isEmpty()) {
				diagnostics.problem(location,
						"is empty. " + origin.whatASchemaSays() + "it a type, a format, a "
								+ "pattern, an enum, an anyOf or at least a description, or remove the entry and "
								+ origin.whatRemovingTheEntryDoes(declaredName));
			}
			if (JSON_MAPPER.writeValueAsString(object).length() > MAX_SCALAR_SCHEMA_LENGTH) {
				diagnostics.problem(location, "is longer than " + MAX_SCALAR_SCHEMA_LENGTH + " characters, and the "
						+ "schema travels in every tools/list and in every prompt that carries the tool.");
			}
			if (diagnostics.problemCount() == problemsBefore) {
				warn(object, location, diagnostics);
				// Stored under the name the schema spells, because relaxed binding may
				// have lowercased the one the configuration wrote.
				schemasByScalar.put(declaredName, object);
			}
		});
		return schemasByScalar.isEmpty() ? NONE : new ScalarSchemas(Map.copyOf(schemasByScalar));
	}

	/**
	 * Returns the schemas the output schema publishes: the override where
	 * {@code gatool.results.scalar-schemas} holds one for the scalar, as written, and
	 * these fragments for every other scalar, reduced to their {@code type}, their
	 * {@code format} and their {@code description}.
	 * @param overrides the schemas
	 * {@link #ofResults(GraphQLSchema, Map, OperationDiagnostics)} read
	 * @return the schema of each scalar in a result, by scalar name
	 */
	// The operator states the format of a scalar once, for the arguments, and a result
	// reuses it, so one tool says the same about a scalar on the way in and on the way
	// out. A second map filled for every scalar would state each format twice.
	//
	// The reuse stops at type and format, which describe the wire form a scalar
	// normally shares in both directions. The enum and the pattern stay on the input
	// side, at the top and inside every branch. A fragment says what a model should
	// send, and an operator may write it narrower than the API on purpose: an enum
	// listing fewer values than the API holds, or a pattern asking for 2026-09-28 while
	// the API returns 2026-09-28T00:00:00Z. An output schema is binding in MCP: a
	// result that fails it is reported as an error after the API already ran the call,
	// and for a mutation that invites a retry of a write that was applied.
	//
	// An entry of the results map is published as written, because the operator wrote
	// its enum and its pattern about results. It covers the scalar that returns another
	// wire form than it accepts.
	public ScalarSchemas forResults(ScalarSchemas overrides) {
		Map<String, ObjectNode> published = new LinkedHashMap<>();
		this.schemasByScalar.forEach((scalarName, fragment) -> {
			if (overrides.schemasByScalar.containsKey(scalarName)) {
				return;
			}
			// Kept where the reduction left it empty as well, so the output schema reads
			// that the operator described this scalar and leaves the table of
			// specifications out of it, as the input schema does.
			published.put(scalarName, reduceToWhatHoldsForAResult(fragment));
		});
		published.putAll(overrides.schemasByScalar);
		return published.isEmpty() ? NONE : new ScalarSchemas(Map.copyOf(published));
	}

	// Returns the fragment with the type and the format of each branch and its own
	// description, each branch kept once.
	//
	// Two branches that differed in their patterns alone are one branch without them,
	// and a single branch is written without an anyOf of its own. A branch left without
	// a type, which an enum written alone produces, accepts every value, so the anyOf as
	// a whole does and the reduction states the empty schema, which says the same in
	// fewer characters.
	private static ObjectNode reduceToWhatHoldsForAResult(ObjectNode fragment) {
		List<ObjectNode> branches = new ArrayList<>();
		for (ObjectNode branch : branchesOf(fragment)) {
			ObjectNode kept = JSON_MAPPER.createObjectNode();
			for (String keyword : RESULT_KEYWORDS) {
				if (branch.has(keyword)) {
					kept.set(keyword, branch.get(keyword));
				}
			}
			if (kept.isEmpty()) {
				branches.clear();
				break;
			}
			if (!branches.contains(kept)) {
				branches.add(kept);
			}
		}
		ObjectNode reduced = JSON_MAPPER.createObjectNode();
		if (branches.size() == 1) {
			reduced.setAll(branches.getFirst());
		}
		else if (!branches.isEmpty()) {
			reduced.putArray(JsonSchemaKeywords.ANY_OF).addAll(branches);
		}
		if (fragment.has(JsonSchemaKeywords.DESCRIPTION)) {
			reduced.set(JsonSchemaKeywords.DESCRIPTION, fragment.get(JsonSchemaKeywords.DESCRIPTION));
		}
		return reduced;
	}

	/**
	 * Returns the schema configured for one scalar.
	 * @param scalarName the name the schema declares
	 * @return a copy of the schema, or {@code null} for a scalar the configuration leaves
	 * out
	 */
	// A copy, because the writer sets the fragment's keywords into the property it
	// builds, and a caller that then edits that property would be editing the
	// fragment every later tool reads.
	public @Nullable ObjectNode schemaFor(String scalarName) {
		ObjectNode configured = this.schemasByScalar.get(scalarName);
		return (configured != null) ? configured.deepCopy() : null;
	}

	/**
	 * Returns the name the schema declares for one configured key, or {@code null} where
	 * the key names a scalar the specification defines, matches two the schema declares,
	 * or fails to match any. Each case is reported as a problem.
	 *
	 * <p>
	 * A key that fails to match a custom scalar would stay unused, and a typo is the
	 * likely cause, so the message lists what the schema declares.
	 * @param schema the schema whose scalars the key is matched against
	 * @param scalarName the configured key
	 * @param location the configuration property, for any problem
	 * @param diagnostics receives the problem
	 * @return the name the schema declares, or {@code null} where the key is refused
	 */
	// The order of the checks decides what a key such as Id or string means, which
	// differs from a specified name in case alone. GraphQL names are case-sensitive, so a
	// schema may declare scalar Id or scalar string beside the ID and the String of the
	// specification. A key spelling a specified name exactly names that scalar. Any other
	// key is looked up among the custom scalars first, so Id, and the id an environment
	// variable binds it under, find the scalar the schema declares. A key left unmatched
	// is then compared with the specified names without regard to case: relaxed binding
	// lowercases a name from an environment variable, so string most likely means String
	// there, and the message saying GATool maps those itself is the one that helps.
	// Running that comparison first would refuse a fragment for a scalar declared as Id
	// or string.
	private static @Nullable String resolveDeclaredScalar(GraphQLSchema schema, String scalarName, String location,
			OperationDiagnostics diagnostics) {
		if (SPECIFIED_SCALARS.contains(scalarName)) {
			reportSpecifiedScalar(location, diagnostics);
			return null;
		}
		String declaredName = findMatchingScalar(schema, scalarName);
		if (declaredName != null) {
			return declaredName;
		}
		if (SPECIFIED_SCALARS.stream().anyMatch((name) -> name.equalsIgnoreCase(scalarName))) {
			reportSpecifiedScalar(location, diagnostics);
			return null;
		}
		diagnostics.problem(location,
				"does not name a custom scalar in the schema. The schema declares: "
						+ String.join(", ", listCustomScalarNames(schema)) + ". A name is matched without regard to "
						+ "case, so an environment variable can set one.");
		return null;
	}

	private static void reportSpecifiedScalar(String location, OperationDiagnostics diagnostics) {
		diagnostics.problem(location,
				"names a scalar the GraphQL specification defines, and GATool maps those "
						+ "itself. ID in particular accepts a string or an integer, which GraphQL's own input "
						+ "coercion takes.");
	}

	// Returns the name the schema spells for one configured scalar key, matching exactly
	// first and then without regard to case. The result is null unless exactly one scalar
	// matches.
	//
	// Relaxed binding lowercases a name that arrives from an environment variable, so
	// GATOOL_INPUTS_SCALARSCHEMAS_STAMP_TYPE binds under "stamp" and an exact match would
	// refuse a scalar the schema spells Stamp. Picking between two case-insensitive
	// matches would be a guess.
	//
	// Both matches run over the custom scalars alone. The caller has already answered a
	// key that spells a specified name exactly, and the second match reads the list
	// that leaves the specified scalars out, so a key such as id finds a custom Id and
	// cannot find ID.
	private static @Nullable String findMatchingScalar(GraphQLSchema schema, String scalarName) {
		if (schema.getTypeAs(scalarName) instanceof GraphQLScalarType) {
			return scalarName;
		}
		List<String> matches = listCustomScalarNames(schema).stream()
			.filter((name) -> name.equalsIgnoreCase(scalarName))
			.toList();
		return (matches.size() == 1) ? matches.getFirst() : null;
	}

	// Returns the custom scalar names the schema declares, sorted, or the single entry
	// "none" for a schema declaring only the scalars the specification defines.
	private static List<String> listCustomScalarNames(GraphQLSchema schema) {
		List<String> names = schema.getAllTypesAsList()
			.stream()
			.filter(GraphQLScalarType.class::isInstance)
			.map(GraphQLNamedType::getName)
			.filter((name) -> !SPECIFIED_SCALARS.contains(name))
			.sorted()
			.toList();
		return names.isEmpty() ? List.of("none") : names;
	}

	private static void refuse(ObjectNode scalarSchema, String location, boolean inBranch, Origin origin,
			OperationDiagnostics diagnostics) {
		refuseUnknownKeywords(scalarSchema, location, diagnostics);
		refuseUnsupportedType(scalarSchema, location, diagnostics);
		refuseUnsupportedFormat(scalarSchema, location, diagnostics);
		refuseUnsafePattern(scalarSchema, location, diagnostics);
		refuseInvalidEnum(scalarSchema, location, origin, diagnostics);
		refuseMisplacedDescription(scalarSchema, location, inBranch, diagnostics);
		refuseInvalidAnyOf(scalarSchema, location, inBranch, origin, diagnostics);
	}

	// Reports every keyword outside the six GATool publishes, with the reason that
	// keyword is left out.
	//
	// The refusal teaches the fix. A message reading only "minimum is not allowed"
	// leaves the reason out, and the keyword comes back in the next schema.
	private static void refuseUnknownKeywords(ObjectNode scalarSchema, String location,
			OperationDiagnostics diagnostics) {
		for (String keyword : scalarSchema.propertyNames()) {
			if (PUBLISHED_KEYWORDS.contains(keyword)) {
				continue;
			}
			diagnostics.problem(location, "carries the keyword '" + keyword + "', and GATool publishes "
					+ String.join(", ", new TreeSet<>(PUBLISHED_KEYWORDS)) + ". " + reasonFor(keyword));
		}
	}

	private static String reasonFor(String keyword) {
		return switch (keyword) {
			case "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf" ->
				"Anthropic's strict tool use answers a numeric bound with HTTP 400: "
						+ "\"For 'integer' type, properties maximum, minimum are not supported\". State the bound in "
						+ "description instead, which is what Anthropic's own SDKs do.";
			case "oneOf" -> "Anthropic's strict tool use answers it with HTTP 400, \"Schema type 'oneOf' is not "
					+ "supported\", and OpenAI's SDKs rewrite it. Write anyOf.";
			case "allOf", "not", "if", "then", "else", "dependentRequired", "dependentSchemas" ->
				"OpenAI's structured outputs guide names it among the compositions it leaves unsupported.";
			case "minLength", "maxLength", "minItems", "maxItems", "uniqueItems", "prefixItems", "contains" ->
				"The client subsets disagree about it, and no measurement settles it. State the limit in "
						+ "description.";
			case "examples" -> "It does not appear in either client subset. Write the example into description, "
					+ "as prose: a model reads it there and both vendors document that path.";
			case JsonSchemaKeywords.DEFAULT -> "GATool writes default itself, from the default the schema or the "
					+ "operation declares, coerced the way GraphQL coerces it.";
			case JsonSchemaKeywords.CONST -> "Write a one-member enum, which both clients document.";
			case "$ref", "$defs", "$id", "$schema", "$anchor" ->
				"GATool inlines every schema it writes, so each one is complete on its own.";
			case JsonSchemaKeywords.PROPERTIES, JsonSchemaKeywords.REQUIRED, JsonSchemaKeywords.ADDITIONAL_PROPERTIES,
					JsonSchemaKeywords.ITEMS ->
				"A scalar is one value. A structured argument is an Input Object in the schema, which GATool "
						+ "describes from the schema itself.";
			case "nullable" -> "That is OpenAPI 3.0, and JSON Schema does not define it. GATool writes the null "
					+ "branch itself, from the GraphQL type.";
			default -> "GATool publishes a closed set, because a keyword nobody has measured against a client "
					+ "can refuse a whole tool.";
		};
	}

	// Reports a type outside the four a single scalar value can take.
	private static void refuseUnsupportedType(ObjectNode scalarSchema, String location,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.TYPE)) {
			return;
		}
		JsonNode type = scalarSchema.get(JsonSchemaKeywords.TYPE);
		if (type.isString() && ALLOWED_TYPES.contains(type.asString())) {
			return;
		}
		String reason = switch (type.isString() ? type.asString() : "") {
			case JsonSchemaKeywords.TYPE_OBJECT, JsonSchemaKeywords.TYPE_ARRAY -> "A scalar is one value, and a "
					+ "structured argument is an Input Object in the schema, which GATool describes from the schema "
					+ "itself.";
			case JsonSchemaKeywords.TYPE_NULL -> "GATool writes the null branch itself, from the GraphQL type.";
			default -> "";
		};
		diagnostics.problem(location, ("takes one of " + String.join(", ", new TreeSet<>(ALLOWED_TYPES))
				+ " for type, and this one is " + describe(type) + ". " + reason)
			.strip());
	}

	// Reports a format outside the ten GATool publishes, and a format written without
	// type: string.
	//
	// Every format JSON Schema defines applies to a string, so a format belongs
	// beside type: string alone.
	private static void refuseUnsupportedFormat(ObjectNode scalarSchema, String location,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.FORMAT)) {
			return;
		}
		JsonNode format = scalarSchema.get(JsonSchemaKeywords.FORMAT);
		if (!format.isString() || !ALLOWED_FORMATS.contains(format.asString())) {
			diagnostics.problem(location, "takes one of " + String.join(", ", new TreeSet<>(ALLOWED_FORMATS))
					+ " for format, and this one is " + describe(format) + ".");
			return;
		}
		if (!isStringTyped(scalarSchema)) {
			diagnostics.problem(location, "carries format without type: string, and every format JSON Schema "
					+ "defines applies to a string.");
		}
	}

	// Reports a pattern that is not a string, runs past the length limit, uses a
	// construct only Java's regular expressions define, fails to compile, uses a
	// construct Anthropic refuses, or is written without type: string.
	//
	// The one allowed keyword that asserts, so it can refuse a value the API would
	// have taken, inside GATool, before the API sees the call.
	private static void refuseUnsafePattern(ObjectNode scalarSchema, String location,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.PATTERN)) {
			return;
		}
		JsonNode pattern = scalarSchema.get(JsonSchemaKeywords.PATTERN);
		if (!pattern.isString()) {
			diagnostics.problem(location,
					"takes a regular expression for pattern, and this one is " + describe(pattern) + ".");
			return;
		}
		String regex = pattern.asString();
		if (regex.length() > MAX_PATTERN_LENGTH) {
			diagnostics.problem(location, "has a pattern longer than " + MAX_PATTERN_LENGTH + " characters.");
			return;
		}
		String javaOnlyConstruct = JavaOnlyRegexConstructs.find(regex);
		if (javaOnlyConstruct != null) {
			diagnostics.problem(location, "has a pattern using " + javaOnlyConstruct + ", which Java's regular "
					+ "expressions define and ECMA 262 leaves out. JSON Schema patterns are ECMA 262 regular "
					+ "expressions, and a client compiles this one as such, so it would fail to compile there or "
					+ "match something else.");
			return;
		}
		try {
			Pattern.compile(regex);
		}
		catch (PatternSyntaxException ex) {
			diagnostics.problem(location, "has a pattern that does not compile: " + ex.getDescription() + ".");
			return;
		}
		if (UNSUPPORTED_REGEX_CONSTRUCT.matcher(regex).find()) {
			diagnostics.problem(location,
					"has a pattern using lookaround, a backreference, a named or atomic "
							+ "group, a possessive quantifier or a word boundary. Anthropic compiles a pattern under "
							+ "strict tool use and answers one of those with 400 Invalid regex in pattern field.");
			return;
		}
		if (!isStringTyped(scalarSchema)) {
			diagnostics.problem(location,
					"carries pattern without type: string, and pattern applies to a " + "string alone.");
		}
	}

	// Reports an enum that is empty or not an array, lists more values than the limit, or
	// holds a value that repeats, carries a JSON type a member cannot have, or
	// contradicts the declared type.
	private static void refuseInvalidEnum(ObjectNode scalarSchema, String location, Origin origin,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.ENUM)) {
			return;
		}
		JsonNode values = scalarSchema.get(JsonSchemaKeywords.ENUM);
		if (!values.isArray() || values.isEmpty()) {
			diagnostics.problem(location,
					"takes a non-empty array for enum, and this one is " + describe(values) + ".");
			return;
		}
		if (values.size() > MAX_ENUM_VALUES) {
			diagnostics.problem(location,
					"has an enum of " + values.size() + " values, and the limit is " + MAX_ENUM_VALUES + ".");
			return;
		}
		Set<String> seen = new LinkedHashSet<>();
		for (JsonNode configuredValue : values) {
			if (!configuredValue.isString() && !configuredValue.isNumber() && !configuredValue.isBoolean()) {
				diagnostics.problem(location, "has an enum holding " + describe(configuredValue)
						+ ", and a member is a string, a number or a boolean.");
				return;
			}
			if (!seen.add(configuredValue.toString())) {
				diagnostics.problem(location, "has an enum listing " + configuredValue + " twice.");
				return;
			}
			if (!matchesType(scalarSchema, configuredValue)) {
				diagnostics.problem(location, "has an enum holding " + configuredValue + " beside type "
						+ scalarSchema.path(JsonSchemaKeywords.TYPE).asString() + ", so no value satisfies both and "
						+ origin.whatAnUnsatisfiableEnumCosts + ". A value from a "
						+ "properties file arrives as a string, so write the enum in YAML to keep its type.");
				return;
			}
		}
	}

	// Reports a description that sits inside an anyOf branch, is blank or not a string,
	// or runs past the length limit.
	private static void refuseMisplacedDescription(ObjectNode scalarSchema, String location, boolean inBranch,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.DESCRIPTION)) {
			return;
		}
		if (inBranch) {
			diagnostics.problem(location, "carries a description inside an anyOf branch. One description "
					+ "belongs to the schema, because a model reads the property once.");
			return;
		}
		JsonNode description = scalarSchema.get(JsonSchemaKeywords.DESCRIPTION);
		if (!description.isString() || description.asString().isBlank()) {
			diagnostics.problem(location,
					"takes a non-empty string for description, and this one is " + describe(description) + ".");
			return;
		}
		if (description.asString().length() > MAX_DESCRIPTION_LENGTH) {
			diagnostics.problem(location, "has a description longer than " + MAX_DESCRIPTION_LENGTH + " characters.");
		}
	}

	// Reports an anyOf nested inside a branch, a keyword beside it that the branches
	// would drop, a branch count outside the allowed range, and a branch that is not an
	// object. Each branch that is an object is then checked on its own.
	private static void refuseInvalidAnyOf(ObjectNode scalarSchema, String location, boolean inBranch, Origin origin,
			OperationDiagnostics diagnostics) {
		if (!scalarSchema.has(JsonSchemaKeywords.ANY_OF)) {
			return;
		}
		if (inBranch) {
			diagnostics.problem(location, "nests anyOf inside an anyOf branch, and GATool writes one level.");
			return;
		}
		// Only description travels beside anyOf, because the branches replace the
		// schema's own keywords and a dropped pattern is the one that would have
		// refused a value.
		List<String> keywordsBesideAnyOf = keywordsBesideAnyOf(scalarSchema);
		if (!keywordsBesideAnyOf.isEmpty()) {
			diagnostics
				.problem(location, "carries " + String.join(" and ", keywordsBesideAnyOf.stream().sorted().toList())
						+ " beside anyOf, and the branches are what GATool publishes, so those would be dropped. Move "
						+ "them into a branch.");
			return;
		}
		JsonNode branches = scalarSchema.get(JsonSchemaKeywords.ANY_OF);
		if (!branches.isArray() || branches.size() < MIN_BRANCHES || branches.size() > MAX_BRANCHES) {
			diagnostics.problem(location, "takes an array of " + MIN_BRANCHES + " to " + MAX_BRANCHES
					+ " branches for anyOf, and this one is " + describe(branches) + ".");
			return;
		}
		for (JsonNode branch : branches) {
			refuseInvalidBranch(branch, location, origin, diagnostics);
		}
	}

	private static List<String> keywordsBesideAnyOf(ObjectNode scalarSchema) {
		List<String> keywords = new ArrayList<>();
		for (String keyword : PUBLISHED_KEYWORDS) {
			if (!JsonSchemaKeywords.ANY_OF.equals(keyword) && !JsonSchemaKeywords.DESCRIPTION.equals(keyword)
					&& scalarSchema.has(keyword)) {
				keywords.add(keyword);
			}
		}
		return keywords;
	}

	private static void refuseInvalidBranch(JsonNode branch, String location, Origin origin,
			OperationDiagnostics diagnostics) {
		if (!(branch instanceof ObjectNode object)) {
			diagnostics.problem(location,
					"has an anyOf branch that is " + describe(branch) + ", and a branch is a JSON Schema object.");
			return;
		}
		// An empty branch accepts every value, so the anyOf as a whole does, and the
		// entry then silences the warning that would have named the scalar. The top-level
		// check reads the fragment alone, so each branch is checked as well.
		if (object.isEmpty()) {
			diagnostics.problem(location,
					"has an empty anyOf branch, which accepts every value, so the " + "anyOf as a whole does. "
							+ origin.whatASchemaSays() + "the branch a type, a format, "
							+ "a pattern or an enum, or remove the branch.");
			return;
		}
		refuse(object, location, true, origin, diagnostics);
	}

	private static void warn(ObjectNode scalarSchema, String location, OperationDiagnostics diagnostics) {
		// A branch carries the same keywords, so it earns the same warnings. Reading the
		// top level alone would let a format: uri inside an anyOf reach a client that
		// refuses the whole tool for it.
		JsonNode branches = scalarSchema.path(JsonSchemaKeywords.ANY_OF);
		if (branches.isArray()) {
			branches.forEach((branch) -> warnAbout(branch, location, diagnostics));
		}
		warnAbout(scalarSchema, location, diagnostics);
	}

	private static void warnAbout(JsonNode scalarSchema, String location, OperationDiagnostics diagnostics) {
		JsonNode format = scalarSchema.path(JsonSchemaKeywords.FORMAT);
		if (format.isString() && JsonSchemaKeywords.FORMAT_URI.equals(format.asString())) {
			diagnostics.warning(location, "publishes format: uri. Anthropic lists uri among the formats it "
					+ "reads, and OpenAI's strict subset leaves it out, which refuses the whole tool. GATool omits "
					+ "it from its own mappings for that reason, and publishes it here because you asked for it.");
		}
		JsonNode pattern = scalarSchema.path(JsonSchemaKeywords.PATTERN);
		if (pattern.isString() && !(pattern.asString().startsWith("^") && pattern.asString().endsWith("$"))) {
			// The check asks for both anchors, so the sentence names both and holds for a
			// pattern that carries one of them.
			diagnostics.warning(location,
					"publishes a pattern that lacks ^ at its start or $ at its end. JSON "
							+ "Schema regular expressions match anywhere in the string, so this one accepts any value "
							+ "holding a match. Write ^ at its start and $ at its end to match the whole value.");
		}
	}

	private static boolean matchesType(ObjectNode scalarSchema, JsonNode configuredValue) {
		JsonNode type = scalarSchema.path(JsonSchemaKeywords.TYPE);
		if (!type.isString()) {
			return true;
		}
		return switch (type.asString()) {
			case JsonSchemaKeywords.TYPE_STRING -> configuredValue.isString();
			case JsonSchemaKeywords.TYPE_INTEGER -> configuredValue.isIntegralNumber();
			case JsonSchemaKeywords.TYPE_NUMBER -> configuredValue.isNumber();
			case JsonSchemaKeywords.TYPE_BOOLEAN -> configuredValue.isBoolean();
			default -> true;
		};
	}

	// Returns the node with every map whose keys run 0 to n rewritten as an array. A
	// container is rebuilt, and a value node comes back as it is.
	//
	// Spring Boot binds an indexed property into a map under the names "0", "1", "2",
	// because the value type here is Object, so the binder fills a map where a list
	// would need a declared element type. A schema writing anyOf[0].type in properties,
	// or an anyOf list in YAML, which Spring flattens to the same indexed keys, would
	// otherwise draw the refusal "takes an array, and this one is an object".
	private static JsonNode withIndexedObjectsAsArrays(JsonNode node) {
		if (node instanceof ObjectNode object) {
			List<String> names = new ArrayList<>();
			object.propertyNames().forEach(names::add);
			if (isIndexed(names)) {
				ArrayNode list = JSON_MAPPER.createArrayNode();
				names.stream()
					.sorted(Comparator.comparingInt(Integer::parseInt))
					.forEach((name) -> list.add(withIndexedObjectsAsArrays(object.get(name))));
				return list;
			}
			ObjectNode rebuilt = JSON_MAPPER.createObjectNode();
			names.forEach((name) -> rebuilt.set(name, withIndexedObjectsAsArrays(object.get(name))));
			return rebuilt;
		}
		if (node instanceof ArrayNode array) {
			ArrayNode rebuilt = JSON_MAPPER.createArrayNode();
			array.forEach((item) -> rebuilt.add(withIndexedObjectsAsArrays(item)));
			return rebuilt;
		}
		return node;
	}

	private static boolean isIndexed(List<String> names) {
		if (names.isEmpty()) {
			return false;
		}
		Set<Integer> indexes = new LinkedHashSet<>();
		for (String name : names) {
			try {
				int index = Integer.parseInt(name);
				if (index < 0) {
					return false;
				}
				indexes.add(index);
			}
			catch (NumberFormatException ex) {
				return false;
			}
		}
		return indexes.size() == names.size()
				&& indexes.stream().max(Integer::compare).orElseThrow() == names.size() - 1;
	}

	private static boolean isStringTyped(ObjectNode scalarSchema) {
		JsonNode type = scalarSchema.path(JsonSchemaKeywords.TYPE);
		return type.isString() && JsonSchemaKeywords.TYPE_STRING.equals(type.asString());
	}

	private static String describe(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return "null";
		}
		if (node.isArray()) {
			return "an array";
		}
		if (node.isObject()) {
			return "an object";
		}
		return node.toString();
	}

	// The branches the writer publishes for one schema, each written in a fixed keyword
	// order. A configuration map iterates in whatever order it was built, and the schema
	// text has to read the same on every run, because a client caches it and it shows up
	// in a diff.
	private static final List<String> BRANCH_KEYWORD_ORDER = List.of(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.FORMAT,
			JsonSchemaKeywords.PATTERN, JsonSchemaKeywords.ENUM);

	static List<ObjectNode> branchesOf(ObjectNode scalarSchema) {
		List<ObjectNode> branches = new ArrayList<>();
		JsonNode anyOf = scalarSchema.path(JsonSchemaKeywords.ANY_OF);
		if (anyOf.isArray()) {
			for (JsonNode branch : anyOf) {
				branches.add(orderKeywords((ObjectNode) branch));
			}
			return branches;
		}
		branches.add(orderKeywords(scalarSchema));
		return branches;
	}

	private static ObjectNode orderKeywords(ObjectNode branch) {
		ObjectNode rebuilt = JSON_MAPPER.createObjectNode();
		for (String keyword : BRANCH_KEYWORD_ORDER) {
			if (branch.has(keyword)) {
				rebuilt.set(keyword, branch.get(keyword).deepCopy());
			}
		}
		return rebuilt;
	}

}
