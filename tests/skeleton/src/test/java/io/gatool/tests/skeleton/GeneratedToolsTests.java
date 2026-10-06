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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tools generated from the schema, which is the development switch.
 *
 * <p>
 * GATool's product is a folder of trusted documents, and this publishes the API's whole
 * root instead, so it sits under {@code gatool.dev.experimental} and startup says so
 * every time. What it buys is the first ten minutes with a new schema.
 *
 * <p>
 * The generated text goes through the same parser, validator and catalog as a written
 * operation file, so these tests assert what a developer actually gets: the tool names,
 * the arguments, and which root fields are left alone.
 */
@ExtendWith(OutputCaptureExtension.class)
class GeneratedToolsTests {

	private static final String SDL = """
			"A place a film was shot."
			type Location { id: ID!  name: String!  country: String! }

			"One film."
			type Movie {
			  id: ID!
			  title: String!
			  rating: Float
			  locations: [Location!]!
			  similar(first: Int!): [Movie!]!
			}

			type MovieConnection { nodes: [Movie!]!  total: Int! }

			type Query {
			  "Returns the highest-rated films, best first."
			  topRatedMovies(first: Int = 10): [Movie!]!
			  movie(id: ID!): Movie
			  movies(after: String): MovieConnection!
			}

			type Mutation {
			  rateMovie(id: ID!, score: Int!): Movie!
			}
			""";

	// One deprecated root field and one deprecated field, which is what the two
	// deprecation properties decide the fate of.
	private static final String DEPRECATED_SDL = """
			type Film { id: ID!  title: String! @deprecated(reason: "Use name.")  name: String! }

			type Query {
			  films: [Film!]!
			  oldFilms: [Film!]! @deprecated(reason: "Use films.")
			}
			""";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@Test
	void generateTools_offByDefault_shouldPublishNoTool(@TempDir Path folder) throws Exception {
		// The folder does not hold an operation file, so the location is marked optional
		// the way a schema would mark one that may stay empty. With the switch off,
		// nothing fills it.
		writeSchema(folder);
		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.mcp.operations.locations=optional:file:" + folder.toAbsolutePath() + "/")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isEmpty();
			});
	}

	@Test
	void generateTools_allRootQueriesWithoutAnyOperationFile_shouldStartAndPublishTools(@TempDir Path folder)
			throws Exception {
		// The case the switch exists for: a developer points GATool at a schema with an
		// empty folder. Without the switch that folder is a startup problem, because a
		// location without an operation file is usually a mistake.
		run(folder, (context) -> assertThat(names(context)).isNotEmpty(),
				"gatool.dev.experimental.generate-tools=all-root-queries");
	}

	@Test
	void generateTools_allRootQueries_shouldPublishOneToolPerQueryRootFieldAndLeaveMutationsAlone(@TempDir Path folder)
			throws Exception {
		run(folder, (context) -> assertThat(names(context))
			// Every query root field, and the mutation root left alone. movies joins
			// them because MovieConnection.total is a scalar, so one level in has
			// something to select.
			.containsExactlyInAnyOrder("topRatedMovies", "movie", "movies"),
				"gatool.dev.experimental.generate-tools=all-root-queries");
	}

	@Test
	void generateTools_allRootQueriesAtDepthOne_shouldSelectTheLeavesOfTheTypeAndStop(@TempDir Path folder)
			throws Exception {
		// The output schema mirrors the selection set, so it is what shows how far the
		// generator went without exposing the document.
		run(folder,
				(context) -> assertThat(outputSchemaOf(context, "movies")).contains("\"total\"")
					.doesNotContain("\"nodes\""),
				"gatool.dev.experimental.generate-tools=all-root-queries", "gatool.results.publish-output-schema=true");
	}

	@Test
	void generateTools_allRootQueriesAtDepthTwo_shouldReachThroughAWrapperType(@TempDir Path folder) throws Exception {
		run(folder,
				(context) -> assertThat(outputSchemaOf(context, "movies")).contains("\"nodes\"").contains("\"title\""),
				"gatool.dev.experimental.generate-tools=all-root-queries",
				"gatool.dev.experimental.generated-selection-depth=2", "gatool.results.publish-output-schema=true");
	}

	@Test
	void generateTools_allRootQueriesAndMutations_shouldPublishTheWriteTools(@TempDir Path folder) throws Exception {
		run(folder, (context) -> {
			assertThat(names(context)).contains("rateMovie");
			assertThat(context.getBean(GAToolCatalog.class).mcpTools())
				.filteredOn((tool) -> "rateMovie".equals(tool.name()))
				.singleElement()
				.satisfies((tool) -> assertThat(tool.readOnly()).isFalse());
		}, "gatool.dev.experimental.generate-tools=all-root-queries-and-mutations",
				"gatool.dev.experimental.generated-selection-depth=2");
	}

	@Test
	void generatedTool_shouldCarryTheRootFieldsArgumentsAndDescription(@TempDir Path folder) throws Exception {
		run(folder,
				(context) -> assertThat(context.getBean(GAToolCatalog.class).mcpTools())
					.filteredOn((tool) -> "topRatedMovies".equals(tool.name()))
					.singleElement()
					.satisfies((tool) -> {
						// The description falls back to the root field's own words, which
						// is the second description source and does not need a written
						// comment.
						assertThat(tool.description()).isEqualTo("Returns the highest-rated films, best first.");
						assertThat(tool.inputSchema()).contains("\"first\"").contains("\"default\":10");
						assertThat(tool.readOnly()).isTrue();
					}),
				"gatool.dev.experimental.generate-tools=all-root-queries");
	}

	@Test
	void generatedSelection_shouldSkipAFieldNeedingAnArgument(@TempDir Path folder) throws Exception {
		// Movie.similar takes a required argument, and a value invented here would be a
		// guess, so the generated selection leaves it out. Startup validating every
		// generated document is what proves it: a selection carrying similar without its
		// argument fails validation and would stop the context.
		run(folder,
				(context) -> assertThat(outputSchemaOf(context, "topRatedMovies")).contains("\"locations\"")
					.doesNotContain("\"similar\""),
				"gatool.dev.experimental.generate-tools=all-root-queries",
				"gatool.dev.experimental.generated-selection-depth=3", "gatool.results.publish-output-schema=true");
	}

	@Test
	void operationFile_shouldWinOverTheGeneratorForThatRootField(@TempDir Path folder) throws Exception {
		Files.writeString(folder.resolve("BestFilms.graphql"), """
				# The ten best, for the landing page.
				query BestFilms { topRatedMovies { id title } }
				""");

		run(folder, (context) -> assertThat(names(context))
			// The file names the tool, and no second tool is generated for the root
			// field it already selects.
			.containsExactlyInAnyOrder("bestFilms", "movie", "movies"),
				"gatool.dev.experimental.generate-tools=all-root-queries");
	}

	@Test
	void generateTools_atADepthOutsideTheBound_shouldStopStartupAndSayWhy(@TempDir Path folder) throws Exception {
		writeSchema(folder);
		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries",
					"gatool.dev.experimental.generated-selection-depth=6")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("generated-selection-depth")
				.hasMessageContaining("1 to 5"));
	}

	@Test
	void generatedToolClashingWithAWrittenOne_shouldYieldToTheWrittenOne(@TempDir Path folder) throws Exception {
		// The file selects one root field and takes the tool name another root field
		// would generate. A clash stops startup and asks for one operation to be renamed,
		// and a team cannot rename an operation GATool wrote, so the generated one is
		// left out instead and startup says which root field lost.
		Files.writeString(folder.resolve("Movies.graphql"), """
				# One film by id.
				query Movies { movie(id: "1") { id } }
				""");

		run(folder, (context) -> assertThat(names(context))
			// movies is the file's tool name, and the root field movies goes
			// without a tool.
			.containsExactlyInAnyOrder("movies", "topRatedMovies"),
				"gatool.dev.experimental.generate-tools=all-root-queries");
	}

	@Test
	void aDeprecatedRootField_withDeprecatedRootFieldsTurnedOff_shouldLoseItsTool(@TempDir Path folder)
			throws Exception {
		// The default keeps it, and the deprecation travels in the description, so this
		// is the property asking for the deprecated root fields to stay out of the tool
		// list.
		Files.writeString(folder.resolve("films.graphqls"), DEPRECATED_SDL);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(names(context)).containsExactlyInAnyOrder("films", "oldFilms");
			});

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries",
					"gatool.dev.experimental.generate-tools-for-deprecated-root-fields=false")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(names(context)).containsExactly("films");
			});
	}

	@Test
	void aDeprecatedField_withDeprecatedFieldsTurnedOff_shouldBeLeftOutOfTheSelection(@TempDir Path folder)
			throws Exception {
		// The output schema mirrors the selection set, so it is what shows which fields
		// the generator took.
		Files.writeString(folder.resolve("films.graphqls"), DEPRECATED_SDL);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries",
					"gatool.dev.experimental.generated-operations-include-deprecated-fields=false",
					"gatool.results.publish-output-schema=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(outputSchemaOf(context, "films")).contains("\"name\"").doesNotContain("\"title\"");
			});
	}

	private static String outputSchemaOf(AssertableApplicationContext context, String toolName) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> toolName.equals(tool.name()))
			.findFirst()
			.map(GATool::outputSchema)
			.orElseThrow(() -> new AssertionError("no tool named " + toolName));
	}

	@Test
	void generateTools_dynamicTwoStep_shouldFailToBindAsAnUnknownValue(@TempDir Path folder, CapturedOutput output)
			throws Exception {
		// dynamic-two-step is not a constant of the enum, so the property fails Boot's
		// own bind step before GATool ever reads it, the way any unknown value would.
		writeSchema(folder);
		String[] settings = settings(folder);
		String[] properties = new String[settings.length + 1];
		System.arraycopy(settings, 0, properties, 0, settings.length);
		properties[settings.length] = "gatool.dev.experimental.generate-tools=dynamic-two-step";

		// ApplicationContextRunner catches the raw exception and skips Boot's failure
		// analysis, so this runs a real application to read the report an operator sees.
		assertThatExceptionOfType(Throwable.class)
			.isThrownBy(() -> new SpringApplicationBuilder(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class)
				.web(WebApplicationType.NONE)
				.properties(properties)
				.properties("spring.main.banner-mode=off")
				.run())
			.havingRootCause()
			.withMessageContaining("No enum constant")
			.withMessageContaining("dynamic-two-step");

		assertThat(output.getAll()).contains("Failed to bind properties under 'gatool.dev.experimental.generate-tools'")
			.contains(String.format("%n    ALL_ROOT_QUERIES%n"))
			.contains(String.format("%n    ALL_ROOT_QUERIES_AND_MUTATIONS%n"))
			.contains(String.format("%n    DYNAMIC_THREE_STEP%n"))
			.contains(String.format("%n    NONE%n"))
			.doesNotContain("DYNAMIC_TWO_STEP");
	}

	@Test
	void generateTools_dynamicThreeStep_shouldPublishTheThreeToolsBesideTheCuratedOnes(@TempDir Path folder)
			throws Exception {
		writeSchema(folder);
		Files.writeString(folder.resolve("Top.graphql"), """
				# Returns the highest-rated films.
				query topRatedMovies($first: Int = 10) { topRatedMovies(first: $first) { id title } }
				""");

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				// The operation file keeps its tool: these three are a fallback for what
				// the trusted documents leave uncovered.
				assertThat(names(context)).contains("topRatedMovies")
					.contains("searchSchema", "introspectType", "executeGraphql");
			});
	}

	@Test
	void generateTools_dynamicThreeStep_shouldReachTheInProcessSide(@TempDir Path folder) throws Exception {
		// They come without an operation file and without a side of their own, so both
		// adapters publish them and they arrive on whichever starter runs.
		run(folder,
				(context) -> assertThat(context.getBean(GAToolCatalog.class).inProcessTools()).extracting(GATool::name)
					.contains("searchSchema", "introspectType", "executeGraphql"),
				"gatool.dev.experimental.generate-tools=dynamic-three-step");
	}

	@Test
	void generateTools_dynamicThreeStep_shouldSearchTheSchemaItValidatesAgainst(@TempDir Path folder) throws Exception {
		run(folder, (context) -> {
			GATool search = context.getBean(GAToolCatalog.class)
				.mcpTools()
				.stream()
				.filter((tool) -> tool.name().equals("searchSchema"))
				.findFirst()
				.orElseThrow();

			assertThat(search.call(Map.of("question", "highest rated films")).text()).contains("Query.topRatedMovies");
		}, "gatool.dev.experimental.generate-tools=dynamic-three-step");
	}

	@Test
	void generateTools_dynamicThreeStep_shouldTellANestedFieldHowToBeReached(@TempDir Path folder) throws Exception {
		run(folder, (context) -> {
			GATool search = context.getBean(GAToolCatalog.class)
				.mcpTools()
				.stream()
				.filter((tool) -> tool.name().equals("searchSchema"))
				.findFirst()
				.orElseThrow();

			// Location.country sits two fields from the root, and a hit that leaves that
			// out cannot be turned into an operation.
			assertThat(search.call(Map.of("question", "country where a film was shot")).text())
				.contains("reach it from Query.topRatedMovies(first: Int) -> Movie.locations");
		}, "gatool.dev.experimental.generate-tools=dynamic-three-step");
	}

	@Test
	void anEmbeddingBackend_withAModel_shouldRankWithIt(@TempDir Path folder) throws Exception {
		writeSchema(folder);

		this.contextRunner.withPropertyValues(settings(folder))
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.search-backend=embedding",
					"gatool.dev.experimental.dynamic-operations.vector-cache-directory=")
			.withBean(EmbeddingModel.class, WordEmbeddingModel::new)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(names(context)).contains("searchSchema", "introspectType", "executeGraphql");
				GATool search = context.getBean(GAToolCatalog.class)
					.mcpTools()
					.stream()
					.filter((tool) -> tool.name().equals("searchSchema"))
					.findFirst()
					.orElseThrow();

				// The stand-in scores a field by the words it shares with the question,
				// so this proves the wiring and leaves the quality of a real model
				// unmeasured.
				assertThat(search.call(Map.of("question", "rating")).text()).contains("Movie.rating");
			});
	}

	private static List<String> names(AssertableApplicationContext context) {
		return context.getBean(GAToolCatalog.class).mcpTools().stream().map(GATool::name).toList();
	}

	private void run(Path folder, Consumer<AssertableApplicationContext> work, String... properties) throws Exception {
		writeSchema(folder);
		this.contextRunner.withPropertyValues(settings(folder)).withPropertyValues(properties).run((context) -> {
			assertThat(context).hasNotFailed();
			work.accept(context);
		});
	}

	private static String[] settings(Path folder) {
		return new String[] { "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/films.graphqls",
				"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/", };
	}

	private static void writeSchema(Path folder) throws Exception {
		Files.writeString(folder.resolve("films.graphqls"), SDL);
	}

	/**
	 * An embedding model that scores a text by the schema words it carries, so the
	 * context test runs without a provider, a key or a service.
	 */
	private static final class WordEmbeddingModel implements EmbeddingModel {

		private static final List<String> WORDS = List.of("rating", "title", "country", "location", "movie", "similar",
				"score");

		@Override
		public float[] embed(String text) {
			float[] vector = new float[WORDS.size()];
			String lower = text.toLowerCase(Locale.ROOT);
			for (int index = 0; index < WORDS.size(); index++) {
				vector[index] = lower.contains(WORDS.get(index)) ? 1 : 0;
			}
			return vector;
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> results = new ArrayList<>();
			for (String text : request.getInstructions()) {
				results.add(new Embedding(embed(text), results.size()));
			}
			return new EmbeddingResponse(results);
		}

		@Override
		public int dimensions() {
			return WORDS.size();
		}

	}

}
