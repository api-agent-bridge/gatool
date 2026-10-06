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

package io.gatool.boot.inprocess.autoconfigure;

/**
 * Stops startup when an in-process tool name is declared twice, carrying what to change
 * for the failure analysis: a {@code ToolCallback} bean of the application that carries
 * GATool's own tools, or two application beans that share a name.
 *
 * <p>
 * A clash stops startup, because the alternative is a {@code ChatClient} that answers
 * from one of two tools without saying which.
 * {@link InProcessToolNameClashFailureAnalyzer} reports this failure as a description and
 * an action, where Boot's other reports appear.
 *
 * @author Željko Kozina
 */
public final class InProcessToolNameClashException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * What to change: the code to write, or the bean to rename.
	 */
	private final String action;

	InProcessToolNameClashException(String message, String action) {
		super(message);
		this.action = action;
	}

	/**
	 * Returns what to change.
	 * @return the code to write, or the bean to rename
	 */
	public String action() {
		return this.action;
	}

}
