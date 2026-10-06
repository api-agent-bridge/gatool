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

package io.gatool.boot.test;

import java.nio.file.Path;
import java.util.List;

import io.gatool.core.check.CheckResult;
import io.gatool.core.check.CheckSettings;
import io.gatool.core.check.OperationFileCheck;

/**
 * Asserts that an application's operation files pass the checks startup runs.
 *
 * <p>
 * A test that calls {@link #assertValid(Path, List, List, CheckSettings)} makes a broken
 * operation file fail the build that changed it, in the same words startup would use, and
 * it runs without an issuer, a running application or a reachable schema URL. The result
 * comes back on success, so a test can go on to assert on the warnings or on the tools
 * the files produce.
 *
 * <p>
 * The check itself is {@link OperationFileCheck} in the core module, which a test can
 * call directly. This class adds the assertion and the message. The settings carry the
 * naming strategy and the choices behind {@code gatool.results.publish-output-schema},
 * {@code gatool.inputs.scalar-schemas} and {@code gatool.results.scalar-schemas}, so the
 * check reads the schema the application publishes. A listed folder that is missing fails
 * the assertion, because startup stops on a required location that is empty.
 *
 * @author Željko Kozina
 */
public final class OperationFilesAssert {

	private OperationFilesAssert() {
	}

	/**
	 * Asserts that the files pass validation under the settings the application uses.
	 * @param schemaFile the SDL file the operations are checked against
	 * @param mcpFolders the folders that hold the MCP operation files
	 * @param inProcessFolders the folders that hold the in-process operation files
	 * @param settings the naming strategy, the output schema switch and the scalar
	 * fragments the application configures for arguments and for results, so the check
	 * reports the input schema and the warnings the application publishes
	 * @return the result, for further assertions on its warnings or tools
	 * @throws AssertionError if any file would stop startup, naming every problem
	 */
	public static CheckResult assertValid(Path schemaFile, List<Path> mcpFolders, List<Path> inProcessFolders,
			CheckSettings settings) {
		return assertValid(OperationFileCheck.check(schemaFile, mcpFolders, inProcessFolders, settings));
	}

	/**
	 * Asserts that a check passed.
	 * @param result the result of an {@link OperationFileCheck} call
	 * @return the same result, for chaining
	 * @throws AssertionError if the result holds problems, naming every one
	 */
	public static CheckResult assertValid(CheckResult result) {
		if (result.passes()) {
			return result;
		}
		int count = result.problems().size();
		StringBuilder message = new StringBuilder("The operation files hold ").append(count)
			.append((count == 1) ? " problem" : " problems")
			.append(" that would stop startup:");
		for (String problem : result.problems()) {
			message.append(System.lineSeparator()).append("  - ").append(problem);
		}
		throw new AssertionError(message.toString());
	}

}
