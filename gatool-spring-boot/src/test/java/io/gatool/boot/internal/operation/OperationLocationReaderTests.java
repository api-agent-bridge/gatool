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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.gatool.core.internal.operation.ToolExposureType;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLocationReaderTests {

	private static final String TOP_RATED_MOVIES = """
			query TopRatedMovies {
			  topRatedMovies { title }
			}
			""";

	@TempDir
	Path tempDir;

	private final OperationLocationReader reader = new OperationLocationReader(
			new PathMatchingResourcePatternResolver());

	@Test
	void read_folderWithGraphqlAndGqlFilesInSubfolders_shouldFindEveryOperationFile() throws IOException {
		Path operations = Files.createDirectories(this.tempDir.resolve("operations"));
		Files.writeString(operations.resolve("TopRatedMovies.graphql"), TOP_RATED_MOVIES);
		Path movies = Files.createDirectories(operations.resolve("movies"));
		Files.writeString(movies.resolve("MovieByTitle.gql"), "query MovieByTitle { allMovies { title } }");
		Files.writeString(operations.resolve("notes.txt"), "Ideas for more tools");

		OperationSources documents = this.reader.read(Map.of(ToolExposureType.MCP, List.of(fileLocation(operations))));

		assertThat(documents.problems()).isEmpty();
		assertThat(documents.sources()).extracting((source) -> fileName(source.location()))
			.containsExactlyInAnyOrder("TopRatedMovies.graphql", "MovieByTitle.gql");
	}

	@Test
	void read_sameFolderOnBothSides_shouldLoadTheFileOnceForBothSides() throws IOException {
		Path operations = writeTopRatedMovies("operations");
		Map<ToolExposureType, List<String>> locations = new EnumMap<>(ToolExposureType.class);
		locations.put(ToolExposureType.MCP, List.of(fileLocation(operations)));
		locations.put(ToolExposureType.IN_PROCESS, List.of(fileLocation(operations) + "/"));

		OperationSources documents = this.reader.read(locations);

		assertThat(documents.sources()).singleElement()
			.satisfies((source) -> assertThat(source.toolExposureTypes()).containsExactly(ToolExposureType.MCP,
					ToolExposureType.IN_PROCESS));
	}

	@Test
	void read_folderReachedThroughSymbolicLink_shouldLoadTheFileOnce() throws IOException {
		Path operations = writeTopRatedMovies("operations");
		Path link = Files.createSymbolicLink(this.tempDir.resolve("linked-operations"), operations);

		OperationSources documents = this.reader
			.read(Map.of(ToolExposureType.MCP, List.of(fileLocation(operations), fileLocation(link))));

		assertThat(documents.problems()).isEmpty();
		assertThat(documents.sources()).hasSize(1);
	}

	@Test
	void read_locationNamingSingleFile_shouldReportProblemAndNameTheFolder() {
		OperationSources documents = this.reader
			.read(Map.of(ToolExposureType.MCP, List.of("classpath*:gatool/mcp/TopRatedMovies.graphql")));

		assertThat(documents.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.isEqualTo("names a single file, and a location names a folder; use classpath*:gatool/mcp/"));
	}

	@Test
	void read_emptyFolderWithoutOptionalPrefix_shouldReportProblem() throws IOException {
		Path empty = Files.createDirectories(this.tempDir.resolve("empty"));

		OperationSources documents = this.reader.read(Map.of(ToolExposureType.MCP, List.of(fileLocation(empty))));

		assertThat(documents.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("holds zero .graphql or .gql files"));
	}

	@Test
	void read_missingFolderWithOptionalPrefix_shouldPassWithZeroFiles() {
		OperationSources documents = this.reader
			.read(Map.of(ToolExposureType.MCP, List.of("optional:" + fileLocation(this.tempDir.resolve("missing")))));

		assertThat(documents.problems()).isEmpty();
		assertThat(documents.sources()).isEmpty();
	}

	@Test
	void read_classpathLocationsReachingOneJarEntryTwice_shouldLoadTheFileOnce() throws IOException {
		Path jar = this.tempDir.resolve("operations.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (String directory : List.of("gatool/", "gatool/mcp/")) {
				out.putNextEntry(new JarEntry(directory));
				out.closeEntry();
			}
			out.putNextEntry(new JarEntry("gatool/mcp/TopRatedMovies.graphql"));
			out.write(TOP_RATED_MOVIES.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null)) {
			OperationLocationReader jarReader = new OperationLocationReader(
					new PathMatchingResourcePatternResolver(classLoader));

			OperationSources documents = jarReader
				.read(Map.of(ToolExposureType.MCP, List.of("classpath*:gatool/mcp/", "classpath*:gatool/")));

			assertThat(documents.problems()).isEmpty();
			assertThat(documents.sources()).singleElement().satisfies((source) -> {
				assertThat(source.location()).startsWith("jar:file:").endsWith("!/gatool/mcp/TopRatedMovies.graphql");
				assertThat(source.content()).isEqualTo(TOP_RATED_MOVIES);
			});
		}
	}

	private Path writeTopRatedMovies(String folder) throws IOException {
		Path operations = Files.createDirectories(this.tempDir.resolve(folder));
		Files.writeString(operations.resolve("TopRatedMovies.graphql"), TOP_RATED_MOVIES);
		return operations;
	}

	private static String fileLocation(Path folder) {
		return "file:" + folder.toAbsolutePath();
	}

	private static String fileName(String location) {
		return location.substring(location.lastIndexOf('/') + 1);
	}

}
