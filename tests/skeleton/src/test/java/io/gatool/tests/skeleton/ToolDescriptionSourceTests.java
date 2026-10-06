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

package io.gatool.tests.skeleton;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a tool's description comes from, and what wins.
 *
 * <p>
 * The schema is the first source: the description of the root field the operation
 * selects, which the API already carries. The operation file overrides it with its own
 * {@code #} lines, so an operation file that wants different wording for the tool writes
 * it beside the query. Both paths produce the text a hand-written {@code @Tool} carries,
 * since a description is a description whichever side wrote it.
 */
class ToolDescriptionSourceTests {

	private static final String SCHEMA_DESCRIPTION = "Returns the highest-rated movies, best first.";

	private static final String FILE_DESCRIPTION = "Lists the films this cinema recommends tonight.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void description_fileWithoutItsOwnWords_shouldComeFromTheSchema(@TempDir Path folder) throws Exception {
		write(folder, """
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""");

		assertThat(descriptionFrom(folder)).isEqualTo(SCHEMA_DESCRIPTION);
	}

	@Test
	void description_fileWithItsOwnWords_shouldWinOverTheSchema(@TempDir Path folder) throws Exception {
		write(folder, """
				# Lists the films this cinema recommends tonight.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""");

		assertThat(descriptionFrom(folder)).isEqualTo(FILE_DESCRIPTION);
		assertThat(descriptionFrom(folder)).doesNotContain(SCHEMA_DESCRIPTION);
	}

	@Test
	void description_severalCommentLines_shouldJoinThemInOrder(@TempDir Path folder) throws Exception {
		write(folder, """
				# Lists the films this cinema recommends tonight.
				# Ask for more with first.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""");

		assertThat(descriptionFrom(folder)).isEqualTo(FILE_DESCRIPTION + "\nAsk for more with first.");
	}

	@Test
	void description_headerAboveABlankLine_shouldStayOutOfTheTool(@TempDir Path folder) throws Exception {
		write(folder, """
				# Copyright 2026 the original author.

				# Lists the films this cinema recommends tonight.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""");

		// A blank line ends the description, so a file header belongs to the file and
		// the model reads the sentence above the operation.
		assertThat(descriptionFrom(folder)).isEqualTo(FILE_DESCRIPTION);
	}

	@Test
	void description_fromTheFile_shouldMatchWhatAHandWrittenToolCarries(@TempDir Path folder) throws Exception {
		write(folder, """
				# Lists the films this cinema recommends tonight.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""");
		ToolCallback handWritten = ToolCallbacks.from(new HandWrittenTool())[0];

		// The tool contract a model reads carries one description field, and both sides
		// fill it the same way.
		assertThat(descriptionFrom(folder)).isEqualTo(handWritten.getToolDefinition().description());
	}

	private String descriptionFrom(Path folder) {
		String[] description = new String[1];
		this.contextRunner
			.withPropertyValues("gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				description[0] = tool.description();
			});
		return description[0];
	}

	private static void write(Path folder, String text) throws Exception {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), text);
	}

	static final class HandWrittenTool {

		@Tool(name = "topRatedMovies", description = FILE_DESCRIPTION)
		String topRatedMovies(Integer first) {
			return "{}";
		}

	}

}
