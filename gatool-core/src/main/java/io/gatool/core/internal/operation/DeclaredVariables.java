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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.execution.TypeFromAST;
import graphql.language.NonNullType;
import graphql.language.VariableDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLSchema;

/**
 * Reads what a tool needs to know at call time about the variables an operation declares,
 * once, at startup.
 *
 * @author Željko Kozina
 */
final class DeclaredVariables {

	private static final Set<String> PAGINATION_VARIABLES = Set.of("first", "after", "last", "before");

	private DeclaredVariables() {
	}

	/**
	 * Returns the pagination variables this operation declares.
	 * @param operation the validated operation whose variables are read
	 * @return the names of the declared variables that are pagination variables, in
	 * declaration order
	 */
	// The message for a result that is too large names the pagination variables, so the
	// model learns how to ask for less. The four names are the ones the Relay connection
	// specification defines, which is the convention the GraphQL community follows, and
	// an operation that pages by other names gets a message without this sentence.
	static List<String> findPaginationVariables(ValidatedOperation operation) {
		return operation.file()
			.operation()
			.getVariableDefinitions()
			.stream()
			.map(VariableDefinition::getName)
			.filter(PAGINATION_VARIABLES::contains)
			.toList();
	}

	/**
	 * Returns the input type of each variable the operation declares, by variable name.
	 * @param schema the schema the operation runs against
	 * @param operation the validated operation whose variables are read
	 * @return the input type of each variable, by variable name, leaving out a variable
	 * whose type the schema lacks
	 */
	// The null rules walk a value against its type, so an input field that declares a
	// default keeps it against the null a model in strict mode writes for every field it
	// would leave out. Reading the types once at startup keeps the lookup off every call,
	// and a type the schema leaves unknown stays out.
	static Map<String, GraphQLInputType> findVariableTypes(GraphQLSchema schema, ValidatedOperation operation) {
		Map<String, GraphQLInputType> typesByVariable = new LinkedHashMap<>();
		for (VariableDefinition variable : operation.file().operation().getVariableDefinitions()) {
			if (TypeFromAST.getTypeFromAST(schema, variable.getType()) instanceof GraphQLInputType type) {
				typesByVariable.put(variable.getName(), type);
			}
		}
		return Map.copyOf(typesByVariable);
	}

	/**
	 * Returns the variables a default protects, declared on the variable itself, at every
	 * place the operation uses it, or at one Non-Null place among them.
	 * @param schema the schema that declares the arguments and the input fields the
	 * variables fill
	 * @param operation the validated operation whose variables are read
	 * @return the names of the variables a default protects
	 */
	// The null rules need to know which variables have a default waiting for them, and
	// GraphQL writes a default in two places. The operation file declares one on the
	// variable, and the schema declares one on the argument or the input field the
	// variable fills. Both make a value left out arrive as the default, so both protect
	// the variable from the null a model in strict mode sends for everything it would
	// omit. Reading the document once at startup keeps this off every call.
	//
	// A variable used in more than one place is protected only where every one of them
	// declares a default. Dropping the null would otherwise turn an explicit null into an
	// absent argument at the place that lacks a default, and GraphQL gives those two
	// different meanings.
	//
	// That holds while every place takes a null. One Non-Null place among them changes
	// it, because the null is then an error whatever the other places make of it.
	// Validation lets a nullable variable reach a Non-Null place only where that place
	// declares a default (the rule All Variable Usages Are Allowed), and at run time the
	// null fails there, with "has coerced Null value for NonNull type" from graphql-java.
	// Sending it fails every call, so dropping it is the one choice under which the call
	// can work: the Non-Null place takes its default and every other place sees an absent
	// argument. An example is votes(locales: [Locale!]! = [en]) selecting
	// publishedBy(locales: [Locale!]) with one variable for both.
	//
	// The rule reads the default from the place itself, so it holds without leaning on
	// what validation refused. An argument and an input field can declare one, and an
	// item of a list literal cannot: graphql-java 25.0 puts a null item where a variable
	// is left out of a list, so dropping the null does not help a Non-Null item. A
	// Non-Null variable stays outside the rule as well. It is required, so the call
	// fails with the null dropped as it does with the null sent, and the null goes
	// through as the model wrote it.
	//
	// The argument of a directive is a place as the argument of a field is, and the rule
	// asks the same of it. A directive the schema declares can give its argument a
	// default, and that default then waits for the variable. The if of @include and of
	// @skip is Boolean! without a default, so it counts as a place that lacks one, and a
	// variable written there is protected by a default of its own. Validation lets a
	// variable reach that argument only where the variable is Non-Null or declares a
	// default, so every variable found there is either required or protected. The null
	// has to stay away from it: graphql-java 25.0 throws a NullPointerException from its
	// conditional check for a null that reaches @include, for example with $flag: Boolean
	// = true and the variables {"flag": null}.
	static Set<String> findVariablesWithDefaults(GraphQLSchema schema, ValidatedOperation operation) {
		Map<String, List<VariableUsages.Usage>> usagesByVariable = VariableUsages.of(schema, operation.file());
		Set<String> protectedVariables = new LinkedHashSet<>();
		for (VariableDefinition variable : operation.file().operation().getVariableDefinitions()) {
			List<VariableUsages.Usage> usages = usagesByVariable.getOrDefault(variable.getName(), List.of());
			boolean everyUsageHasDefault = !usages.isEmpty()
					&& usages.stream().allMatch(VariableUsages.Usage::hasDefault);
			boolean nullFailsWhereADefaultWaits = !(variable.getType() instanceof NonNullType)
					&& usages.stream().anyMatch((usage) -> usage.nonNull() && usage.hasDefault());
			if (variable.getDefaultValue() != null || everyUsageHasDefault || nullFailsWhereADefaultWaits) {
				protectedVariables.add(variable.getName());
			}
		}
		return Set.copyOf(protectedVariables);
	}

}
