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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.gatool.core.check.CheckResult;
import io.gatool.core.check.CheckSettings;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OperationFilesAssertTests {

	@TempDir
	Path directory;

	private Path schema;

	private Path mcp;

	@BeforeEach
	void writeSchemaAndFolders() throws IOException {
		this.schema = Files.writeString(this.directory.resolve("movies.graphqls"), MoviesSchema.sdl());
		this.mcp = Files.createDirectories(this.directory.resolve("gatool/mcp"));
	}

	@Test
	void assertValid_validFile_shouldReturnTheResultWithItsTool() throws IOException {
		Files.writeString(this.mcp.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) {
				    id
				    title
				  }
				}
				""");

		CheckResult result = OperationFilesAssert.assertValid(this.schema, List.of(this.mcp), List.of(),
				CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.name()).isEqualTo("topRatedMovies"));
	}

	@Test
	void assertValid_fileNamingAnUnknownField_shouldFailWithStartupsOwnWords() throws IOException {
		Files.writeString(this.mcp.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies {
				    tagline
				  }
				}
				""");

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> OperationFilesAssert.assertValid(this.schema, List.of(this.mcp), List.of(),
					CheckSettings.defaults()))
			.withMessageContaining("1 problem that would stop startup")
			.withMessageContaining("TopRatedMovies.graphql")
			.withMessageContaining("tagline");
	}

	@Test
	void assertValid_withTheApplicationsScalarFragment_shouldRunTheChecksStartupRunsUnderThatSetting()
			throws IOException {
		// An application with gatool.inputs.scalar-schemas publishes the fragment, and a
		// fragment the writers refuse stops its startup, so the assertion has to take the
		// same setting to fail the build that changed it.
		Path scalarSchema = Files.writeString(this.directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviews(since: Stamp): String! }
				""");
		Files.writeString(this.mcp.resolve("Reviews.graphql"), """
				# Reviews since a moment.
				query Reviews($since: Stamp) { reviews(since: $since) }
				""");

		CheckResult described = OperationFilesAssert.assertValid(scalarSchema, List.of(this.mcp), List.of(),
				CheckSettings.defaults().withScalarSchema("Stamp", Map.of("type", "string", "format", "date-time")));
		assertThat(described.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema()).contains("\"format\":\"date-time\""));

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> OperationFilesAssert.assertValid(scalarSchema, List.of(this.mcp), List.of(),
					CheckSettings.defaults().withScalarSchema("Stamp", Map.of("type", "integer", "minimum", 0))))
			.withMessageContaining("minimum");
	}

	@Test
	void assertValid_withTheApplicationsResultScalarFragment_shouldRunTheChecksStartupRunsUnderThatSetting()
			throws IOException {
		// An application with gatool.results.scalar-schemas stops at startup on a
		// fragment the rules refuse, so the assertion takes the same setting and names
		// the same property.
		Path scalarSchema = Files.writeString(this.directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviewedAt(since: Stamp): Stamp }
				""");
		Files.writeString(this.mcp.resolve("ReviewedAt.graphql"), """
				# When the newest review since a moment was written.
				query ReviewedAt($since: Stamp) { reviewedAt(since: $since) }
				""");
		CheckSettings settings = CheckSettings.defaults()
			.withPublishedOutputSchema(true)
			.withScalarSchema("Stamp", Map.of("type", "integer"));

		CheckResult described = OperationFilesAssert.assertValid(scalarSchema, List.of(this.mcp), List.of(),
				settings.withResultScalarSchema("Stamp", Map.of("type", "string", "format", "date-time")));
		assertThat(described.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema()).contains("\"type\":\"integer\""));

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> OperationFilesAssert.assertValid(scalarSchema, List.of(this.mcp), List.of(),
					settings.withResultScalarSchema("Stamp", Map.of("type", "string", "minLength", 20))))
			.withMessageContaining(
					"The property gatool.results.scalar-schemas.Stamp carries the keyword " + "'minLength'");
	}

	@Test
	void assertValid_aListedFolderThatDoesNotExist_shouldFailNamingTheFolder() {
		// A renamed or misspelled folder yields zero files. Startup stops on a required
		// location without files, so the check has to stop too, or the build passes on
		// an application that will not start.
		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> OperationFilesAssert.assertValid(this.schema, List.of(Path.of("does-not-exist")),
					List.of(), CheckSettings.defaults()))
			.withMessageContaining("does-not-exist");
	}

}
