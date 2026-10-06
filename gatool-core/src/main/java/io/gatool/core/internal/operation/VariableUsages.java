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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import org.jspecify.annotations.Nullable;

/**
 * Where each variable of one operation is used, and what the schema says about that
 * place.
 *
 * <p>
 * Two things need this, and reading the document twice would let them disagree. The input
 * schema takes an argument's own words as the description of the variable that fills it.
 * The null rules need to know whether a default waits at the other end, and whether that
 * place is Non-Null. A model in strict mode sends {@code null} for every value it would
 * leave out, and a {@code null} that lands where a default exists throws the default
 * away, or fails the call where the place is Non-Null.
 *
 * <p>
 * The whole selection set is walked, because an argument inside a nested field, an inline
 * fragment or a fragment spread describes its variable just as well as one at the root,
 * and its default counts just as much.
 *
 * <p>
 * The argument of a directive is such a place too, wherever the file writes the
 * directive: on the operation, a field, an inline fragment, a fragment spread or a
 * fragment definition. The schema describes that argument, may give it a default and may
 * declare it Non-Null, as it does for the argument of a field. The {@code if} of
 * {@code @include} and of {@code @skip} is {@code Boolean!} without a default.
 *
 * @author Željko Kozina
 */
public final class VariableUsages {

	private VariableUsages() {
	}

	/**
	 * Returns every place each variable is used.
	 * @param schema the schema the operation validates against
	 * @param file the operation as the file wrote it
	 * @return the usages by variable name, in the order the walk met them, and empty for
	 * a variable the document declares and does not use
	 */
	public static Map<String, List<Usage>> of(GraphQLSchema schema, OperationFile file) {
		GraphQLObjectType rootType = (file.operationType() == OperationType.MUTATION) ? schema.getMutationType()
				: schema.getQueryType();
		if (rootType == null) {
			return Map.of();
		}
		Map<String, FragmentDefinition> fragments = new LinkedHashMap<>();
		file.document()
			.getDefinitionsOfType(FragmentDefinition.class)
			.forEach((fragment) -> fragments.put(fragment.getName(), fragment));
		Map<String, List<Usage>> usagesByVariable = new LinkedHashMap<>();
		collectDirectives(schema, file.operation().getDirectives(), usagesByVariable);
		collectArguments(schema, rootType, file.operation().getSelectionSet(), fragments, new HashSet<>(),
				usagesByVariable);
		return usagesByVariable;
	}

	private static void collectArguments(GraphQLSchema schema, @Nullable GraphQLFieldsContainer parent,
			SelectionSet selectionSet, Map<String, FragmentDefinition> fragments, Set<String> visitedFragments,
			Map<String, List<Usage>> usagesByVariable) {
		for (Selection<?> selection : selectionSet.getSelections()) {
			if (selection instanceof Field field) {
				collectField(schema, parent, field, fragments, visitedFragments, usagesByVariable);
			}
			else if (selection instanceof InlineFragment inlineFragment) {
				collectDirectives(schema, inlineFragment.getDirectives(), usagesByVariable);
				TypeName condition = inlineFragment.getTypeCondition();
				GraphQLFieldsContainer container = (condition != null)
						? fieldsContainerNamed(schema, condition.getName()) : parent;
				collectArguments(schema, container, inlineFragment.getSelectionSet(), fragments, visitedFragments,
						usagesByVariable);
			}
			else if (selection instanceof FragmentSpread spread) {
				// Every spread is read for its own directives, and the fragment behind it
				// for the ones on its definition, the one time the fragment is visited.
				collectDirectives(schema, spread.getDirectives(), usagesByVariable);
				FragmentDefinition fragment = fragments.get(spread.getName());
				// A fragment is visited once, which stops a pair that spreads each other
				// from walking without end.
				if (fragment != null && visitedFragments.add(spread.getName())) {
					collectDirectives(schema, fragment.getDirectives(), usagesByVariable);
					collectArguments(schema, fieldsContainerNamed(schema, fragment.getTypeCondition().getName()),
							fragment.getSelectionSet(), fragments, visitedFragments, usagesByVariable);
				}
			}
		}
	}

	private static void collectField(GraphQLSchema schema, @Nullable GraphQLFieldsContainer parent, Field field,
			Map<String, FragmentDefinition> fragments, Set<String> visitedFragments,
			Map<String, List<Usage>> usagesByVariable) {
		GraphQLFieldDefinition fieldDefinition = (parent != null) ? parent.getFieldDefinition(field.getName()) : null;
		if (fieldDefinition == null) {
			return;
		}
		for (Argument argument : field.getArguments()) {
			collectArgument(fieldDefinition.getArgument(argument.getName()), argument, usagesByVariable);
		}
		collectDirectives(schema, field.getDirectives(), usagesByVariable);
		SelectionSet nested = field.getSelectionSet();
		if (nested != null) {
			// A union declares members and leaves the fields to them, so the selection
			// under a union field holds __typename, inline fragments and spreads, and
			// each fragment names the type whose fields it selects. The walk goes on
			// without a parent there, and the type condition of each fragment supplies
			// one. Asking for a type with fields before walking would skip every argument
			// under a union.
			GraphQLFieldsContainer container = (GraphQLTypeUtil
				.unwrapAll(fieldDefinition.getType()) instanceof GraphQLFieldsContainer fields) ? fields : null;
			collectArguments(schema, container, nested, fragments, visitedFragments, usagesByVariable);
		}
	}

	// Records each variable inside the arguments of the directives written at one node.
	//
	// A variable written at a directive is used there as it is at a field argument, so
	// the null rules see the default that argument declares.
	//
	// The directive is read from the schema, which holds @include and @skip beside
	// the ones the API declares. A directive the schema lacks fails validation before
	// this walk, and @gatool is GATool's own and takes literals alone, so both are
	// passed over.
	private static void collectDirectives(GraphQLSchema schema, List<Directive> directives,
			Map<String, List<Usage>> usagesByVariable) {
		for (Directive directive : directives) {
			GraphQLDirective declared = schema.getDirective(directive.getName());
			if (declared == null) {
				continue;
			}
			for (Argument argument : directive.getArguments()) {
				collectArgument(declared.getArgument(argument.getName()), argument, usagesByVariable);
			}
		}
	}

	// Records each variable inside the value written for one argument of a field or of a
	// directive, read against the argument the schema declares.
	private static void collectArgument(@Nullable GraphQLArgument definition, Argument argument,
			Map<String, List<Usage>> usagesByVariable) {
		if (definition != null) {
			collectValue(definition.getType(), new Usage(definition.getDescription(), definition.hasSetDefaultValue(),
					GraphQLTypeUtil.isNonNull(definition.getType())), argument.getValue(), usagesByVariable);
		}
	}

	// Records each variable inside one argument value, walking into a list or an object
	// literal to reach the variables written there.
	//
	// A variable given straight to an argument takes that argument's words, its
	// default and its nullability. One written inside an object literal takes those of
	// the input field it fills, which is the place its value actually lands.
	private static void collectValue(GraphQLInputType type, Usage location, Value<?> value,
			Map<String, List<Usage>> usagesByVariable) {
		if (value instanceof VariableReference reference) {
			usagesByVariable.computeIfAbsent(reference.getName(), (name) -> new ArrayList<>()).add(location);
			return;
		}
		GraphQLInputType unwrapped = (GraphQLTypeUtil.unwrapAll(type) instanceof GraphQLInputType named) ? named : type;
		if (value instanceof ArrayValue array) {
			// An element keeps the list's words, because they describe the values it
			// holds, and loses its default: a list's default replaces the whole list, so
			// dropping one element's null would shorten the list instead.
			//
			// The place an element lands is the item of the list, so its nullability is
			// the item type's: [Locale!] refuses a null item and [Locale] takes one. The
			// item type travels on with its wrappers for that reason.
			GraphQLInputType itemType = (GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList list
					&& list.getWrappedType() instanceof GraphQLInputType wrapped) ? wrapped : unwrapped;
			Usage elementUsage = new Usage(location.description(), false, GraphQLTypeUtil.isNonNull(itemType));
			array.getValues().forEach((item) -> collectValue(itemType, elementUsage, item, usagesByVariable));
			return;
		}
		if (value instanceof ObjectValue object && unwrapped instanceof GraphQLInputObjectType inputObject) {
			for (ObjectField objectField : object.getObjectFields()) {
				GraphQLInputObjectField declared = inputObject.getField(objectField.getName());
				if (declared != null) {
					collectValue(declared.getType(),
							new Usage(declared.getDescription(), declared.hasSetDefaultValue(),
									GraphQLTypeUtil.isNonNull(declared.getType())),
							objectField.getValue(), usagesByVariable);
				}
			}
		}
	}

	private static @Nullable GraphQLFieldsContainer fieldsContainerNamed(GraphQLSchema schema, String typeName) {
		return (schema.getTypeAs(typeName) instanceof GraphQLFieldsContainer container) ? container : null;
	}

	/**
	 * One place a variable is used.
	 *
	 * @param description what the schema says about that argument of a field or of a
	 * directive, or about that input field, or {@code null} where the schema leaves it
	 * undescribed
	 * @param hasDefault whether the schema declares a default there, so a value left out
	 * arrives as that default
	 * @param nonNull whether the schema declares that place Non-Null, so a {@code null}
	 * sent there fails the call
	 */
	public record Usage(@Nullable String description, boolean hasDefault, boolean nonNull) {
	}

}
