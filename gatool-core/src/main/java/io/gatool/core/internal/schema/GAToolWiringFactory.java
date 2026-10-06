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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import graphql.language.ScalarTypeDefinition;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;
import org.jspecify.annotations.Nullable;

/**
 * Supplies a scalar for every custom scalar a schema declares, and a type resolver for
 * every interface and union, so that a schema from an API GATool does not own builds and
 * coerces values.
 *
 * <p>
 * There are three ways to wire such a schema, and GATool takes the last:
 *
 * <ul>
 * <li>{@code SchemaGenerator.createdMockedSchema(String)} with
 * {@code RuntimeWiring.MOCKED_WIRING} costs one line and refuses to coerce. It throws
 * "Not implemented...this is only a mocked wiring" for a {@code DateTime} variable, and
 * that failure would move from startup to the first tool call. Its own Javadoc names
 * testing as its purpose.</li>
 * <li>graphql-java's {@code MockedWiringFactory} for type resolvers, with scalars of
 * GATool's own, keeps the same refusal for values a mocked scalar serializes.</li>
 * <li>This factory, which GATool owns and follows graphql-java with when the wiring API
 * changes.</li>
 * </ul>
 *
 * <p>
 * The scalars pass values through unchanged. A remote schema declares a scalar by name,
 * and the name leaves its coercion rules undescribed. Resolving each name against the
 * graphql-java-extended-scalars constants would reject values the API accepts:
 * {@code ExtendedScalars.DateTime} rejects {@code 2026-09-15T18:30:00-00:00}, which RFC
 * 3339 permits, and GATool forwards calls to an API it does not own, so such a rejection
 * becomes a tool error a user cannot repair. The API's own coercion rejects a wrong value
 * with a request error the model reads.
 *
 * <p>
 * Each scalar is built without a description, so
 * {@code SchemaGeneratorHelper.getScalarDesc} falls through to the schema's own
 * description, which reaches the model in the tool's input schema. graphql-java attaches
 * the {@code @specifiedBy} URL from the schema while it builds the type.
 *
 * @author Željko Kozina
 */
public final class GAToolWiringFactory implements WiringFactory {

	@Override
	public boolean providesScalar(ScalarWiringEnvironment environment) {
		return !ScalarInfo.isGraphqlSpecifiedScalar(environment.getScalarTypeDefinition().getName());
	}

	@Override
	public GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
		ScalarTypeDefinition definition = environment.getScalarTypeDefinition();
		return GraphQLScalarType.newScalar()
			.name(definition.getName())
			.definition(definition)
			.coercing(new PassThroughCoercing())
			.build();
	}

	@Override
	public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
		return true;
	}

	@Override
	public TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
		return remoteTypeResolver(environment.getInterfaceTypeDefinition().getName());
	}

	@Override
	public boolean providesTypeResolver(UnionWiringEnvironment environment) {
		return true;
	}

	@Override
	public TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
		return remoteTypeResolver(environment.getUnionTypeDefinition().getName());
	}

	// Returns a type resolver that throws UnsupportedOperationException naming the
	// interface or union it was asked to resolve.
	//
	// The GraphQL API resolves the concrete type, because GATool sends the document to
	// it. This resolver exists so that makeExecutableSchema accepts a schema whose
	// interfaces and unions arrive without implementations.
	private static TypeResolver remoteTypeResolver(String typeName) {
		return (environment) -> {
			throw new UnsupportedOperationException("The GraphQL API resolves the concrete type of " + typeName
					+ ", because GATool sends the document to the API");
		};
	}

	/**
	 * Hands every value to the GraphQL API as it arrived, so that the API's own coercion
	 * decides whether the value is valid.
	 */
	static final class PassThroughCoercing implements Coercing<Object, Object> {

		@Override
		public @Nullable Object serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
			return dataFetcherResult;
		}

		@Override
		public @Nullable Object parseValue(Object input, GraphQLContext context, Locale locale) {
			return input;
		}

		@Override
		public @Nullable Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
				Locale locale) {
			return javaValue(input, variables);
		}

		private static @Nullable Object javaValue(Value<?> input, CoercedVariables variables) {
			if (input instanceof NullValue) {
				return null;
			}
			if (input instanceof StringValue value) {
				return value.getValue();
			}
			if (input instanceof IntValue value) {
				return value.getValue();
			}
			if (input instanceof FloatValue value) {
				return value.getValue();
			}
			if (input instanceof BooleanValue value) {
				return value.isValue();
			}
			if (input instanceof EnumValue value) {
				return value.getName();
			}
			if (input instanceof VariableReference value) {
				return variables.get(value.getName());
			}
			if (input instanceof ArrayValue value) {
				List<Object> values = new ArrayList<>();
				for (Value<?> element : value.getValues()) {
					values.add(javaValue(element, variables));
				}
				return values;
			}
			if (input instanceof ObjectValue value) {
				Map<String, Object> fields = new LinkedHashMap<>();
				value.getObjectFields()
					.forEach((field) -> fields.put(field.getName(), javaValue(field.getValue(), variables)));
				return fields;
			}
			return input;
		}

	}

}
