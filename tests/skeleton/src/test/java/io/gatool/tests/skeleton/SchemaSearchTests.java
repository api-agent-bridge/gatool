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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings of {@code searchSchema}: what a result is rendered as, how many hits are
 * ranked, how many tokens a call returns, and what the embedding backend sends to the
 * model and keeps on disk.
 *
 * <p>
 * The movie schema has 30 coordinates in the corpus by default: the mutation root and the
 * payload only it reaches join once {@code allow-mutations} is on, which makes 32. A
 * question made of the words every SDL entry carries, {@code on type}, matches all of
 * them, which is how a test sees a budget or a hit count cut the list.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaSearchTests {

	private static final String DYNAMIC = "gatool.dev.experimental.generate-tools=dynamic-three-step";

	private static final String PREFIX = "gatool.dev.experimental.dynamic-operations.";

	// A question that every SDL entry matches, because each one ends in "# on type X".
	private static final String EVERY_ENTRY = "on type";

	// Each hit opens with its coordinate on a line of its own, followed by a colon.
	private static final Pattern COORDINATE_LINE = Pattern.compile("(?m)^[A-Za-z]+\\.[A-Za-z]+:$");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/", DYNAMIC);

	@Test
	void searchSchema_tokenBudgetOfZero_shouldReturnEveryRankedHit() {
		this.contextRunner.withPropertyValues(PREFIX + "search-token-budget=0").run((context) -> {
			String text = search(context, EVERY_ENTRY).text();

			assertThat(coordinatesIn(text)).hasSize(30);
		});
	}

	@Test
	void searchSchema_allowMutationsOn_shouldIndexTheMutationRootAndWhatItReaches() {
		this.contextRunner.withPropertyValues(PREFIX + "search-token-budget=0", PREFIX + "allow-mutations=true")
			.run((context) -> {
				String text = search(context, EVERY_ENTRY).text();

				assertThat(coordinatesIn(text)).hasSize(32)
					.contains("Mutation.addReview:", "AddReviewResponse.review:");
			});
	}

	@Test
	void searchSchema_allowMutationsOff_shouldOfferNoCoordinateExecuteGraphqlWouldRefuse() {
		// The model wrote the mutation a search offered and read the refusal. The
		// question names the field, because StandardAnalyzer keeps addReview as one term.
		this.contextRunner.run((context) -> {
			String text = search(context, "addReview mutation").text();

			assertThat(text).doesNotContain("Mutation.addReview").doesNotContain("AddReviewResponse");
		});
	}

	@Test
	void searchSchema_aSmallTokenBudget_shouldStopBeforeTheHitThatWouldCrossIt() {
		// The shortest entry costs about ten tokens with its path, so a budget of 40
		// admits a few hits and refuses the rest, where zero admitted all 30.
		this.contextRunner.withPropertyValues(PREFIX + "search-token-budget=40").run((context) -> {
			String text = search(context, EVERY_ENTRY).text();

			assertThat(coordinatesIn(text)).isNotEmpty().hasSizeLessThan(10);
		});
	}

	@Test
	void searchSchema_aBudgetBelowEveryEntry_shouldNameTheBestMatchItsCostAndTheProperty() {
		// The walk admits a hit only when it fits, so a budget of one token, which is
		// below every hit, leaves the list empty. The answer names the best match and the
		// property, because the sentence an unmatched question gets would make the model
		// rephrase a question that had matched.
		this.contextRunner.withPropertyValues(PREFIX + "search-token-budget=1").run((context) -> {
			ToolCallOutcome outcome = search(context, EVERY_ENTRY);

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).matches("The best match, [A-Za-z]+\\.[A-Za-z]+, costs [0-9]+ tokens, and "
					+ "gatool\\.dev\\.experimental\\.dynamic-operations\\.search-token-budget allows 1\\. "
					+ "Read [A-Za-z]+ with introspectType, or raise the property\\.");
		});
	}

	@Test
	void searchSchema_rankedHitsOfOne_shouldReturnOneCoordinateWhateverTheBudget() {
		this.contextRunner.withPropertyValues(PREFIX + "ranked-hits=1", PREFIX + "search-token-budget=0")
			.run((context) -> {
				String text = search(context, EVERY_ENTRY).text();

				assertThat(coordinatesIn(text)).hasSize(1);
			});
	}

	@Test
	void searchSchema_corpusFormatSdl_shouldWriteTheFieldDeclarationWithItsOwnerInAComment() {
		this.contextRunner.run((context) -> {
			String text = search(context, "highest rated movies").text();

			assertThat(text).contains("Query.topRatedMovies:\n")
				.contains("\"\"\"Returns the highest-rated movies, best first.\"\"\"\n"
						+ "topRatedMovies(first: Int): [Movie!]!  # on type Query");
		});
	}

	@Test
	void searchSchema_corpusFormatGloss_shouldWriteOneSentencePerField() {
		this.contextRunner.withPropertyValues(PREFIX + "corpus-format=gloss").run((context) -> {
			String text = search(context, "highest rated movies").text();

			assertThat(text).contains("Query.topRatedMovies:\nGraphQL field Query.topRatedMovies. Owner type: Query. "
					+ "Returns: [Movie!]!. Returns the highest-rated movies, best first.");
		});
	}

	@Test
	void searchSchema_corpusFormatRaw_shouldWriteTheCoordinateAlone() {
		// Raw text holds the coordinate alone, so a question has to use the words of the
		// coordinate itself to match it.
		this.contextRunner.withPropertyValues(PREFIX + "corpus-format=raw").run((context) -> {
			String text = search(context, "Query.topRatedMovies").text();

			assertThat(text).startsWith("Query.topRatedMovies:\nQuery.topRatedMovies");
		});
	}

	@Test
	void searchSchema_aBlankQuestion_shouldAskForOne() {
		this.contextRunner.run((context) -> {
			ToolCallOutcome outcome = search(context, "   ");

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).startsWith("Ask for something: this tool takes a question in plain words");
		});
	}

	@Test
	void searchSchema_aQuestionMatchingNoEntry_shouldSayWhatToTryInstead() {
		this.contextRunner.run((context) -> {
			ToolCallOutcome outcome = search(context, "zxqv");

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).isEqualTo("Nothing in this schema matched \"zxqv\". Try the words the API "
					+ "would use for it, or a single noun such as \"films\" or \"reviews\".");
		});
	}

	@Test
	void searchSchema_embeddingBackend_shouldPrefixQuestionsAndDocumentsAndBatchTheCorpus() {
		RecordingEmbeddingModel model = new RecordingEmbeddingModel();

		this.contextRunner
			// Property binding trims a value, so the prefixes here end without a space.
			.withPropertyValues(PREFIX + "search-backend=embedding", PREFIX + "question-prefix=Q:",
					PREFIX + "field-prefix=D:", PREFIX + "embed-batch-size=7", PREFIX + "vector-cache-directory=")
			.withBean(EmbeddingModel.class, () -> model)
			.run((context) -> {
				assertThat(context).hasNotFailed();

				String text = search(context, "rating").text();

				assertThat(text).contains("Movie.rating");
				// The probe travels alone and without a prefix, because it identifies the
				// model for the cache key. The corpus then arrives in batches of the
				// configured size, 30 entries as 7, 7, 7, 7 and 2, each entry behind the
				// document prefix, and the question arrives last behind the query prefix.
				assertThat(model.batchSizes).containsExactly(1, 7, 7, 7, 7, 2, 1);
				assertThat(model.texts.getFirst()).isEqualTo("GATool schema navigation probe");
				assertThat(model.texts.subList(1, 31)).allSatisfy((entry) -> assertThat(entry).startsWith("D:"));
				assertThat(model.texts.getLast()).isEqualTo("Q:rating");
			});
	}

	@Test
	void startup_embeddingBackendWithACacheDirectory_shouldEmbedOnceAndReadTheCacheNextTime(@TempDir Path cache,
			CapturedOutput output) throws Exception {
		RecordingEmbeddingModel firstStartModel = new RecordingEmbeddingModel();
		RecordingEmbeddingModel secondStartModel = new RecordingEmbeddingModel();
		ApplicationContextRunner cachedRunner = this.contextRunner.withPropertyValues(
				PREFIX + "search-backend=embedding", PREFIX + "vector-cache-directory=" + cache.toAbsolutePath());

		cachedRunner.withBean(EmbeddingModel.class, () -> firstStartModel)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("GATool is embedding 30 schema coordinates")
			.contains("GATool cached them in " + cache.toAbsolutePath());
		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files.map((file) -> file.getFileName().toString())).singleElement()
				.satisfies((name) -> assertThat(name).endsWith(".vectors"));
		}

		cachedRunner.withBean(EmbeddingModel.class, () -> secondStartModel)
			.run((context) -> assertThat(context).hasNotFailed());

		// The second start sends the probe alone, which is the one call the cache key
		// needs, and reads the 30 vectors from disk.
		assertThat(output.getAll()).contains("GATool read 30 embedded schema coordinates from its cache.");
		assertThat(secondStartModel.batchSizes).containsExactly(1);
	}

	private static ToolCallOutcome search(AssertableApplicationContext context, String question) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> "searchSchema".equals(tool.name()))
			.findFirst()
			.map((tool) -> tool.call(Map.of("question", question)))
			.orElseThrow(() -> new AssertionError("no tool named searchSchema"));
	}

	private static List<String> coordinatesIn(String text) {
		List<String> coordinates = new ArrayList<>();
		Matcher matcher = COORDINATE_LINE.matcher(text);
		while (matcher.find()) {
			coordinates.add(matcher.group());
		}
		return coordinates;
	}

	/**
	 * An embedding model that records every request it receives and scores a text by the
	 * schema words it carries, so the tests run without a provider, a key or a service.
	 */
	private static final class RecordingEmbeddingModel implements EmbeddingModel {

		private static final List<String> WORDS = List.of("rating", "title", "country", "location", "movie", "review",
				"score");

		private final List<Integer> batchSizes = new ArrayList<>();

		private final List<String> texts = new ArrayList<>();

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			this.batchSizes.add(request.getInstructions().size());
			List<Embedding> results = new ArrayList<>();
			for (String text : request.getInstructions()) {
				this.texts.add(text);
				results.add(new Embedding(vectorOf(text), results.size()));
			}
			return new EmbeddingResponse(results);
		}

		@Override
		public int dimensions() {
			return WORDS.size();
		}

		private static float[] vectorOf(String text) {
			float[] vector = new float[WORDS.size()];
			String lower = text.toLowerCase(Locale.ROOT);
			for (int index = 0; index < WORDS.size(); index++) {
				vector[index] = lower.contains(WORDS.get(index)) ? 1 : 0;
			}
			return vector;
		}

	}

}
