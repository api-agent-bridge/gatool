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

package io.gatool.core.check;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.gatool.core.internal.schema.SdlSchemaFactory;
import io.gatool.core.model.RequestLimits;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

// An application runs this check in its own CI, so these tests call it the way a plain
// JUnit test would: a schema file, two folders, and Spring left out.
class OperationFileCheckTests {

	private static final String TOP_RATED_MOVIES = """
			# Returns the highest-rated movies, best first.
			query TopRatedMovies($first: Int = 10) {
			  topRatedMovies(first: $first) { id title }
			}
			""";

	@Test
	void check_validOperationFile_shouldPassAndDescribeTheTool(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql", TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.problems()).isEmpty();
		assertThat(result.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.name()).isEqualTo("topRatedMovies");
			assertThat(tool.description()).isEqualTo("Returns the highest-rated movies, best first.");
			assertThat(tool.inputSchema()).contains("\"first\"");
			assertThat(tool.location()).endsWith("TopRatedMovies.graphql");
			assertThat(tool.exposureTypeLabels()).containsExactly("MCP");
		});
	}

	@Test
	void check_fileFailingValidation_shouldReportTheProblemNamingTheFile(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "UnknownField.graphql", "query UnknownField { tagline }");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isFalse();
		assertThat(result.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem).contains("UnknownField.graphql", "validation"));
		assertThat(result.tools()).isEmpty();
	}

	@Test
	void check_fileOnBothSides_shouldServeItOnBoth(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path shared = folderWith(directory, "shared", "TopRatedMovies.graphql", TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(shared), List.of(shared),
				CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.exposureTypeLabels()).containsExactlyInAnyOrder("MCP", "in-process"));
	}

	@Test
	void check_toolWithoutADescription_shouldWarnAndStillServeIt(@TempDir Path directory) throws Exception {
		// A schema of its own, because every Query field of the movie schema carries a
		// description, and a tool whose file stays silent falls back to the field's.
		Path schema = directory.resolve("plain.graphqls");
		Files.writeString(schema, """
				type Query {
				  movies: [String!]!
				}
				""");
		Path mcp = folderWith(directory, "plain", "Movies.graphql", "query Movies { movies }");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement().satisfies((tool) -> assertThat(tool.name()).isEqualTo("movies"));
		assertThat(result.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning).contains("Movies.graphql", "description"));
	}

	@Test
	void check_toolWhoseDocumentPassesTheTokensOfARequest_shouldCarryTheWarningStartupLogs(@TempDir Path directory)
			throws Exception {
		// The build is where a team learns that a document outgrew what an API reads,
		// ahead of the first call the API refuses.
		StringBuilder fields = new StringBuilder();
		for (int number = 1; number <= 6000; number++) {
			fields.append("    a").append(number).append(": id\n");
		}
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "Wide.graphql",
				"# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n" + fields + "  }\n}\n");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning)
				.contains("Wide.graphql becomes the tool wide and sends a document of more than 15,000 tokens")
				.contains("which passes gatool.api.request-limits.max-tokens"));
	}

	@Test
	void check_requestLimitsOfTheApplication_shouldDecideWhichDocumentEarnsTheWarning(@TempDir Path directory)
			throws Exception {
		// The application sets the limits it expects of its API, so the check has to read
		// them, or the build and the startup would disagree about the same file.
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRated.graphql",
				"# Returns the highest-rated movies.\nquery TopRated { topRatedMovies { id title rating } }\n");

		CheckResult byDefault = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());
		CheckResult strict = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				CheckSettings.defaults().withRequestLimits(new RequestLimits(1_048_576, 5, 200_000)));

		assertThat(byDefault.warnings()).isEmpty();
		assertThat(strict.passes()).isTrue();
		assertThat(strict.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning).contains(
					"sends a document of more than 5 tokens, which passes " + "gatool.api.request-limits.max-tokens"));
	}

	@Test
	void check_toolCostingMoreThanFourThousandTokens_shouldCarryTheWarningStartupLogs(@TempDir Path directory)
			throws Exception {
		// Four characters make a token, so three hundred lines of sixty characters cost
		// about four and a half thousand tokens before any call, and the check carries
		// the warning startup logs.
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "Wordy.graphql",
				String.join("\n", Collections.nCopies(300, "# " + "x".repeat(60)))
						+ "\nquery Wordy { topRatedMovies { id } }\n");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning)
				.containsPattern("Wordy\\.graphql becomes the tool wordy and costs about \\d+ tokens")
				.contains("which is a large share of a model's context before any call"));
	}

	@Test
	void check_toolWhoseOutputSchemaTakesItPastFourThousandTokens_shouldCarryTheWarningStartupLogs(
			@TempDir Path directory) throws Exception {
		// Four hundred aliases of one field make an output schema of about five and a
		// half thousand tokens. The MCP tool list carries it, so the estimate counts it.
		StringBuilder selections = new StringBuilder();
		for (int number = 1; number <= 400; number++) {
			selections.append("a").append(number).append(": id ");
		}
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "Wide.graphql",
				"# Returns the highest-rated movies.\nquery Wide { topRatedMovies { " + selections + "} }\n");

		CheckResult published = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				CheckSettings.defaults().withPublishedOutputSchema(true));
		CheckResult unpublished = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(published.passes()).isTrue();
		assertThat(published.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning)
				.containsPattern("Wide\\.graphql becomes the tool wide and costs about \\d+ tokens as an MCP tool")
				.containsPattern("Its output schema is about \\d+ of them")
				.contains("@gatool(outputSchema: false) leaves the output schema out of this tool"));
		assertThat(unpublished.warnings()).isEmpty();
	}

	@Test
	void check_toolThatPublishesAnOutputSchema_shouldReportTheSchemaTitleHintsAndScopes(@TempDir Path directory)
			throws Exception {
		// The five accessors this asserts are the fields the tool contract snapshot
		// already records, so the check has to report the same ones the running
		// application would publish.
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql",
				"""
						# Returns the highest-rated movies, best first.
						query TopRatedMovies($first: Int = 10) @gatool(title: "Top rated movies", scopes: ["movies:read"], outputSchema: true) {
						  topRatedMovies(first: $first) { id title }
						}
						""");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				CheckSettings.defaults().withPublishedOutputSchema(true));

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.title()).isEqualTo("Top rated movies");
			assertThat(tool.outputSchema()).contains("\"topRatedMovies\"");
			assertThat(tool.readOnly()).isTrue();
			assertThat(tool.openWorld()).isNull();
			assertThat(tool.scopes()).containsExactly("movies:read");
		});
	}

	@Test
	void check_mutationWithOpenWorld_shouldReportReadOnlyFalseAndOpenWorldTrue(@TempDir Path directory)
			throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "AddReview.graphql", """
				# Adds a review to a movie.
				mutation AddReview($movieId: ID!, $score: Int!) @gatool(openWorld: true) {
				  addReview(input: { movieId: $movieId, score: $score }) { review { id } }
				}
				""");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.readOnly()).isFalse();
			assertThat(tool.openWorld()).isTrue();
		});
	}

	@Test
	void check_toolBelowFourThousandTokens_shouldStayQuietAboutItsCost(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql", TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.warnings()).isEmpty();
	}

	@Test
	void check_publicOverloads_shouldEachTakeCheckSettings() {
		// A setting the check cannot see makes it report a different input schema and a
		// different set of warnings from the application it guards, so every public
		// overload takes the settings instead of assuming defaults for the caller.
		List<Method> overloads = Arrays.stream(OperationFileCheck.class.getMethods())
			.filter((method) -> method.getName().equals("check"))
			.toList();

		assertThat(overloads).isNotEmpty()
			.allSatisfy((method) -> assertThat(method.getParameterTypes()).contains(CheckSettings.class));
	}

	@Test
	void check_mcpToolJustBelowTheWarningThreshold_shouldWarnAsStartupDoes(@TempDir Path directory) throws Exception {
		// tools/list names each schema with a $id keyword built from a SHA-256 digest,
		// and McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA counts it as 98
		// characters. This tool's own text totals 15,953 characters: 3,988 tokens by that
		// text alone, and 4,012 tokens the way startup counts it with the $id added. A
		// check that leaves the $id out passes this tool in silence where startup would
		// warn about it.
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "Wordy.graphql",
				"# " + "x".repeat(15_815) + "\nquery Wordy { topRatedMovies { id } }\n");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning)
				.containsPattern("Wordy\\.graphql becomes the tool wordy and costs about \\d+ tokens as an MCP tool")
				.contains("which is a large share of a model's context before any call"));
	}

	@Test
	void extensions_shouldMatchTheOnesSpringForGraphQlReads() {
		// The running starter reads this pair from Spring for GraphQL's public
		// ResourceDocumentSource.FILE_EXTENSIONS, and gatool-core depends on
		// graphql-java, Jackson and Commons Lang alone, so the two ends are pinned
		// separately. A test in gatool-spring-boot asserts the Spring end, and this one
		// asserts the copy here.
		assertThat(OperationFileCheck.EXTENSIONS).containsExactly(".graphql", ".gql");
	}

	@Test
	void mcpSchemaIdCharacters_shouldMatchWhatTheMcpAdapterAdds() {
		// The running starter's MCP adapter counts these as
		// McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA, and gatool-core depends
		// on graphql-java, Jackson and Commons Lang alone, so the two ends are pinned
		// separately. A test in gatool-mcp-spring-boot asserts the adapter's end, and
		// this one asserts the copy here.
		assertThat(OperationFileCheck.MCP_SCHEMA_ID_CHARACTERS).isEqualTo(98);
	}

	@Test
	void check_gqlExtension_shouldReadTheFile(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.gql", TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isTrue();
		assertThat(result.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.name()).isEqualTo("topRatedMovies"));
	}

	@Test
	void check_folderThatIsMissing_shouldReportItAsAProblemNamingTheFolder(@TempDir Path directory) throws Exception {
		// A renamed or misspelled folder yields zero files, and zero files pass every
		// other check, where startup stops on a required location that holds zero files.
		// A check that passed here would guard an application that cannot start.
		Path absent = directory.resolve("absent");

		CheckResult result = OperationFileCheck.check(schemaFile(directory), List.of(absent), List.of(),
				CheckSettings.defaults());

		assertThat(result.passes()).isFalse();
		assertThat(result.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem).contains(absent.toString(), "not a directory"));
		assertThat(result.tools()).isEmpty();
	}

	@Test
	void check_listedPathThatIsAFile_shouldReportItAsAProblem(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path file = Files.writeString(directory.resolve("TopRatedMovies.graphql"), TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(), List.of(file), CheckSettings.defaults());

		assertThat(result.passes()).isFalse();
		assertThat(result.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem).contains(file.toString()));
	}

	@Test
	void check_schemaFileThatIsNotAValidSchema_shouldReportTheProblemNamingTheFile(@TempDir Path directory)
			throws Exception {
		// Startup stops on a schema it cannot build, and the check reports that stop the
		// way it reports a broken operation file, so a test prints the schema errors
		// instead of a stack trace.
		Path schema = Files.writeString(directory.resolve("broken.graphqls"), "type Query { movies: [Movie!]! }");
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql", TOP_RATED_MOVIES);

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.passes()).isFalse();
		assertThat(result.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem).contains("broken.graphqls", "Movie"));
		assertThat(result.tools()).isEmpty();
	}

	@Test
	void check_fromAFileWithTheApplicationsScalarFragment_shouldPublishWhatTheApplicationPublishes(
			@TempDir Path directory) throws Exception {
		// The overload that takes the schema file and the settings is the one an
		// application with gatool.inputs.scalar-schemas calls, because the schema built
		// from the file here is wired the way startup wires it.
		Path schema = Files.writeString(directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviews(since: Stamp): String! }
				""");
		Path mcp = folderWith(directory, "scalar-mcp", "Reviews.graphql", """
				# Reviews since a moment.
				query Reviews($since: Stamp) { reviews(since: $since) }
				""");

		CheckResult described = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				CheckSettings.defaults().withScalarSchema("Stamp", Map.of("type", "string", "format", "date-time")));

		assertThat(described.passes()).isTrue();
		assertThat(described.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema()).contains("\"format\":\"date-time\""));
		assertThat(described.warnings()).noneMatch((warning) -> warning.contains("any JSON value"));
		// The same files without the fragment publish the scalar as any JSON value and
		// warn, which is what a check left on the defaults would report for this
		// application.
		CheckResult undescribed = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());
		assertThat(undescribed.warnings()).anyMatch((warning) -> warning.contains("any JSON value"));
	}

	@Test
	void check_withTheApplicationsScalarFragments_shouldPublishWhatTheApplicationPublishes(@TempDir Path directory)
			throws Exception {
		// The check exists to fail the build that broke an operation file instead of the
		// deployment after it, and it can only do that while it reads the schema the
		// application publishes. A setting it cannot see makes it disagree both ways: a
		// warning already answered, and a misconfiguration that stops
		// startup and passes the check.
		Path schema = Files.writeString(directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviews(since: Stamp): String! }
				""");
		Path mcp = folderWith(directory, "scalar-mcp", "Reviews.graphql", """
				# Reviews since a moment.
				query Reviews($since: Stamp) { reviews(since: $since) }
				""");

		CheckResult described = OperationFileCheck.check(
				SdlSchemaFactory.schemaFrom(Files.readString(schema), schema.toString()), List.of(mcp), List.of(),
				CheckSettings.defaults().withScalarSchema("Stamp", Map.of("type", "string", "format", "date-time")));

		assertThat(described.passes()).isTrue();
		assertThat(described.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema()).contains("\"format\":\"date-time\""));
		// And the scalar stops being named as one GATool describes as any JSON value.
		assertThat(described.warnings()).noneMatch((warning) -> warning.contains("any JSON value"));
	}

	@Test
	void check_withAFragmentTheApplicationWouldRefuse_shouldFailTheBuild(@TempDir Path directory) throws Exception {
		Path schema = Files.writeString(directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviews(since: Stamp): String! }
				""");
		Path mcp = folderWith(directory, "bad-mcp", "Reviews.graphql", """
				# Reviews since a moment.
				query Reviews($since: Stamp) { reviews(since: $since) }
				""");

		CheckResult result = OperationFileCheck.check(
				SdlSchemaFactory.schemaFrom(Files.readString(schema), schema.toString()), List.of(mcp), List.of(),
				CheckSettings.defaults().withScalarSchema("Stamp", Map.of("type", "integer", "minimum", 0)));

		assertThat(result.passes()).isFalse();
		assertThat(result.problems()).anyMatch((problem) -> problem.contains("minimum"));
	}

	@Test
	void check_withAResultFragmentTheApplicationWouldRefuse_shouldFailTheBuildNamingTheResultsProperty(
			@TempDir Path directory) throws Exception {
		// An application with gatool.results.scalar-schemas stops at startup on a
		// fragment the rules refuse, so a check that could not take the setting would
		// pass the build of an application that cannot start.
		Path schema = Files.writeString(directory.resolve("scalars.graphqls"), """
				scalar Stamp

				type Query { reviewedAt(since: Stamp): Stamp }
				""");
		Path mcp = folderWith(directory, "result-mcp", "ReviewedAt.graphql", """
				# When the newest review since a moment was written.
				query ReviewedAt($since: Stamp) { reviewedAt(since: $since) }
				""");
		CheckSettings settings = CheckSettings.defaults()
			.withPublishedOutputSchema(true)
			.withScalarSchema("Stamp", Map.of("type", "integer"));

		CheckResult refused = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				settings.withResultScalarSchema("Stamp", Map.of("type", "string", "minLength", 20)));
		CheckResult accepted = OperationFileCheck.check(schema, List.of(mcp), List.of(),
				settings.withResultScalarSchema("Stamp", Map.of("type", "string", "format", "date-time")));

		assertThat(refused.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem)
				.startsWith("The property gatool.results.scalar-schemas.Stamp carries the keyword 'minLength'"));
		assertThat(accepted.passes()).isTrue();
		assertThat(accepted.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema()).contains("\"type\":\"integer\"")
				.doesNotContain("date-time"));
	}

	@Test
	void check_sharedFragmentInASubfolder_shouldReportTheAssembledDocument(@TempDir Path directory) throws Exception {
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql", """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""");
		Files.writeString(Files.createDirectories(mcp.resolve("fragments")).resolve("MovieCard.graphql"),
				"fragment MovieCard on Movie { id title }");

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		// The check walks the folder the way startup reads a location, so a fragments
		// subfolder is found, and the result carries the text the tool would send, for a
		// test to print.
		assertThat(result.passes()).isTrue();
		assertThat(result.warnings()).isEmpty();
		assertThat(result.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.document()).contains("fragment MovieCard on Movie");
			assertThat(tool.sharedFragmentFiles()).singleElement().asString().endsWith("MovieCard.graphql");
		});
	}

	@Test
	void check_symlinkedFragmentsFolder_shouldReadItTheWayStartupDoes(@TempDir Path directory) throws Exception {
		// Startup resolves a location with Spring's resolver, which follows a symbolic
		// link to a folder, so the check follows links as well and reads a fragments
		// folder linked into the operations folder.
		Path schema = schemaFile(directory);
		Path mcp = folderWith(directory, "mcp", "TopRatedMovies.graphql", """
				# Returns the highest-rated movies, best first.
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""");
		Path shared = folderWith(directory, "shared", "MovieCard.graphql", "fragment MovieCard on Movie { id title }");
		assumeLinkable(mcp.resolve("fragments"), shared);

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.problems()).isEmpty();
		assertThat(result.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.sharedFragmentFiles()).singleElement()
				.asString()
				.endsWith("MovieCard.graphql"));
	}

	@Test
	void check_severalFiles_shouldListTheToolsInPathOrderWhateverOrderTheDiskReturns(@TempDir Path directory)
			throws Exception {
		// Startup sorts the files a location holds, and the check sorts them as well, so
		// two machines list the same tools in one order. Fourteen names, because a
		// directory of two or three can come back sorted by chance on a file system that
		// hashes its entries.
		Path schema = schemaFile(directory);
		Path mcp = Files.createDirectories(directory.resolve("mcp"));
		for (String name : List.of("b", "a", "c", "z", "m", "d", "q", "e", "y", "f", "w", "g", "v", "h")) {
			Files.writeString(mcp.resolve(name + ".graphql"),
					"# The tool " + name + ".\nquery " + name + " { topRatedMovies { title } }\n");
		}

		CheckResult result = OperationFileCheck.check(schema, List.of(mcp), List.of(), CheckSettings.defaults());

		assertThat(result.problems()).isEmpty();
		assertThat(result.tools()).hasSize(14).extracting(CheckedTool::name).isSorted();
	}

	// A platform that refuses symbolic links, such as Windows without the privilege,
	// skips the test that needs one.
	private static void assumeLinkable(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		}
		catch (UnsupportedOperationException | IOException ex) {
			Assumptions.abort("symbolic links are unavailable here: " + ex);
		}
	}

	private static Path schemaFile(Path directory) throws Exception {
		Path schema = directory.resolve("movies.graphqls");
		Files.writeString(schema, MoviesSchema.sdl());
		return schema;
	}

	private static Path folderWith(Path directory, String name, String file, String text) throws Exception {
		Path folder = Files.createDirectories(directory.resolve(name));
		Files.writeString(folder.resolve(file), text);
		return folder;
	}

}
