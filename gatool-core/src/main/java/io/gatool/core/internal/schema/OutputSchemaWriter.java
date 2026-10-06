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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import graphql.language.DirectivesContainer;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.OperationType;

/**
 * Writes the JSON Schema 2020-12 object that describes what a tool call returns, which
 * MCP publishes as a tool's {@code outputSchema}.
 *
 * <p>
 * The schema describes the GraphQL response envelope, because that is what a tool
 * returns: a {@code data} member holding what the operation selected, and an
 * {@code errors} member when the API reports one. Describing {@code data} alone would
 * leave the text result and the structured result disagreeing, which is a worse trap than
 * publishing one of them alone.
 *
 * <p>
 * MCP makes {@code outputSchema} optional and binding: "Servers MUST provide structured
 * results that conform to this schema". Every rule here is therefore written to be true
 * of every response the API can send, and where the selection set fails to prove a shape,
 * the schema stays permissive:
 *
 * <ul>
 * <li>{@code data} is nullable, because a failed call answers with {@code "data": null}
 * beside its errors.</li>
 * <li>Every value is nullable, whatever the schema declares. GraphQL nulls the parent of
 * a Non-Null field that errors, and an API that nulls the field itself is one this server
 * still has to answer for.</li>
 * <li>The {@code required} array lists the keys the operation selects, since GraphQL
 * returns the keys that were asked for whenever their object is there.</li>
 * <li>An enum publishes {@code string} without its values, although the input schema
 * publishes them: a value added to the API answers before the SDL catches up, and a
 * response outside the published list would break the MUST.</li>
 * <li>A field carrying {@code @skip} or {@code @include} stays out of {@code required},
 * because the model's own argument decides whether it arrives.</li>
 * <li>A field brought in by a fragment whose type condition names another type stays out
 * of {@code required} as well, since another concrete type leaves it out. A condition
 * naming the surrounding type itself, or an abstract type it belongs to, holds for every
 * object that can answer there, so its fields are as certain as the ones beside it.</li>
 * <li>A custom scalar publishes the {@code type} and the {@code format} of the fragment
 * configured for it under {@code gatool.inputs.scalar-schemas}, so a tool says the same
 * about a scalar on the way in and on the way out. The fragment's {@code enum} and
 * {@code pattern} stay on the input side, because a fragment says what a model should
 * send, and an operator may write it narrower than what the API returns. An entry under
 * {@code gatool.results.scalar-schemas} is published as written in its place, for a
 * scalar that returns another form than it accepts. A scalar without an entry in either
 * map publishes the {@code type} and the {@code format} of the specification its
 * {@code @specifiedBy} URL cites, where GATool has read that specification, and the empty
 * schema, which accepts any JSON value, otherwise.</li>
 * <li>A custom scalar's description is written at the first position of a schema that
 * carries the scalar, and left out at the later ones.</li>
 * <li>A union or interface position publishes one branch per member the operation names
 * in a type condition, then an open {@code {"type": "object"}} branch, then {@code null}.
 * See below.</li>
 * <li>A response key the operation selects twice, which a document spreading two
 * fragments over one field does constantly, is described once with the fields of both
 * occurrences, as 6.4.3 MergeSelectionSets appends them. That holds for an object, for
 * the items of a list and for the branches of a union or interface alike.</li>
 * <li>The number of object shapes one schema holds is bounded by {@link #SHAPE_BUDGET}. A
 * position past the bound publishes the open object shape with a note, and startup warns
 * and names it.</li>
 * <li>The envelope itself leaves {@code additionalProperties} unsaid, so a member GATool
 * has yet to meet cannot make a response fail its own schema.</li>
 * <li>The {@code errors} member describes the list the GraphQL specification gives, then
 * {@code null}, then a branch that accepts any JSON value. The {@code message},
 * {@code locations}, {@code path} and {@code extensions} of an entry are each nullable,
 * because an API such as AWS AppSync writes a member it cannot fill as {@code null}, and
 * an entry may leave each of them out, because an API or a gateway in front of it answers
 * an entry without a {@code message}. The last branch keeps a response conforming whose
 * errors have another shape, such as an entry that is a plain string, or one object where
 * the list belongs.</li>
 * </ul>
 *
 * <h2>Unions and interfaces</h2>
 *
 * <p>
 * An abstract position becomes {@code anyOf} over one object branch per member the
 * operation names in a type condition. Each branch holds that member's selected fields
 * and the position's own selections, with {@code __typename} pinned as {@code {"const":
 * "Payment"}} where the operation selects it at the position itself. After the members
 * come {@code {"type": "object"}} and {@code {"type": "null"}}.
 *
 * <p>
 * A type condition naming the position's own abstract type, or an interface that type
 * implements, holds for whichever member answers, so what it selects counts as the
 * position's own and stays out of the branches. A position whose every selection is its
 * own has one shape and publishes it without branches. Without this rule, {@code node {
 * ...NodeFields }} with a fragment on {@code Node} would publish one identical branch per
 * implementation, and a nested {@code owner: Node} would multiply the branches by the
 * number of implementations at each level: 6, 18 and 54 {@code anyOf} arrays at depth 1,
 * 2 and 3 with three implementations.
 *
 * <p>
 * This serves the errors-as-data pattern, where a field returns a union of one success
 * type and several error types. Two error members commonly carry the same field, such as
 * {@code message}, and the {@code const} is the only thing that tells them apart for a
 * model, which is what it switches on.
 *
 * <p>
 * The shape has these trade-offs under the validator the MCP Java SDK uses:
 *
 * <ul>
 * <li><b>It describes more than it validates.</b> With the open branch present, the
 * position enforces "an object, or null", and which keys that object holds stays open. A
 * {@code Payment} missing {@code amountCents} passes by falling through to the open
 * branch. The branches are there to be read; the open branch is there so that the reading
 * always stays free.</li>
 * <li><b>The open branch is what makes the {@code const} safe.</b> A member the API adds
 * after the operation file was written answers with the position's own selections alone,
 * and lands there. Without it, that call fails at runtime on a file that stayed as it
 * was, which is this project's worst failure mode. Add both or leave both out.</li>
 * <li><b>{@code oneOf} is ruled out twice.</b> Two members selecting the same fields
 * match several branches, and any open branch matches as well, so {@code oneOf} refuses
 * ordinary responses.</li>
 * <li><b>Size.</b> An errors-as-data envelope grows from about 430 bytes to about 1,260,
 * roughly 270 per member, and that text travels in every {@code tools/list}.</li>
 * <li><b>Without {@code __typename} the branches drop the {@code const}.</b> A response
 * then leaves a model without a key to switch on, which is a property of the operation
 * alone, so startup warns and the document is left as written.</li>
 * <li><b>A type condition naming another interface or a union</b> is expanded into the
 * members it covers, because {@code __typename} answers with a concrete type and a branch
 * pinning it to an interface name cannot match any response. A condition naming a
 * supertype of the position's own type is the one exception, because a member added
 * tomorrow has to implement it too, so its selections are the position's own.</li>
 * </ul>
 *
 * @author Željko Kozina
 */
public final class OutputSchemaWriter {

	/**
	 * How many object shapes one operation's output schema may hold, branches of a union
	 * or interface position included.
	 *
	 * <p>
	 * Each branch of an abstract position is one shape, and a nested abstract position
	 * inside every branch multiplies them: an operation naming ten members at each of
	 * three levels asks for 10, then 100, then 1,000 shapes. The text a model reads is
	 * bounded, and so is what this process holds while it writes, so the walk stops
	 * taking shapes here and publishes the open object shape at the position it reached.
	 * A budget of 500 sits far above what a hand-written operation reaches: the
	 * errors-as-data example in the README takes four, one for the root and one per
	 * member it names.
	 */
	public static final int SHAPE_BUDGET = 500;

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private static final String SKIP = "skip";

	private static final String INCLUDE = "include";

	private static final String TYPENAME = "__typename";

	private OutputSchemaWriter() {
	}

	/**
	 * Writes the output schema of one operation for an application that leaves both maps
	 * of scalar schemas empty, so each custom scalar publishes the empty schema.
	 * @param schema the schema the operation validated against
	 * @param file the parsed operation file
	 * @return the JSON Schema 2020-12 object as text, with the positions startup warns
	 * about
	 */
	public static OutputSchema write(GraphQLSchema schema, OperationFile file) {
		return write(schema, file, ScalarSchemas.none());
	}

	/**
	 * Writes the output schema of one operation.
	 * @param schema the schema the operation validated against
	 * @param file the parsed operation file
	 * @param resultScalarSchemas the schema each custom scalar has in a result, as
	 * {@link ScalarSchemas#forResults(ScalarSchemas)} returns it
	 * @return the JSON Schema 2020-12 object as text, with the positions startup warns
	 * about
	 */
	public static OutputSchema write(GraphQLSchema schema, OperationFile file, ScalarSchemas resultScalarSchemas) {
		GraphQLObjectType rootType = (file.operationType() == OperationType.MUTATION) ? schema.getMutationType()
				: schema.getQueryType();
		ObjectNode outputSchema = JSON_MAPPER.createObjectNode();
		outputSchema.put(JsonSchemaKeywords.SCHEMA, JsonSchemaKeywords.DIALECT);
		outputSchema.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		ObjectNode properties = outputSchema.putObject(JsonSchemaKeywords.PROPERTIES);
		Walk walk = new Walk(schema, fragmentsByName(file), resultScalarSchemas);
		writeData(rootType, file, properties.putObject("data"), walk);
		writeErrors(properties.putObject("errors"));
		// Nothing is required: a successful response carries data alone, and a failed
		// one carries errors and a null data.
		outputSchema.set(JsonSchemaKeywords.REQUIRED, JSON_MAPPER.createArrayNode());
		return new OutputSchema(JSON_MAPPER.writeValueAsString(outputSchema), walk.positionsWithoutTypename(),
				walk.cutPositions());
	}

	private static void writeData(@Nullable GraphQLObjectType rootType, OperationFile file, ObjectNode data,
			Walk walk) {
		if (rootType == null) {
			return;
		}
		ArrayNode anyOf = data.putArray(JsonSchemaKeywords.ANY_OF);
		ObjectNode typed = anyOf.addObject();
		typed.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		Collected root = new Collected(new LinkedHashMap<>());
		collect(rootType, file.operation().getSelectionSet(), true, root, walk);
		// The root shape is the first the budget sees, so it is always covered.
		walk.takeShapes(1);
		writeObject(root, typed, walk);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
	}

	// Writes the schema of the errors member: a list of GraphQL errors, or null, or the
	// errors as the API wrote them in another shape. Each of the four members of an error
	// may be null or absent.
	//
	// The errors member is the GraphQL specification's own shape, so it is the one part
	// of this schema that does not depend on the operation.
	//
	// Each member of an error is nullable, as every value under data is. Most servers
	// leave out a member they cannot fill, and AWS AppSync writes it as null: a variable
	// that fails coercion answers "path": null and "locations": null. Typed as an array
	// alone, the two would refuse the error the API wrote, and with partial results read
	// as success the SDK's validation message would take the place of the data and of the
	// error.
	//
	// The specification gives every error a message, and the shape is another server's to
	// write, so GATool cannot prove it. A gateway answers {"code": "UNAUTHENTICATED"}, or
	// "errors": ["Rate limit exceeded"], or one object where the list belongs. A schema
	// that requires message would refuse each of these, so the described error leaves
	// required out, and the last branch carries a description alone, which makes it the
	// schema that accepts every JSON value. The list and null are there for a model to
	// read, and the last branch keeps a response conforming whatever its errors hold, as
	// the open branch of a union position does.
	//
	// Two narrower shapes are possible. Items written as anyOf over the described error
	// and the empty schema accept an entry of another shape, and still refuse a member
	// that is one object or one string. Items left unconstrained, with the four members
	// named in a description, accept the same entries, and a client that reads types from
	// the schema then finds an entry without members. One open branch on the member
	// covers the entry, a member of another type inside an entry, and the member itself.
	// The branch is 93 characters in every schema, and leaving required out takes 23
	// away.
	private static void writeErrors(ObjectNode errors) {
		ArrayNode anyOf = errors.putArray(JsonSchemaKeywords.ANY_OF);
		ObjectNode list = anyOf.addObject();
		list.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_ARRAY);
		ObjectNode error = list.putObject(JsonSchemaKeywords.ITEMS);
		error.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
		ObjectNode properties = error.putObject(JsonSchemaKeywords.PROPERTIES);
		writeJsonType(properties.putObject("message"), JsonSchemaKeywords.TYPE_STRING);
		writeJsonType(properties.putObject("locations"), JsonSchemaKeywords.TYPE_ARRAY);
		writeJsonType(properties.putObject("path"), JsonSchemaKeywords.TYPE_ARRAY);
		writeJsonType(properties.putObject("extensions"), JsonSchemaKeywords.TYPE_OBJECT);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
		anyOf.addObject()
			.put(JsonSchemaKeywords.DESCRIPTION,
					"errors in another shape, as the API or a gateway in front of it wrote them");
	}

	/**
	 * Gathers the fields, inline fragments and fragment spreads of one selection set
	 * under the type they resolve against.
	 *
	 * <p>
	 * {@code certain} is false where a directive or a type condition can keep the keys
	 * out of the answer, and those keys then stay out of {@code required}.
	 * @param parent the type the selections resolve against
	 * @param selectionSet the selection set to gather
	 * @param certain whether the keys arrive whenever the surrounding object does
	 * @param into receives the gathered selections
	 * @param walk the values one write carries through the recursion
	 */
	private static void collect(GraphQLFieldsContainer parent, SelectionSet selectionSet, boolean certain,
			Collected into, Walk walk) {
		for (Selection<?> selection : selectionSet.getSelections()) {
			if (selection instanceof Field field) {
				// A key the operation selects is present in data whenever its object is,
				// so the obligation belongs to presence alone. A field the model can
				// switch off with @skip or @include may be absent.
				into.add(parent, field, certain && !switched(field));
			}
			else if (selection instanceof InlineFragment inlineFragment) {
				collectInlineFragment(parent, inlineFragment, certain && !switched(inlineFragment), into, walk);
			}
			else if (selection instanceof FragmentSpread spread) {
				FragmentDefinition fragment = walk.fragments().get(spread.getName());
				if (fragment != null) {
					collectConditioned(parent, fragment.getTypeCondition().getName(), fragment.getSelectionSet(),
							certain && !switched(spread), into, walk);
				}
			}
		}
	}

	// The fields go into the object the fragment sits in, because GraphQL returns them
	// beside the ones selected around it. A fragment without a condition resolves
	// against the surrounding type.
	private static void collectInlineFragment(GraphQLFieldsContainer parent, InlineFragment inlineFragment,
			boolean certain, Collected into, Walk walk) {
		if (inlineFragment.getTypeCondition() == null) {
			collect(parent, inlineFragment.getSelectionSet(), certain, into, walk);
		}
		else {
			collectConditioned(parent, inlineFragment.getTypeCondition().getName(), inlineFragment.getSelectionSet(),
					certain, into, walk);
		}
	}

	// Gathers what a fragment with a type condition selects, resolving its fields against
	// the type the condition names.
	//
	// A condition the surrounding object type cannot satisfy is skipped, which a union
	// condition inside one member's branch produces when it carries a fragment on another
	// member. The field would otherwise be published in a branch where the API leaves it
	// out. A condition that holds for every object the surrounding type can answer with
	// keeps the fields certain. An inline fragment and a named spread are read the same
	// way, so the same fields land in required through either spelling. Any other
	// condition means another concrete type leaves the fields out, so they are published
	// as optional.
	//
	// A condition naming a union the surrounding object belongs to, which 5.5.2.3 allows
	// at an object position, resolves against the surrounding type: the only field a
	// union has of its own is __typename, which resolves against any member, and the
	// member fragments inside it resolve against their own types. A union is not a fields
	// container, so a lookup of the union itself would answer null, and a fragment
	// written on the union and spread over one member would publish that member as an
	// object without properties.
	private static void collectConditioned(GraphQLFieldsContainer parent, String conditionName, SelectionSet selections,
			boolean certain, Collected into, Walk walk) {
		GraphQLType condition = walk.schema().getType(conditionName);
		if (parent instanceof GraphQLObjectType object
				&& !TypeConditions.possibleTypeNamesOf(walk.schema(), condition).contains(object.getName())) {
			return;
		}
		GraphQLFieldsContainer container = fieldsContainerOf(condition, parent);
		if (container == null) {
			return;
		}
		collect(container, selections, certain && TypeConditions.holdsForEvery(walk.schema(), conditionName, parent),
				into, walk);
	}

	// Returns the type a condition's selections resolve against: the object or interface
	// the condition names, the surrounding type for a union, and null for a name the
	// schema lacks.
	private static @Nullable GraphQLFieldsContainer fieldsContainerOf(@Nullable GraphQLType condition,
			GraphQLFieldsContainer parent) {
		if (condition instanceof GraphQLFieldsContainer container) {
			return container;
		}
		return (condition instanceof GraphQLUnionType) ? parent : null;
	}

	// Writes one object shape: a property per response key, and required listing the keys
	// that arrive whenever the object does.
	private static void writeObject(Collected collected, ObjectNode object, Walk walk) {
		ObjectNode properties = object.putObject(JsonSchemaKeywords.PROPERTIES);
		ArrayNode required = object.putArray(JsonSchemaKeywords.REQUIRED);
		for (Map.Entry<String, List<Occurrence>> entry : collected.byKey().entrySet()) {
			String key = entry.getKey();
			List<Occurrence> occurrences = entry.getValue();
			if (TYPENAME.equals(occurrences.getFirst().field().getName())) {
				// Written as a string before any lookup, because the parent type does not
				// declare it as a field. GraphQL answers a selected __typename on every
				// object, so the key is as certain as any other the operation asked for.
				properties.putObject(key).put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_STRING);
			}
			else {
				GraphQLFieldDefinition definition = definitionOf(occurrences);
				if (definition == null) {
					continue;
				}
				// Every value is written nullable, whatever the schema declares. GraphQL
				// nulls the parent of a Non-Null field that errors, and an API that nulls
				// the field itself is one GATool has to keep answering for, since the
				// MUST
				// binds this server alone.
				writeType(definition.getType(), key, occurrences, properties.putObject(key), walk);
			}
			// One certain occurrence is enough: the key then arrives whichever way the
			// operation reached it. JSON Schema 2020-12 says the elements of required
			// MUST
			// be unique, and one entry per key keeps them so.
			if (occurrences.stream().anyMatch(Occurrence::certain)) {
				required.add(key);
			}
		}
	}

	// Writes the schema of one field's type, unwrapping Non-Null and List and recursing
	// into the selection sets of every occurrence for an object, union or interface
	// position.
	private static void writeType(GraphQLType type, String position, List<Occurrence> occurrences, ObjectNode property,
			Walk walk) {
		if (type instanceof GraphQLNonNull nonNull) {
			writeType(nonNull.getWrappedType(), position, occurrences, property, walk);
			return;
		}
		if (type instanceof GraphQLList list) {
			ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_ARRAY);
			writeType(list.getWrappedType(), position, occurrences, typed.putObject(JsonSchemaKeywords.ITEMS), walk);
			return;
		}
		if (type instanceof GraphQLScalarType scalar) {
			String jsonType = jsonTypeOf(scalar.getName());
			if (jsonType != null) {
				writeJsonType(property, jsonType);
			}
			else {
				writeCustomScalar(scalar, property, walk);
			}
			return;
		}
		if (type instanceof GraphQLEnumType) {
			// The value list stays out here, although the input schema publishes it. An
			// API that adds a value answers with it before the SDL GATool holds catches
			// up, and a response outside the published list would break the MUST. On the
			// way in the list guides the model; on the way out it is a promise about
			// someone else's data.
			writeJsonType(property, JsonSchemaKeywords.TYPE_STRING);
			return;
		}
		if (type instanceof GraphQLObjectType objectType) {
			writeObjectPosition(objectType, position, occurrences, property, walk);
			return;
		}
		if (type instanceof GraphQLUnionType union) {
			List<GraphQLObjectType> members = TypeConditions.membersOf(walk.schema(), union);
			if (!members.isEmpty()) {
				// The only field a union has of its own is __typename, which resolves
				// against any member, so the first one stands in for the union.
				writeAbstractPosition(union, members, members.getFirst(), position, occurrences, property, walk);
			}
			return;
		}
		if (type instanceof GraphQLInterfaceType interfaceType) {
			// The interface itself resolves the position's own selections, so an
			// interface no object type implements, which is legal SDL, still publishes
			// the shape of what the operation selected on it. Taking the first of an
			// empty member list would throw NoSuchElementException.
			writeAbstractPosition(interfaceType, walk.schema().getImplementations(interfaceType), interfaceType,
					position, occurrences, property, walk);
		}
	}

	// Writes the schema a custom scalar has in a result: the branch or branches of its
	// fragment beside null, and the fragment's description where this schema meets the
	// scalar first. A scalar without a fragment takes the type and the format of the
	// specification it cites.
	//
	// The fragment is the one ScalarSchemas.forResults chose: the entry of
	// gatool.results.scalar-schemas as the operator wrote it, and otherwise the type and
	// the format of the entry of gatool.inputs.scalar-schemas. A fragment that states a
	// description alone, or an enum alone on the input side, leaves the property empty,
	// because a custom scalar carries whatever the API decides and the empty schema
	// accepts all of it.
	//
	// The null branch is written whatever the GraphQL type says, as it is around every
	// other value of this schema.
	//
	// The description is written once per schema, at the first position that carries the
	// scalar, which is the rule the input schema follows inside one argument. The choice
	// is about tokens: the text travels in every tools/list, an operation reading five
	// timestamps would carry the same sentence five times, and a model that read it at
	// the first position knows the scalar at the others.
	private static void writeCustomScalar(GraphQLScalarType scalar, ObjectNode property, Walk walk) {
		ObjectNode fragment = walk.resultScalarSchemas().schemaFor(scalar.getName());
		if (fragment == null) {
			writeSpecifiedScalar(scalar, property);
			return;
		}
		List<ObjectNode> branches = ScalarSchemas.branchesOf(fragment);
		if (!branches.stream().allMatch(ObjectNode::isEmpty)) {
			writeNullable(property, branches);
		}
		if (fragment.has(JsonSchemaKeywords.DESCRIPTION) && walk.describedScalars().add(scalar.getName())) {
			property.set(JsonSchemaKeywords.DESCRIPTION, fragment.get(JsonSchemaKeywords.DESCRIPTION));
		}
	}

	// Writes the type and the format of the specification a scalar cites with
	// @specifiedBy, where GATool has read that specification and it states a JSON type.
	//
	// A specification says how a scalar is written on the wire, and it says so for a
	// result as for an argument: the date-time specification returns the string it
	// accepts, and the Long specifications return the integer or the string they accept.
	// So a scalar that cites one says the same in both schemas of a tool. The
	// specifications behind the table (the date-time of andimarek, the Long of
	// chillicream and of jakobmerrild, the Decimal and the UnsignedLong of chillicream)
	// state one form for both directions.
	//
	// A configured fragment wins over the table here as it does on the input side, which
	// is why this runs for a scalar without an entry alone: the operator who wrote a
	// fragment took the scalar over, and an API that cites a specification and returns
	// another form is described by an entry of gatool.results.scalar-schemas.
	//
	// The words of the specification stay with the input schema. They tell a model what
	// to send, and in a result the type and the format say what arrived.
	private static void writeSpecifiedScalar(GraphQLScalarType scalar, ObjectNode property) {
		ScalarSpecifications.Specification specification = ScalarSpecifications.of(scalar.getSpecifiedByUrl());
		if (specification == null) {
			return;
		}
		String jsonType = specification.jsonType();
		if (jsonType == null) {
			return;
		}
		ObjectNode typed = JSON_MAPPER.createObjectNode().put(JsonSchemaKeywords.TYPE, jsonType);
		String format = specification.format();
		if (format != null) {
			typed.put(JsonSchemaKeywords.FORMAT, format);
		}
		writeNullable(property, List.of(typed));
	}

	private static void writeObjectPosition(GraphQLObjectType objectType, String position, List<Occurrence> occurrences,
			ObjectNode property, Walk walk) {
		Collected shape = new Collected(new LinkedHashMap<>());
		boolean selected = false;
		for (Occurrence occurrence : occurrences) {
			SelectionSet selectionSet = occurrence.field().getSelectionSet();
			if (selectionSet != null) {
				selected = true;
				// The certainty of this occurrence, because a copy the model can switch
				// off takes everything it selects with it. Passing true here would
				// publish the switched copy's own keys as required. A key selected once
				// is the other case: the object then reaches the answer through that one
				// selection alone, and whenever it arrives its whole selection set came
				// with it, so its keys are as certain as the object itself, whatever
				// switch or type condition sits above. Passing the copy's own certainty
				// there would describe keys that may be missing from an object that
				// always carries them.
				collect(objectType, selectionSet, wholeSelectionArrives(occurrence, occurrences), shape, walk);
			}
		}
		if (!selected) {
			return;
		}
		ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT);
		if (walk.takeShapes(1)) {
			writeObject(shape, typed, walk);
		}
		else {
			cut(position, typed, walk);
		}
	}

	// Returns whether the selections of one occurrence arrive whenever the object they
	// sit in does: true for a certain occurrence, and for the only occurrence of a key,
	// since the object then arrives through that one selection alone.
	private static boolean wholeSelectionArrives(Occurrence occurrence, List<Occurrence> occurrences) {
		return occurrence.certain() || occurrences.size() == 1;
	}

	// Writes one union or interface position as anyOf: one object branch per member the
	// operation names, then an open object branch, then null. A position whose selections
	// are all its own publishes one shape without branches.
	//
	// A union or an interface answers with one of several shapes, and the operation says
	// which ones it expects: one type condition per member it selects against. Each of
	// those becomes a branch, so a model reads the members by name and the keys that
	// travel with each. The open branch after them is what keeps the promise true when
	// the API answers with a member this operation did not name.
	private static void writeAbstractPosition(GraphQLNamedType positionType, List<GraphQLObjectType> members,
			GraphQLFieldsContainer ownContainer, String position, List<Occurrence> occurrences, ObjectNode property,
			Walk walk) {
		Abstracted collected = new Abstracted(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
		boolean selected = false;
		for (Occurrence occurrence : occurrences) {
			SelectionSet selectionSet = occurrence.field().getSelectionSet();
			if (selectionSet != null) {
				selected = true;
				// The same rule as for an object position: a switched copy's selections
				// are switched, and the one selection an abstract position has arrives
				// whole whenever the position does.
				collectAbstractSelections(selectionSet, positionType, members, collected, walk,
						!wholeSelectionArrives(occurrence, occurrences));
			}
		}
		if (!selected) {
			return;
		}
		if (collected.byMember().isEmpty()) {
			// Every selection belongs to the position itself: __typename, the fields of
			// an interface position, and whatever a fragment on the position's own type
			// or a supertype of it brings. The position then has one shape whichever
			// member answers, and publishes it without branches.
			if (collected.isEmpty()) {
				return;
			}
			ObjectNode typed = writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT);
			if (walk.takeShapes(1)) {
				writeObject(shapeOf(ownContainer, collected, List.of(), walk), typed, walk);
			}
			else {
				cut(position, typed, walk);
			}
			return;
		}
		if (!walk.takeShapes(collected.byMember().size())) {
			cut(position, writeJsonType(property, JsonSchemaKeywords.TYPE_OBJECT), walk);
			return;
		}
		String discriminatorKey = findDiscriminatorKey(collected.own());
		if (discriminatorKey == null) {
			// The branches still name the members and their keys, and a model reading a
			// response cannot tell which one it holds. That is a property of the
			// operation, and selecting __typename is the fix, so startup says so and
			// leaves the document as written.
			walk.positionsWithoutTypename().add(position);
		}
		writeMemberBranches(members, collected, discriminatorKey, property.putArray(JsonSchemaKeywords.ANY_OF), walk);
	}

	// Writes the branches of one abstract position: one object per member the operation
	// names, with the discriminator pinned where the operation selects it, then the open
	// object branch, then null.
	private static void writeMemberBranches(List<GraphQLObjectType> members, Abstracted collected,
			@Nullable String discriminatorKey, ArrayNode anyOf, Walk walk) {
		for (Map.Entry<String, List<Conditioned>> member : collected.byMember().entrySet()) {
			GraphQLObjectType memberType = TypeConditions.memberNamed(members, member.getKey());
			if (memberType == null) {
				continue;
			}
			ObjectNode branch = anyOf.addObject();
			branch.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT);
			writeObject(shapeOf(memberType, collected, member.getValue(), walk), branch, walk);
			if (discriminatorKey != null) {
				// The one key that tells the branches apart. Two error members carrying
				// the same field, which the errors-as-data pattern produces constantly,
				// are otherwise indistinguishable to a model.
				((ObjectNode) branch.get(JsonSchemaKeywords.PROPERTIES)).putObject(discriminatorKey)
					.put(JsonSchemaKeywords.CONST, member.getKey());
			}
		}
		// The member this operation did not name, and the member the API adds tomorrow.
		anyOf.addObject()
			.put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_OBJECT)
			.put(JsonSchemaKeywords.DESCRIPTION,
					"another member of this type, which this operation did not select fields from");
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
	}

	// Publishes a position the budget refused as the open object shape, with a note a
	// model reads, and records it for the startup warning.
	//
	// The open shape is the same one the anyOf of an abstract position ends with, so a
	// response there conforms whatever it holds. The note says why the fields are
	// absent, which is what a model reading the schema would otherwise wonder.
	private static void cut(String position, ObjectNode open, Walk walk) {
		open.put(JsonSchemaKeywords.DESCRIPTION, "An object whose fields this schema leaves unlisted, because the "
				+ "operation selects more shapes than a model reads. The result still carries what the operation "
				+ "selected here.");
		walk.cut(position);
	}

	// Gathers one member's shape: the position's own selections, which every member
	// answers with, the switched ones without their keys required, and what the member's
	// own type conditions bring.
	private static Collected shapeOf(GraphQLFieldsContainer container, Abstracted collected,
			List<Conditioned> conditioned, Walk walk) {
		Collected shape = new Collected(new LinkedHashMap<>());
		if (!collected.own().isEmpty()) {
			collect(container, asSelectionSet(collected.own()), true, shape, walk);
		}
		if (!collected.switched().isEmpty()) {
			collect(container, asSelectionSet(collected.switched()), false, shape, walk);
		}
		for (Conditioned each : conditioned) {
			collect(container, each.selections(), !each.switched(), shape, walk);
		}
		return shape;
	}

	// Collects an abstract position's selections into the keys every member answers with,
	// the keys a directive can remove, and the selections each member's type condition
	// brings.
	//
	// The switched flag travels down with the selections, because a @skip or @include on
	// a fragment removes everything inside it from the answer. Dropping it here would
	// leave the inner keys in required, and the published schema would then refuse a
	// response the API really sends.
	private static void collectAbstractSelections(SelectionSet selectionSet, GraphQLNamedType positionType,
			List<GraphQLObjectType> members, Abstracted into, Walk walk, boolean switched) {
		for (Selection<?> selection : selectionSet.getSelections()) {
			if (selection instanceof Field) {
				(switched ? into.switched() : into.own()).add(selection);
			}
			else if (selection instanceof InlineFragment inlineFragment) {
				collectAbstractInlineFragment(inlineFragment, positionType, members, into, walk,
						switched || switched(inlineFragment));
			}
			else if (selection instanceof FragmentSpread spread) {
				// A named fragment is resolved here, so a union member reached through
				// one gets its branch. Reading inline fragments alone would leave the
				// empty schema for an operation that spells its members out in fragments,
				// which is how a large operation is usually written.
				FragmentDefinition fragment = walk.fragments().get(spread.getName());
				if (fragment != null) {
					collectAbstractCondition(fragment.getTypeCondition().getName(), fragment.getSelectionSet(),
							positionType, members, into, walk, switched || switched(spread));
				}
			}
		}
	}

	private static void collectAbstractInlineFragment(InlineFragment inlineFragment, GraphQLNamedType positionType,
			List<GraphQLObjectType> members, Abstracted into, Walk walk, boolean switched) {
		if (inlineFragment.getTypeCondition() == null) {
			collectAbstractSelections(inlineFragment.getSelectionSet(), positionType, members, into, walk, switched);
		}
		else {
			collectAbstractCondition(inlineFragment.getTypeCondition().getName(), inlineFragment.getSelectionSet(),
					positionType, members, into, walk, switched);
		}
	}

	// Routes what one type condition selects at an abstract position: to the position's
	// own selections where the condition holds for every member, and to the members it
	// covers otherwise.
	//
	// A condition naming the position's own type, or an interface that type implements,
	// holds for whichever member answers, a member added tomorrow included, so what it
	// selects is the position's own. A condition naming a member, another interface or a
	// union is expanded into the members it covers, because __typename answers with a
	// concrete type name and a branch pinning it to an interface name cannot match any
	// response. An interface every current member happens to implement without the
	// position's type declaring it stays in the second group: the member added tomorrow
	// need not implement it, and its answer has to conform as well.
	private static void collectAbstractCondition(String conditionName, SelectionSet selections,
			GraphQLNamedType positionType, List<GraphQLObjectType> members, Abstracted into, Walk walk,
			boolean switched) {
		if (TypeConditions.holdsForEvery(walk.schema(), conditionName, positionType)) {
			collectAbstractSelections(selections, positionType, members, into, walk, switched);
			return;
		}
		Set<String> covered = TypeConditions.possibleTypeNamesOf(walk.schema(), walk.schema().getType(conditionName));
		for (GraphQLObjectType member : members) {
			if (covered.contains(member.getName())) {
				into.byMember()
					.computeIfAbsent(member.getName(), (name) -> new ArrayList<>())
					.add(new Conditioned(selections, switched));
			}
		}
	}

	// Returns the response key of the __typename an abstract position selects
	// unconditionally, or null otherwise.
	//
	// The const is written only for a __typename the operation selects at the position
	// itself, unconditionally. Selected inside each branch it would leave a member the
	// operation did not name answering without it, and under @skip or @include the
	// model's own argument decides whether the key arrives at all.
	private static @Nullable String findDiscriminatorKey(List<Selection<?>> ownSelections) {
		for (Selection<?> selection : ownSelections) {
			if (selection instanceof Field field && TYPENAME.equals(field.getName()) && !switched(field)) {
				return responseKeyOf(field);
			}
		}
		return null;
	}

	// @skip and @include on a fragment or a spread remove every field it brings, exactly
	// as they do on a field, so the keys stay out of required whichever node carries
	// them.
	private static boolean switched(DirectivesContainer<?> node) {
		return !node.getDirectives(SKIP).isEmpty() || !node.getDirectives(INCLUDE).isEmpty();
	}

	private static String responseKeyOf(Field field) {
		return (field.getAlias() != null) ? field.getAlias() : field.getName();
	}

	// The occurrences of one key are the same field under the same or a compatible type,
	// so the first that resolves gives the type for all of them.
	private static @Nullable GraphQLFieldDefinition definitionOf(List<Occurrence> occurrences) {
		for (Occurrence occurrence : occurrences) {
			GraphQLFieldDefinition definition = occurrence.parent().getFieldDefinition(occurrence.field().getName());
			if (definition != null) {
				return definition;
			}
		}
		return null;
	}

	private static SelectionSet asSelectionSet(List<Selection<?>> selections) {
		return SelectionSet.newSelectionSet().selections(selections).build();
	}

	// Writes a property as anyOf over one branch carrying this JSON type and a null
	// branch, and returns the typed branch so the caller writes items or properties into
	// it.
	private static ObjectNode writeJsonType(ObjectNode property, String jsonType) {
		ObjectNode typed = JSON_MAPPER.createObjectNode().put(JsonSchemaKeywords.TYPE, jsonType);
		writeNullable(property, List.of(typed));
		return typed;
	}

	// Writes a property as anyOf over these branches and a null branch.
	private static void writeNullable(ObjectNode property, List<ObjectNode> branches) {
		ArrayNode anyOf = property.putArray(JsonSchemaKeywords.ANY_OF);
		branches.forEach(anyOf::add);
		anyOf.addObject().put(JsonSchemaKeywords.TYPE, JsonSchemaKeywords.TYPE_NULL);
	}

	private static @Nullable String jsonTypeOf(String scalarName) {
		return switch (scalarName) {
			case "Int" -> JsonSchemaKeywords.TYPE_INTEGER;
			case "Float" -> JsonSchemaKeywords.TYPE_NUMBER;
			case "String", "ID" -> JsonSchemaKeywords.TYPE_STRING;
			case "Boolean" -> JsonSchemaKeywords.TYPE_BOOLEAN;
			default -> null;
		};
	}

	private static Map<String, FragmentDefinition> fragmentsByName(OperationFile file) {
		return file.document()
			.getDefinitionsOfType(FragmentDefinition.class)
			.stream()
			.collect(Collectors.toMap(FragmentDefinition::getName, (fragment) -> fragment, (first, second) -> first));
	}

	// One write carries these values unchanged through the recursion: the schema each
	// field and type condition is resolved against, the operation's named fragments, and
	// the schema each custom scalar has in a result. It also carries the custom scalars
	// this schema has described so far, the two lists a write accumulates for startup
	// to warn about, and the shapes the operation has left to spend.
	private static final class Walk {

		private final GraphQLSchema schema;

		private final Map<String, FragmentDefinition> fragments;

		private final ScalarSchemas resultScalarSchemas;

		private final Set<String> describedScalars = new HashSet<>();

		private final List<String> positionsWithoutTypename = new ArrayList<>();

		private final List<String> cutPositions = new ArrayList<>();

		private final AtomicInteger remainingShapes = new AtomicInteger(SHAPE_BUDGET);

		// A class that creates the collections one write fills.
		Walk(GraphQLSchema schema, Map<String, FragmentDefinition> fragments, ScalarSchemas resultScalarSchemas) {
			this.schema = schema;
			this.fragments = fragments;
			this.resultScalarSchemas = resultScalarSchemas;
		}

		GraphQLSchema schema() {
			return this.schema;
		}

		Map<String, FragmentDefinition> fragments() {
			return this.fragments;
		}

		ScalarSchemas resultScalarSchemas() {
			return this.resultScalarSchemas;
		}

		Set<String> describedScalars() {
			return this.describedScalars;
		}

		List<String> positionsWithoutTypename() {
			return this.positionsWithoutTypename;
		}

		List<String> cutPositions() {
			return this.cutPositions;
		}

		/**
		 * Takes this many object shapes out of the operation's budget.
		 * @param count the number of object shapes to take
		 * @return whether the budget covered them
		 */
		// A position takes every branch it names or is published open. Writing the first
		// branches and cutting the rest would leave one stub per unwritten member, and
		// that is the text the budget exists to bound.
		boolean takeShapes(int count) {
			int remaining = this.remainingShapes.get();
			if (remaining < count) {
				return false;
			}
			this.remainingShapes.set(remaining - count);
			return true;
		}

		void cut(String position) {
			if (!this.cutPositions.contains(position)) {
				this.cutPositions.add(position);
			}
		}

	}

	/**
	 * The selections of one object shape, gathered by response key before anything is
	 * written, so a key the operation selects twice is described once with the fields of
	 * every occurrence.
	 */
	// 6.4.3 MergeSelectionSets appends the second occurrence's sub-selections to the
	// first, and 5.3.2 permits the pair only where the two are the same field, so one key
	// has one type and the union of its sub-selections. Writing each occurrence as it is
	// met would merge an object in place but replace the items of a list and the branches
	// of a union, so two fragments reaching the same list would publish the fields of
	// whichever came last. Gathering first means every node is written once, from
	// everything the operation selects under it.
	private record Collected(Map<String, List<Occurrence>> byKey) {

		void add(GraphQLFieldsContainer parent, Field field, boolean certain) {
			this.byKey.computeIfAbsent(responseKeyOf(field), (key) -> new ArrayList<>())
				.add(new Occurrence(parent, field, certain));
		}
	}

	/**
	 * One selection of a field: the type its name resolves against, the field as the
	 * operation wrote it, and whether its key is certain to arrive whenever the
	 * surrounding object does.
	 */
	private record Occurrence(GraphQLFieldsContainer parent, Field field, boolean certain) {
	}

	/**
	 * What an abstract position selects: the keys every member answers with, the keys a
	 * {@code @skip} or {@code @include} can remove from the answer, and the selections
	 * each named member carries.
	 */
	private record Abstracted(List<Selection<?>> own, List<Selection<?>> switched,
			Map<String, List<Conditioned>> byMember) {

		boolean isEmpty() {
			return this.own.isEmpty() && this.switched.isEmpty();
		}
	}

	/** One member's selections, and whether a directive can remove them. */
	private record Conditioned(SelectionSet selections, boolean switched) {
	}

}
