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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

/**
 * Answers what a type condition covers: which object types can answer for a type, and
 * whether a condition holds for every one of them.
 *
 * @author Željko Kozina
 */
final class TypeConditions {

	private TypeConditions() {
	}

	/**
	 * Returns whether a type condition holds for every object the given type can answer
	 * with: the condition names that type itself, an interface it implements, or a union
	 * holding it.
	 * @param schema the schema the types belong to
	 * @param conditionName the name the type condition carries
	 * @param type the object or interface type at the position
	 * @return whether the condition holds for every object the type can answer with
	 */
	// For an object type the condition either holds or the document fails validation, so
	// any condition that can hold, holds always. For an interface, only the interfaces it
	// declares are certain: every implementation, present or future, has to implement
	// those as well. A union cannot declare a supertype.
	static boolean holdsForEvery(GraphQLSchema schema, String conditionName, GraphQLNamedType type) {
		if (conditionName.equals(type.getName())) {
			return true;
		}
		if (type instanceof GraphQLObjectType object) {
			return possibleTypeNamesOf(schema, schema.getType(conditionName)).contains(object.getName());
		}
		if (type instanceof GraphQLInterfaceType interfaceType) {
			return implementsTransitively(interfaceType, conditionName);
		}
		return false;
	}

	// The specification asks an interface to declare every interface it inherits through
	// another, and this walks the chain all the same, so a schema built in code with the
	// shortcut still reads the way the SDL form does.
	private static boolean implementsTransitively(GraphQLInterfaceType interfaceType, String conditionName) {
		for (GraphQLNamedOutputType declared : interfaceType.getInterfaces()) {
			if (declared.getName().equals(conditionName)) {
				return true;
			}
			if (declared instanceof GraphQLInterfaceType above && implementsTransitively(above, conditionName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the names of the object types that can answer for a type: the type itself,
	 * the implementations of an interface, or the members of a union.
	 * @param schema the schema the types belong to
	 * @param type the type, or {@code null} for a name the schema lacks
	 * @return the names of the object types, an empty set for a scalar, an enum or
	 * {@code null}
	 */
	static Set<String> possibleTypeNamesOf(GraphQLSchema schema, @Nullable GraphQLType type) {
		if (type instanceof GraphQLObjectType object) {
			return Set.of(object.getName());
		}
		if (type instanceof GraphQLInterfaceType interfaceType) {
			return schema.getImplementations(interfaceType)
				.stream()
				.map(GraphQLObjectType::getName)
				.collect(Collectors.toSet());
		}
		if (type instanceof GraphQLUnionType union) {
			return membersOf(schema, union).stream().map(GraphQLObjectType::getName).collect(Collectors.toSet());
		}
		return Set.of();
	}

	static List<GraphQLObjectType> membersOf(GraphQLSchema schema, GraphQLUnionType union) {
		return union.getTypes()
			.stream()
			.map((member) -> schema.getTypeAs(member.getName()))
			.filter(GraphQLObjectType.class::isInstance)
			.map(GraphQLObjectType.class::cast)
			.toList();
	}

	static @Nullable GraphQLObjectType memberNamed(List<GraphQLObjectType> members, String name) {
		return members.stream().filter((member) -> member.getName().equals(name)).findFirst().orElse(null);
	}

}
