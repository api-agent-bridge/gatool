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

import java.util.ArrayList;
import java.util.List;

/**
 * Stops startup when the operation files hold problems, carrying every one of them.
 *
 * <p>
 * All the problems of all the files arrive in one failure, so that one restart shows the
 * whole list. {@link OperationFileProblemsFailureAnalyzer} reports that failure as a
 * description and an action.
 *
 * <p>
 * The problems arrive as finished lines, so this exception and its analyzer stay free of
 * a type that {@code gatool-core} keeps internal.
 *
 * @author Željko Kozina
 */
public final class OperationFileProblemsException extends RuntimeException {

	// A Throwable is serializable, so this class declares both halves of that contract:
	// a version of its own, and a field whose declared type serializes with it. The
	// interface List leaves that open, which javac reports under -Xlint, so the field
	// holds an ArrayList and the accessor hands out an immutable copy.
	private static final long serialVersionUID = 1L;

	/**
	 * Every problem, in the order the files were read.
	 */
	private final ArrayList<String> problems;

	OperationFileProblemsException(List<String> problems) {
		super(message(problems));
		this.problems = new ArrayList<>(problems);
	}

	/**
	 * Returns the problems.
	 * @return every problem, in the order the files were read
	 */
	public List<String> problems() {
		return List.copyOf(this.problems);
	}

	// Builds the exception message: a first line naming GATool, then one indented line
	// per problem.
	//
	// The message carries the whole list as well, because a log that shows the exception
	// without Boot's analysis still has to name every file that needs a change.
	private static String message(List<String> problems) {
		StringBuilder message = new StringBuilder("GATool found these problems in the operation files:");
		for (String problem : problems) {
			message.append(System.lineSeparator()).append("  - ").append(problem);
		}
		return message.toString();
	}

}
