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

import java.util.List;
import java.util.Objects;

import graphql.language.Document;
import graphql.language.OperationDefinition;

/**
 * A file that holds one query or mutation and the fragments it uses.
 *
 * <p>
 * The document and the operation arrive without {@code @gatool}, which
 * {@link ToolDirective} takes out in the parser, because a GraphQL API answers that
 * directive with {@code UnknownDirective}. What the directive said travels beside them
 * instead.
 *
 * @param source the file
 * @param document the parsed file, with its comments, and with the shared fragments the
 * operation spreads appended once {@link SharedFragments} has attached them
 * @param operation the operation in the file
 * @param directive what {@code @gatool} set, which is empty where the file leaves the
 * directive out
 * @param sharedFragmentFiles the shared fragment files whose fragments the document
 * borrows, in the order they were appended, empty where every fragment is the file's own
 * @author Željko Kozina
 */
public record OperationFile(OperationSource source, Document document, OperationDefinition operation,
		ToolDirective directive, List<String> sharedFragmentFiles) implements ParsedFile {

	public OperationFile {
		sharedFragmentFiles = List.copyOf(sharedFragmentFiles);
	}

	/**
	 * A file as the parser reads it, before any shared fragment joins it.
	 * @param source the file
	 * @param document the parsed file, with its comments
	 * @param operation the operation in the file
	 * @param directive what {@code @gatool} set, which is empty where the file leaves the
	 * directive out
	 */
	public OperationFile(OperationSource source, Document document, OperationDefinition operation,
			ToolDirective directive) {
		this(source, document, operation, directive, List.of());
	}

	public String operationName() {
		return Objects.requireNonNull(this.operation.getName(),
				"the parser names an anonymous operation after its file");
	}

	public OperationType operationType() {
		return (this.operation.getOperation() == OperationDefinition.Operation.MUTATION) ? OperationType.MUTATION
				: OperationType.QUERY;
	}
}
