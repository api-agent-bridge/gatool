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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import graphql.GraphQL;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;
import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.naming.ToolNamingStrategy;

import static org.assertj.core.api.Assertions.assertThat;

// Every condition the skeleton declares gets a test here, so a condition that stops
// matching fails a named test in this class. Each test asserts on the auto-configuration
// class itself, which a context registers as a bean while its conditions match, so the
// assertions survive a renamed bean method. FilteredClassLoader delegates to the parent
// loader, so each class it leaves visible stays the Class object this test holds.
@ExtendWith(OutputCaptureExtension.class)
class GAToolAutoConfigurationSliceTests {

	// Startup builds the client, and the API is first reached inside a tool call, so a
	// closed port serves every test in this class.
	private static final String API_URL = "gatool.api.url=http://localhost:1/graphql";

	private static final String SCHEMA_LOCATION = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String STATELESS_PROTOCOL = "spring.ai.mcp.server.protocol=STATELESS";

	private static final String UNSAFE_SWITCH = "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication";

	// GATool serves MCP over HTTP without authentication in this release, and startup
	// stops until an application says so. Every runner below that reaches MCP therefore
	// sets it, as an application does.
	private static final String UNSAFE_SWITCH_ON = UNSAFE_SWITCH + "=true";

	private static final String METADATA_FILES = "classpath*:META-INF/spring-configuration-metadata.json";

	private static final ResolvableType TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpStatelessServerFeatures.SyncToolSpecification.class);

	private static final ResolvableType STATEFUL_TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpServerFeatures.SyncToolSpecification.class);

	// The schema side on its own. Both adapters read the GAToolCatalog bean, so the two
	// tests that hide a class the schema side needs run that side by itself.
	private final ApplicationContextRunner schemaSide = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA_LOCATION);

	// Both adapters beside the schema side. McpServerJsonMapperAutoConfiguration
	// publishes the mcpServerJsonMapper bean that GAToolMcpAutoConfiguration injects by
	// name, and an application receives that auto-configuration from the MCP starter.
	private final ApplicationContextRunner bothAdapters = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA_LOCATION, UNSAFE_SWITCH_ON);

	// The MCP server runs on WebMVC, so the path where every condition matches runs in a
	// servlet context, which is the shape an application gets from the MCP starter.
	private final WebApplicationContextRunner everyCondition = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA_LOCATION, STATELESS_PROTOCOL, UNSAFE_SWITCH_ON);

	@Test
	void autoConfiguration_everyConditionMet_shouldPublishGAToolBothAdaptersAndTheToolSpecifications() {
		this.everyCondition.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCatalog.class);
			assertThat(context).hasSingleBean(GAToolCallbacks.class);
			assertThat(context.getBeanNamesForType(TOOL_SPECIFICATIONS)).hasSize(1);
		});
	}

	@Test
	void gaToolAutoConfiguration_graphQlJavaMissing_shouldBackOff() {
		this.schemaSide.withClassLoader(new FilteredClassLoader(GraphQL.class)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(GAToolAutoConfiguration.class);
			assertThat(context).doesNotHaveBean(GAToolCatalog.class);
		});
	}

	@Test
	void gaToolAutoConfiguration_springForGraphQlClientMissing_shouldBackOff() {
		this.schemaSide.withClassLoader(new FilteredClassLoader(HttpSyncGraphQlClient.class)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(GAToolAutoConfiguration.class);
			assertThat(context).doesNotHaveBean(GAToolCatalog.class);
		});
	}

	@Test
	void gaToolGraphQlExecutor_springBootHttpClientHidden_shouldStopNamingThePropertyAndTheModule() {
		// The types of spring-boot-http-client are reached through a nested configuration
		// with its own class condition, so the outer bean methods stay loadable without
		// the jar, and remote mode says at startup what is missing instead of failing
		// with a NoClassDefFoundError that Boot cannot analyse. A FilteredClassLoader
		// hides the class from the conditions alone, which is enough to show that path.
		this.schemaSide.withClassLoader(new FilteredClassLoader(HttpClientSettings.class)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.url")
				.hasMessageContaining("spring-boot-http-client");
		});
	}

	@Test
	void gaToolCatalog_springBootHttpClientHiddenWithAnApplicationExecutor_shouldStillBuildFromAClasspathSchema() {
		// Embedded mode with a schema file runs without an HTTP client, so the catalog
		// builds while the nested configuration backs off.
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GAToolAutoConfiguration.class))
			.withClassLoader(new FilteredClassLoader(HttpClientSettings.class))
			.withBean(GraphQlExecutor.class, () -> (request) -> {
				throw new UnsupportedOperationException("this test reads the schema alone");
			})
			.withPropertyValues(SCHEMA_LOCATION)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GAToolCatalog.class);
				assertThat(context).doesNotHaveBean(RemoteHttpClients.class);
			});
	}

	@Test
	void gaToolCatalog_springBootHttpClientPresent_shouldPublishTheClientsAndBuildTheExecutor() {
		this.schemaSide.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(RemoteHttpClients.class);
			assertThat(context).hasSingleBean(GraphQlExecutor.class);
			assertThat(context).hasSingleBean(GAToolCatalog.class);
		});
	}

	@Test
	void gaToolCatalog_springAiModelHidden_shouldStillBuild() {
		// The catalog's bean method reads EmbeddingModel beans through the bean factory
		// by class name, so its signature stays free of spring-ai-model.
		this.schemaSide.withClassLoader(new FilteredClassLoader(EmbeddingModel.class)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCatalog.class);
		});
	}

	@Test
	void gaToolCatalog_embeddingBackendWhileSpringAiModelIsHidden_shouldStopNamingTheProperty() {
		this.schemaSide.withClassLoader(new FilteredClassLoader(EmbeddingModel.class))
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.search-backend=embedding")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.dev.experimental.dynamic-operations.search-backend")
					.hasMessageContaining("which this application does not publish");
			});
	}

	@Test
	void gaToolCatalog_embeddingBackendWithAModelBean_shouldRankWithIt() {
		this.schemaSide
			.withPropertyValues("gatool.dev.experimental.generate-tools=dynamic-three-step",
					"gatool.dev.experimental.dynamic-operations.search-backend=embedding",
					"gatool.dev.experimental.dynamic-operations.vector-cache-directory=")
			.withBean(EmbeddingModel.class, () -> new EmbeddingModel() {

				@Override
				public float[] embed(Document document) {
					return new float[] { 1 };
				}

				@Override
				public EmbeddingResponse call(EmbeddingRequest request) {
					List<Embedding> results = new ArrayList<>();
					for (int index = 0; index < request.getInstructions().size(); index++) {
						results.add(new Embedding(new float[] { 1 }, index));
					}
					return new EmbeddingResponse(results);
				}
			})
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).extracting(GATool::name)
					.contains("searchSchema");
			});
	}

	@Test
	void gaToolMcpAutoConfiguration_mcpJavaSdkMissing_shouldBackOff() {
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL)
			.withClassLoader(new FilteredClassLoader(McpStatelessSyncServer.class))
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GAToolCatalog.class);
				assertThat(context).doesNotHaveBean(GAToolMcpAutoConfiguration.class);
			});
	}

	@Test
	void complianceFilter_servletContext_shouldRegisterTheFilter() {
		// The filter carries the Origin check, the body size cap, the protocol version
		// rule and the cursor rule. A condition that stopped matching would serve the
		// endpoint without any of them, and the served tools would look unchanged.
		this.everyCondition.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasBean("gaToolMcpComplianceFilter");
		});
	}

	@Test
	void complianceFilter_outsideAServletContext_shouldBackOff() {
		// The same auto-configuration serves stdio, where the application runs without a
		// servlet chain for a filter to join.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("gaToolMcpComplianceFilter");
		});
	}

	@Test
	void gaToolMcpAutoConfiguration_protocolUnset_shouldLeaveBothListsUnpublished() {
		// An ApplicationContextRunner skips every EnvironmentPostProcessor, so this
		// runner sees the property as an application sees it before
		// GAToolEnvironmentPostProcessor contributes STATELESS. Spring AI 2.0.1 would
		// start the deprecated SSE transport on this value, and GATool publishes tools
		// for the two protocols it serves.
		this.bothAdapters.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCatalog.class);
			assertThat(context.getBeanNamesForType(TOOL_SPECIFICATIONS)).isEmpty();
			assertThat(context.getBeanNamesForType(STATEFUL_TOOL_SPECIFICATIONS)).isEmpty();
		});
	}

	@Test
	void inProcessAutoConfiguration_springAiToolCallbackMissing_shouldBackOff() {
		this.bothAdapters.withClassLoader(new FilteredClassLoader(ToolCallback.class)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCatalog.class);
			assertThat(context).doesNotHaveBean(GAToolInProcessAutoConfiguration.class);
			assertThat(context).doesNotHaveBean(GAToolCallbacks.class);
		});
	}

	@Test
	void gaToolCatalog_applicationDeclaresItsOwnBean_shouldReplaceTheAutoConfiguredBean() {
		GAToolCatalog declared = GAToolCatalog.builder().mcpTools(List.of()).inProcessTools(List.of()).build();

		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL)
			.withBean(GAToolCatalog.class, () -> declared)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GAToolCatalog.class);
				assertThat(context.getBean(GAToolCatalog.class)).isSameAs(declared);
				// The MCP adapter reads the declared bean, so it publishes its list from
				// the empty catalog that bean carries.
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isEmpty();
				assertThat(context.getBeanNamesForType(TOOL_SPECIFICATIONS)).hasSize(1);
			});
	}

	@Test
	void gaToolCatalog_applicationDeclaresItsOwnBeanWithoutAnyGAToolProperty_shouldStart() {
		// An application's own catalog carries tools with handlers of their own, so the
		// executor, the result writer and the runner would go unused. GATool leaves them
		// unbuilt, so startup does not ask for gatool.api.url.
		GAToolCatalog declared = GAToolCatalog.builder().mcpTools(List.of()).inProcessTools(List.of()).build();

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withBean(GAToolCatalog.class, () -> declared)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class)).isSameAs(declared);
				assertThat(context).doesNotHaveBean(GraphQlExecutor.class);
			});
	}

	@Test
	void gaToolCatalog_operationLocationsUnset_shouldBuildOneToolPerSideFromTheDefaults() {
		// optional:classpath*:gatool/mcp/ and optional:classpath*:gatool/in-process/ are
		// the default operation locations, and this module's test resources hold one file
		// under each.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL).run((context) -> {
			GAToolCatalog catalog = context.getBean(GAToolCatalog.class);

			assertThat(catalog.mcpTools()).extracting(GATool::name).containsExactly("topRatedMovies");
			assertThat(catalog.inProcessTools()).extracting(GATool::name).containsExactly("topRatedMovies");
		});
	}

	@Test
	void gaToolCatalog_namingStrategySnakeCase_shouldNameEveryToolInSnakeCase() {
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, "gatool.naming.strategy=snake-case").run((context) -> {
			GAToolCatalog catalog = context.getBean(GAToolCatalog.class);

			assertThat(catalog.mcpTools()).extracting(GATool::name).containsExactly("top_rated_movies");
			assertThat(catalog.inProcessTools()).extracting(GATool::name).containsExactly("top_rated_movies");
		});
	}

	@Test
	void gaToolCatalog_applicationDeclaresANamingStrategyBean_shouldNameEveryToolWithIt() {
		// An application can replace the built-in naming strategies, so this bean wins
		// over the property that selects one of them.
		ToolNamingStrategy houseStyle = (graphQlName) -> "movies_" + graphQlName.toLowerCase(Locale.ROOT);

		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, "gatool.naming.strategy=snake-case")
			.withBean(ToolNamingStrategy.class, () -> houseStyle)
			.run((context) -> {
				GAToolCatalog catalog = context.getBean(GAToolCatalog.class);

				assertThat(catalog.mcpTools()).extracting(GATool::name).containsExactly("movies_topratedmovies");
			});
	}

	@Test
	void gaToolMcpAutoConfiguration_streamableOverHttp_shouldPublishTheStatefulToolSpecifications() {
		// Stateful Streamable HTTP keeps a session per client, and it reads the same list
		// type stdio does.
		this.bothAdapters.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanNamesForType(STATEFUL_TOOL_SPECIFICATIONS)).hasSize(1);
			assertThat(context.getBeanNamesForType(TOOL_SPECIFICATIONS)).isEmpty();
		});
	}

	@Test
	void gaToolStreamableServerTransportProvider_streamableOverHttp_shouldWrapSpringAisProviderWithGAToolsRevisions() {
		// Spring AI's provider is final and its default revision list carries 2025-03-26,
		// so GATool wraps its bean and publishes the wrapper as the primary provider, the
		// way the stateless side wraps its transport.
		new WebApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
							GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
							McpServerStreamableHttpWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, SCHEMA_LOCATION, UNSAFE_SWITCH_ON, "spring.ai.mcp.server.protocol=STREAMABLE")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanNamesForType(McpServerTransportProviderBase.class)).containsExactlyInAnyOrder(
						"webMvcStreamableServerTransportProvider", "gaToolStreamableServerTransportProvider");
				assertThat(context.getBean(McpServerTransportProviderBase.class).protocolVersions())
					.containsExactly("2025-06-18", "2025-11-25");
			});
	}

	@Test
	void gaToolMcpAutoConfiguration_streamableOverHttpWithoutTheUnsafeSwitch_shouldStopStartupAndNameTheProperty() {
		// The switch guards the port whichever protocol serves it. Spring Security is on
		// this test classpath, so it is hidden here: with it present the security
		// auto-configuration takes over, which McpSecuritySliceTests covers.
		new WebApplicationContextRunner().withClassLoader(new FilteredClassLoader(HttpSecurity.class))
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, SCHEMA_LOCATION, "spring.ai.mcp.server.protocol=STREAMABLE")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("allow-mcp-calls-without-authentication"));
	}

	@Test
	void gaToolMcpAutoConfiguration_stdio_shouldPublishTheStatefulToolSpecifications() {
		// Each protocol gets its own list type, and stdio runs on the stateful one, so
		// the two lists stay apart. Stdio runs without the unsafe switch, because the
		// caller is the process that started the server.
		this.bothAdapters
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanNamesForType(STATEFUL_TOOL_SPECIFICATIONS)).hasSize(1);
				assertThat(context.getBeanNamesForType(TOOL_SPECIFICATIONS)).isEmpty();
			});
	}

	@Test
	void gaToolStdioServerTransport_stdio_shouldPublishATransportCarryingTheRevisionsGAToolServes() {
		// The SDK reads its revision list from the transport provider, and its own
		// default
		// carries 2025-03-26, which this SDK cannot serve in full. GATool builds the
		// provider
		// itself: a condition on Spring AI's stdioServerTransport bean cannot match,
		// because Boot evaluates @ConditionalOnBean while GATool's own definitions are
		// being
		// registered and GATool is ordered before the configuration that declares that
		// bean.
		this.bothAdapters
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
			.run((context) -> {
				assertThat(context).hasNotFailed().hasSingleBean(McpServerTransportProviderBase.class);
				assertThat(context.getBean(McpServerTransportProviderBase.class).protocolVersions())
					.containsExactly("2025-06-18", "2025-11-25");
			});
	}

	@Test
	void gaToolMcpAutoConfiguration_httpWithoutTheUnsafeSwitch_shouldStopStartupAndNameTheProperty() {
		// Without Spring Security, an application says plainly that callers reach the
		// tools without authentication, and the stop names the starter that secures them.
		new WebApplicationContextRunner().withClassLoader(new FilteredClassLoader(HttpSecurity.class))
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, SCHEMA_LOCATION, STATELESS_PROTOCOL)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("allow-mcp-calls-without-authentication"));
	}

	@Test
	void gaToolMcpAutoConfiguration_stdioWithTheStatelessProtocol_shouldStopStartupAndNameTheProtocol() {
		// Spring AI serves stdio on a stateful session, so this pair would start an
		// application without a server, and GATool stops startup first.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, "spring.ai.mcp.server.stdio=true")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring.ai.mcp.server.protocol"));
	}

	@Test
	void gaToolGraphQlExecutor_withoutUrlAndWithoutGraphQlBeans_shouldStopStartupAndNameBothWays() {
		// GATool runs a tool call in two ways, over HTTP and inside the application, so
		// the message names both of them. A message naming the URL alone would hide
		// embedded mode from an application that already serves a GraphQL API.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.url")
				.hasMessageContaining("Spring for GraphQL"));
	}

	@Test
	void startup_operationFiles_shouldLogEveryToolWithTheFileItCameFrom(CapturedOutput output) {
		// GATool writes this listing at startup, because Spring AI's own line prints the
		// count and leaves the names out.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getAll()).contains("topRatedMovies")
				.contains("TopRatedMovies.graphql")
				.contains("MCP tool")
				.contains("in-process tool")
				// What the tool costs an agent before it calls anything, per tool
				// and per exposure type, so the log shows the context being spent.
				.contains("tokens)")
				.contains("tokens in all");
		});
	}

	@Test
	void startup_operationFiles_shouldCountTheTokensOfEachToolAndOfTheSide(CapturedOutput output) {
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL).run((context) -> {
			assertThat(context).hasNotFailed();

			// The one tool of this fixture is small, and the count is the name, the
			// description and the input schema at four characters to a token.
			assertThat(output.getAll()).containsPattern("topRatedMovies from [^\\n]+ \\(about \\d+ tokens\\)")
				.containsPattern("about \\d+ tokens in all");
		});
	}

	@Test
	void configurationMetadata_unsafeSwitch_shouldBeCompleteAndSayWhatItDoes() throws IOException {
		JsonNode property = unsafeSwitchMetadata();

		assertThat(property.get("type").asString()).isEqualTo("java.lang.Boolean");
		assertThat(property.get("defaultValue").asBoolean()).isFalse();
		// The assertion reads the package and the suffix, so the class that holds the
		// group stays free to change.
		assertThat(property.get("sourceType").asString()).startsWith("io.gatool.boot.autoconfigure.GATool")
			.endsWith("Properties$McpSecurity$Unsafe");
		// One sentence saying what the switch does, which is how Spring Boot writes a
		// property: across 80 of its own, the median is nine words and 63 are a single
		// sentence, and each of them stands without a caveat. What the risk costs is in
		// the README, and the warning the test below asserts repeats it at every startup.
		String description = property.get("description").asString();
		assertThat(description).contains("authenticated");
		assertThat(description.split("\\s+")).hasSizeLessThan(20);
	}

	@Test
	void startup_unsafeSwitchTrue_shouldLogWarningNamingTheProperty(CapturedOutput output) {
		// GATool warns at every startup while a switch in this group is on, and
		// GAToolMcpAutoConfiguration writes it, so this runner sets the stateless
		// protocol. getAll() covers both streams, so the assertion holds whichever stream
		// the logging backend writes to.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, UNSAFE_SWITCH + "=true").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getAll()).contains(UNSAFE_SWITCH);
		});
	}

	@Test
	void startup_supersededRevisionsSwitchTrue_shouldLogWarningNamingTheProperty(CapturedOutput output) {
		// Every switch in the group warns at every startup, this one included.
		this.bothAdapters
			.withPropertyValues(STATELESS_PROTOCOL, "gatool.mcp.security.unsafe.allow-superseded-mcp-revisions=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(output.getAll())
					.contains("gatool.mcp.security.unsafe.allow-superseded-mcp-revisions is true");
			});
	}

	@Test
	void startup_unauthenticatedWithServerAddressUnset_shouldWarnThatEveryInterfaceIsOpen(CapturedOutput output) {
		// MCP asks a local server to bind to loopback. Spring AI's MCP starter leaves
		// server.address alone and so does GATool, so the warning is the whole of what
		// startup does about it.
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getAll()).contains("server.address is unset, so it defaults to 0.0.0.0")
				.contains("any machine that can reach this one");
		});
	}

	@Test
	void startup_unauthenticatedOnEverySpellingOfTheWildcard_shouldWarnThatEveryInterfaceIsOpen(CapturedOutput output) {
		for (String address : List.of("0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0")) {
			this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, "server.address=" + address)
				.run((context) -> assertThat(context).hasNotFailed());
			assertThat(output.getAll()).as(address)
				.contains("server.address is " + address)
				.contains("any machine that can reach this one");
		}
	}

	@Test
	void startup_stdioWithServerAddressUnset_shouldLeaveTheInterfaceWarningOut(CapturedOutput output) {
		this.bothAdapters
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).doesNotContain("any machine that can reach this one");
	}

	@Test
	void startup_unauthenticatedStateful_shouldWarnThatAnyCallerCanFillTheSessionCap(CapturedOutput output) {
		this.bothAdapters.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("can open sessions up to gatool.mcp.sessions.max-count (1000)")
			.contains("gatool.mcp.sessions.idle-timeout (PT30M)");
	}

	@Test
	void startup_zeroMcpTools_shouldWarnNamingTheLocationsRead(CapturedOutput output) {
		this.bothAdapters
			.withPropertyValues(STATELESS_PROTOCOL, "gatool.mcp.operations.locations=optional:classpath*:gatool/none/")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("GATool serves zero MCP tools").contains("gatool/none/");
	}

	@Test
	void startup_unauthenticatedOnLoopback_shouldLeaveTheInterfaceWarningOut(CapturedOutput output) {
		this.bothAdapters.withPropertyValues(STATELESS_PROTOCOL, "server.address=127.0.0.1").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getAll()).doesNotContain("any machine that can reach this one");
		});
	}

	@Test
	void gaToolMcpAutoConfiguration_reactiveWebApplication_shouldStopAndSayWhatIsMissing() {
		// The MCP endpoint's guards are servlet filters, and each carries
		// @ConditionalOnWebApplication(SERVLET) while this auto-configuration does not
		// carry it. A WebFlux application would therefore publish the endpoint with the
		// Origin check and the MCP-Protocol-Version check absent, both of them MCP MUSTs,
		// along with the body cap, the revision limit, the per-caller rate limit and the
		// whole security chain. Starting insecurely is worse than refusing to start.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, SCHEMA_LOCATION, UNSAFE_SWITCH_ON, STATELESS_PROTOCOL)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("WebFlux")
					.hasMessageContaining("Origin")
					.hasMessageContaining("MCP-Protocol-Version")
					.hasMessageContaining("gatool-in-process-spring-boot-starter");
			});
	}

	@Test
	void metadata_theTwoOperationsLocations_shouldPublishTheirDefaultsAndTheirOwnFolder() throws IOException {
		// Spring Boot replaces a list property as a whole, so a team that names a second
		// folder loses the default one and every tool in it, and startup does not mention
		// it. The configuration processor reads field initialisers, and this list is
		// empty there while the real default arrives through Operations.at at each use
		// site, so the metadata states the default for an IDE to show.
		assertThat(operationsMetadata("gatool.mcp.operations.locations").path("defaultValue").path(0).asString())
			.isEqualTo("optional:classpath*:gatool/mcp/");
		assertThat(operationsMetadata("gatool.in-process.operations.locations").path("defaultValue").path(0).asString())
			.isEqualTo("optional:classpath*:gatool/in-process/");
		// Each description names its own folder, so the two keys read differently.
		assertThat(operationsMetadata("gatool.mcp.operations.locations").path("description").asString())
			.contains("gatool/mcp/")
			.contains("replaces this default");
		assertThat(operationsMetadata("gatool.in-process.operations.locations").path("description").asString())
			.contains("gatool/in-process/")
			.contains("replaces this default");
	}

	@Test
	void metadata_theRequestLimits_shouldPublishTheDefaultsOfGraphQlJava() throws IOException {
		assertThat(operationsMetadata("gatool.api.request-limits.max-characters").path("defaultValue").asInt())
			.isEqualTo(1_048_576);
		assertThat(operationsMetadata("gatool.api.request-limits.max-tokens").path("defaultValue").asInt())
			.isEqualTo(15_000);
		assertThat(operationsMetadata("gatool.api.request-limits.max-whitespace-tokens").path("defaultValue").asInt())
			.isEqualTo(200_000);
		assertThat(operationsMetadata("gatool.api.request-limits.max-tokens").path("description").asString())
			.contains("warns");
	}

	@Test
	void metadata_theStdioLogSwitch_shouldPublishItsDefault() throws IOException {
		// The listener that attaches the stderr appender reads the key from the
		// Environment, ahead of the binder, so the field in GAToolProperties is what
		// shows the key and its default to an IDE.
		JsonNode property = operationsMetadata("gatool.mcp.stdio.log-to-stderr");

		assertThat(property.path("type").asString()).isEqualTo("java.lang.Boolean");
		assertThat(property.path("defaultValue").asBoolean()).isTrue();
		assertThat(property.path("description").asString()).contains("stderr");
	}

	@Test
	void startup_aToolWithAnImageMimeTypeAndAnOutputSchema_shouldStopNamingBothSettings() {
		// The two settings contradict each other. An image response answers with image
		// content alone, while a published output schema tells the SDK to expect
		// structured content, so the SDK answers every call to that tool with its own
		// missing-structured-content error. The pair is documented, so a team could set
		// both and meet a tool that fails on every call.
		this.bothAdapters
			.withPropertyValues(STATELESS_PROTOCOL, "gatool.results.publish-output-schema=true",
					"spring.ai.mcp.server.tool-response-mime-type.topRatedMovies=image/png")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("spring.ai.mcp.server.tool-response-mime-type.topRatedMovies")
					.hasMessageContaining("gatool.results.publish-output-schema")
					.hasMessageContaining("topRatedMovies");
			});
	}

	@Test
	void startup_aToolResponseMimeTypeThatIsNotAMediaType_shouldStopNamingTheProperty() {
		// image is a plausible typo for image/png, and a raw startsWith check would pass
		// it, so the tool would answer with an ImageContent whose mimeType a client
		// cannot map to a decoder. Spring AI parses the same property at startup for an
		// application's own tools, so a GATool tool fails the same way at the same time.
		this.bothAdapters
			.withPropertyValues(STATELESS_PROTOCOL, "spring.ai.mcp.server.tool-response-mime-type.topRatedMovies=image")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("spring.ai.mcp.server.tool-response-mime-type.topRatedMovies")
					.hasMessageContaining("image/png");
			});
	}

	@Test
	void startup_aToolResponseMimeType_shouldBeReadFromTheEnvironmentOnce() {
		// The startup check of an image tool and the settings every call runs under use
		// the same map, so startup binds it once and hands it to both. A source that
		// answers a second read differently would otherwise leave the check and the calls
		// with two maps.
		String key = "spring.ai.mcp.server.tool-response-mime-type.topRatedMovies";
		for (String protocol : List.of("STATELESS", "STREAMABLE")) {
			AtomicInteger reads = new AtomicInteger();
			this.bothAdapters.withPropertyValues("spring.ai.mcp.server.protocol=" + protocol)
				.withInitializer((context) -> context.getEnvironment()
					.getPropertySources()
					.addFirst(new MapPropertySource("counted", Map.of(key, "text/plain")) {

						@Override
						public @Nullable Object getProperty(String name) {
							if (name.equals(key)) {
								reads.incrementAndGet();
							}
							return super.getProperty(name);
						}
					}))
				.run((context) -> {
					assertThat(context).hasNotFailed();
					assertThat(reads).as("reads of the mime type under " + protocol).hasValue(1);
				});
		}
	}

	@Test
	void startup_anApiUrlWithoutAScheme_shouldStopNamingTheProperty() {
		// movies.example.com/graphql reads like a URL and is not one. Left unchecked, the
		// application would start and every tool call would fail. The same check guards
		// gatool.mcp.security.resource.
		this.schemaSide.withPropertyValues("gatool.api.url=movies.example.com/graphql").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("gatool.api.url")
				.hasMessageContaining("absolute");
		});
	}

	// The generated file sits beside the classes of gatool-spring-boot, and every Spring
	// Boot module on the classpath ships one of its own, so the test reads them all and
	// takes the one that carries the key. A path into target/classes would tie the test
	// to the layout of the build.
	private static JsonNode unsafeSwitchMetadata() throws IOException {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources(METADATA_FILES)) {
			JsonNode metadata = jsonMapper.readTree(resource.getContentAsString(StandardCharsets.UTF_8));
			for (JsonNode property : metadata.path("properties")) {
				if (UNSAFE_SWITCH.equals(property.path("name").asString())) {
					return property;
				}
			}
		}
		throw new AssertionError(UNSAFE_SWITCH + " is missing from " + METADATA_FILES);
	}

	private static JsonNode operationsMetadata(String name) throws IOException {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources(METADATA_FILES)) {
			JsonNode metadata = jsonMapper.readTree(resource.getContentAsString(StandardCharsets.UTF_8));
			for (JsonNode property : metadata.path("properties")) {
				if (name.equals(property.path("name").asString())) {
					return property;
				}
			}
		}
		throw new AssertionError(name + " is missing from " + METADATA_FILES);
	}

}
