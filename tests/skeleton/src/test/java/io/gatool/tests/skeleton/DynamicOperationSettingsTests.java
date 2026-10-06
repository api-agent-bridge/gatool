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

import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.search.SchemaSearch;
import io.gatool.core.search.SearchHit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings that decide, at startup, which of the three dynamic tools exist and how
 * they describe themselves. They are the switch itself, the mutation switch, the
 * validate-only switch and the ranking behind {@code searchSchema}, and the tests also
 * read what startup logs while the layer is on.
 *
 * <p>
 * Every context here runs the MCP auto-configuration beside the schema side, the way an
 * application that adds the MCP starter does, so the startup lines are the ones an
 * operator reads.
 */
@ExtendWith(OutputCaptureExtension.class)
class DynamicOperationSettingsTests {

	private static final String DYNAMIC = "gatool.dev.experimental.generate-tools=dynamic-three-step";

	private static final String PREFIX = "gatool.dev.experimental.dynamic-operations.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		// The API is first reached inside a tool call, so a closed port serves every
		// test in this class.
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_generateToolsLeftAtItsDefault_shouldPublishNoneOfTheThreeTools(CapturedOutput output) {
		this.contextRunner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(names(context)).doesNotContain("searchSchema", "introspectType", "executeGraphql");
		});

		// The warning belongs to the switch, so a startup with the switch off stays
		// quiet.
		assertThat(output.getAll()).doesNotContain("can search your schema");
	}

	@Test
	void startup_aSearchTokenBudgetBelowOne_shouldSayEveryRankedHitIsReturned(CapturedOutput output) {
		// A budget below one takes every ranked hit, which is the mode a team switches
		// the bound off with. The startup line says so, because printing the number would
		// make zero read as an empty result while the tool returns the most it ever does.
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "search-token-budget=0")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("returning every ranked hit").doesNotContain("returning up to 0 tokens");
	}

	@Test
	void startup_dynamicThreeStep_shouldWarnThatAModelRunsGraphQlItWrote(CapturedOutput output) {
		this.contextRunner.withPropertyValues(DYNAMIC).run((context) -> assertThat(context).hasNotFailed());

		// The warning names the property, says what a model can now do, and says to turn
		// it off before deploying, on every boot while it is on.
		assertThat(output.getAll()).contains("gatool.dev.experimental.generate-tools is dynamic-three-step")
			.contains("can search your schema and run GraphQL it wrote against your API.")
			.contains("Turn this off before you deploy.");
	}

	@Test
	void startup_dynamicThreeStep_shouldNameTheToolsTheRankingAndTheBudget(CapturedOutput output) {
		this.contextRunner.withPropertyValues(DYNAMIC).run((context) -> assertThat(context).hasNotFailed());

		// The corpus size is the number that says whether the layer is worth its tokens.
		// The movie schema declares 33 field coordinates a query or a mutation reaches,
		// with the subscription root and everything behind it left out. The deprecated
		// Query.allMovies stays out by default, and so do Mutation.addReview and the
		// AddReviewResponse.review only it reaches while mutations are off, which leaves
		// 30.
		assertThat(output.getAll())
			.contains("GATool publishes searchSchema, introspectType, executeGraphql, ranking 30 schema coordinates "
					+ "with BM25 and returning up to 2000 tokens of results per call.");
	}

	@Test
	void startup_allowMutationsOn_shouldRankTheMutationRootAndWhatItReachesToo(CapturedOutput output) {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "allow-mutations=true")
			.run((context) -> assertThat(context).hasNotFailed());

		// The two coordinates the switch adds back: the mutation and its payload.
		assertThat(output.getAll()).contains("ranking 32 schema coordinates with BM25");
	}

	@Test
	void startup_allowMutationsOn_shouldWarnThatMutationsNobodyReviewedAreIncluded(CapturedOutput output) {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "allow-mutations=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("mutations included, which nobody reviewed.");
	}

	@Test
	void executeGraphql_allowMutationsOff_shouldSayQueriesOnlyAndReadOnly() {
		this.contextRunner.withPropertyValues(DYNAMIC).run((context) -> {
			GATool execute = tool(context, "executeGraphql");

			// The description is where the rule reaches the model, because GATool leaves
			// the system prompt to the application, and the read-only flag becomes
			// readOnlyHint on the wire.
			assertThat(execute.description()).startsWith("Executes a GraphQL document against the API.")
				.contains("Queries only: a mutation is refused.");
			assertThat(execute.readOnly()).isTrue();
		});
	}

	@Test
	void executeGraphql_allowMutationsOn_shouldDropTheQueriesOnlySentenceAndStopBeingReadOnly() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "allow-mutations=true").run((context) -> {
			GATool execute = tool(context, "executeGraphql");

			assertThat(execute.description()).doesNotContain("Queries only");
			assertThat(execute.readOnly()).isFalse();
		});
	}

	@Test
	void descriptions_atTheDefaults_shouldNameTheOrderOfTheThreeTools() {
		this.contextRunner.withPropertyValues(DYNAMIC).run((context) -> {
			assertThat(tool(context, "introspectType").description())
				.startsWith("Returns the details of one type that searchSchema returned");
			assertThat(tool(context, "executeGraphql").description())
				.contains("Always call searchSchema first and executeGraphql after that.")
				.contains("call introspectType on it before executeGraphql.")
				.endsWith("Do not try to guess a valid operation.");
		});
	}

	@Test
	void descriptions_setThroughTheProperties_shouldReplaceTheWholeText() {
		this.contextRunner
			.withPropertyValues(DYNAMIC, PREFIX + "search-schema-description=Search text.",
					PREFIX + "introspect-type-description=Introspect text.",
					PREFIX + "execute-graphql-description=Execute text.")
			.run((context) -> {
				assertThat(tool(context, "searchSchema").description()).isEqualTo("Search text.");
				assertThat(tool(context, "introspectType").description()).isEqualTo("Introspect text.");
				assertThat(tool(context, "executeGraphql").description()).isEqualTo("Execute text.");
			});
	}

	@Test
	void descriptions_aBlankProperty_shouldKeepTheTextGAToolWrites() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "search-schema-description= ")
			.run((context) -> assertThat(tool(context, "searchSchema").description())
				.startsWith("Finds the fields of the GraphQL schema"));
	}

	@Test
	void executeGraphql_validateOnlyWithMutationsOn_shouldStayReadOnlyAndSayAPersonRunsIt() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "allow-mutations=true", PREFIX + "validate-only=true")
			.run((context) -> {
				GATool execute = tool(context, "executeGraphql");

				// In validate-only mode the tool does not call the API, so it only reads
				// whatever the mutation switch says, and its description tells the model
				// to expect the operation back, since the data it wrote for does not
				// arrive.
				assertThat(execute.description())
					.contains("returns it with its variables for a person to run, without calling the API");
				assertThat(execute.readOnly()).isTrue();
			});
	}

	@Test
	void theTwoReadingTools_allowMutationsOn_shouldStayReadOnly() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "allow-mutations=true").run((context) -> {
			assertThat(tool(context, "searchSchema").readOnly()).isTrue();
			assertThat(tool(context, "introspectType").readOnly()).isTrue();
		});
	}

	@Test
	void startup_luceneMissingFromTheClassLoader_shouldStopNamingTheSwitchAndTheJar() {
		// FilteredClassLoader hides a class from the context, which is how the slice
		// tests take a dependency off the classpath. The Lucene check reads that same
		// class loader, the one Spring Boot's own class conditions read, so a
		// deployment that left lucene-core out meets this message at startup.
		this.contextRunner.withClassLoader(new FilteredClassLoader(IndexSearcher.class))
			.withPropertyValues(DYNAMIC)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.dev.experimental.generate-tools")
					.hasMessageContaining("org.apache.lucene:lucene-core is missing from the classpath")
					.hasMessageContaining("publish a SchemaSearch bean of your own");
			});
	}

	@Test
	void startup_luceneAnalysisMissingFromTheClassLoader_shouldStopNamingTheSwitchAndTheJar() {
		// The word splitting and the stemming live in lucene-analysis-common, and a
		// deployment that added lucene-core alone would otherwise start and then fail the
		// first search with a NoClassDefFoundError.
		this.contextRunner.withClassLoader(new FilteredClassLoader(WordDelimiterGraphFilter.class))
			.withPropertyValues(DYNAMIC)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.dev.experimental.generate-tools")
					.hasMessageContaining("org.apache.lucene:lucene-analysis-common is missing from the classpath")
					.hasMessageContaining("publish a SchemaSearch bean of your own");
			});
	}

	@Test
	void startup_luceneMissingButAnOwnSchemaSearchBean_shouldStart() {
		// The refusal names the bean as the third way out, so a context without
		// lucene-core and with the bean starts and ranks with the bean.
		SchemaSearch fixed = (question, maxHits) -> List.of();

		this.contextRunner.withClassLoader(new FilteredClassLoader(IndexSearcher.class))
			.withPropertyValues(DYNAMIC)
			.withBean(SchemaSearch.class, () -> fixed)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(names(context)).contains("searchSchema");
			});
	}

	@Test
	void searchSchema_anApplicationsOwnSchemaSearchBean_shouldRankWithItInsteadOfLucene(CapturedOutput output) {
		// An application replaces the ranking the way it replaces the executor: with a
		// bean of the public interface. The stand-in answers every question with one
		// fixed coordinate, so a result carrying it came from the bean.
		SchemaSearch fixed = (question, maxHits) -> List.of(new SearchHit("Movie.title", 1.0,
				"title: String!  # on type Movie", "Query.topRatedMovies -> Movie", 12));

		this.contextRunner.withPropertyValues(DYNAMIC).withBean(SchemaSearch.class, () -> fixed).run((context) -> {
			assertThat(context).hasNotFailed();

			String text = tool(context, "searchSchema").call(Map.of("question", "anything at all")).text();

			assertThat(text).isEqualTo(
					"Movie.title:\nreach it from Query.topRatedMovies -> Movie\n" + "title: String!  # on type Movie");
		});

		// The startup line still names the configured backend, because the property was
		// left at its default even though the bean took over.
		assertThat(output.getAll()).contains("with BM25");
	}

	@Test
	void startup_rankedHitsBelowOne_shouldStopNamingThePropertyAndTheBudget() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "ranked-hits=0").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining(PREFIX + "ranked-hits")
				.hasMessageContaining("token budget");
		});
	}

	@Test
	void startup_embeddingBackendWithoutAModelBean_shouldStopNamingWhatToAdd() {
		this.contextRunner.withPropertyValues(DYNAMIC, PREFIX + "search-backend=embedding").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining(PREFIX + "search-backend")
				.hasMessageContaining("spring-ai-starter-model-ollama")
				.hasMessageContaining("bm25");
		});
	}

	private static List<String> names(AssertableApplicationContext context) {
		return context.getBean(GAToolCatalog.class).mcpTools().stream().map(GATool::name).toList();
	}

	private static GATool tool(AssertableApplicationContext context, String name) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> name.equals(tool.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no tool named " + name));
	}

}
