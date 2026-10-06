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

import java.util.HashMap;
import java.util.Map;

import graphql.language.AstPrinter;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.OperationDefinition;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLTypeUtil;
import graphql.validation.AbstractRule;
import graphql.validation.ValidationContext;
import graphql.validation.ValidationErrorCollector;
import graphql.validation.ValidationErrorType;

/**
 * The two OneOf rules the GraphQL specification states and graphql-java 25.0 leaves out:
 * the one field of a OneOf Input Object literal holds a value other than the null
 * literal, and a variable that supplies it counts as Non-Null.
 *
 * <p>
 * A OneOf Input Object takes exactly one field, and that field always holds a value.
 * graphql-java refuses zero fields and two fields, and accepts the null literal and a
 * nullable variable, which a conforming API then refuses on every call. The
 * specification's variable rule treats the field as a Non-Null position: a nullable
 * variable is allowed there when its definition carries a default other than null, and
 * refused otherwise. The check runs here beside graphql-java's rules, so a document fails
 * at startup for the same reason a conforming API would refuse it.
 *
 * <p>
 * The default-value exception is kept where the OneOf object fills a Non-Null argument or
 * input field, and dropped where it fills a nullable one. That distinction is the one
 * graphql-js 17 makes: its {@code VariablesInAllowedPositionRule} (read at tag v17.0.2)
 * refuses a nullable variable in a OneOf field when the enclosing position is the bare
 * object type, default or not, and skips the check when the position is Non-Null. Under
 * that rule, {@code movie(by: Lookup)} refuses {@code $id: ID = "1"}, and
 * {@code movie(by: Lookup!)} takes it. graphql-js 16 (read at tag v16.11.0) refuses the
 * nullable variable in both positions, through a second check in
 * {@code ValuesOfCorrectTypeRule} that 17 dropped.
 *
 * <p>
 * The September 2025 edition of the specification, section 5.8.5, allows the default in
 * both positions: its {@code IsVariableUsageAllowed} counts a OneOf field as a Non-Null
 * position and then lets a nullable variable fill it when
 * {@code hasNonNullVariableDefaultValue} holds. GATool refuses the document the
 * specification accepts in the nullable position, siding with graphql-js by decision,
 * because a document GATool accepts is then one the most common server accepts too, and
 * because a default fills a variable the caller leaves out while a caller can still send
 * null for it.
 *
 * @author Željko Kozina
 */
final class OneOfFieldValueRule extends AbstractRule {

	private static final String OF_THE_ONE_OF_INPUT_OBJECT = "' of the OneOf input object '";

	private final Map<String, VariableDefinition> variables = new HashMap<>();

	OneOfFieldValueRule(ValidationContext context, ValidationErrorCollector collector) {
		super(context, collector);
	}

	// A fragment is checked where it is spread, inside the operation whose variables
	// apply to it, which is how graphql-java's own variable rules read a fragment.
	@Override
	public boolean isVisitFragmentSpreads() {
		return true;
	}

	@Override
	public void checkOperationDefinition(OperationDefinition operationDefinition) {
		this.variables.clear();
		for (VariableDefinition definition : operationDefinition.getVariableDefinitions()) {
			this.variables.put(definition.getName(), definition);
		}
	}

	// The specification's IsVariableUsageAllowed lets a nullable variable fill a
	// Non-Null position when its definition holds a default other than null.
	private static boolean hasNonNullDefault(VariableDefinition definition) {
		return definition.getDefaultValue() != null && !(definition.getDefaultValue() instanceof NullValue);
	}

	@Override
	public void checkObjectValue(ObjectValue objectValue) {
		GraphQLInputType type = getValidationContext().getInputType();
		if (type == null || !(GraphQLTypeUtil.unwrapAll(type) instanceof GraphQLInputObjectType inputObject)
				|| !inputObject.isOneOf()) {
			return;
		}
		// The input type here is the one declared at the literal's position: the
		// argument's own type, the input field's, or the element type of a list, each
		// with its Non-Null wrapper where the schema wrote one. So the wrapper says
		// whether the OneOf object was reached through a nullable position.
		boolean nullablePosition = !(type instanceof GraphQLNonNull);
		for (ObjectField field : objectValue.getObjectFields()) {
			if (field.getValue() instanceof NullValue) {
				addError(ValidationErrorType.WrongType, field.getSourceLocation(),
						"The field '" + field.getName() + OF_THE_ONE_OF_INPUT_OBJECT + inputObject.getName()
								+ "' is null, and the one field of a OneOf input object holds a value");
			}
			if (field.getValue() instanceof VariableReference reference) {
				VariableDefinition definition = this.variables.get(reference.getName());
				if (definition == null || definition.getType() instanceof NonNullType) {
					continue;
				}
				if (nullablePosition) {
					addError(ValidationErrorType.WrongType, field.getSourceLocation(),
							"Variable '$" + reference.getName() + "' supplies the field '" + field.getName()
									+ OF_THE_ONE_OF_INPUT_OBJECT + inputObject.getName()
									+ "' through a nullable argument or input field, so the operation declares it "
									+ AstPrinter.printAstCompact(definition.getType()) + "!; a default does not "
									+ "stand in for that, because a caller can still send null, and graphql-js "
									+ "refuses the document");
				}
				else if (!hasNonNullDefault(definition)) {
					addError(ValidationErrorType.WrongType, field.getSourceLocation(),
							"Variable '$" + reference.getName() + "' supplies the field '" + field.getName()
									+ OF_THE_ONE_OF_INPUT_OBJECT + inputObject.getName()
									+ "', so the operation declares it Non-Null, or gives it a default");
				}
			}
		}
	}

}
