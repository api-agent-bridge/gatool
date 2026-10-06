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

/**
 * An operation file that passed validation, with the document the executor sends.
 *
 * @param file the parsed operation file
 * @param printedDocument the printed document, which passed validation a second time
 * @author Željko Kozina
 */
public record ValidatedOperation(OperationFile file, String printedDocument) {

	public String location() {
		return this.file.source().location();
	}

	public String operationName() {
		return this.file.operationName();
	}

	public OperationType operationType() {
		return this.file.operationType();
	}
}
