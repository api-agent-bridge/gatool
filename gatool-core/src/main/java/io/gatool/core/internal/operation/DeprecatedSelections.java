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

import java.util.LinkedHashSet;
import java.util.Set;

import graphql.analysis.QueryTraversalOptions;
import graphql.analysis.QueryTraverser;
import graphql.analysis.QueryVisitorFieldArgumentEnvironment;
import graphql.analysis.QueryVisitorFieldArgumentInputValue;
import graphql.analysis.QueryVisitorFieldArgumentValueEnvironment;
import graphql.analysis.QueryVisitorFieldEnvironment;
import graphql.analysis.QueryVisitorStub;
import graphql.execution.CoercedVariables;
import graphql.execution.TypeFromAST;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.AstTransformer;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumValue;
import graphql.language.Node;
import graphql.language.NodeVisitorStub;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInputValueDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;
import org.jspecify.annotations.Nullable;

/**
 * Warns when a written operation selects a field, an argument, an enum value or an input
 * field the schema marks deprecated.
 *
 * @author Željko Kozina
 */
// The operation keeps working, and this warning is the one place the team hears
// that the API has announced a removal, before the day the field goes and the file
// fails validation at startup. A generated operation handles deprecation itself in
// RootFieldOperations, which is why the caller skips it here. The walk leaves
// variables uncoerced, because their values are the model's to send at call time
// and nothing here reads them. An enum value or input field that arrives through a
// variable is therefore outside this check, and the literals the file writes are in:
// the ones in a field argument, in the default of a variable and in the argument of
// a directive.
final class DeprecatedSelections {

	private final GraphQLSchema schema;

	DeprecatedSelections(GraphQLSchema schema) {
		this.schema = schema;
	}

	/**
	 * Adds one warning naming every deprecated selection of the operation, or one saying
	 * the walk failed, to the diagnostics.
	 * @param operation the validated operation whose selections are checked
	 * @param diagnostics receives the warning
	 */
	void warnAbout(ValidatedOperation operation, OperationDiagnostics diagnostics) {
		// A set, because a field selected in two fragments earns one mention.
		Set<String> deprecatedSelections = new LinkedHashSet<>();
		try {
			visitDeprecatedSelections(operation, deprecatedSelections);
		}
		catch (RuntimeException ex) {
			// The check is advisory, and the tool runs without it, so a walk that fails
			// on a shape this code has not met says so and leaves the tool alone.
			diagnostics.warning(operation.location(), "could not be checked for deprecated fields: " + ex);
			return;
		}
		if (!deprecatedSelections.isEmpty()) {
			diagnostics.warning(operation.location(),
					"selects " + String.join(" and ", deprecatedSelections)
							+ ", which the schema marks deprecated. The tool works until the API removes it, and this "
							+ "file then fails validation at startup.");
		}
	}

	// The traversal evaluates @skip and @include from the variables, which are the
	// model's to send at call time, so a copy of the document without those two
	// directives is walked. Every field the file selects is one the model may receive,
	// and each is worth the warning. Walked as written, `$withTitle: Boolean! = false`
	// under `@include` throws a NullPointerException from the traverser's conditional
	// check, because an empty set of coerced variables skips the default as well.
	private void visitDeprecatedSelections(ValidatedOperation operation, Set<String> deprecatedSelections) {
		QueryTraverser.newQueryTraverser()
			.schema(this.schema)
			.document(withoutConditionalDirectives(operation.file().document()))
			.operationName(operation.file().operationName())
			.coercedVariables(CoercedVariables.emptyVariables())
			.options(QueryTraversalOptions.defaultOptions().coerceFieldArguments(false))
			.build()
			.visitPreOrder(new QueryVisitorStub() {

				@Override
				public void visitField(QueryVisitorFieldEnvironment environment) {
					GraphQLFieldDefinition field = environment.getFieldDefinition();
					if (field.isDeprecated()) {
						// The parent arrives as the enclosing field declares it, list and
						// Non-Null wrappers included, and the coordinate wants the name
						// alone.
						String parentTypeName = GraphQLTypeUtil.unwrapAll(environment.getParentType()).getName();
						deprecatedSelections.add(describeDeprecation(
								"the field " + parentTypeName + "." + field.getName(), field.getDeprecationReason()));
					}
				}

				@Override
				public TraversalControl visitArgument(QueryVisitorFieldArgumentEnvironment environment) {
					GraphQLArgument argument = environment.getGraphQLArgument();
					if (argument.isDeprecated()) {
						deprecatedSelections.add(describeDeprecation("the argument " + argument.getName() + " of "
								+ environment.getFieldDefinition().getName(), argument.getDeprecationReason()));
					}
					return TraversalControl.CONTINUE;
				}

				// The traverser visits every literal under an argument, input object
				// fields included, and hands over the definition the literal fills: the
				// argument itself, or the input field, whose deprecation is read here. An
				// enum literal is looked up on its type, because the deprecation sits on
				// the value, and a literal the schema lacks fails validation earlier.
				@Override
				public TraversalControl visitArgumentValue(QueryVisitorFieldArgumentValueEnvironment environment) {
					QueryVisitorFieldArgumentInputValue inputValue = environment.getArgumentInputValue();
					GraphQLInputValueDefinition definition = inputValue.getInputValueDefinition();
					if (definition instanceof GraphQLInputObjectField inputField && inputField.isDeprecated()) {
						deprecatedSelections.add(describeDeprecatedInputField(inputField,
								GraphQLTypeUtil.unwrapAll(inputValue.getParent().getInputType()).getName()));
					}
					if (inputValue.getValue() instanceof EnumValue literal && GraphQLTypeUtil
						.unwrapAll(inputValue.getInputType()) instanceof GraphQLEnumType enumType) {
						GraphQLEnumValueDefinition enumValue = enumType.getValue(literal.getName());
						if (enumValue != null && enumValue.isDeprecated()) {
							deprecatedSelections.add(describeDeprecatedEnumValue(enumType, enumValue));
						}
					}
					return TraversalControl.CONTINUE;
				}
			});
		visitLiteralsOutsideFieldArguments(operation.file().document(), deprecatedSelections);
	}

	// Adds what the file writes outside its field arguments: the default of each
	// variable, and the arguments of each directive, on the operation, a variable, a
	// field or a fragment.
	//
	// The traverser above visits field arguments alone, so a deprecated enum value in
	// `$status: Status = OLD` or in the argument of a directive the schema declares needs
	// a walk of its own. This walk reads the syntax tree with the schema's own types: a
	// variable's type by the name the file writes, and a directive's arguments from the
	// directive the schema declares.
	//
	// The alternative is a validation rule reading graphql-java's ValidationContext,
	// which is how graphql-js writes NoDeprecatedCustomRule. graphql-java marks
	// ValidationContext, TraversalContext and its rule classes internal, and version 26
	// removes AbstractRule and RulesVisitor, so a rule written against 25.0 stops
	// compiling at that upgrade. The syntax tree and the schema types are public API.
	// TypeFromAST is the one internal class this walk calls. The null rules further down
	// already call it, and 26.1 ships it with the same signature.
	//
	// The whole document is walked, because validation refuses a fragment the operation
	// leaves unspread, so every node here belongs to the tool. The conditional
	// directives stay in for this walk, since it reads literals and leaves the variables
	// alone.
	private void visitLiteralsOutsideFieldArguments(Node<?> node, Set<String> deprecatedSelections) {
		if (node instanceof VariableDefinition variable) {
			visitVariableDefault(variable, deprecatedSelections);
		}
		if (node instanceof Directive directive) {
			visitDirectiveArguments(directive, deprecatedSelections);
		}
		for (Node<?> child : node.getChildren()) {
			visitLiteralsOutsideFieldArguments(child, deprecatedSelections);
		}
	}

	private void visitVariableDefault(VariableDefinition variable, Set<String> deprecatedSelections) {
		Value<?> defaultValue = variable.getDefaultValue();
		if (defaultValue != null
				&& TypeFromAST.getTypeFromAST(this.schema, variable.getType()) instanceof GraphQLInputType type) {
			collectDeprecatedLiterals(type, defaultValue, deprecatedSelections);
		}
	}

	private void visitDirectiveArguments(Directive directive, Set<String> deprecatedSelections) {
		// A directive the schema lacks fails validation earlier, and @gatool left the
		// document in the parser.
		GraphQLDirective declared = this.schema.getDirective(directive.getName());
		for (Argument written : directive.getArguments()) {
			GraphQLArgument argument = (declared != null) ? declared.getArgument(written.getName()) : null;
			if (argument == null) {
				continue;
			}
			if (argument.isDeprecated()) {
				deprecatedSelections
					.add(describeDeprecation("the argument " + argument.getName() + " of @" + directive.getName(),
							argument.getDeprecationReason()));
			}
			collectDeprecatedLiterals(argument.getType(), written.getValue(), deprecatedSelections);
		}
	}

	// Follows the literal and reads each part against the type it fills, the way the
	// traverser does for a field argument, so both name a deprecation in the same words
	// and the set keeps one mention of it.
	private static void collectDeprecatedLiterals(GraphQLType type, Value<?> value, Set<String> deprecatedSelections) {
		GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(type);
		if (unwrapped instanceof GraphQLList list) {
			collectDeprecatedListItems(list, value, deprecatedSelections);
		}
		else if (value instanceof ObjectValue object && unwrapped instanceof GraphQLInputObjectType inputObject) {
			collectDeprecatedInputFields(inputObject, object, deprecatedSelections);
		}
		else if (value instanceof EnumValue literal && unwrapped instanceof GraphQLEnumType enumType) {
			GraphQLEnumValueDefinition enumValue = enumType.getValue(literal.getName());
			if (enumValue != null && enumValue.isDeprecated()) {
				deprecatedSelections.add(describeDeprecatedEnumValue(enumType, enumValue));
			}
		}
	}

	// GraphQL takes one value where a list is expected, so a literal written without
	// brackets is read against the item type as well.
	private static void collectDeprecatedListItems(GraphQLList list, Value<?> value, Set<String> deprecatedSelections) {
		if (value instanceof ArrayValue array) {
			for (Value<?> item : array.getValues()) {
				collectDeprecatedLiterals(list.getWrappedType(), item, deprecatedSelections);
			}
		}
		else {
			collectDeprecatedLiterals(list.getWrappedType(), value, deprecatedSelections);
		}
	}

	private static void collectDeprecatedInputFields(GraphQLInputObjectType inputObject, ObjectValue object,
			Set<String> deprecatedSelections) {
		for (ObjectField written : object.getObjectFields()) {
			GraphQLInputObjectField field = inputObject.getField(written.getName());
			if (field == null) {
				continue;
			}
			if (field.isDeprecated()) {
				deprecatedSelections.add(describeDeprecatedInputField(field, inputObject.getName()));
			}
			collectDeprecatedLiterals(field.getType(), written.getValue(), deprecatedSelections);
		}
	}

	private static String describeDeprecatedInputField(GraphQLInputObjectField field, String typeName) {
		return describeDeprecation("the input field " + field.getName() + " of " + typeName,
				field.getDeprecationReason());
	}

	private static String describeDeprecatedEnumValue(GraphQLEnumType enumType, GraphQLEnumValueDefinition value) {
		return describeDeprecation("the value " + value.getName() + " of " + enumType.getName(),
				value.getDeprecationReason());
	}

	private static Document withoutConditionalDirectives(Document document) {
		return (Document) new AstTransformer().transform(document, new NodeVisitorStub() {

			// graphql-java declares the visitor's context with a raw Node, so overriding
			// it faithfully means repeating the raw type under -Werror.
			@Override
			@SuppressWarnings("rawtypes")
			public TraversalControl visitDirective(Directive node, TraverserContext<Node> context) {
				if ("skip".equals(node.getName()) || "include".equals(node.getName())) {
					return TreeTransformerUtil.deleteNode(context);
				}
				return TraversalControl.CONTINUE;
			}
		});
	}

	private static String describeDeprecation(String subject, @Nullable String reason) {
		return (reason == null || reason.isBlank()) ? subject : subject + " (" + reason.strip() + ")";
	}

}
