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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.OperationFileProblemsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shared fragment files do to startup: a spread without a definition and two
 * definitions of one name stop it, and a shadowed or unused fragment lets it start with a
 * warning.
 *
 * <p>
 * Each test writes its files into a folder of its own and points a location at it. An
 * operation file is on the side whose location reaches it, and a file both locations
 * reach is on both sides, which is how the cross-side case is built. Startup is the whole
 * test, so the API URL points at a closed port.
 */
@ExtendWith(OutputCaptureExtension.class)
class SharedFragmentsStartupTests {

	private static final String TOP_RATED = """
			# Returns the highest-rated movies, best first.
			query TopRatedMovies { topRatedMovies { ...MovieCard } }
			""";

	private static final String MOVIE_CARD = "fragment MovieCard on Movie { id title }";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void startup_spreadWithoutADefinitionAnywhere_shouldStopNamingTheFragmentAndTheFile(@TempDir Path folder)
			throws IOException {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), TOP_RATED);

		this.contextRunner.withPropertyValues(mcp(folder), tools()).run((context) -> {
			assertThat(context).hasFailed();
			// Validation reports the spread the way it reports any other error, with
			// the fragment's name and the file that spreads it.
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(OperationFileProblemsException.class)
				.hasMessageContaining("TopRatedMovies.graphql")
				.hasMessageContaining("MovieCard");
		});
	}

	@Test
	void startup_twoSharedFilesOnOneSideDefiningOneName_shouldStopNamingBothFiles(@TempDir Path folder)
			throws IOException {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), TOP_RATED);
		Path fragments = Files.createDirectories(folder.resolve("fragments"));
		Files.writeString(fragments.resolve("MovieCard.graphql"), MOVIE_CARD);
		Files.writeString(fragments.resolve("MovieCardAgain.graphql"), "fragment MovieCard on Movie { id rating }");

		this.contextRunner.withPropertyValues(mcp(folder), tools()).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(OperationFileProblemsException.class)
				.hasMessageContaining("defines the fragment MovieCard")
				.hasMessageContaining("MovieCard.graphql")
				.hasMessageContaining("MovieCardAgain.graphql")
				.hasMessageContaining("a spread has to find one definition")
				.hasMessageContaining("keep one, or rename one of them");
		});
	}

	@Test
	void startup_eachSideDefiningOneNameDifferentlyAndABothSidedFileSpreadingIt_shouldStopNamingBothFiles(
			@TempDir Path operations, @TempDir Path mcpFragments, @TempDir Path toolsFragments) throws IOException {
		// The operation folder is listed on both sides, so its file is published by
		// both exposure types and sees both sides' fragment files.
		Files.writeString(operations.resolve("TopRatedMovies.graphql"), TOP_RATED);
		Files.writeString(mcpFragments.resolve("MovieCard.graphql"), MOVIE_CARD);
		Files.writeString(toolsFragments.resolve("MovieCard.graphql"), "fragment MovieCard on Movie { id rating }");

		this.contextRunner
			.withPropertyValues(
					"gatool.mcp.operations.locations=" + location(operations) + "," + location(mcpFragments),
					"gatool.in-process.operations.locations=" + location(operations) + "," + location(toolsFragments))
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(OperationFileProblemsException.class)
					.hasMessageContaining("TopRatedMovies.graphql")
					.hasMessageContaining("is published by both exposure types, and the fragment MovieCard")
					.hasMessageContaining(mcpFragments.getFileName() + "/MovieCard.graphql")
					.hasMessageContaining(toolsFragments.getFileName() + "/MovieCard.graphql");
			});
	}

	@Test
	void startup_eachSideDefiningOneNameDifferentlyThatStaysUnspread_shouldStart(@TempDir Path operations,
			@TempDir Path mcpFragments, @TempDir Path toolsFragments) throws IOException {
		Files.writeString(operations.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies { topRatedMovies { id } }
				""");
		Files.writeString(mcpFragments.resolve("MovieCard.graphql"), MOVIE_CARD);
		Files.writeString(toolsFragments.resolve("MovieCard.graphql"), "fragment MovieCard on Movie { id rating }");

		// The two definitions clash only for a file that spreads the name, and this
		// file holds a plain selection, so the application starts.
		this.contextRunner
			.withPropertyValues(
					"gatool.mcp.operations.locations=" + location(operations) + "," + location(mcpFragments),
					"gatool.in-process.operations.locations=" + location(operations) + "," + location(toolsFragments))
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_ownFragmentShadowingASharedOne_shouldStartAndWarnNamingBothFiles(@TempDir Path folder,
			CapturedOutput output) throws IOException {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), TOP_RATED + "\n" + MOVIE_CARD);
		Files.writeString(Files.createDirectories(folder.resolve("fragments")).resolve("MovieCard.graphql"),
				"fragment MovieCard on Movie { id rating }");

		this.contextRunner.withPropertyValues(mcp(folder), tools())
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("TopRatedMovies.graphql defines the fragment MovieCard, which")
			.contains("MovieCard.graphql also defines; the file keeps its own");
	}

	@Test
	void startup_sharedFragmentLeftUnspread_shouldStartAndWarnNamingItsFile(@TempDir Path folder, CapturedOutput output)
			throws IOException {
		Files.writeString(folder.resolve("TopRatedMovies.graphql"), """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies { topRatedMovies { id } }
				""");
		Files.writeString(Files.createDirectories(folder.resolve("fragments")).resolve("MovieCard.graphql"),
				MOVIE_CARD);

		this.contextRunner.withPropertyValues(mcp(folder), tools())
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("MovieCard.graphql holds the fragment MovieCard, which zero operation files spread");
	}

	private static String mcp(Path folder) {
		return "gatool.mcp.operations.locations=" + location(folder);
	}

	private static String tools() {
		return "gatool.in-process.operations.locations=optional:classpath*:gatool/none/";
	}

	private static String location(Path folder) {
		return "file:" + folder.toAbsolutePath() + "/";
	}

}
