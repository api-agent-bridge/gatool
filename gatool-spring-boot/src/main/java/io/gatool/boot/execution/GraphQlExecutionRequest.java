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

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One call to the GraphQL API.
 *
 * <p>
 * The document, the operation name and the variables travel together, so
 * {@link GraphQlExecutor} takes one argument. The credential is added by the interceptor
 * on GATool's own {@code RestClient}, so it stays out of this record. A component that a
 * later release adds joins here, and the constructor of this release compiles for at
 * least one minor release.
 *
 * @param document the printed document that startup validated
 * @param operationName the name of the operation in the document
 * @param variables the variables, with GATool's null rules already applied to the tool
 * arguments
 * @author Željko Kozina
 */
public record GraphQlExecutionRequest(String document, String operationName, Map<String, @Nullable Object> variables) {
}
