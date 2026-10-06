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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import graphql.language.AstPrinter;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.InputValueWithState;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.VariableUsages;

/**
 * Writes the JSON Schema 2020-12 object that describes one operation's variables, which
 * both adapters publish as the tool's input schema.
 *
 * <p>
 * Three ways of producing that JSON were open: a JSON Schema library such as the
 * networknt validator's builders, Spring AI's {@code JsonSchemaGenerator}, and Jackson's
 * node API here. This class builds the nodes itself, for three reasons. Neither
 * graphql-java 25.0 nor Spring for GraphQL 2.0.5 ships a JSON Schema class. Spring AI's
 * generator works from a Java {@code Method} or {@code Type} and lives behind Spring,
 * which gatool-core keeps out. Jackson 3 arrives through the version Spring Boot manages.
 * An {@code ObjectNode} keeps the properties in the order the operation declares them, so
 * the text is the same on every run.
 *
 * <p>
 * The writer walks {@link NonNullType}, {@link ListType} and {@link TypeName} itself and
 * resolves each named type with {@link GraphQLSchema#getTypeAs(String)}. graphql-java's
 * {@code TypeFromAST} and {@code TypeInfo} do the same walk, and both carry
 * {@code graphql.Internal} in 25.0, so calling them would tie the starter to an internal
 * API.
 *
 * <p>
 * Each GraphQL type maps to one JSON Schema shape: {@code Int} maps to {@code integer},
 * {@code Float} to {@code number}, {@code String} to {@code string}, {@code ID} to
 * {@code string} or {@code integer}, {@code Boolean} to {@code boolean}, a list to
 * {@code array} with its {@code items}. The {@code required} list holds the Non-Null
 * variables without a default value. The root carries the {@code $schema} keyword, which
 * Spring AI's generator writes for a hand-written tool, so a generated tool reads the
 * same way. An {@code Int} leaves out {@code minimum} and {@code maximum}, because
 * Anthropic's strict mode answers both with HTTP 400.
 *
 * <p>
 * A nullable position writes {@code anyOf} over that type and {@code null}, instead of a
 * type array such as {@code ["integer", "null"]}. Both forms are legal JSON Schema
 * 2020-12, and the MCP Inspector's strict check reports the array form as a portability
 * risk: several MCP clients read {@code type} as a single string, and they either reject
 * the tool or drop the constraint. The {@code anyOf} form also matches what a
 * {@code $ref} position writes, so one idiom covers both. The branch carrying the type
 * also carries its {@code items} or its {@code format}, which keeps each branch a
 * complete schema on its own.
 *
 * <p>
 * Every object whose fields this schema lists carries
 * {@code additionalProperties: false}, which a hand-written tool leaves unsaid because
 * {@code @ToolParam} cannot express it. GraphQL answers an unknown input object field
 * with a request error, so the schema states what the API already enforces and the model
 * learns it before it calls.
 *
 * <p>
 * A custom scalar carrying a {@code @specifiedBy} URL GATool has read gets that mapping,
 * from the table in {@code ScalarSpecifications}, which holds every specification the
 * community registry publishes. Three examples: RFC 4122 becomes {@code string} with
 * {@code format: uuid}, the scalars.graphql.org date-time specification becomes
 * {@code string} with {@code format: date-time}, and RFC 3986 becomes {@code string} with
 * the format left off, because OpenAI's strict subset omits {@code uri} from its format
 * list. The match reads the whole URL, normalised first, so the spellings of one page
 * find one entry and a schema citing another page for the same format keeps {@code {}}. A
 * scalar the table describes keeps its own description beside the mapping.
 *
 * <p>
 * An enum publishes its values, and an Input Object publishes its fields. A custom scalar
 * nothing describes gets {@code {}}, the schema that accepts any JSON value, and takes
 * its property description from the scalar's own description and its {@code @specifiedBy}
 * URL. The model then reads the format in the words the schema itself carries, and the
 * API's own coercion answers a wrong value with a request error the model can read.
 *
 * <p>
 * Every enum and Input Object is written out at each use, instead of being hoisted into a
 * per-tool definitions map behind a {@code $ref}. In Spring AI 2.0.1,
 * {@code AnthropicChatModel} builds a tool from the {@code properties} and
 * {@code required} keys of this text and reads only those two. A root-level {@code $defs}
 * would therefore stay unsent, and every {@code $ref} on the in-process path would point
 * at a missing target. The day the two exposure types publish different texts, MCP can
 * share definitions, because there the schema travels as JSON to a client that resolves
 * it.
 *
 * <p>
 * A property's description comes from the argument the variable feeds, which is the third
 * of the description sources. The writer walks the whole selection set, fragments
 * included, and a variable feeding two arguments carries both sentences joined by a
 * newline, with a sentence the two share written once. The argument of a directive counts
 * as the argument of a field does, so a variable written at {@code @include(if:)} or
 * {@code @skip(if:)} carries the sentence graphql-java gives that argument,
 * {@code Included when true.} or {@code Skipped when true.}, which tells a model which
 * way the flag switches. A description string on the variable and {@code #} lines above
 * it would win over this source, and they arrive in a later release.
 *
 * @author Željko Kozina
 */
public final class InputSchemaWriter {

	// A decimal default keeps every digit the operation file wrote, and Jackson writes a
	// BigDecimal with a negative scale in exponent form, so 2.5e3 would publish as
	// 2.5E+3. Both are JSON numbers, and the plain 2500 is the one a model and a diff
	// read without converting.
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
		.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
		.build();

	/**
	 * How many input objects one variable may expand.
	 *
	 * <p>
	 * The cycle guard below is scoped to the path, so a type that two fields reach is
	 * written once per path and the node count follows the number of simple paths through
	 * the input graph. An eight-table filter API of the shape Hasura and PostGraphile
	 * publish writes over 270,000 characters for one variable without this bound, and
	 * twenty tables run a 2 GB heap out of memory before startup finishes. The size
	 * warning reads the schema after the walk built it, so it reports the cost and cannot
	 * prevent it.
	 *
	 * <p>
	 * 500 sits far above what a hand-written schema reaches. The movies fixture's widest
	 * variable expands 3, and a normalised eight-table shape with nine foreign keys
	 * writes about 29,000 characters, which is roughly 150 nodes and already crosses the
	 * 4,000 token startup warning. A schema that passes 500 is the multiplying kind, and
	 * the text it would write is past what a model reads anyway.
	 */
	private static final int NODE_BUDGET = 500;

	/**
	 * How many fields the sentence names where an input object repeats on its own path.
	 *
	 * <p>
	 * A filter type of a generated API holds one field for each column of a table, so the
	 * sentence names the first ten, in the order the schema declares them, and counts the
	 * rest.
	 */
	private static final int REPEAT_FIELDS = 10;

	private InputSchemaWriter() {
	}

	/**
	 * Writes the input schema of one operation.
	 * @param schema the schema the operation validated against
	 * @param file the parsed operation file
	 * @return the JSON Schema 2020-12 object as text, with the variables as its
	 * properties, and the custom scalars that stayed unmapped
	 */
	public static InputSchema write(GraphQLSchema schema, OperationFile file) {
		return write(schema, file, ScalarSchemas.none());
	}

	/**
	 * Writes the input schema of one operation.
	 * @param schema the schema the operation validated against
	 * @param file the parsed operation file
	 * @param scalarSchemas the fragments configured for the schema's custom scalars
	 * @return the JSON Schema 2020-12 object as text, with the variables as its
	 * properties, and the custom scalars that stayed unmapped
	 */
	public static InputSchema write(GraphQLSchema schema, OperationFile file, ScalarSchemas scalarSchemas) {
		ObjectNode inputSchema = JSON_MAPPER.createObjectNode();
		// Spring AI's generator writes this keyword for a hand-written tool. MCP reads
		// the dialect as 2020-12 whether or not the keyword appears, so writing it costs
		// one field and keeps a generated tool identical to a hand-written one here.
		inputSchema.put(JsonSchemaKeywords.SCHEMA, JsonSchemaKeywords.DIALECT);
		inputSchema.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		ObjectNode properties = inputSchema.putObject(JsonSchemaKeywords.PROPERTIES);
		ArrayNode required = JSON_MAPPER.createArrayNode();
		Map<String, String> argumentDescriptionsByVariable = readArgumentDescriptions(schema, file);
		List<String> unmappedScalars = new ArrayList<>();
		List<String> refusedDefaults = new ArrayList<>();
		List<String> refusedFieldDefaults = new ArrayList<>();
		List<String> cutTypes = new ArrayList<>();
		for (VariableDefinition variable : file.operation().getVariableDefinitions()) {
			ObjectNode property = properties.putObject(variable.getName());
			// A fresh set per property, so the scalar's words appear once inside one
			// argument however deep its type goes, and each argument stays readable on
			// its own. The budget is per variable, like the two sets beside it, so one
			// wide argument cannot spend what the next one needs.
			WriteContext walk = new WriteContext(schema, scalarSchemas, unmappedScalars, refusedFieldDefaults);
			writeVariableType(variable.getType(), true, property, walk);
			for (String cutType : walk.cutTypes()) {
				cutTypes.add(variable.getName() + " at " + cutType);
			}
			String argumentDescription = argumentDescriptionsByVariable.get(variable.getName());
			if (argumentDescription != null) {
				// The argument's own words come first, and the scalar's words, which the
				// walk already wrote where the scalar sits, follow when they share a
				// node.
				String scalarDescription = property.has(JsonSchemaKeywords.DESCRIPTION)
						? " " + property.get(JsonSchemaKeywords.DESCRIPTION).asString() : "";
				property.put(JsonSchemaKeywords.DESCRIPTION, argumentDescription + scalarDescription);
			}
			writeDefault(schema, variable, property, refusedDefaults);
			if (variable.getType() instanceof NonNullType && variable.getDefaultValue() == null) {
				required.add(variable.getName());
			}
		}
		inputSchema.set(JsonSchemaKeywords.REQUIRED, required);
		inputSchema.put(JsonSchemaKeywords.ADDITIONAL_PROPERTIES, false);
		return new InputSchema(JSON_MAPPER.writeValueAsString(inputSchema), unmappedScalars, refusedDefaults,
				refusedFieldDefaults, cutTypes);
	}

	// Writes the variable's declared default into the property, and records a default the
	// property would refuse.
	//
	// The keyword is an annotation, so validation ignores it and it tells the model what
	// it gets by leaving the argument out. Leaving the default out is better than
	// publishing one the property refuses: the MCP SDK validates arguments against this
	// property before the call runs. A model that reads the default and sends it back is
	// refused, and the refusal names a value GATool told it to use. The two disagree when
	// the scalar's JSON type comes from a configured fragment or from the @specifiedBy
	// page while the default is written in the API's own terms, which means one of the
	// two is wrong about the API.
	private static void writeDefault(GraphQLSchema schema, VariableDefinition variable, ObjectNode property,
			List<String> refusedDefaults) {
		@Nullable Value<?> declaredDefault = variable.getDefaultValue();
		if (declaredDefault == null) {
			return;
		}
		JsonNode defaultValue = DefaultValues.of(schema, variable.getType(), declaredDefault);
		if (defaultValue == null) {
			return;
		}
		if (accepts(property, defaultValue)) {
			property.set(JsonSchemaKeywords.DEFAULT, defaultValue);
		}
		else {
			refusedDefaults.add(variable.getName() + " = " + AstPrinter.printAstCompact(declaredDefault));
		}
	}

	// Returns whether the published property would accept this value.
	//
	// A nullable position writes its branches into anyOf, and one branch accepting the
	// value is enough. An empty schema leaves the type unstated and accepts anything,
	// which a custom scalar GATool cannot describe leaves behind.
	private static boolean accepts(ObjectNode property, JsonNode value) {
		if (property.get(JsonSchemaKeywords.ANY_OF) instanceof ArrayNode anyOf) {
			for (JsonNode branch : anyOf) {
				if (acceptsJsonType(branch, value)) {
					return true;
				}
			}
			return false;
		}
		return acceptsJsonType(property, value);
	}

	// The type first, then the two keywords that assert on a value of that type. A
	// configured fragment's enum or pattern refuses a value in the SDK's validator
	// exactly as a wrong type does, and a default outside the enum would be published and
	// then refused: $color: Color = "blue" against enum [red, green]. The format keyword
	// stays out, because it annotates; the validator accepts "not-a-date" under format:
	// date-time.
	private static boolean acceptsJsonType(JsonNode branch, JsonNode value) {
		JsonNode type = branch.path(JsonSchemaKeywords.TYPE);
		if (type.isString() && !hasJsonType(type.asString(), value)) {
			return false;
		}
		JsonNode allowed = branch.path(JsonSchemaKeywords.ENUM);
		if (allowed.isArray() && allowed.valueStream().noneMatch((member) -> sameValue(member, value))) {
			return false;
		}
		JsonNode pattern = branch.path(JsonSchemaKeywords.PATTERN);
		// JSON Schema matches a pattern anywhere in the string, and ScalarSchemas
		// compiled this one before it reached the writer, so compiling it again cannot
		// fail.
		return !pattern.isString() || !value.isString()
				|| Pattern.compile(pattern.asString()).matcher(value.asString()).find();
	}

	private static boolean hasJsonType(String jsonType, JsonNode value) {
		return switch (jsonType) {
			case JsonSchemaKeywords.TYPE_STRING -> value.isString();
			case JsonSchemaKeywords.TYPE_INTEGER -> value.isIntegralNumber();
			case JsonSchemaKeywords.TYPE_NUMBER -> value.isNumber();
			case JsonSchemaKeywords.TYPE_BOOLEAN -> value.isBoolean();
			case JsonSchemaKeywords.TYPE_OBJECT -> value.isObject();
			case JsonSchemaKeywords.TYPE_ARRAY -> value.isArray();
			case JsonSchemaKeywords.TYPE_NULL -> value.isNull();
			default -> true;
		};
	}

	// An enum member read from configuration and a default coerced from a literal can
	// carry one number as different node classes, and a validator compares the values.
	private static boolean sameValue(JsonNode member, JsonNode value) {
		if (member.isNumber() && value.isNumber()) {
			return member.decimalValue().compareTo(value.decimalValue()) == 0;
		}
		return member.equals(value);
	}

	// Writes the JSON Schema for a variable's declared type, walking Non-Null and list
	// wrappers to the named type.
	//
	// A type outside the three branches keeps an empty property, which accepts any JSON
	// value, so a schema the starter cannot describe still passes every value on.
	private static void writeVariableType(Type<?> type, boolean nullable, ObjectNode property, WriteContext walk) {
		if (type instanceof NonNullType nonNull) {
			writeVariableType(nonNull.getType(), false, property, walk);
			return;
		}
		if (type instanceof ListType list) {
			ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_ARRAY, nullable);
			writeVariableType(list.getType(), true, typed.putObject(JsonSchemaKeywords.ITEMS), walk);
			return;
		}
		if (type instanceof TypeName typeName) {
			GraphQLNamedType named = walk.schema().getTypeAs(typeName.getName());
			if (named instanceof GraphQLScalarType scalar) {
				describeScalar(writeScalar(scalar, nullable, property, walk), property, walk);
				return;
			}
			if (named instanceof GraphQLEnumType enumType) {
				writeEnum(enumType, nullable, property);
				return;
			}
			if (named instanceof GraphQLInputObjectType inputObject) {
				writeInputObject(inputObject, nullable, property, walk);
			}
		}
	}

	// Writes an enum as a string property carrying its values, with the schema's own
	// words as the description.
	//
	// An enum publishes its values, which is what Spring AI's generator writes for a
	// hand-written tool, and it is the difference between a model choosing a value that
	// exists and guessing at one.
	private static void writeEnum(GraphQLEnumType enumType, boolean nullable, ObjectNode property) {
		ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_STRING, nullable);
		ArrayNode values = typed.putArray(JsonSchemaKeywords.ENUM);
		enumType.getValues().forEach((value) -> values.add(value.getName()));
		// The names alone say what a model may send, and the schema's own words say what
		// each one means, which is what stops a model choosing a value that exists and
		// means something else.
		//
		// The words go on the property instead of on the typed node. A nullable variable
		// publishes anyOf, where the typed node is one branch, so the property's own
		// description would stay empty for a client that reads it. The two are the same
		// node for a Non-Null variable, so this only moves the nullable case.
		String documentation = documentationOf(enumType);
		if (!documentation.isEmpty()) {
			property.put(JsonSchemaKeywords.DESCRIPTION, documentation);
		}
	}

	// Joins the enum's own description with a line for each value the schema describes.
	private static String documentationOf(GraphQLEnumType enumType) {
		StringBuilder documentation = new StringBuilder();
		String typeDescription = enumType.getDescription();
		if (typeDescription != null) {
			documentation.append(typeDescription);
		}
		List<String> describedValues = enumType.getValues()
			.stream()
			.filter((value) -> value.getDescription() != null || value.isDeprecated())
			.map(InputSchemaWriter::describeEnumValue)
			.toList();
		if (!describedValues.isEmpty()) {
			if (!documentation.isEmpty()) {
				documentation.append("\n\n");
			}
			documentation.append("Values:\n").append(String.join("\n", describedValues));
		}
		return documentation.toString();
	}

	// A deprecated value keeps its line even without words of its own, because the
	// GraphQL specification asks a tool to discourage deprecated use. A value left
	// undescribed is one a model reads as an equal choice.
	private static String describeEnumValue(GraphQLEnumValueDefinition value) {
		List<String> words = new ArrayList<>();
		String description = value.getDescription();
		if (description != null) {
			words.add(description);
		}
		if (value.isDeprecated()) {
			words.add(deprecation(value.getDeprecationReason()));
		}
		return value.getName() + ": " + String.join(" ", words);
	}

	// graphql-java fills in "No longer supported" for a @deprecated without a reason,
	// which is the wording the specification's own directive definition carries.
	private static String deprecation(@Nullable String reason) {
		return (reason != null) ? "Deprecated: " + reason : "Deprecated.";
	}

	// Writes an Input Object as a JSON Schema object, from its fields or, where it takes
	// exactly one of them, from one branch per field. A type already on the path being
	// written is published as an open object whose description names the type and its
	// fields.
	//
	// An Input Object publishes its fields, and a field that is Non-Null without a
	// default joins the object's own required list, so obligation reads the way GraphQL
	// declares it.
	//
	// A type already on the path would recurse without end, so a repeat publishes an
	// open object with the sentence describeRepeat writes. Each name is removed on the
	// way out, so the first occurrence on any path is written in full and the repeat
	// names a shape the model has already read. A filter publishes its fields at the
	// top, and the and, or and not inside it carry the sentence.
	//
	// A $defs map behind $ref stays out, because Spring AI's AnthropicChatModel reads
	// only the properties and required keys of this text.
	private static void writeInputObject(GraphQLInputObjectType inputObject, boolean nullable, ObjectNode property,
			WriteContext walk) {
		// Every input object node costs one, a repeat and a cut included, because each
		// one is written text and the budget bounds the text. A cut stops before its
		// children, so the walk always ends.
		if (!walk.takeNode()) {
			ObjectNode cut = writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT, nullable);
			cut.put(JsonSchemaKeywords.DESCRIPTION,
					"A " + inputObject.getName()
							+ ". The schema of the API names its fields, and this one stops here, because the input "
							+ "types of this API reach each other in enough ways to write more than a model reads.");
			if (!walk.cutTypes().contains(inputObject.getName())) {
				walk.cutTypes().add(inputObject.getName());
			}
			return;
		}
		if (!walk.ancestors().add(inputObject.getName())) {
			ObjectNode repeat = writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT, nullable);
			repeat.put(JsonSchemaKeywords.DESCRIPTION, describeRepeat(inputObject, walk));
			return;
		}
		if (inputObject.isOneOf()) {
			writeOneOfBranches(inputObject, nullable, property, walk);
		}
		else {
			writeFields(inputObject, nullable, property, walk);
		}
		// The type's own sentence is the one place a @oneOf input states its rule, and
		// the branches below carry the shape without it.
		String typeDescription = inputObject.getDescription();
		if (typeDescription != null) {
			property.put(JsonSchemaKeywords.DESCRIPTION, typeDescription);
		}
		walk.ancestors().remove(inputObject.getName());
	}

	// Returns the sentence published where an Input Object repeats on its own path: the
	// name of the type, and its fields with their types where the variable names them for
	// the first time.
	//
	// The repeat is an open object, so the sentence is all a model reads about what goes
	// into it. The type of each field is written the way GraphQL writes it, [MovieWhere!]
	// and String!, which says what is a list and what is required in a few characters.
	//
	// The sentence names REPEAT_FIELDS fields and counts the rest, and the object the
	// repeat sits inside lists every one of them, because a type is on the path only
	// while it is being written in full. The fields are named once for each type in one
	// variable, at the first repeat the walk meets, and a later repeat of that type
	// points there, so the text stays small. The repeats are most of the nodes of a
	// filter graph: eight tables with three relationships each write 416 of them into one
	// variable, where naming eight fields at every repeat comes to 149,196 characters
	// against the 90,540 of the sentence without the fields. Named once for each type,
	// the same variable comes to 99,420.
	//
	// A OneOf Input Object writes its rule as branches where it is written in full, and
	// an open object is written without branches, so the sentence says the rule in
	// words.
	private static String describeRepeat(GraphQLInputObjectType inputObject, WriteContext walk) {
		String typeName = inputObject.getName();
		String opening = "A " + typeName + ", which holds another of its own kind"
				+ (inputObject.isOneOf() ? " and takes exactly one of its fields" : "") + ". ";
		if (!walk.namedRepeats().add(typeName)) {
			return opening + "The first " + typeName + " described this way names its fields.";
		}
		List<GraphQLInputObjectField> fields = inputObject.getFields();
		String named = fields.stream()
			.limit(REPEAT_FIELDS)
			.map((field) -> field.getName() + ": " + GraphQLTypeUtil.simplePrint(field.getType())
					+ (field.isDeprecated() ? " (deprecated)" : ""))
			.collect(Collectors.joining(", "));
		if (fields.size() <= REPEAT_FIELDS) {
			return opening + "Its fields, as GraphQL writes them: " + named + ".";
		}
		return opening + "The first " + REPEAT_FIELDS + " of its " + fields.size() + " fields, as GraphQL writes them: "
				+ named + ". The " + typeName + " this one sits inside lists all " + fields.size() + ".";
	}

	private static void writeFields(GraphQLInputObjectType inputObject, boolean nullable, ObjectNode property,
			WriteContext walk) {
		ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT, nullable);
		ObjectNode properties = typed.putObject(JsonSchemaKeywords.PROPERTIES);
		ArrayNode required = JSON_MAPPER.createArrayNode();
		for (GraphQLInputObjectField field : inputObject.getFields()) {
			ObjectNode fieldNode = properties.putObject(field.getName());
			writeInputFieldType(field.getType(), true, fieldNode, walk);
			describeField(field, fieldNode);
			writeFieldDefault(inputObject, field, fieldNode, walk);
			if (field.getType() instanceof GraphQLNonNull && !field.hasSetDefaultValue()) {
				required.add(field.getName());
			}
		}
		typed.set(JsonSchemaKeywords.REQUIRED, required);
		typed.put(JsonSchemaKeywords.ADDITIONAL_PROPERTIES, false);
	}

	// Writes one branch per field of a OneOf Input Object, each branch taking that field
	// alone.
	//
	// A OneOf Input Object takes exactly one of its fields, which JSON Schema says as a
	// branch per field, each requiring that one field alone. GraphQL declares every
	// OneOf field nullable and requires the value given to be non-null, so each branch
	// writes the field's type without a null of its own, and a nullable variable adds
	// one null branch beside them.
	private static void writeOneOfBranches(GraphQLInputObjectType inputObject, boolean nullable, ObjectNode property,
			WriteContext walk) {
		ArrayNode anyOf = property.putArray(JsonSchemaKeywords.ANY_OF);
		for (GraphQLInputObjectField field : inputObject.getFields()) {
			ObjectNode branch = anyOf.addObject();
			branch.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
			ObjectNode fieldNode = branch.putObject(JsonSchemaKeywords.PROPERTIES).putObject(field.getName());
			writeInputFieldType(field.getType(), false, fieldNode, walk);
			describeField(field, fieldNode);
			branch.putArray(JsonSchemaKeywords.REQUIRED).add(field.getName());
			branch.put(JsonSchemaKeywords.ADDITIONAL_PROPERTIES, false);
		}
		if (nullable) {
			anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
		}
	}

	// Writes an input field's declared default into its property, coerced by GraphQL's
	// own rules.
	//
	// A default on an input field is how a schema states its normal behaviour, and the
	// model reads it here for the same reason it reads a variable's default. Inside
	// graphql-java the value keeps the form it was written in: a schema read from SDL
	// holds a literal, and a schema built in code holds a Java value. Both go through
	// DefaultValues, which applies GraphQL's own coercion, so the same default publishes
	// the same JSON either way. Written straight through Jackson, a programmatic value
	// would publish "drama" for a field of type [String!], a default that fails the array
	// property beside it.
	private static void writeFieldDefault(GraphQLInputObjectType inputObject, GraphQLInputObjectField field,
			ObjectNode fieldNode, WriteContext walk) {
		if (!field.hasSetDefaultValue()) {
			return;
		}
		InputValueWithState declaredDefault = field.getInputFieldDefaultValue();
		Object value = declaredDefault.getValue();
		if (value == null) {
			return;
		}
		JsonNode defaultValue = (declaredDefault.isLiteral() && value instanceof Value<?> literal)
				? DefaultValues.of(field, literal) : DefaultValues.ofProgrammatic(field, value);
		if (defaultValue == null) {
			return;
		}
		// The same check the variable's default passes, for the same reason: the MCP SDK
		// validates arguments against this property before the call runs. A default the
		// property refuses gets a model refused for sending back the value GATool told it
		// to use.
		if (accepts(fieldNode, defaultValue)) {
			fieldNode.set(JsonSchemaKeywords.DEFAULT, defaultValue);
			return;
		}
		String refusal = inputObject.getName() + "." + field.getName() + " = " + defaultValue.toString();
		if (!walk.refusedFieldDefaults().contains(refusal)) {
			walk.refusedFieldDefaults().add(refusal);
		}
	}

	// The field's own words come first, and an unmapped scalar keeps the sentence the
	// scalar contributed, so a model reads both.
	private static void describeField(GraphQLInputObjectField field, ObjectNode fieldNode) {
		List<String> words = new ArrayList<>();
		String fieldDescription = field.getDescription();
		if (fieldDescription != null) {
			words.add(fieldDescription);
		}
		// A retired field reads as an equal choice while the words beside it leave the
		// deprecation out, which is the use the GraphQL specification asks a tool to
		// discourage.
		if (field.isDeprecated()) {
			words.add(deprecation(field.getDeprecationReason()));
		}
		if (words.isEmpty()) {
			return;
		}
		if (fieldNode.has(JsonSchemaKeywords.DESCRIPTION)) {
			words.add(fieldNode.get(JsonSchemaKeywords.DESCRIPTION).asString());
		}
		fieldNode.put(JsonSchemaKeywords.DESCRIPTION, String.join(" ", words));
	}

	// Writes the JSON Schema for an Input Object field's type, walking Non-Null and list
	// wrappers to the named type.
	//
	// The variables of an operation arrive as parsed AST types, and the fields of an
	// Input Object arrive as the schema's own types, so this method walks what
	// writeType walks, over the other type hierarchy.
	private static void writeInputFieldType(GraphQLInputType type, boolean nullable, ObjectNode property,
			WriteContext walk) {
		if (type instanceof GraphQLNonNull nonNull) {
			writeInputFieldType((GraphQLInputType) nonNull.getWrappedType(), false, property, walk);
			return;
		}
		if (type instanceof GraphQLList list) {
			ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_ARRAY, nullable);
			writeInputFieldType((GraphQLInputType) list.getWrappedType(), true,
					typed.putObject(JsonSchemaKeywords.ITEMS), walk);
			return;
		}
		if (type instanceof GraphQLScalarType scalar) {
			describeScalar(writeScalar(scalar, nullable, property, walk), property, walk);
			return;
		}
		if (type instanceof GraphQLEnumType enumType) {
			writeEnum(enumType, nullable, property);
			return;
		}
		if (type instanceof GraphQLInputObjectType inputObject) {
			writeInputObject(inputObject, nullable, property, walk);
		}
	}

	private static @Nullable CustomScalar writeScalar(GraphQLScalarType scalar, boolean nullable, ObjectNode property,
			WriteContext walk) {
		if ("ID".equals(scalar.getName())) {
			writeId(property, nullable);
			return null;
		}
		String builtInJsonType = jsonTypeOf(scalar.getName());
		if (builtInJsonType != null) {
			writeJsonType(property, builtInJsonType, nullable);
			// Int carries a bound the GraphQL specification sets, a signed 32-bit value,
			// and this writer leaves it unstated. JSON Schema says such a bound with
			// minimum and maximum, which this writer keeps out, because Anthropic's
			// strict mode answers both with HTTP 400. That leaves the description as the
			// only place to state it, and a sentence on every Int argument in every
			// listing costs more than it returns. An API typing a field as Int answers a
			// larger value with a request error that says so.
			return null;
		}
		// A configured fragment wins over the table, because it describes the API being
		// called and the table holds what GATool read from a published specification.
		ObjectNode fragment = walk.scalarSchemas().schemaFor(scalar.getName());
		if (fragment != null) {
			return writeConfiguredSchema(scalar, fragment, nullable, property);
		}
		String specifiedByUrl = scalar.getSpecifiedByUrl();
		ScalarSpecifications.@Nullable Specification specification = ScalarSpecifications.of(specifiedByUrl);
		if (specification == null) {
			return new CustomScalar(scalar.getName(), scalar.getDescription(), specifiedByUrl, false, false);
		}
		// A specification for a scalar that takes any JSON value leaves the property
		// empty, which is what the empty schema means, and keeps the description.
		String jsonType = specification.jsonType();
		String format = specification.format();
		if (jsonType != null) {
			ObjectNode typed = writeJsonType(property, jsonType, nullable);
			if (format != null) {
				typed.put(JsonSchemaKeywords.FORMAT, format);
			}
		}
		// The page's own words where the scalar's SDL leaves it undescribed, since a
		// schema that cites a specification has said what the value is. Read once into a
		// local, because a nullable getter called again is a getter that can answer
		// differently.
		String ownWords = scalar.getDescription();
		String described = (ownWords != null && !ownWords.isBlank()) ? ownWords : specification.description();
		return new CustomScalar(scalar.getName(), described, specifiedByUrl, format != null, true);
	}

	// Writes the configured fragment for a custom scalar into the property, with a null
	// branch where the position is nullable.
	//
	// GATool owns the wrapper, so the fragment's own keywords become the typed branch or
	// branches and the null branch is written around them from the GraphQL type. A
	// fragment that is itself an anyOf contributes each of its branches, which is how a
	// scalar that travels as either a number or a quoted string is described.
	private static CustomScalar writeConfiguredSchema(GraphQLScalarType scalar, ObjectNode fragment, boolean nullable,
			ObjectNode property) {
		List<ObjectNode> branches = ScalarSchemas.branchesOf(fragment);
		// A fragment that says only what the value means, without a type or a pattern,
		// leaves the property empty, which is the schema that accepts any JSON value, and
		// the property keeps its words. Wrapping an empty branch in an anyOf beside null
		// would publish {"anyOf":[{}, {"type":"null"}]}, which says the same thing at
		// three times the length.
		if (branches.stream().allMatch(ObjectNode::isEmpty)) {
			branches = List.of();
		}
		if (branches.size() == 1 && !nullable) {
			property.setAll(branches.getFirst());
		}
		else if (!branches.isEmpty()) {
			ArrayNode anyOf = property.putArray(JsonSchemaKeywords.ANY_OF);
			branches.forEach(anyOf::add);
			if (nullable) {
				anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
			}
		}
		// A fragment's own description is what an application wrote for this scalar, so
		// it stands in place of the schema's: the application configuring the fragment is
		// the one saying what the value is.
		String described = fragment.path(JsonSchemaKeywords.DESCRIPTION).isString()
				? fragment.path(JsonSchemaKeywords.DESCRIPTION).asString() : scalar.getDescription();
		// The @specifiedBy URL is dropped along with it. A fragment configured for a
		// scalar says what the API takes, and the URL can say something else. A schema
		// declaring the date-time specification while the service answers with epoch
		// seconds would otherwise publish the fragment's integer beside a sentence
		// pointing at a string format.
		return new CustomScalar(scalar.getName(), described, null, true, true);
	}

	// Writes a custom scalar's words onto the property, and records a scalar GATool could
	// not describe.
	//
	// The scalar's words go on the node that carries the scalar, which is the items node
	// inside a list, and they are written once per argument. A filter carrying the same
	// scalar in five fields would otherwise repeat the same sentence five times, and the
	// repetition multiplies with nesting.
	private static void describeScalar(@Nullable CustomScalar customScalar, ObjectNode property, WriteContext walk) {
		if (customScalar == null) {
			return;
		}
		if (!customScalar.mapped() && !walk.unmappedScalars().contains(customScalar.name())) {
			walk.unmappedScalars().add(customScalar.name());
		}
		if (!walk.describedScalars().add(customScalar.name())) {
			return;
		}
		String description = descriptionOf(customScalar);
		if (description != null) {
			property.put(JsonSchemaKeywords.DESCRIPTION, description);
		}
	}

	// Returns the words to publish for a custom scalar: its own description, then the
	// @specifiedBy URL where the property does not name a format.
	//
	// The scalar's own words, then the URL, when nothing else names the format. The
	// argument's description joins them at the call site, which prepends it, because one
	// argument can carry the scalar at several depths and the argument is described once.
	//
	// A scalar keeps its own description whether or not the table described it. The URL
	// sentence is what the format replaces, so it is written where the property leaves
	// the format unstated: an unmapped scalar, and the RFC 3986 entry, which maps to
	// string and leaves the format off for OpenAI's strict subset. Without it that
	// property reads {"type":"string"} and leaves the model to guess what kind of string.
	private static @Nullable String descriptionOf(CustomScalar customScalar) {
		List<String> parts = new ArrayList<>();
		// A scalar the SDL leaves undescribed arrives with an empty description instead
		// of a null one, and an empty part would join as a leading space.
		String description = customScalar.description();
		if (description != null && !description.isBlank()) {
			parts.add(description);
		}
		if (customScalar.specifiedByUrl() != null && !customScalar.hasFormat()) {
			parts.add("See " + customScalar.specifiedByUrl() + " for the format of this value.");
		}
		return parts.isEmpty() ? null : String.join(" ", parts);
	}

	// Writes ID as a string or an integer, with a null branch where the position is
	// nullable.
	//
	// GraphQL's own input coercion for ID takes both forms: "any string (such as \"4\")
	// or integer (such as 4 or -4) input value should be coerced to ID". Publishing
	// string alone would refuse a call the API takes, because the MCP SDK validates
	// arguments against this schema before the handler runs. Widening is safe in this
	// direction only: every integer has a faithful string form, so the API coerces one to
	// the other. A schema that accepted a string for an Int would send the API something
	// it has to reject. The way out stays string, which is what a conforming service
	// serializes.
	private static void writeId(ObjectNode property, boolean nullable) {
		ArrayNode anyOf = property.putArray(JsonSchemaKeywords.ANY_OF);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_STRING);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_INTEGER);
		if (nullable) {
			anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
		}
	}

	private static @Nullable String jsonTypeOf(String scalarName) {
		return switch (scalarName) {
			case "Int" -> JsonSchemaKeywords.TYPE_INTEGER;
			case "Float" -> JsonSchemaKeywords.TYPE_NUMBER;
			case "String" -> JsonSchemaKeywords.TYPE_STRING;
			case "Boolean" -> JsonSchemaKeywords.TYPE_BOOLEAN;
			default -> null;
		};
	}

	// Returns the node that carries the type, so a caller writes items or format into the
	// branch that owns them, which keeps each branch a complete schema.
	private static ObjectNode writeJsonType(ObjectNode property, String jsonType, boolean nullable) {
		if (!nullable) {
			property.put(JsonSchemaKeywords.TYPE, jsonType);
			return property;
		}
		ArrayNode anyOf = property.putArray(JsonSchemaKeywords.ANY_OF);
		ObjectNode typed = anyOf.addObject();
		typed.put(JsonSchemaKeywords.TYPE, jsonType);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
		return typed;
	}

	// Returns the description of each variable, read from the arguments the variable
	// feeds. A variable feeding two arguments carries both sentences, joined by a
	// newline, and a sentence that two usages share appears once.
	//
	// One walk serves the descriptions here and the null rules in
	// OperationCatalogFactory, so the two cannot disagree about where a variable lands.
	private static Map<String, String> readArgumentDescriptions(GraphQLSchema schema, OperationFile file) {
		Map<String, String> argumentDescriptionsByVariable = new LinkedHashMap<>();
		VariableUsages.of(schema, file).forEach((variable, usages) -> {
			// A variable can feed two arguments. Both sentences describe the one value
			// the model sends, so both are kept, and a newline reads as two lines
			// wherever a client renders the schema.
			//
			// Every usage brings the sentence of its argument, so a variable used at one
			// argument under two aliases, or at two arguments the schema describes in the
			// same words, would repeat that sentence once per usage. Each distinct
			// sentence is kept once, in the order the walk met it first.
			List<String> sentences = usages.stream()
				.map(VariableUsages.Usage::description)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
			if (!sentences.isEmpty()) {
				argumentDescriptionsByVariable.put(variable, String.join("\n", sentences));
			}
		});
		return argumentDescriptionsByVariable;
	}

	// The values one write carries unchanged through the recursion: the schema each named
	// type is resolved against, the fragments configured for the schema's custom scalars,
	// and the three collections one write accumulates. The ancestors set holds the Input
	// Object names on the path being written, and writeInputObject adds a name on the way
	// in and removes it on the way out, so every step shares this one set. The
	// describedScalars set holds the custom scalars whose words the property being built
	// already carries, and unmappedScalars holds the ones GATool could not describe,
	// which startup warns about. The namedRepeats set holds the Input Objects whose
	// fields the property being built has named at a repeat.
	private static final class WriteContext {

		private final GraphQLSchema schema;

		private final ScalarSchemas scalarSchemas;

		private final Set<String> ancestors = new HashSet<>();

		private final Set<String> describedScalars = new HashSet<>();

		private final Set<String> namedRepeats = new HashSet<>();

		private final List<String> unmappedScalars;

		private final AtomicInteger remainingNodes = new AtomicInteger(NODE_BUDGET);

		private final List<String> cutTypes = new ArrayList<>();

		private final List<String> refusedFieldDefaults;

		// A class that creates the collections one write fills.
		WriteContext(GraphQLSchema schema, ScalarSchemas scalarSchemas, List<String> unmappedScalars,
				List<String> refusedFieldDefaults) {
			this.schema = schema;
			this.scalarSchemas = scalarSchemas;
			this.unmappedScalars = unmappedScalars;
			this.refusedFieldDefaults = refusedFieldDefaults;
		}

		GraphQLSchema schema() {
			return this.schema;
		}

		ScalarSchemas scalarSchemas() {
			return this.scalarSchemas;
		}

		Set<String> ancestors() {
			return this.ancestors;
		}

		Set<String> describedScalars() {
			return this.describedScalars;
		}

		Set<String> namedRepeats() {
			return this.namedRepeats;
		}

		List<String> unmappedScalars() {
			return this.unmappedScalars;
		}

		List<String> cutTypes() {
			return this.cutTypes;
		}

		List<String> refusedFieldDefaults() {
			return this.refusedFieldDefaults;
		}

		/**
		 * Takes one input object out of this variable's budget.
		 * @return whether the budget had one left
		 */
		boolean takeNode() {
			return this.remainingNodes.getAndDecrement() > 0;
		}

	}

	// A custom scalar the walk met, with the words to publish for it and whether the
	// property already names its format. Both matter: a scalar GATool described keeps its
	// own words all the same, and the startup warning names the ones left undescribed.
	private record CustomScalar(String name, @Nullable String description, @Nullable String specifiedByUrl,
			boolean hasFormat, boolean mapped) {
	}

}
