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

package io.gatool.boot.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.schema.GraphQLSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.core.NativeDetector;

import io.gatool.boot.internal.execution.ToolCallRunner;
import io.gatool.boot.internal.search.DynamicOperationSettings;
import io.gatool.boot.internal.search.DynamicOperationTools;
import io.gatool.core.internal.operation.OperationCatalogFactory;
import io.gatool.core.internal.operation.RootFieldOperations;
import io.gatool.core.internal.operation.ScopeNames;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.model.GATool;
import io.gatool.core.naming.ToolNamingStrategy;
import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.SchemaCorpus;
import io.gatool.core.search.SchemaSearch;

/**
 * Builds the tools that {@code gatool.dev.experimental.generate-tools} selects: a tool
 * per root field through the catalog's generation settings, or the three tools of the
 * dynamic layer.
 *
 * <p>
 * The methods log under the name of {@link GAToolAutoConfiguration}, so a logging level
 * an operator set for it covers these lines.
 *
 * @author Željko Kozina
 */
final class DynamicToolBuilding {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	private DynamicToolBuilding() {
	}

	/**
	 * Stops startup where {@code gatool.dev.experimental.generate-tools} selects the
	 * tools a model writes GraphQL through and the application runs as a native image.
	 * @param generate the value of the property
	 * @param nativeImage whether the application runs as a GraalVM native image
	 */
	// The three tools rank the schema with Lucene, and GraalVM's reachability metadata
	// for lucene-core ends at a version far older than the one GATool uses. Whether
	// they run in a native image is unmeasured, so startup stops there until a release
	// has run them. The tools of operation files and of root fields send a document
	// GATool holds, and they pass. The detector's answer is a parameter, because
	// Spring's NativeDetector reads a system property once, when its class loads, which
	// leaves a test without a way to change it.
	static void requireTheJvmForDynamicTools(ToolGeneration generate, boolean nativeImage) {
		if (generate.isDynamic() && nativeImage) {
			throw new InvalidConfigurationPropertyValueException("gatool.dev.experimental.generate-tools", generate,
					"The tools searchSchema, introspectType and executeGraphql run on the JVM in this "
							+ "release, and this application runs as a native image. Set the property to none and "
							+ "write operation files, or run the application on the JVM.");
		}
	}

	/**
	 * Returns the generation settings that {@code gatool.dev.experimental.generate-tools}
	 * selects, and refuses a selection depth GATool cannot serve.
	 * @param properties the GATool properties, which hold the switch
	 * @return the generation settings the switch selects
	 */
	// The switch that publishes the API's whole root to a model says so at every startup,
	// the way the other unsafe switches do, because a development setting that reaches
	// production quietly is the failure worth preventing.
	static OperationCatalogFactory.Generation resolveGeneration(GAToolProperties properties) {
		GAToolDevProperties.Experimental experimental = properties.getDev().getExperimental();
		ToolGeneration generate = experimental.getGenerateTools();
		requireTheJvmForDynamicTools(generate, NativeDetector.inNativeImage());
		if (!generate.generatesPerRootField()) {
			return OperationCatalogFactory.Generation.off();
		}
		int depth = experimental.getGeneratedSelectionDepth();
		if (depth < 1 || depth > RootFieldOperations.MAX_DEPTH) {
			throw new InvalidConfigurationPropertyValueException("gatool.dev.experimental.generated-selection-depth",
					depth, "GATool expands 1 to " + RootFieldOperations.MAX_DEPTH + " object levels. A selection set "
							+ "grows with the shape of the graph, so each level of a wide graph multiplies it.");
		}
		logger.warn("gatool.dev.experimental.generate-tools is " + generate + ", so every "
				+ (generate.includesMutations() ? "query and mutation" : "query") + " root field your schema declares"
				// The log names every tool it serves, and this sentence is read against
				// the schema, so without the clause it reads one root field short.
				+ (experimental.isGenerateToolsForDeprecatedRootFields() ? ""
						: ", except the deprecated ones, which "
								+ "gatool.dev.experimental.generate-tools-for-deprecated-root-fields leaves out,")
				+ " becomes a tool, expanded " + depth + " level" + ((depth == 1) ? "" : "s")
				+ " deep. That publishes the whole root of your API to a model. "
				+ "Write operation files for the ones you want and turn this off before you deploy.");
		return new OperationCatalogFactory.Generation(true, generate.includesMutations(),
				experimental.isGenerateToolsForDeprecatedRootFields(),
				experimental.isGeneratedOperationsIncludeDeprecatedFields(), depth);
	}

	/**
	 * Returns the operation tools with the dynamic tools behind them, and refuses an
	 * operation file whose tool takes a dynamic tool's name.
	 * @param operationTools the tools built from the operation files
	 * @param dynamicTools the dynamic tools the switch publishes
	 * @param operations the operations the operation tools came from, which name the
	 * files
	 * @return the operation tools followed by the dynamic tools
	 */
	// The file and the switch are both in hand here, so the refusal names the file to
	// change and the property that publishes the other tool. The names are compared as
	// published, so a naming strategy that spells the dynamic tools its own way still
	// meets the check.
	static List<GATool> withDynamicTools(List<GATool> operationTools, List<GATool> dynamicTools,
			List<ToolOperation> operations) {
		if (dynamicTools.isEmpty()) {
			return operationTools;
		}
		Set<String> dynamicToolNames = dynamicTools.stream()
			.map(GATool::name)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<String> problems = operations.stream()
			.filter((operation) -> dynamicToolNames.contains(operation.toolName()))
			.map((operation) -> operation.location() + " publishes the tool name '" + operation.toolName()
					+ "', which gatool.dev.experimental.generate-tools=dynamic-three-step"
					+ " gives to one of its three tools; name this file's tool with @gatool(name:), or set the "
					+ "property to none")
			.toList();
		if (!problems.isEmpty()) {
			throw new OperationFileProblemsException(problems);
		}
		List<GATool> tools = new ArrayList<>(operationTools);
		tools.addAll(dynamicTools);
		return List.copyOf(tools);
	}

	/**
	 * Returns the three tools of the dynamic layer, or an empty list where
	 * {@code generate-tools} selects another shape.
	 * @param properties the GATool properties
	 * @param schema the schema the tools search and run against
	 * @param runner runs each tool call
	 * @param namingStrategy names each tool
	 * @param classLoader the context's class loader, which the Lucene and the
	 * {@code EmbeddingModel} classes are looked up in
	 * @param beanFactory the bean factory, which the application's own
	 * {@link SchemaSearch} and {@code EmbeddingModel} beans are read through and the
	 * default ranking's index is registered for disposal with
	 * @return the three dynamic tools, or an empty list where the switch selects another
	 * shape
	 */
	// The dynamic layer publishes three fixed tools and does not generate a tool per
	// field, so it is the other value of the same switch. The property selects a tool per
	// root field or these three, never both, because the two answer the same question
	// twice.
	static List<GATool> buildDynamicTools(GAToolProperties properties, GraphQLSchema schema, ToolCallRunner runner,
			ToolNamingStrategy namingStrategy, @Nullable ClassLoader classLoader,
			ConfigurableListableBeanFactory beanFactory) {
		GAToolDevProperties.Experimental experimental = properties.getDev().getExperimental();
		if (experimental.getGenerateTools() != ToolGeneration.DYNAMIC_THREE_STEP) {
			return List.of();
		}
		GAToolDevProperties.Experimental.DynamicOperations settings = experimental.getDynamicOperations();
		int rankedHits = requireRankedHits(settings);
		List<String> dynamicScopes = requireValidDynamicScopes(settings.getRequiredScopes());
		// Built once: the default ranking indexes it, and the startup line reports its
		// size, the number that says whether this layer is worth its tokens. The mutation
		// switch reaches the corpus as well as executeGraphql, so a search offers a
		// mutation coordinate only while the tool would run the mutation.
		List<CorpusEntry> corpus = SchemaCorpus.of(schema, settings.getCorpusFormat(),
				settings.isIncludeDeprecatedFields(), settings.isAllowMutations());
		SchemaSearch search = chooseSearch(corpus, settings, classLoader, beanFactory);
		List<GATool> tools = DynamicOperationTools
			.of(schema, search, runner, namingStrategy,
					new DynamicOperationSettings(settings.getSearchTokenBudget(), rankedHits,
							settings.isAllowMutations(), settings.isIncludeDeprecatedFields(),
							settings.isValidateOnly(),
							new DynamicOperationSettings.Limits(settings.getMaxDepth(), settings.getMaxFields(),
									settings.getMaxAliases()),
							new DynamicOperationSettings.Descriptions(settings.getSearchSchemaDescription(),
									settings.getIntrospectTypeDescription(), settings.getExecuteGraphqlDescription())))
			.stream()
			.map((tool) -> withScopes(tool, dynamicScopes))
			.toList();
		logDynamicTools(tools, corpus, settings);
		return tools;
	}

	// Returns the number of coordinates a search ranks, and fails startup where it is
	// below one.
	private static int requireRankedHits(GAToolDevProperties.Experimental.DynamicOperations settings) {
		int rankedHits = settings.getRankedHits();
		if (rankedHits < 1) {
			throw new InvalidConfigurationPropertyValueException(
					"gatool.dev.experimental.dynamic-operations.ranked-hits", rankedHits,
					"A search ranks at least one coordinate. The token budget is what decides how many of them "
							+ "a model reads.");
		}
		return rankedHits;
	}

	// Returns the application's own SchemaSearch bean, or the default ranking built here,
	// which the context is told to dispose of.
	//
	// The default ranking holds a Lucene index, which is closeable, and it is built here
	// instead of as a bean, so the context is told how to dispose of it. An application's
	// own search is a bean, which Spring closes already, and a callback registered for it
	// as well would close it a second time.
	private static SchemaSearch chooseSearch(List<CorpusEntry> corpus,
			GAToolDevProperties.Experimental.DynamicOperations settings, @Nullable ClassLoader classLoader,
			ConfigurableListableBeanFactory beanFactory) {
		ObjectProvider<SchemaSearch> schemaSearch = beanFactory.getBeanProvider(SchemaSearch.class);
		ObjectProvider<EmbeddingModel> embeddingModel = SchemaSearchBuilding.embeddingModels(beanFactory, classLoader);
		SchemaSearch ownSearch = schemaSearch.getIfAvailable();
		SchemaSearch search = (ownSearch != null) ? ownSearch
				: SchemaSearchBuilding.buildDefaultSearch(corpus, settings, embeddingModel, classLoader);
		if (ownSearch == null && search instanceof AutoCloseable closeable
				&& beanFactory instanceof DefaultSingletonBeanRegistry registry) {
			registry.registerDisposableBean("gaToolSchemaSearch", closeable::close);
		}
		return search;
	}

	// Warns that a model can write GraphQL against the API, and names the three tools,
	// the size of the corpus and what one search returns.
	private static void logDynamicTools(List<GATool> tools, List<CorpusEntry> corpus,
			GAToolDevProperties.Experimental.DynamicOperations settings) {
		logger.warn("gatool.dev.experimental.generate-tools is dynamic-three-step, so a model "
				+ "can search your schema and run GraphQL it wrote against your API"
				+ (settings.isAllowMutations() ? ", mutations included, which nobody reviewed." : ".")
				+ " The trusted documents keep working beside it. Turn this off before you deploy.");
		// A budget below one takes every ranked hit, so the line says that outright. A
		// printed zero would read as an empty result while the tool returns the most it
		// ever does.
		int budget = settings.getSearchTokenBudget();
		String whatOneCallReturns = (budget < 1) ? "returning every ranked hit"
				: "returning up to " + budget + " tokens of results";
		logger.info("GATool publishes " + tools.stream().map(GATool::name).collect(Collectors.joining(", "))
				+ ", ranking " + corpus.size() + " schema coordinates " + "with " + settings.getSearchBackend()
				+ " and " + whatOneCallReturns + " per call.");
	}

	// The three tools share one scope list, because a caller who may run GraphQL it
	// wrote may search the schema for it as well, and the list is checked the way an
	// operation file's is.
	private static @Nullable List<String> requireValidDynamicScopes(@Nullable List<String> scopes) {
		if (scopes == null) {
			return null;
		}
		for (String scope : scopes) {
			String problem = ScopeNames.problemWith(scope);
			if (problem != null) {
				throw new InvalidConfigurationPropertyValueException(
						"gatool.dev.experimental.dynamic-operations.required-scopes", scope, "The scope " + problem
								+ ". A scope is printable ASCII without a space, a quote or a backslash.");
			}
		}
		return scopes;
	}

	private static GATool withScopes(GATool tool, @Nullable List<String> scopes) {
		return tool.toBuilder().scopes(scopes).build();
	}

}
