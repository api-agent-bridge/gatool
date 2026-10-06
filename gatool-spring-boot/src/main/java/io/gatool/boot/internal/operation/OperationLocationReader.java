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

package io.gatool.boot.internal.operation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.graphql.support.ResourceDocumentSource;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.ToolExposureType;

/**
 * Finds the operation files under the locations of both exposure types with Spring's
 * {@link ResourcePatternResolver}, and loads each distinct file once.
 *
 * @author Željko Kozina
 */
public final class OperationLocationReader {

	// Spring Framework lacks an optional: prefix for resource locations, so the reader
	// removes it itself, as Spring Boot's script database initializer does.
	private static final String OPTIONAL_PREFIX = "optional:";

	// The reader follows Spring for GraphQL, whose GraphQlClient and GraphQlTester read
	// operation documents from these two extensions. Taking the pair from its public
	// constant keeps GATool reading the files Spring reads, and a change there arrives
	// with the upgrade. That class looks one document up by name through createRelative,
	// so walking a folder stays the work below.
	private static final List<String> EXTENSIONS = ResourceDocumentSource.FILE_EXTENSIONS;

	private final ResourcePatternResolver resolver;

	public OperationLocationReader(ResourcePatternResolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * Reads every location of both exposure types.
	 * @param locationsByToolExposureType the configured locations of each exposure type
	 * @return every distinct operation file, with every problem found
	 */
	public OperationSources read(Map<ToolExposureType, List<String>> locationsByToolExposureType) {
		return read(locationsByToolExposureType, false);
	}

	/**
	 * Reads every location of both exposure types.
	 * @param locationsByToolExposureType the configured locations of each exposure type
	 * @param locationsMayBeEmpty whether a location without an operation file is allowed,
	 * which it is while the tools come from the schema instead
	 * @return every distinct operation file, with every problem found
	 */
	public OperationSources read(Map<ToolExposureType, List<String>> locationsByToolExposureType,
			boolean locationsMayBeEmpty) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		Map<String, FoundFile> files = new LinkedHashMap<>();
		for (Map.Entry<ToolExposureType, List<String>> entry : locationsByToolExposureType.entrySet()) {
			for (String location : entry.getValue()) {
				readLocation(entry.getKey(), location, files, diagnostics, locationsMayBeEmpty);
			}
		}
		List<OperationSource> sources = new ArrayList<>();
		for (FoundFile file : files.values()) {
			OperationSource source = load(file.resource(), file.toolExposureTypes(), diagnostics);
			if (source != null) {
				sources.add(source);
			}
		}
		return new OperationSources(List.copyOf(sources), diagnostics.problems());
	}

	// Finds every readable operation file under one location and records each file once
	// per exposure type that reaches it. A location that names a single file, that cannot
	// be read, or that is required and holds zero operation files, becomes a problem.
	private void readLocation(ToolExposureType toolExposureType, String location, Map<String, FoundFile> files,
			OperationDiagnostics diagnostics, boolean locationsMayBeEmpty) {
		boolean optional = location.startsWith(OPTIONAL_PREFIX);
		String path = optional ? location.substring(OPTIONAL_PREFIX.length()) : location;
		if (EXTENSIONS.stream().anyMatch(path::endsWith)) {
			int folderEnd = Math.max(path.lastIndexOf('/'), path.indexOf(':')) + 1;
			String folder = (optional ? OPTIONAL_PREFIX : "") + path.substring(0, folderEnd);
			diagnostics.problem(location, "names a single file, and a location names a folder; use " + folder);
			return;
		}
		String folder = path.endsWith("/") ? path : path + "/";
		int fileCount;
		try {
			fileCount = readFolder(folder, toolExposureType, files);
		}
		catch (IOException ex) {
			diagnostics.problem(location, "cannot be read: " + ex.getMessage());
			return;
		}
		if (fileCount == 0 && !optional && !locationsMayBeEmpty) {
			diagnostics.problem(location, "holds zero .graphql or .gql files; add an operation file, "
					+ "or start the location with optional: when the folder may stay empty");
		}
	}

	// Reads every operation file under the folder into the map and returns how many
	// readable files it met; a file reached under two exposure types is one entry.
	private int readFolder(String folder, ToolExposureType toolExposureType, Map<String, FoundFile> files)
			throws IOException {
		int fileCount = 0;
		for (String extension : EXTENSIONS) {
			for (Resource resource : this.resolver.getResources(folder + "**/*" + extension)) {
				if (resource.isReadable()) {
					String identity = identityOf(resource);
					files
						.computeIfAbsent(identity,
								(key) -> new FoundFile(resource, EnumSet.noneOf(ToolExposureType.class)))
						.toolExposureTypes()
						.add(toolExposureType);
					fileCount++;
				}
			}
		}
		return fileCount;
	}

	// Returns the key that counts one resource once: the real path of a file on disk, or
	// the URL of anything else.
	//
	// A file on disk counts once however many locations reach it, even through a
	// symbolic link, and a file inside a jar counts once for its URL.
	private static String identityOf(Resource resource) throws IOException {
		if (resource.isFile()) {
			return resource.getFile().toPath().toRealPath().toString();
		}
		return resource.getURL().toExternalForm();
	}

	private static @Nullable OperationSource load(Resource resource, Set<ToolExposureType> toolExposureTypes,
			OperationDiagnostics diagnostics) {
		String location = locationOf(resource);
		try {
			return new OperationSource(location, resource.getContentAsString(StandardCharsets.UTF_8),
					toolExposureTypes);
		}
		catch (IOException ex) {
			diagnostics.problem(location, "cannot be read: " + ex.getMessage());
			return null;
		}
	}

	private static String locationOf(Resource resource) {
		try {
			return resource.getURL().toExternalForm();
		}
		catch (IOException ex) {
			return resource.getDescription();
		}
	}

	private record FoundFile(Resource resource, Set<ToolExposureType> toolExposureTypes) {
	}

}
