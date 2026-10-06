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
 * The two places a tool can appear.
 *
 * @author Željko Kozina
 */
public enum ToolExposureType {

	/** Tools that the MCP server serves. */
	MCP("MCP"),

	/** Tools that the application's own AI model calls through Spring AI. */
	IN_PROCESS("in-process");

	private final String label;

	ToolExposureType(String label) {
		this.label = label;
	}

	/**
	 * Returns the name of the exposure type as messages write it.
	 * @return {@code MCP} or {@code in-process}
	 */
	public String label() {
		return this.label;
	}

}
