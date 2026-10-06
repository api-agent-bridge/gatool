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

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;

import io.gatool.fixtures.movies.MoviesSchema;

final class TestSchemas {

	// graphql-java's mocked wiring supplies scalars and type resolvers, which validation
	// needs, without an implementation behind the schema.
	private static final GraphQLSchema MOVIES = SchemaGenerator.createdMockedSchema(MoviesSchema.sdl());

	private TestSchemas() {
	}

	static GraphQLSchema movies() {
		return MOVIES;
	}

}
