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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import org.jspecify.annotations.Nullable;

/**
 * Applies the null rules to the arguments of one call.
 *
 * <p>
 * Strict function calling makes a model send {@code null} for every value it would leave
 * out. Those nulls would replace the defaults written in the operation file and the ones
 * the API declares on its arguments and input fields. A {@code null} for a variable that
 * has a default waiting for it leaves the map, and the GraphQL API applies the default
 * instead.
 *
 * <p>
 * A variable without a default keeps its {@code null}, because a model clearing a value
 * is a real request, and GraphQL separates an explicit {@code null} from a missing one.
 * An argument the model left out stays out.
 *
 * <p>
 * The rule lives in the core, so a call behaves the same over MCP and in process.
 *
 * @author Željko Kozina
 */
public final class NullArguments {

	private NullArguments() {
	}

	/**
	 * Prepares the variables of one call.
	 * @param arguments the arguments the caller sent
	 * @param variablesWithDefaults the variables that have a default waiting for them,
	 * from {@link ToolOperation#variablesWithDefaults()}
	 * @param variableTypes the input type of each variable, read to apply the same rule
	 * to the input fields inside a value; a variable left out of this map keeps its value
	 * as the caller sent it
	 * @param sendExplicitNulls the value of {@code gatool.inputs.send-explicit-nulls},
	 * where {@code true} sends every argument unchanged
	 * @return the variables the executor sends, in a map of their own whichever branch
	 * built it, so the caller's map stays the caller's
	 */
	public static Map<String, @Nullable Object> prepare(Map<String, @Nullable Object> arguments,
			Set<String> variablesWithDefaults, Map<String, GraphQLInputType> variableTypes, boolean sendExplicitNulls) {
		// A copy here as well, so the two branches return the same kind of map, and a
		// caller changing its own map after the call leaves the variables the executor
		// holds unchanged.
		if (sendExplicitNulls || arguments.isEmpty()) {
			return new LinkedHashMap<>(arguments);
		}
		Map<String, @Nullable Object> prepared = new LinkedHashMap<>();
		arguments.forEach((name, value) -> {
			if (value == null && variablesWithDefaults.contains(name)) {
				return;
			}
			prepared.put(name, withinValue(value, variableTypes.get(name)));
		});
		return prepared;
	}

	// Applies the same rule inside the value: a null written for an input field that
	// declares a default leaves the value, and the API applies that default.
	//
	// The walk follows the value instead of the type, so an input type that holds another
	// of its own kind is bounded by what the model actually sent. Where the caller left
	// the type out, the value stays as it is.
	private static @Nullable Object withinValue(@Nullable Object value, @Nullable GraphQLInputType type) {
		if (value == null || type == null) {
			return value;
		}
		GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(type);
		if (unwrapped instanceof GraphQLList list && value instanceof List<?> elements) {
			return withinList(list, elements);
		}
		if (unwrapped instanceof GraphQLInputObjectType inputObject && value instanceof Map<?, ?> fields) {
			return withinInputObject(inputObject, fields);
		}
		return value;
	}

	private static List<@Nullable Object> withinList(GraphQLList list, List<?> elements) {
		GraphQLType elementType = GraphQLTypeUtil.unwrapNonNull(list.getWrappedType());
		List<@Nullable Object> walked = new ArrayList<>(elements.size());
		for (Object element : elements) {
			walked.add(withinValue(element, (elementType instanceof GraphQLInputType input) ? input : null));
		}
		return walked;
	}

	private static Map<String, @Nullable Object> withinInputObject(GraphQLInputObjectType inputObject,
			Map<?, ?> fields) {
		Map<String, @Nullable Object> walked = new LinkedHashMap<>();
		fields.forEach((name, fieldValue) -> {
			GraphQLInputObjectField field = inputObject.getField(String.valueOf(name));
			if (field == null) {
				walked.put(String.valueOf(name), fieldValue);
				return;
			}
			if (fieldValue == null && field.hasSetDefaultValue()) {
				return;
			}
			walked.put(field.getName(), withinValue(fieldValue, field.getType()));
		});
		return walked;
	}

}
