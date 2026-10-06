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

package io.gatool.boot.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports every operation file problem as a description and an action, which is what
 * GATool gives each startup stop.
 *
 * <p>
 * Boot analyses its own {@code InvalidConfigurationPropertyValueException}, so a stop
 * about one property value already arrives with the property name and the value. This
 * failure names several files at once, which is why the starter analyses it itself.
 *
 * <p>
 * Boot loads this class from {@code META-INF/spring.factories} before the application
 * context exists, so it reads the exception alone and leaves beans to the
 * auto-configuration.
 *
 * @author Željko Kozina
 */
class OperationFileProblemsFailureAnalyzer extends AbstractFailureAnalyzer<OperationFileProblemsException> {

	/**
	 * Creates the analyzer, which Spring Boot instantiates from
	 * {@code META-INF/spring.factories}.
	 */
	OperationFileProblemsFailureAnalyzer() {
	}

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, OperationFileProblemsException cause) {
		StringBuilder description = new StringBuilder("GATool read the operation files and found ")
			.append(cause.problems().size())
			.append((cause.problems().size() != 1) ? " problems:" : " problem:");
		for (String problem : cause.problems()) {
			description.append(System.lineSeparator()).append("    ").append(problem);
		}
		String action = "Correct each file above, or move it out of the folders that "
				+ "gatool.mcp.operations.locations and gatool.in-process.operations.locations name. Every file is read at "
				+ "startup, so the whole list appears again on the next run.";
		return new FailureAnalysis(description.toString(), action, cause);
	}

}
