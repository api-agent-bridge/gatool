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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.gatool.boot.GAToolCatalog;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ToolContractSnapshotTests {

	private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	@TempDir
	Path directory;

	@Test
	void assertMatches_missingSnapshot_shouldWriteItAndFailAskingForReview() {
		Path snapshot = this.directory.resolve("gatool/in-process.json");

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> ToolContractSnapshot.of(catalog("topRatedMovies")).assertMatches(snapshot))
			.withMessageContaining(snapshot.toString())
			.withMessageContaining("commit");
		// Written into a folder that was missing too, so a fresh project's first run
		// works as it is.
		assertThat(snapshot).exists().content().contains("\"topRatedMovies\"");
	}

	@Test
	void assertMatches_unchangedContract_shouldAcceptTheContract() {
		Path snapshot = this.directory.resolve("tools.json");
		ToolContractSnapshot.of(catalog("topRatedMovies")).writeTo(snapshot);

		assertThatCode(() -> ToolContractSnapshot.of(catalog("topRatedMovies")).assertMatches(snapshot))
			.doesNotThrowAnyException();
	}

	@Test
	void assertMatches_renamedTool_shouldFailNamingTheFileAndTheWayToAccept() {
		Path snapshot = this.directory.resolve("tools.json");
		ToolContractSnapshot.of(catalog("topRatedMovies")).writeTo(snapshot);

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> ToolContractSnapshot.of(catalog("top_rated_movies")).assertMatches(snapshot))
			.withMessageContaining(snapshot.toString())
			.withMessageContaining(ToolContractSnapshot.UPDATE_PROPERTY);
	}

	@Test
	void assertMatches_updatePropertySet_shouldRewriteTheFileAndPass() {
		Path snapshot = this.directory.resolve("tools.json");
		ToolContractSnapshot.of(catalog("topRatedMovies")).writeTo(snapshot);

		System.setProperty(ToolContractSnapshot.UPDATE_PROPERTY, "true");
		try {
			assertThatCode(() -> ToolContractSnapshot.of(catalog("top_rated_movies")).assertMatches(snapshot))
				.doesNotThrowAnyException();
		}
		finally {
			System.clearProperty(ToolContractSnapshot.UPDATE_PROPERTY);
		}
		assertThat(snapshot).content().contains("\"top_rated_movies\"").doesNotContain("\"topRatedMovies\"");
	}

	@Test
	void contract_toolsDeclaredInAnyOrder_shouldSortByName() {
		GAToolCatalog catalog = GAToolCatalog.builder()
			.mcpTools(List.of(tool("movies"), tool("addReview")))
			.inProcessTools(List.of(tool("topRatedMovies")))
			.build();

		String contract = ToolContractSnapshot.of(catalog).contract();

		assertThat(contract.indexOf("\"addReview\"")).isLessThan(contract.indexOf("\"movies\""));
		assertThat(contract).contains("\"mcp\"", "\"inProcess\"", "\"readOnly\" : true");
	}

	@Test
	void contract_inputSchema_shouldBeHeldAsJsonRatherThanAsAString() throws Exception {
		Path snapshot = this.directory.resolve("tools.json");
		ToolContractSnapshot.of(catalog("topRatedMovies")).writeTo(snapshot);

		// A snapshot holding the schema as an escaped string would show one long line
		// in a diff, where a field-level change wants a field-level diff.
		assertThat(Files.readString(snapshot)).contains("\"additionalProperties\" : false")
			.doesNotContain("\\\"type\\\"");
	}

	@Test
	void contract_onEveryPlatform_shouldEndLinesWithALineFeedAlone() throws Exception {
		Path snapshot = this.directory.resolve("tools.json");
		ToolContractSnapshot.of(catalog("topRatedMovies")).writeTo(snapshot);

		// A snapshot committed on one operating system compares equal on another, and
		// the file ends with one line feed, which is what a repository's own line-ending
		// rules expect of a text file.
		String text = Files.readString(snapshot);
		assertThat(text).doesNotContain("\r").endsWith("}\n").doesNotEndWith("\n\n");
		assertThat(text.lines().count()).isGreaterThan(1);
	}

	@Test
	void contract_toolWithScopes_shouldListThemInTheOrderTheFileDeclaresThem() {
		GATool scoped = GATool.builder()
			.name("addReview")
			.description("Adds a review.")
			.inputSchema(INPUT_SCHEMA)
			.readOnly(false)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.scopes(List.of("reviews:write", "movies:read"))
			.build();
		GAToolCatalog catalog = GAToolCatalog.builder().mcpTools(List.of(scoped, tool("movies"))).build();

		String contract = ToolContractSnapshot.of(catalog).contract();

		// The order is the file's, so a reordering shows in review, and a tool without
		// declared scopes writes null, the way the other optional fields do.
		assertThat(contract.replaceAll("\\s", "")).contains("\"scopes\":[\"reviews:write\",\"movies:read\"]")
			.contains("\"scopes\":null");
	}

	private static GAToolCatalog catalog(String toolName) {
		return GAToolCatalog.builder().mcpTools(List.of(tool(toolName))).build();
	}

	private static GATool tool(String name) {
		return GATool.builder()
			.name(name)
			.description("Returns the highest-rated movies, best first.")
			.inputSchema(INPUT_SCHEMA)
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();
	}

}
