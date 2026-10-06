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
 * Something that startup logs as a warning.
 *
 * @param location where the warning applies, which is an operation file, or
 * {@link #THE_SCHEMA} for a warning about the schema that every file shares
 * @param message what to improve, written to follow the location
 * @author Željko Kozina
 */
public record OperationWarning(String location, String message) {

	/**
	 * The location of a warning about the schema every file shares, such as the warning
	 * about unmapped custom scalars.
	 */
	public static final String THE_SCHEMA = "The schema";

	@Override
	public String toString() {
		return this.location + " " + this.message;
	}
}
