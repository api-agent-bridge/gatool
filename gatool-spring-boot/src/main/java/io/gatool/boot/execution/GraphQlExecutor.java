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

package io.gatool.boot.execution;

import org.springframework.graphql.GraphQlResponse;

/**
 * Runs one validated document with its variables and returns the GraphQL response.
 *
 * <p>
 * The remote executor implements this, and so does the embedded executor. An application
 * replaces both by declaring a bean of this type.
 *
 * <p>
 * This interface sits in {@code gatool-spring-boot}, because both executors return Spring
 * for GraphQL's {@link GraphQlResponse} and {@code gatool-core} bans
 * {@code org.springframework} with {@code searchTransitive}. A response type of GATool's
 * own would cost a second model and lose {@code ResponseError.getParsedPath()}.
 *
 * @author Željko Kozina
 */
@FunctionalInterface
public interface GraphQlExecutor {

	/**
	 * Runs one document.
	 * @param request the document, the operation name and the variables
	 * @return the GraphQL response, which may hold data, errors or both
	 */
	GraphQlResponse execute(GraphQlExecutionRequest request);

}
