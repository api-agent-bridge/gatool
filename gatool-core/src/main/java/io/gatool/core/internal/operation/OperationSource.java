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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One operation file: where it came from, what it holds, and which exposure types publish
 * it as a tool.
 *
 * <p>
 * A file carries its exposure types because GATool reads two location properties,
 * {@code gatool.mcp.operations.locations} and
 * {@code gatool.in-process.operations.locations}, and one file can sit under both. The
 * reader keeps one source per file and records which properties found it, so a file
 * shared by both is parsed, validated and turned into a tool once.
 *
 * @param location the URL of the file, which every message about the file names
 * @param content the content of the file
 * @param toolExposureTypes the exposure types that turn the file into a tool, at least
 * one
 * @author Željko Kozina
 */
public record OperationSource(String location, String content, Set<ToolExposureType> toolExposureTypes) {

	public OperationSource {
		if (toolExposureTypes.isEmpty()) {
			throw new IllegalArgumentException(location + " is not published by any exposure type");
		}
		toolExposureTypes = Collections.unmodifiableSet(EnumSet.copyOf(toolExposureTypes));
	}

	/**
	 * Returns this source published by the given exposure types.
	 *
	 * <p>
	 * The generator writes an operation before anything knows which exposure types lack a
	 * tool for that root field, so the caller that does know puts them on afterwards.
	 * @param toolExposureTypes the exposure types that publish the file
	 * @return a copy carrying them
	 */
	public OperationSource withToolExposureTypes(Set<ToolExposureType> toolExposureTypes) {
		return new OperationSource(this.location, this.content, toolExposureTypes);
	}
}
