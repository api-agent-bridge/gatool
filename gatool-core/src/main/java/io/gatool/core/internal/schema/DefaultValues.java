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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;

import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a declared default, on an operation's variable or on a schema argument or input
 * field, into the JSON Schema {@code default} keyword.
 *
 * <p>
 * The keyword is an annotation in JSON Schema 2020-12, so it leaves validation unchanged
 * and tells the model what happens when it leaves the argument out. Without it,
 * {@code $first: Int = 10} publishes an optional integer that does not mention 10.
 *
 * <p>
 * GraphQL coerces a default against its declared type before it applies, so the same
 * rules run here. A single value given for a list becomes a one element list, an
 * {@code Int} literal for an {@code ID} becomes a string, an enum value becomes its name,
 * and an explicit null stays null. An object or list literal on a custom scalar publishes
 * as the JSON it spells, since such a scalar takes any literal and the literal is the
 * only description of its value. A number publishes the digits the literal spells: a
 * {@code Float} as a JSON float, and a whole number or a decimal on a custom scalar as
 * the digits themselves, because the API reads those as a {@code BigInteger} or a
 * {@code BigDecimal} and JSON carries any number of digits. A literal JSON cannot carry
 * faithfully, such as a variable reference, is left out. A {@code default} that fails its
 * own property schema is worse than a missing one. A strict client rejects the whole
 * tool, and a model that reads it and sends it back is refused by the argument validation
 * in front of the tool.
 *
 * <p>
 * A default arrives one of two ways, and both end here. A schema read from SDL carries a
 * GraphQL literal. A schema built in code carries a Java value, which
 * {@link #ofProgrammatic(graphql.schema.GraphQLInputObjectField, Object)} puts through
 * the same rules, so the same default publishes the same JSON whichever way the schema
 * was built.
 *
 * @author Željko Kozina
 */
public final class DefaultValues {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private DefaultValues() {
	}

	/**
	 * Converts the default of one operation variable.
	 * @param schema the schema the operation validated against
	 * @param declaredType the type the operation declares for the variable
	 * @param value the default the operation file wrote
	 * @return the JSON to publish, or {@code null} where the literal lacks a faithful
	 * JSON form
	 */
	public static @Nullable JsonNode of(GraphQLSchema schema, Type<?> declaredType, Value<?> value) {
		GraphQLInputType inputType = inputTypeOf(schema, declaredType);
		return (inputType != null) ? of(inputType, value) : null;
	}

	/**
	 * Converts the default of one Input Object field.
	 * @param field the field the schema declares
	 * @param value the default the schema wrote
	 * @return the JSON to publish, or {@code null} where the literal lacks a faithful
	 * JSON form
	 */
	public static @Nullable JsonNode of(GraphQLInputObjectField field, Value<?> value) {
		return of(field.getType(), value);
	}

	/**
	 * Converts the default of one Input Object field that the schema holds as a Java
	 * value.
	 *
	 * <p>
	 * A schema built in code carries its defaults as Java objects in place of literals,
	 * and the same coercion applies to them. Writing such a value straight through
	 * Jackson would publish {@code "default": "drama"} on a field of type
	 * {@code [String!]}, where GraphQL reads that value as {@code ["drama"]}. That
	 * default fails its own property schema, and the model would be refused for sending
	 * the value it read.
	 * @param field the field the schema declares
	 * @param value the default the schema holds
	 * @return the JSON to publish, or {@code null} where the value lacks a faithful JSON
	 * form
	 */
	public static @Nullable JsonNode ofProgrammatic(GraphQLInputObjectField field, Object value) {
		return ofProgrammatic(field.getType(), value);
	}

	// Converts a Java default value against the type it is declared for, walking non-null
	// wrappers, list items and input object fields as it goes.
	private static @Nullable JsonNode ofProgrammatic(GraphQLInputType type, @Nullable Object value) {
		if (type instanceof GraphQLNonNull nonNull && nonNull.getWrappedType() instanceof GraphQLInputType wrapped) {
			return ofProgrammatic(wrapped, value);
		}
		if (value == null) {
			return NODES.nullNode();
		}
		if (type instanceof GraphQLList list && list.getWrappedType() instanceof GraphQLInputType itemType) {
			return listOfProgrammatic(itemType, value);
		}
		if (type instanceof GraphQLInputObjectType inputObject && value instanceof Map<?, ?> fieldValues) {
			return objectOfProgrammatic(inputObject, fieldValues);
		}
		// A custom scalar holding a map or a collection publishes the JSON it spells, as
		// the literal form does. Any other Java type a custom scalar holds is left out,
		// because rendering one by guesswork would tell the model to send something the
		// API refuses.
		if (type instanceof GraphQLScalarType && (value instanceof Map<?, ?> || value instanceof Collection<?>)) {
			return jsonOfProgrammatic(type, value);
		}
		return plainOfProgrammatic(type, value);
	}

	// Converts a string, a boolean or a number, which carry into JSON as they stand, and
	// answers null for any other Java type.
	//
	// An enum default arrives as the value's name, which graphql-java checks against
	// the type when it builds the schema.
	private static @Nullable JsonNode plainOfProgrammatic(GraphQLInputType type, Object value) {
		if (value instanceof String text) {
			return NODES.stringNode(text);
		}
		if (value instanceof Boolean flag) {
			return NODES.booleanNode(flag);
		}
		if (value instanceof Number number) {
			return numberOf(type, number);
		}
		return null;
	}

	// Converts a Java value held by a custom scalar into the JSON it spells: a map
	// becomes an object with its keys, a collection a list, and each leaf goes through
	// the plain rules. The answer is null unless every leaf converts.
	private static @Nullable JsonNode jsonOfProgrammatic(GraphQLInputType scalar, @Nullable Object value) {
		if (value == null) {
			return NODES.nullNode();
		}
		if (value instanceof Map<?, ?> entries) {
			ObjectNode object = NODES.objectNode();
			for (Map.Entry<?, ?> entry : entries.entrySet()) {
				JsonNode converted = jsonOfProgrammatic(scalar, entry.getValue());
				if (converted == null) {
					return null;
				}
				object.set(String.valueOf(entry.getKey()), converted);
			}
			return object;
		}
		if (value instanceof Collection<?> elements) {
			ArrayNode list = NODES.arrayNode();
			for (Object element : elements) {
				JsonNode converted = jsonOfProgrammatic(scalar, element);
				if (converted == null) {
					return null;
				}
				list.add(converted);
			}
			return list;
		}
		return plainOfProgrammatic(scalar, value);
	}

	// Converts a Java default declared for a list type. A collection converts element by
	// element against the item type, and a single value becomes a one element list. The
	// answer is null unless every element converts faithfully.
	//
	// GraphQL wraps a single value that way itself, and a default a model reads has to
	// say the same thing.
	private static @Nullable JsonNode listOfProgrammatic(GraphQLInputType itemType, Object value) {
		ArrayNode list = NODES.arrayNode();
		if (value instanceof Collection<?> elements) {
			for (Object element : elements) {
				JsonNode converted = ofProgrammatic(itemType, element);
				if (converted == null) {
					return null;
				}
				list.add(converted);
			}
			return list;
		}
		JsonNode single = ofProgrammatic(itemType, value);
		return (single != null) ? list.add(single) : null;
	}

	private static @Nullable JsonNode objectOfProgrammatic(GraphQLInputObjectType inputObject, Map<?, ?> fieldValues) {
		ObjectNode object = NODES.objectNode();
		for (Map.Entry<?, ?> field : fieldValues.entrySet()) {
			String name = String.valueOf(field.getKey());
			GraphQLInputObjectField declared = inputObject.getField(name);
			if (declared == null) {
				return null;
			}
			JsonNode converted = ofProgrammatic(declared.getType(), field.getValue());
			if (converted == null) {
				return null;
			}
			object.set(name, converted);
		}
		return object;
	}

	// Converts a Java number default. A whole value takes the integer path, which
	// publishes a string for ID and the digits for anything else. Every other value
	// becomes a JSON float for a Float and keeps its digits for a custom scalar.
	//
	// A whole number takes the ID rule the literal path uses, so the two paths publish
	// the same JSON for the same default. A Float takes the double path whatever the
	// digits, as the literal path does, and a decimal on a custom scalar keeps every
	// digit the schema holds, as the literal path keeps every digit the file wrote.
	private static @Nullable JsonNode numberOf(GraphQLInputType type, Number number) {
		BigDecimal value;
		try {
			value = new BigDecimal(number.toString());
		}
		catch (NumberFormatException ex) {
			// NaN and the infinities, which every GraphQL scalar refuses.
			return null;
		}
		if (isFloat(type)) {
			return floatOf(value);
		}
		try {
			return integerOf(type, value.toBigIntegerExact());
		}
		catch (ArithmeticException ex) {
			return NODES.numberNode(value);
		}
	}

	private static @Nullable JsonNode of(GraphQLInputType type, Value<?> value) {
		if (type instanceof GraphQLNonNull nonNull && nonNull.getWrappedType() instanceof GraphQLInputType wrapped) {
			return of(wrapped, value);
		}
		if (value instanceof NullValue) {
			return NODES.nullNode();
		}
		if (type instanceof GraphQLList list && list.getWrappedType() instanceof GraphQLInputType itemType) {
			return listOf(itemType, value);
		}
		if (value instanceof ObjectValue object && type instanceof GraphQLInputObjectType inputObject) {
			return objectOf(inputObject, object);
		}
		// A custom scalar takes any literal. An object or a list on one, such as a filter
		// on a JSON scalar, is spelled in the operation file exactly as the API takes it,
		// so the JSON form of the literal is the default to publish. Validation
		// refuses the document before a built-in scalar could receive one.
		if (type instanceof GraphQLScalarType && (value instanceof ObjectValue || value instanceof ArrayValue)) {
			return jsonOf(value);
		}
		return scalarOf(type, value);
	}

	// The scalar and enum literals, each in the JSON form the API reads for the type.
	private static @Nullable JsonNode scalarOf(GraphQLInputType type, Value<?> value) {
		if (value instanceof StringValue text) {
			return NODES.stringNode(text.getValue());
		}
		if (value instanceof BooleanValue flag) {
			return NODES.booleanNode(flag.isValue());
		}
		if (value instanceof EnumValue enumValue) {
			return NODES.stringNode(enumValue.getName());
		}
		if (value instanceof IntValue number) {
			// GraphQL's Float coerces a whole literal, so 10000000000000000000 is a legal
			// Float default, and a JSON float carries it the way the API reads it.
			return isFloat(type) ? floatOf(new BigDecimal(number.getValue())) : integerOf(type, number.getValue());
		}
		if (value instanceof FloatValue number) {
			// A custom scalar such as Decimal reads the literal as a BigDecimal, and
			// GATool sends it to the API intact, so the default keeps every digit the
			// file wrote. Rounding it to a double would publish 1.2345678901234567E9 for
			// a default the file spells to eighteen decimal places.
			return isFloat(type) ? floatOf(number.getValue()) : NODES.numberNode(number.getValue());
		}
		// A variable reference, and anything a later GraphQL release adds, lacks a
		// faithful JSON form here.
		return null;
	}

	// Converts a literal into the JSON it spells, without a type to coerce against: a
	// string, a boolean, an enum name, a number, null, or an object or list of those. The
	// answer is null unless every nested literal converts.
	//
	// A number keeps its digits here as it does on the typed path for a custom scalar, so
	// one default reads the same whether it sits at the top or inside a custom scalar's
	// object.
	private static @Nullable JsonNode jsonOf(Value<?> value) {
		if (value instanceof NullValue) {
			return NODES.nullNode();
		}
		if (value instanceof StringValue text) {
			return NODES.stringNode(text.getValue());
		}
		if (value instanceof BooleanValue flag) {
			return NODES.booleanNode(flag.isValue());
		}
		if (value instanceof EnumValue enumValue) {
			return NODES.stringNode(enumValue.getName());
		}
		if (value instanceof IntValue number) {
			return wholeNumberOf(number.getValue());
		}
		if (value instanceof FloatValue number) {
			return NODES.numberNode(number.getValue());
		}
		if (value instanceof ArrayValue array) {
			return jsonArrayOf(array);
		}
		if (value instanceof ObjectValue object) {
			return jsonObjectOf(object);
		}
		return null;
	}

	// Null where any item lacks a JSON form, so the default is dropped as a whole.
	private static @Nullable ArrayNode jsonArrayOf(ArrayValue array) {
		ArrayNode list = NODES.arrayNode();
		for (Value<?> item : array.getValues()) {
			JsonNode converted = jsonOf(item);
			if (converted == null) {
				return null;
			}
			list.add(converted);
		}
		return list;
	}

	private static @Nullable ObjectNode jsonObjectOf(ObjectValue object) {
		ObjectNode fieldValues = NODES.objectNode();
		for (ObjectField field : object.getObjectFields()) {
			JsonNode converted = jsonOf(field.getValue());
			if (converted == null) {
				return null;
			}
			fieldValues.set(field.getName(), converted);
		}
		return fieldValues;
	}

	private static boolean isFloat(GraphQLInputType type) {
		return type instanceof GraphQLScalarType scalar && "Float".equals(scalar.getName());
	}

	// Converts a GraphQL literal declared for a list type. A list literal converts item
	// by item against the item type, and a single literal becomes a one element list. The
	// answer is null unless every item converts faithfully.
	//
	// GraphQL wraps a single value that way itself, and a default a model reads has to
	// say the same thing.
	private static @Nullable JsonNode listOf(GraphQLInputType itemType, Value<?> value) {
		ArrayNode list = NODES.arrayNode();
		if (value instanceof ArrayValue array) {
			for (Value<?> item : array.getValues()) {
				JsonNode converted = of(itemType, item);
				if (converted == null) {
					return null;
				}
				list.add(converted);
			}
			return list;
		}
		JsonNode single = of(itemType, value);
		return (single != null) ? list.add(single) : null;
	}

	// Converts a whole number default, as a JSON string for an ID and as a JSON number
	// carrying every digit for anything else.
	//
	// GraphQL's ID accepts an integer and holds a string, so the default says what the
	// API will hold instead of what the file typed.
	private static JsonNode integerOf(GraphQLInputType type, BigInteger value) {
		if (type instanceof GraphQLScalarType scalar && "ID".equals(scalar.getName())) {
			return NODES.stringNode(value.toString());
		}
		return wholeNumberOf(value);
	}

	// Converts a whole number to a JSON number: a long where the value fits one, and the
	// digits themselves past that.
	//
	// A default past a long is legal on a custom scalar, which reads it as a BigInteger,
	// and JSON carries the digits whatever their number.
	private static JsonNode wholeNumberOf(BigInteger value) {
		return (value.bitLength() < Long.SIZE) ? NODES.numberNode(value.longValueExact()) : NODES.numberNode(value);
	}

	private static @Nullable JsonNode floatOf(BigDecimal value) {
		double number = value.doubleValue();
		return Double.isInfinite(number) ? null : NODES.numberNode(number);
	}

	// Converts an Input Object literal into a JSON object, with one property for each
	// field the literal gives.
	//
	// The fields a default leaves out take their own defaults at the API, so they stay
	// out here as well.
	private static @Nullable JsonNode objectOf(GraphQLInputObjectType inputObject, ObjectValue object) {
		ObjectNode fieldValues = NODES.objectNode();
		for (ObjectField field : object.getObjectFields()) {
			GraphQLInputObjectField declared = inputObject.getField(field.getName());
			if (declared == null) {
				return null;
			}
			JsonNode converted = of(declared.getType(), field.getValue());
			if (converted == null) {
				return null;
			}
			fieldValues.set(field.getName(), converted);
		}
		return fieldValues;
	}

	private static @Nullable GraphQLInputType inputTypeOf(GraphQLSchema schema, Type<?> declaredType) {
		if (declaredType instanceof NonNullType nonNull) {
			GraphQLInputType wrapped = inputTypeOf(schema, nonNull.getType());
			return (wrapped != null) ? GraphQLNonNull.nonNull(wrapped) : null;
		}
		if (declaredType instanceof ListType list) {
			GraphQLInputType itemType = inputTypeOf(schema, list.getType());
			return (itemType != null) ? GraphQLList.list(itemType) : null;
		}
		if (declaredType instanceof TypeName named) {
			GraphQLNamedType type = schema.getTypeAs(named.getName());
			return (type instanceof GraphQLInputType input) ? input : null;
		}
		return null;
	}

}
