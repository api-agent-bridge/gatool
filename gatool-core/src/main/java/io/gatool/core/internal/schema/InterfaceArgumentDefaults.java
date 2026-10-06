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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.language.AstPrinter;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import org.jspecify.annotations.Nullable;

/**
 * Finds the arguments of a schema whose default differs from the default the same
 * argument has on the interface, and says what that means for a schema graphql-java
 * refused.
 *
 * <p>
 * The GraphQL specification asks a field that implements an interface field for every
 * argument of that field, each accepting the same type, and leaves the default of an
 * argument to each type. graphql-java compares the default as well, as the text the
 * schema wrote, so it refuses a schema that graphql-js builds and that an API runs in
 * production. Calling such a schema invalid would send the operator looking for a mistake
 * in a schema that is free of one.
 *
 * <p>
 * The schema stays refused. Giving the implementing arguments the default of their
 * interface inside GATool is the alternative, and the schema GATool holds would then name
 * a default the API does not apply, in the text a model reads for a type. The message
 * names that way out for an operator who chooses it for a copy of the schema.
 *
 * <p>
 * This class goes once GATool builds on a graphql-java that builds such a schema, which
 * {@value #UPSTREAM_ISSUE} asks for.
 *
 * @author Željko Kozina
 */
final class InterfaceArgumentDefaults {

	static final String UPSTREAM_ISSUE = "https://github.com/graphql-java/graphql-java/issues/4480";

	private InterfaceArgumentDefaults() {
	}

	/**
	 * Returns every place where an implementing type gives an argument the type its
	 * interface declares and another default.
	 * @param document the parsed schema
	 * @return the places in the order the schema writes them, one for each pair of an
	 * implementing type and an interface, which is how graphql-java counts its errors
	 */
	// The syntax tree is read, because the schema was refused and a built one does not
	// exist. A type and its extensions are read as one, since graphql-java merges them
	// before it compares. The types and the defaults are compared as printed text, which
	// is the comparison graphql-java makes, so [Int] = 1 beside [Int] = [1] counts as a
	// difference here as it does there.
	static List<Difference> findIn(Document document) {
		Map<String, Parts> partsByType = new LinkedHashMap<>();
		Set<String> interfaces = new LinkedHashSet<>();
		for (Definition<?> definition : document.getDefinitions()) {
			if (definition instanceof ImplementingTypeDefinition<?> type) {
				addParts(type, partsByType.computeIfAbsent(type.getName(), (name) -> new Parts()));
				if (definition instanceof InterfaceTypeDefinition) {
					interfaces.add(type.getName());
				}
			}
		}
		List<Difference> differences = new ArrayList<>();
		partsByType.forEach((typeName, parts) -> {
			for (String interfaceName : parts.implemented()) {
				Parts declared = interfaces.contains(interfaceName) ? partsByType.get(interfaceName) : null;
				if (declared != null) {
					collect(typeName, parts, declared, differences);
				}
			}
		});
		return differences;
	}

	// The interfaces a type or an extension of it implements, and its fields, the first
	// definition of a field name winning the way graphql-java merges extensions.
	private static void addParts(ImplementingTypeDefinition<?> type, Parts parts) {
		for (Type<?> implemented : type.getImplements()) {
			if (implemented instanceof TypeName name) {
				parts.implemented().add(name.getName());
			}
		}
		for (FieldDefinition field : type.getFieldDefinitions()) {
			parts.fields().putIfAbsent(field.getName(), field);
		}
	}

	private static void collect(String typeName, Parts implementing, Parts declared, List<Difference> differences) {
		declared.fields().forEach((fieldName, declaredField) -> {
			FieldDefinition field = implementing.fields().get(fieldName);
			if (field == null) {
				return;
			}
			for (InputValueDefinition declaredArgument : declaredField.getInputValueDefinitions()) {
				for (InputValueDefinition argument : field.getInputValueDefinitions()) {
					if (differsInTheDefaultAlone(argument, declaredArgument)) {
						differences.add(new Difference(typeName, fieldName, argument.getName()));
					}
				}
			}
		});
	}

	// The same argument with the same type and another default, which is the one case
	// graphql-java refuses and the specification allows.
	private static boolean differsInTheDefaultAlone(InputValueDefinition argument,
			InputValueDefinition declaredArgument) {
		return argument.getName().equals(declaredArgument.getName())
				&& print(argument.getType()).equals(print(declaredArgument.getType()))
				&& !print(argument.getDefaultValue()).equals(print(declaredArgument.getDefaultValue()));
	}

	/**
	 * Returns the sentences that follow graphql-java's errors in the message.
	 * @param differences the places found, at least one
	 * @return what graphql-java refused, what the specification says, where the request
	 * to graphql-java is, and how a copy of the schema starts meanwhile
	 */
	static String describe(List<Difference> differences) {
		List<String> places = differences.stream().map(Difference::toString).distinct().toList();
		boolean one = places.size() == 1;
		return "graphql-java refuses " + join(places) + " because "
				+ (one ? "its default differs" : "the default of each differs")
				+ " from the one its interface declares. The GraphQL specification asks an implementing field for "
				+ "the same argument type and leaves the default to each type, so other servers run such a schema, "
				+ "and " + UPSTREAM_ISSUE + " asks graphql-java to build it. Until a release does, a copy of the "
				+ "schema that gives " + (one ? "that argument" : "each of those arguments") + " the default of its "
				+ "interface starts, and GATool then describes that default where the API applies its own.";
	}

	private static String join(List<String> places) {
		if (places.size() == 1) {
			return places.getFirst();
		}
		return String.join(", ", places.subList(0, places.size() - 1)) + " and " + places.getLast();
	}

	private static String print(@Nullable Type<?> type) {
		return (type != null) ? AstPrinter.printAstCompact(type) : "";
	}

	private static String print(@Nullable Value<?> value) {
		return (value != null) ? AstPrinter.printAstCompact(value) : "";
	}

	/**
	 * One argument whose default differs from its interface's.
	 *
	 * @param type the implementing type
	 * @param field the field that carries the argument
	 * @param argument the argument
	 */
	record Difference(String type, String field, String argument) {

		/**
		 * Returns the place in the form a schema coordinate takes, such as
		 * {@code Host.memberOf(limit:)}.
		 */
		@Override
		public String toString() {
			return this.type + "." + this.field + "(" + this.argument + ":)";
		}
	}

	// The interfaces a type implements and the fields it declares, over the type and
	// its extensions.
	private record Parts(Set<String> implemented, Map<String, FieldDefinition> fields) {

		Parts() {
			this(new LinkedHashSet<>(), new LinkedHashMap<>());
		}
	}

}
