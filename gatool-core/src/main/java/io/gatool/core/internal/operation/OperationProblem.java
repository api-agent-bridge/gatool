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
 * A problem that stops startup.
 *
 * @param location the operation file or the configured location that has the problem
 * @param message what is wrong and what to do, written to follow the location
 * @author Željko Kozina
 */
public record OperationProblem(String location, String message) {

	@Override
	public String toString() {
		return this.location + " " + this.message;
	}
}
