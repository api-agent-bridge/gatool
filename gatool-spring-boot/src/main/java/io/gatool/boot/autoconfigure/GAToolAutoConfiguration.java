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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.internal.execution.EmbeddedGraphQlExecutor;
import io.gatool.boot.internal.execution.GraphQlResultWriter;
import io.gatool.boot.internal.execution.RemoteGraphQlExecutor;
import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.boot.internal.execution.ToolCallRunner;
import io.gatool.boot.internal.operation.OperationLocationReader;
import io.gatool.boot.internal.operation.OperationSources;
import io.gatool.boot.internal.schema.SchemaUrlReader;
import io.gatool.core.internal.operation.OperationCatalog;
import io.gatool.core.internal.operation.OperationCatalogFactory;
import io.gatool.core.internal.operation.OperationProblem;
import io.gatool.core.internal.operation.OperationWarning;
import io.gatool.core.internal.operation.SchemaAddition;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.model.GATool;
import io.gatool.core.model.RequestLimits;
import io.gatool.core.naming.ToolNamingStrategy;

/**
 * Reads the schema and the operation files, and publishes the executor, the call runner
 * and the {@link GAToolCatalog} bean.
 *
 * <p>
 * The property {@code gatool.api.url} selects the executor. A configured URL runs remote
 * mode, and an application that leaves it unset runs embedded mode on its own Spring for
 * GraphQL beans. The schema follows the same shape, arriving from
 * {@code gatool.api.schema.location} or from the application's {@link GraphQlSource}.
 * Startup stops unless one of the two ways is open, through
 * {@link InvalidConfigurationPropertyValueException}, so Boot's own
 * {@code InvalidConfigurationPropertyValueFailureAnalyzer} names the property and the
 * message names both ways.
 *
 * <p>
 * The ordering names Boot's GraphQL auto-configurations as text, because
 * {@code spring-boot-graphql} stays off this module's compile classpath. An application
 * that runs embedded mode brings that module itself, and an application that runs remote
 * mode has a GraphQL API somewhere else.
 *
 * <p>
 * An application changes GATool's behaviour by publishing beans of its own. A
 * {@link GraphQlExecutor} bean, part of the public API, decides how a call runs. Because
 * {@link GraphQlResultWriter} writes from whatever executor ran, that same bean also
 * decides the result text. A {@code ToolRateLimiter} bean decides what a call costs. A
 * {@link ToolNamingStrategy} bean names every tool in place of the strategy
 * {@code gatool.naming.strategy} selects.
 *
 * <p>
 * The checks, the messages and the startup lines the bean methods share live in
 * package-private helper classes beside this one: {@link RemoteApiChecks},
 * {@link CredentialChecks}, {@link SchemaLoading}, {@link DynamicToolBuilding},
 * {@link SchemaSearchBuilding}, {@link StartupLines} and {@link SpringVersionChecks}.
 * Every one of them logs under this class's name.
 *
 * @author Željko Kozina
 */
@AutoConfiguration(after = RestClientAutoConfiguration.class,
		afterName = { "org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration",
				"org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration" })
// RestClient is in the condition because the bean methods below name it, and the JVM
// resolves every type a bean method names before Boot's method conditions apply.
// The two optional jars the bean methods would otherwise name, spring-boot-http-client
// and spring-ai-model, stay out of it: their types are reached through the nested
// configuration and the bean factory instead, so a build without either jar starts and
// is told at startup, naming the property, when it configures something that needs
// them. Both starters bring both jars, so only a project that depends on this module
// directly meets that.
@ConditionalOnClass({ GraphQL.class, HttpSyncGraphQlClient.class, RestClient.class })
@EnableConfigurationProperties(GAToolProperties.class)
public final class GAToolAutoConfiguration {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	static final String EMBEDDING_MODEL = "org.springframework.ai.embedding.EmbeddingModel";

	private static final String HTTP_COMPONENTS_BUILDER = "org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder";

	// The class is named by a string because spring-boot-http-client is optional here,
	// and its auto-configuration is a class this module reads from a bean definition
	// alone.
	private static final String BOOT_HTTP_CLIENT_AUTO_CONFIGURATION = "org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration";

	/**
	 * Creates the auto-configuration, which Spring Boot instantiates at startup.
	 */
	public GAToolAutoConfiguration() {
		// The beans come from the methods below; Spring Boot instantiates the class.
	}

	/**
	 * Publishes the executor that {@code gatool.api.url} selects.
	 *
	 * <p>
	 * A configured URL wins, so an application that serves its own GraphQL API and points
	 * GATool at a second one reaches the second one. Without the URL, the application's
	 * own {@link WebGraphQlHandler} runs the document, and an application without that
	 * bean gets a handler built from its {@link ExecutionGraphQlService}, which carries
	 * the same engine and the same {@link WebGraphQlInterceptor} beans.
	 * <p>
	 * Remote mode builds its client from Boot's {@code RestClient.Builder} through
	 * {@link RemoteHttpClients}, so the application's customizers, observations and SSL
	 * apply. A request factory an application installs through a
	 * {@code RestClientCustomizer} is replaced by the one built from the application's
	 * {@code ClientHttpRequestFactoryBuilder} and {@code HttpClientSettings}; the
	 * customizer's other settings stay.
	 * @param properties the GATool properties
	 * @param httpClients the clients built on the application's HTTP client settings,
	 * which the nested configuration publishes while {@code spring-boot-http-client} is
	 * on the classpath
	 * @param jsonMapper boot's mapper, which remote mode copies so that an explicit null
	 * survives the trip
	 * @param webGraphQlHandler the application's handler, when it has one
	 * @param executionGraphQlService the application's execution service, when it has one
	 * @param interceptors the application's interceptors, which the handler built here
	 * runs and which startup names
	 * @param credential the {@link ApiCredentialStrategy} bean, when the context holds
	 * one: the application's own, or the one the credentials auto-configuration built
	 * from {@code gatool.api.credentials.strategy}
	 * @return the executor behind every tool call
	 */
	// The executor, the writer and the runner below exist to serve the catalog GATool
	// builds, so all three back off for an application's own GAToolCatalog, whose tools
	// carry handlers of their own. Without that, this method would stop such an
	// application asking for gatool.api.url, a URL that would go uncalled. The condition
	// reads the bean definitions registered so far, and the three methods are declared
	// ahead of gaToolCatalog, so GATool's own catalog is still unregistered when they are
	// read.
	@Bean
	@ConditionalOnMissingBean({ GraphQlExecutor.class, GAToolCatalog.class })
	GraphQlExecutor gaToolGraphQlExecutor(GAToolProperties properties, ObjectProvider<RemoteHttpClients> httpClients,
			ObjectProvider<JsonMapper> jsonMapper, ObjectProvider<WebGraphQlHandler> webGraphQlHandler,
			ObjectProvider<ExecutionGraphQlService> executionGraphQlService,
			ObjectProvider<WebGraphQlInterceptor> interceptors, ObjectProvider<ApiCredentialStrategy> credential) {
		String url = properties.getApi().getUrl();
		if (url != null) {
			RemoteApiChecks.requireAbsoluteApiUrl(url);
			// The URL may carry a password in its userinfo or a key in its query, the way
			// a registry URL does, so every line and every message names it the way the
			// schema fetch names its own, without either.
			String printableUrl = SchemaUrlReader.printable(url);
			RemoteHttpClients clients = RemoteApiChecks.requireHttpClients(httpClients, "gatool.api.url", printableUrl);
			StartupLines.reportDeadlines("sends each document to the GraphQL API at " + printableUrl, clients);
			DataSize maxResponseSize = RemoteApiChecks
				.requirePositiveResponseSize(properties.getApi().getMaxResponseSize());
			RestClient.Builder builder = clients.apiClientBuilder(maxResponseSize);
			if (builder == null) {
				throw new InvalidConfigurationPropertyValueException("gatool.api.url", url,
						"GATool builds the client for this URL from Spring Boot's RestClient.Builder bean, which "
								+ "RestClientAutoConfiguration publishes and this application does not have. "
								+ "Publish a RestClient.Builder bean, or a GraphQlExecutor bean of your own.");
			}
			JsonMapper nullKeepingMapper = RemoteApiChecks
				.nullKeepingMapper(jsonMapper.getIfAvailable(JsonMapper::new));
			return new RemoteGraphQlExecutor(RemoteApiChecks.withNullKeepingConverter(builder, nullKeepingMapper), url,
					CredentialChecks.chooseCredentialStrategy(properties, credential), nullKeepingMapper);
		}
		WebGraphQlHandler handler = webGraphQlHandler.getIfAvailable();
		if (handler != null) {
			StartupLines.logEmbeddedMode(interceptors, false);
			return new EmbeddedGraphQlExecutor(handler);
		}
		ExecutionGraphQlService service = executionGraphQlService.getIfAvailable();
		if (service != null) {
			// The handler built here carries the interceptor beans the way Boot's own
			// handler bean does, so a gate an interceptor puts in front of every request
			// sits in front of every tool call in a non-web application as well.
			StartupLines.logEmbeddedMode(interceptors, true);
			return new EmbeddedGraphQlExecutor(
					WebGraphQlHandler.builder(service).interceptors(interceptors.orderedStream().toList()).build());
		}
		throw new InvalidConfigurationPropertyValueException("gatool.api.url", null,
				"GATool runs every tool call in one of two ways. Set this property to the URL of a GraphQL API, "
						+ "such as https://movies.example.com/graphql, or add Spring for GraphQL to this "
						+ "application, and GATool runs each document inside it.");
	}

	/**
	 * Publishes the writer that turns a GraphQL response into tool result text.
	 *
	 * <p>
	 * {@link GraphQlResultWriter} lives in an internal package, and this method leaves a
	 * condition on its own type off, so the class stays free to change. It backs off for
	 * an application's own {@code GAToolCatalog}, as the executor does.
	 * @param jsonMapper the mapper the application publishes, when it has one
	 * @return the writer behind every tool result
	 */
	@Bean
	@ConditionalOnMissingBean(GAToolCatalog.class)
	GraphQlResultWriter gaToolGraphQlResultWriter(ObjectProvider<JsonMapper> jsonMapper) {
		return new GraphQlResultWriter(jsonMapper.getIfAvailable(JsonMapper::new));
	}

	/**
	 * Publishes the runner that executes one tool call for both adapters.
	 *
	 * <p>
	 * {@link ToolCallRunner} lives in an internal package and carries the same reasoning
	 * as the writer above.
	 * @param properties the configuration under {@code GATool}
	 * @param executor runs each document
	 * @param writer writes each result
	 * @param httpClients the clients of remote mode, whose read deadline the runner names
	 * to the model when a query outlasts it
	 * @return the runner that MCP and in-process share
	 */
	@Bean
	@ConditionalOnMissingBean(GAToolCatalog.class)
	ToolCallRunner gaToolCallRunner(GAToolProperties properties, GraphQlExecutor executor, GraphQlResultWriter writer,
			ObjectProvider<RemoteHttpClients> httpClients) {
		int maxCharacters = properties.getResults().getMaxCharacters();
		// A cap of zero or less would answer every call with the size error, so the
		// application would serve tools that cannot return anything.
		if (maxCharacters < 1) {
			throw new InvalidConfigurationPropertyValueException("gatool.results.max-characters", maxCharacters,
					"A tool returns at least one character, so this limit is a positive number.");
		}
		if (executor instanceof RemoteGraphQlExecutor) {
			RemoteApiChecks.warnWhenTheResponseCapIsBelowTheResultLimit(properties.getApi().getMaxResponseSize(),
					maxCharacters);
		}
		return ToolCallRunner.builder(executor, writer)
			.maxCharacters(maxCharacters)
			.nullInputs(properties.getInputs().isSendExplicitNulls() ? ToolCallRunner.NullInputs.SEND
					: ToolCallRunner.NullInputs.OMIT)
			.partialResults(properties.getResults().isPartialResultsAsSuccess()
					? ToolCallRunner.PartialResults.AS_SUCCESS : ToolCallRunner.PartialResults.AS_ERROR)
			.redaction(CredentialChecks.credentialRedaction(properties))
			.readTimeout(RemoteApiChecks.readTimeoutBehind(executor, httpClients))
			.build();
	}

	/**
	 * Publishes the built-in strategy that {@code gatool.naming.strategy} selects.
	 *
	 * <p>
	 * The switch expression stays exhaustive over the enum, so a strategy added later
	 * fails to compile until this method handles it.
	 * @param properties the GATool properties
	 * @return the strategy that names every tool
	 */
	@Bean
	@ConditionalOnMissingBean
	ToolNamingStrategy gaToolNamingStrategy(GAToolProperties properties) {
		return switch (properties.getNaming().getStrategy()) {
			case CAMEL_CASE -> ToolNamingStrategy.camelCase();
			case SNAKE_CASE -> ToolNamingStrategy.snakeCase();
			case AS_WRITTEN -> ToolNamingStrategy.asWritten();
		};
	}

	/**
	 * Keeps the tool catalog eager under {@code spring.main.lazy-initialization=true}.
	 *
	 * <p>
	 * Building the catalog is what reads the schema and validates every operation file.
	 * The MCP module keeps Spring AI's server bean eager, and the catalog is built as its
	 * dependency. An application on the in-process side alone would have a lazy catalog,
	 * so it would start with a broken operation file and fail the first chat request
	 * inside a bean creation, outside Spring Boot's failure analysis. The method is
	 * static, as Boot asks for a filter that runs this early.
	 * @return the filter that keeps the catalog out of lazy initialization
	 */
	@Bean
	static LazyInitializationExcludeFilter gaToolCatalogLazyInitializationExcludeFilter() {
		return LazyInitializationExcludeFilter.forBeanTypes(GAToolCatalog.class);
	}

	/**
	 * Reads the schema and the operation files and publishes the catalog both adapters
	 * read.
	 * @param properties the GATool properties
	 * @param resourcePatternResolver resolves the operation locations
	 * @param runner runs each tool call
	 * @param namingStrategy names each tool
	 * @param graphQlSource the application's own schema, when it serves one
	 * @param httpClients the clients a schema URL is fetched with, which the nested
	 * configuration publishes while {@code spring-boot-http-client} is on the classpath
	 * @param beanFactory the bean factory, which this method reads the
	 * {@code SchemaAddition} beans of the adapters through, and which the dynamic layer
	 * reads the application's own {@code SchemaSearch} and {@code EmbeddingModel} beans
	 * through and registers its index for disposal with. The additions are what the
	 * adapter of each exposure type adds to a schema it publishes, which the token
	 * estimates of the listing and of the warning about a large tool count
	 * @return the catalog
	 */
	// The embedding model is looked up through the bean factory by class name, so
	// this signature stays free of spring-ai-model, which is optional here. The
	// SchemaSearch and SchemaAddition beans are read through the same bean factory,
	// with the lookups an ObjectProvider parameter makes, so the method stays within
	// the parameter limit.
	@Bean
	@ConditionalOnMissingBean
	GAToolCatalog gaToolCatalog(GAToolProperties properties, ResourcePatternResolver resourcePatternResolver,
			ToolCallRunner runner, ToolNamingStrategy namingStrategy, ObjectProvider<GraphQlSource> graphQlSource,
			ObjectProvider<RemoteHttpClients> httpClients, ConfigurableListableBeanFactory beanFactory) {
		SchemaLoading.LoadedSchema loaded = SchemaLoading.readSchema(properties, graphQlSource, httpClients);
		GraphQLSchema schema = loaded.schema();
		// One reader call covers both exposure types, so a file that both exposure types
		// reach loads once and every problem of every file arrives in one failure.
		Map<ToolExposureType, List<String>> locations = new EnumMap<>(ToolExposureType.class);
		locations.put(ToolExposureType.MCP, properties.getMcp().getOperations().getLocations());
		locations.put(ToolExposureType.IN_PROCESS, properties.getInProcess().getOperations().getLocations());
		// A developer who turned either shape on does not have operation files yet, which
		// is the point of both, so an empty location stops being a problem while one is
		// on.
		OperationCatalogFactory.Generation generation = DynamicToolBuilding.resolveGeneration(properties);
		boolean toolsWithoutFiles = generation.enabled()
				|| properties.getDev().getExperimental().getGenerateTools() == ToolGeneration.DYNAMIC_THREE_STEP;
		OperationSources documents = new OperationLocationReader(resourcePatternResolver).read(locations,
				toolsWithoutFiles);
		// Read once, so the warning the catalog raises and the two listings below count
		// the same additions.
		List<SchemaAddition> additions = beanFactory.getBeanProvider(SchemaAddition.class).orderedStream().toList();
		OperationCatalog catalog = buildOperationCatalog(properties, schema, namingStrategy, generation, additions,
				documents);
		// A schema fetched from a registry reaches the copy on disk here, once every
		// operation file validated against it, so a fetch that broke an operation
		// leaves the last copy that validated for the day the registry is down.
		loaded.commit();
		for (OperationWarning warning : catalog.warnings()) {
			logger.warn(warning.toString());
		}
		// A stdio server checks a tool's own scopes against
		// gatool.mcp.stdio.granted-scopes
		// and leaves the baseline unread, so the listing names the baseline over HTTP
		// alone.
		List<String> baselineScopes = SpringAiMcpKeys.servesStdio(beanFactory.getBean(Environment.class))
				? List.<String>of() : properties.getMcp().getSecurity().getBaselineScopes();
		StartupLines.logTools(ToolExposureType.MCP, catalog.toolsFor(ToolExposureType.MCP),
				locations.get(ToolExposureType.MCP), baselineScopes, additions);
		StartupLines.logTools(ToolExposureType.IN_PROCESS, catalog.toolsFor(ToolExposureType.IN_PROCESS),
				locations.get(ToolExposureType.IN_PROCESS), List.of(), additions);
		List<GATool> mcpTools = catalog.toolsFor(ToolExposureType.MCP).stream().map(runner::toolFor).toList();
		List<GATool> inProcessTools = catalog.toolsFor(ToolExposureType.IN_PROCESS)
			.stream()
			.map(runner::toolFor)
			.toList();
		// The three tools of the dynamic layer do not have an operation file or an
		// exposure type of their own, so both adapters publish them and they arrive on
		// whichever starter an application runs. They join the tools from trusted
		// documents.
		List<GATool> dynamicTools = DynamicToolBuilding.buildDynamicTools(properties, schema, runner, namingStrategy,
				resourcePatternResolver.getClassLoader(), beanFactory);
		return GAToolCatalog.builder()
			.mcpTools(DynamicToolBuilding.withDynamicTools(mcpTools, dynamicTools,
					catalog.toolsFor(ToolExposureType.MCP)))
			.inProcessTools(DynamicToolBuilding.withDynamicTools(inProcessTools, dynamicTools,
					catalog.toolsFor(ToolExposureType.IN_PROCESS)))
			.build();
	}

	// Builds the catalog of the operation files against the schema, and stops startup
	// with every problem of every file where one of them fails.
	private static OperationCatalog buildOperationCatalog(GAToolProperties properties, GraphQLSchema schema,
			ToolNamingStrategy namingStrategy, OperationCatalogFactory.Generation generation,
			List<SchemaAddition> additions, OperationSources documents) {
		OperationCatalog catalog = OperationCatalogFactory.builder(schema, namingStrategy)
			.outputSchemaByDefault(properties.getResults().isPublishOutputSchema())
			.scalarSchemas(properties.getInputs().getScalarSchemas())
			.resultScalarSchemas(properties.getResults().getScalarSchemas())
			.requestLimits(requestLimitsOf(properties))
			.generation(generation)
			.schemaAdditions(additions)
			.build()
			.create(documents.sources());
		stopOnProblems(documents.problems(), catalog.problems());
		return catalog;
	}

	/**
	 * Warns at startup where this application runs Spring Boot or Spring AI outside the
	 * line this release was tested on.
	 * @return the singleton that reads both versions once every other singleton is ready
	 * and reports them
	 */
	// A SmartInitializingSingleton runs after every other bean is ready, and this check
	// does not depend on any of them, so it is the simplest place for a line that belongs
	// to the whole application rather than to one bean.
	@Bean
	SmartInitializingSingleton gaToolUntestedSpringVersionsWarning() {
		return () -> SpringVersionChecks.warnWhereSpringVersionsAreUntested(SpringBootVersion.getVersion(),
				SpringVersionChecks.springAiVersion());
	}

	// The limits the application expects of its API, in the form the catalog compares
	// each document with.
	private static RequestLimits requestLimitsOf(GAToolProperties properties) {
		GAToolApiProperties.RequestLimits limits = properties.getApi().getRequestLimits();
		return new RequestLimits(limits.getMaxCharacters(), limits.getMaxTokens(), limits.getMaxWhitespaceTokens());
	}

	private static void stopOnProblems(List<OperationProblem> locationProblems,
			List<OperationProblem> catalogueProblems) {
		List<OperationProblem> problems = new ArrayList<>(locationProblems);
		problems.addAll(catalogueProblems);
		if (problems.isEmpty()) {
			return;
		}
		throw new OperationFileProblemsException(problems.stream().map(OperationProblem::toString).toList());
	}

	/**
	 * Publishes the HTTP clients of remote mode and the schema fetch while
	 * {@code spring-boot-http-client} is on the classpath.
	 *
	 * <p>
	 * The three types this bean method names are optional in this module, and the JVM
	 * resolves every type a bean method names before a method condition applies. The
	 * condition sits on this nested class, so the outer bean methods name
	 * {@link RemoteHttpClients} alone and read this bean through an
	 * {@code ObjectProvider}; where the jar is missing, they stop startup naming the
	 * property that needs it.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ HttpClientSettings.class, ClientHttpRequestFactoryBuilder.class })
	static class HttpClients {

		/**
		 * Builds the clients on the application's settings, and says at startup which
		 * request factory builder and which {@code RestClientCustomizer} beans reach
		 * them.
		 * @param restClientBuilder boot's builder, which a reactive application leaves
		 * out
		 * @param httpClientSettings the settings the application configured under
		 * {@code spring.http.clients}
		 * @param requestFactoryBuilder the application's own request factory builder, or
		 * the one Boot detects for the classpath
		 * @param beanFactory the bean factory, which names the customizer beans
		 * @return the clients
		 */
		@Bean
		RemoteHttpClients gaToolRemoteHttpClients(ObjectProvider<RestClient.Builder> restClientBuilder,
				ObjectProvider<HttpClientSettings> httpClientSettings,
				ObjectProvider<ClientHttpRequestFactoryBuilder<?>> requestFactoryBuilder,
				ConfigurableListableBeanFactory beanFactory) {
			RestClient.Builder bootBuilder = restClientBuilder.getIfAvailable();
			ClientHttpRequestFactoryBuilder<?> bean = requestFactoryBuilder.getIfAvailable();
			ClientHttpRequestFactoryBuilder<?> builder = (bean != null) ? bean
					: ClientHttpRequestFactoryBuilder.detect();
			reportRequestFactoryBuilder(builder, bean != null && !bootDeclaresTheBuilder(beanFactory));
			if (bootBuilder != null) {
				StartupLines.reportRestClientCustomizers(beanFactory);
			}
			return new RemoteHttpClients(bootBuilder, httpClientSettings.getIfAvailable(HttpClientSettings::defaults),
					builder);
		}

		// Tells whether the builder bean is the one Spring Boot's own auto-configuration
		// declares.
		//
		// Spring Boot 4.1 declares that bean in every application that is not reactive,
		// behind @ConditionalOnMissingBean, so the presence of a bean does not say the
		// application wrote one. A bean method's definition names the class that declares
		// it, which is what tells the two apart.
		private static boolean bootDeclaresTheBuilder(ConfigurableListableBeanFactory beanFactory) {
			for (String name : beanFactory.getBeanNamesForType(ClientHttpRequestFactoryBuilder.class, false, false)) {
				if (beanFactory.containsBeanDefinition(name) && BOOT_HTTP_CLIENT_AUTO_CONFIGURATION
					.equals(beanFactory.getBeanDefinition(name).getFactoryBeanName())) {
					return true;
				}
			}
			return false;
		}

		// Names the request factory builder GATool's clients are built with, and warns
		// where it is Boot's Apache builder, whose pool is small.
		//
		// detect() prefers Apache HttpClient 5, Jetty and Reactor Netty over the JDK
		// client, so an application that carries httpclient5 for another reason moves
		// GATool onto a pool of 5 connections per route, which is 5 tool calls in flight,
		// without a word. The builder lacks a getter for the pool, so the warning cannot
		// read the size a customizer set and says as much. The method sits in this nested
		// class because its signature names a type of spring-boot-http-client: on the
		// outer class it would fail the whole auto-configuration with a
		// NoClassDefFoundError where that jar is absent, since Spring Boot reads every
		// declared method of a class to deduce the bean type of a condition.
		private static void reportRequestFactoryBuilder(ClientHttpRequestFactoryBuilder<?> builder,
				boolean applicationsOwn) {
			String name = builder.getClass().getSimpleName();
			logger.info("GATool builds the request factory of its HTTP clients with " + name
					+ (applicationsOwn ? ", the ClientHttpRequestFactoryBuilder bean of this application."
							: ", which Spring Boot detected for the classpath or built for "
									+ "spring.http.clients.imperative.factory; that property selects another."));
			if (HTTP_COMPONENTS_BUILDER.equals(builder.getClass().getName())) {
				logger
					.warn("GATool's HTTP clients run on Apache HttpClient 5, whose pool holds 5 connections per route "
							+ "and 25 in all unless a customizer raised them, so at most 5 tool calls reach the GraphQL "
							+ "API at once. Set spring.http.clients.imperative.factory=jdk, or publish a "
							+ "ClientHttpRequestFactoryBuilder bean from ClientHttpRequestFactoryBuilder.httpComponents()"
							+ ".withConnectionManagerCustomizer(...) that raises both.");
			}
		}

	}

}
