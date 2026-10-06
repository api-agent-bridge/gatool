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

package io.gatool.boot.mcp.autoconfigure;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.GAToolMcpProperties;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.mcp.internal.server.McpCallSettings;
import io.gatool.boot.mcp.internal.server.McpErrorBodies;
import io.gatool.boot.mcp.internal.server.McpToolSpecifications;
import io.gatool.boot.mcp.internal.server.RevisionLimitedServerTransportProvider;
import io.gatool.boot.mcp.internal.server.RevisionLimitedStatelessTransport;
import io.gatool.boot.mcp.internal.server.RevisionLimitedStreamableServerTransportProvider;
import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;
import io.gatool.boot.mcp.limit.ToolRateLimiter;
import io.gatool.core.internal.operation.SchemaAddition;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.model.GATool;

/**
 * Publishes the tool specifications that Spring AI's MCP server reads, in the list type
 * the running protocol expects.
 *
 * <p>
 * Each protocol reads its own list. A stateless server reads
 * {@code List<McpStatelessServerFeatures.SyncToolSpecification>}, and a stateful server,
 * which stdio also runs on, reads {@code List<McpServerFeatures.SyncToolSpecification>}.
 * Each bean is a {@code List}, because Spring AI's server bean methods inject
 * {@code ObjectProvider<List<...>>} and flatten every such bean. A specification
 * published outside a list stays unread and the log stays quiet, so GATool publishes a
 * list even for one tool.
 *
 * <p>
 * The condition reads {@code spring.ai.mcp.server.protocol}, which
 * {@link GAToolEnvironmentPostProcessor} defaults to {@code STATELESS} over HTTP and to
 * {@code STREAMABLE} under stdio. Startup logs the effective transport from the
 * {@code Environment} instead of from the bound {@code McpServerProperties} bean, because
 * that bean reports {@code STREAMABLE} from its field initializer while the unset
 * property starts SSE.
 *
 * <p>
 * The {@link McpJsonMapper} wraps the {@code mcpServerJsonMapper} bean, the one Spring
 * AI's own transports read and write with, so every transport in the application handles
 * a request body the same way. {@link GAToolMcpJsonMapperAutoConfiguration} declares that
 * bean with a null map value kept, unless the application declares its own. The bean
 * carries {@code defaultCandidate=false}, so the injection point names it with
 * {@link Qualifier}.
 *
 * <p>
 * An application replaces the built-in rate limiter with a {@link ToolRateLimiter} bean,
 * which is where a count in a shared store belongs for a deployment behind a load
 * balancer.
 *
 * @author Željko Kozina
 */
// Ordered before both of Spring AI's WebMvc auto-configurations, because each one
// publishes its router function under @ConditionalOnMissingBean(name = ...), which backs
// off for GATool's bean of that name only while GATool's is registered first.
@AutoConfiguration(after = GAToolAutoConfiguration.class,
		before = { McpServerStatelessAutoConfiguration.class, McpServerAutoConfiguration.class },
		beforeName = {
				"org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration",
				"org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration" })
// Every type this class needs, and each one arrives in a different jar. Naming the SDK
// alone would let an application that adds the in-process starter beside any dependency
// carrying the MCP SDK match this condition and then fail to start. The class body also
// reads Spring AI's McpServerProperties and builds the Bucket4j limiter over a Caffeine
// cache, and gatool-mcp-spring-boot declares both of those optional. The MCP starter
// brings all four together, so an application that has it matches, and one that lacks it
// backs off here instead of dying on a linkage error.
@ConditionalOnClass({ McpStatelessSyncServer.class, McpServerProperties.class, Bucket.class, Caffeine.class })
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
// Eager under spring.main.lazy-initialization=true, because the three stops in
// afterPropertiesSet run when this instance is built. Today the two tool lists, which
// carry the same mark for their own checks, reach this instance anyway, through the
// limiter bean whose factory method it holds. The mark says the intent outright, so the
// stops stay at startup if that path ever moves.
@Lazy(false)
public final class GAToolMcpAutoConfiguration implements InitializingBean {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	// The two tool beans below are conditional on the protocol, and Spring AI's server is
	// conditional on the API type. A value GATool does not serve would otherwise leave a
	// running MCP endpoint with zero GATool tools and a silent startup. These checks sit
	// on the auto-configuration itself, which Spring builds before either nested
	// configuration, so the failure names the property whatever the value is.
	private final Environment environment;

	private final ApplicationContext applicationContext;

	GAToolMcpAutoConfiguration(Environment environment, ApplicationContext applicationContext) {
		this.environment = environment;
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() {
		McpStartupStops.stopOnWhitespaceAroundAServerSetting(this.environment);
		McpStartupStops.stopOnUnsupportedProtocol(this.environment);
		McpStartupStops.stopOnAsyncServer(this.environment);
		McpStartupStops.stopOnReactiveWebApplication(this.applicationContext);
		McpStartupStops.warnOnReactiveBeansInAServletApplication(this.applicationContext);
	}

	/**
	 * Spring Boot's property for the order of Spring Security's filter, whose default is
	 * {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}, -100 in 4.1.1. Read as a
	 * property, because spring-boot-security is optional on this module's path and a
	 * class reference would bind the two.
	 */
	static final String SECURITY_FILTER_ORDER_PROPERTY = "spring.security.filter.order";

	/**
	 * The name of the compliance filter's registration bean.
	 */
	static final String COMPLIANCE_FILTER_BEAN_NAME = "gaToolMcpComplianceFilter";

	static final int DEFAULT_SECURITY_FILTER_ORDER = -100;

	/**
	 * Publishes the limiter that runs in front of every tool call.
	 *
	 * <p>
	 * With {@code gatool.mcp.security.unsafe.allow-unlimited-tool-calls} on, the limiter
	 * allows every call, and the warning below names the switch at every startup.
	 * <p>
	 * The limiter counts at most {@code gatool.mcp.rate-limit.max-tracked-pairs} caller
	 * and tool pairs, and counts each bucket it drops inside its window into
	 * {@code gatool.rate-limit.evictions} while the application has a
	 * {@code MeterRegistry} bean. The registry is looked up through the bean factory by
	 * class name, so this signature stays free of micrometer-core, which is optional
	 * here.
	 * @param properties the GATool properties
	 * @param beanFactory the bean factory that may hold a meter registry
	 * @return the limiter that every MCP tool call passes
	 */
	@Bean
	@ConditionalOnMissingBean
	ToolRateLimiter gaToolRateLimiter(GAToolProperties properties, ConfigurableListableBeanFactory beanFactory) {
		return McpToolRateLimiters.build(properties, beanFactory);
	}

	/**
	 * States what the MCP adapter adds to each schema it publishes, so the startup
	 * listing of the MCP tools and the warning about a large tool count it.
	 *
	 * <p>
	 * {@link McpToolSpecifications} names every schema with a {@code $id}, and
	 * {@code tools/list} carries the keyword with the schema. The catalog holds the
	 * schema as its writer produced it, so the keyword is counted from what this bean
	 * states. The method is static, because the catalog reads the bean while it is built,
	 * and a static method answers without this configuration's own instance, whose
	 * startup checks keep their place.
	 * @return the characters the {@code $id} adds to a schema
	 */
	@Bean
	static SchemaAddition gaToolMcpSchemaAddition() {
		return new SchemaAddition(ToolExposureType.MCP, McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA);
	}

	/**
	 * Keeps Spring AI's server bean eager under
	 * {@code spring.main.lazy-initialization=true}.
	 *
	 * <p>
	 * The server bean installs the handler on the transport while it is built, and a lazy
	 * context builds a bean once another bean asks for it. Requests reach the transport,
	 * and the transport is what the server installs its handler on, so the server bean is
	 * one that every other bean leaves alone and it would stay unbuilt. The context would
	 * then report itself active and bind the port, which is what a health check reads,
	 * while every request answered 500: "MCP handler not configured" on the stateless
	 * transport and "SessionFactory not configured" on the stateful one. The two sync
	 * types cover it, because {@link McpStartupStops#stopOnAsyncServer} refuses the async
	 * ones. The method is static, as Boot asks for a filter that runs this early.
	 * @return the filter that keeps the server bean out of lazy initialization
	 */
	@Bean
	static LazyInitializationExcludeFilter gaToolMcpServerLazyInitializationExcludeFilter() {
		return LazyInitializationExcludeFilter.forBeanTypes(McpSyncServer.class, McpStatelessSyncServer.class);
	}

	/**
	 * Stops the server advertising a capability the application does not serve.
	 *
	 * <p>
	 * Spring AI's capability properties default to true, so an application whose only
	 * tools come from GATool would still answer {@code initialize} with
	 * {@code resources}, {@code prompts} and {@code completions}. A client that reads
	 * capabilities before it calls then tries {@code resources/list} and
	 * {@code prompts/list} against a server that serves tools alone. Each one is switched
	 * off only where the application does not publish a specification of its own, so an
	 * application that declares its own resources keeps them, and a property set
	 * explicitly keeps the value it was given.
	 * @param serverProperties the properties Spring AI's server reads, which a context
	 * without an MCP server leaves out
	 * @param environment carries the properties an application set by hand
	 * @param beanFactory holds the specification beans an application publishes
	 * @return the marker that says the capabilities were settled
	 */
	@Bean
	GAToolServerCapabilitiesMarker gaToolServerCapabilitiesMarker(ObjectProvider<McpServerProperties> serverProperties,
			Environment environment, ConfigurableListableBeanFactory beanFactory) {
		McpServerCapabilities.settle(serverProperties, environment, beanFactory);
		return new GAToolServerCapabilitiesMarker();
	}

	/**
	 * The name of the bean that says the capabilities were settled.
	 *
	 * <p>
	 * The server name and the instructions are defaults, contributed by
	 * {@link GAToolEnvironmentPostProcessor} to Boot's {@code defaultProperties} source,
	 * which is where a library's default for another library's key belongs. The three
	 * capability switches depend on which specification beans the application publishes,
	 * so they are settled at bean time.
	 */
	static final String CAPABILITIES_MARKER_BEAN_NAME = "gaToolServerCapabilitiesMarker";

	// One post-processor per server shape Spring AI publishes. Each is static, the way
	// Boot registers its own: a BeanFactoryPostProcessor is created before any ordinary
	// bean, and a method on the instance would force this whole auto-configuration to be
	// built that early.
	@Bean
	static ServerDependsOnCapabilitiesPostProcessor gaToolStatelessSyncServerDependsOnCapabilities() {
		return new ServerDependsOnCapabilitiesPostProcessor(McpStatelessSyncServer.class);
	}

	@Bean
	static ServerDependsOnCapabilitiesPostProcessor gaToolStatelessAsyncServerDependsOnCapabilities() {
		return new ServerDependsOnCapabilitiesPostProcessor(McpStatelessAsyncServer.class);
	}

	@Bean
	static ServerDependsOnCapabilitiesPostProcessor gaToolSyncServerDependsOnCapabilities() {
		return new ServerDependsOnCapabilitiesPostProcessor(McpSyncServer.class);
	}

	@Bean
	static ServerDependsOnCapabilitiesPostProcessor gaToolAsyncServerDependsOnCapabilities() {
		return new ServerDependsOnCapabilitiesPostProcessor(McpAsyncServer.class);
	}

	/**
	 * Binds Spring AI's Streamable HTTP keys through Spring AI's own properties class, so
	 * its defaults, {@code /mcp} among them, are read from the one place Spring AI keeps
	 * them.
	 * @param environment the environment the keys are bound from
	 * @return the bound properties, with Spring AI's defaults where a key is unset
	 */
	// The Binder is used instead of the bean, because Spring AI registers that bean from
	// its WebMvc auto-configurations, which a context running this class alone leaves
	// out, and a bean parameter would tie every bean here to that registration.
	static McpServerStreamableHttpProperties bindStreamableHttp(Environment environment) {
		return Binder.get(environment)
			.bindOrCreate(McpServerStreamableHttpProperties.CONFIG_PREFIX, McpServerStreamableHttpProperties.class);
	}

	/**
	 * Registers the MCP compliance filter ahead of Spring Security.
	 *
	 * <p>
	 * Spring Boot registers Spring Security's chain at
	 * {@code spring.security.filter.order}, -100 by default, so this filter takes the
	 * number below it and runs first, clamped at the highest precedence, where one below
	 * would wrap round to the end of the chain. A page from a foreign origin then reads
	 * 403 ahead of the 401 that a bearer token check would answer, which is what MCP asks
	 * a server to do. The security auto-configuration checks the two orders once every
	 * singleton exists.
	 *
	 * <p>
	 * The path comes from {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}, the
	 * key Spring AI binds, read through Spring AI's own properties class so that its
	 * default stays Spring AI's. Spring AI's stateless pages name
	 * {@code spring.ai.mcp.server.stateless.mcp-endpoint} instead, a prefix that every
	 * class in the release leaves unbound.
	 *
	 * <p>
	 * The registration sits in a nested configuration carrying the servlet conditions,
	 * which is Spring Boot's own convention for a bean whose type belongs to a framework
	 * an application may leave out.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(FilterRegistrationBean.class)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class ComplianceFilter {

		/**
		 * Registers the filter with an order that runs it ahead of Spring Security.
		 * @param properties the GATool properties
		 * @param environment carries the endpoint path
		 * @param mcpServerJsonMapper the mapper the MCP server itself reads bodies with,
		 * so the filter and the server parse under the same rules and the tool name the
		 * filter reads is the one the server calls. A context without that bean gets a
		 * mapper built here
		 * @return the filter registration
		 */
		// Backs off for a registration under GATool's name, and for a registration of
		// GATool's filter under any other name, so an application declares one or the
		// other and the startup check finds exactly one.
		@Bean
		@ConditionalOnMissingBean(value = McpComplianceFilter.class,
				parameterizedContainer = FilterRegistrationBean.class, name = COMPLIANCE_FILTER_BEAN_NAME)
		FilterRegistrationBean<McpComplianceFilter> gaToolMcpComplianceFilter(GAToolProperties properties,
				Environment environment,
				@Qualifier("mcpServerJsonMapper") ObjectProvider<JsonMapper> mcpServerJsonMapper) {
			McpComplianceFilter filter = McpComplianceFilters.build(properties, environment, mcpServerJsonMapper);
			FilterRegistrationBean<McpComplianceFilter> registration = new FilterRegistrationBean<>(filter);
			registration.setOrder(McpComplianceFilters.order(environment));
			registration.setName(COMPLIANCE_FILTER_BEAN_NAME);
			// Every dispatch, the way Spring Boot registers Spring Security's filter, so
			// a forward or an error dispatch to the endpoint meets the same rules and the
			// security chain finds the attributes this filter leaves.
			registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
			return registration;
		}

	}

	/**
	 * Declares that one shape of Spring AI's server bean is built after the capabilities
	 * were settled.
	 *
	 * <p>
	 * Spring AI reads the three capability switches inside the one method that builds its
	 * server, so the marker bean has to have run by then. Ordering the two
	 * auto-configurations settles the order the definitions are registered in, and that
	 * is the order {@code preInstantiateSingletons} follows only while nothing else asks
	 * for a server first. An application's own bean definitions are registered ahead of
	 * every auto-configuration, so one application bean holding
	 * {@code McpStatelessSyncServer} would build the server from Spring AI's defaults:
	 * resources, prompts and completions all advertised, although the application does
	 * not serve any of them. The same would happen under
	 * {@code spring.main.lazy-initialization=true}, where the marker bean is left
	 * uncreated. A {@code dependsOn} entry says the order outright, and it holds however
	 * the server comes to be built.
	 *
	 * <p>
	 * Boot's post-processor finds the server definition by its type, so a server bean
	 * under a name of the application's own gets the entry too.
	 */
	static final class ServerDependsOnCapabilitiesPostProcessor extends AbstractDependsOnBeanFactoryPostProcessor {

		ServerDependsOnCapabilitiesPostProcessor(Class<?> serverType) {
			super(serverType, CAPABILITIES_MARKER_BEAN_NAME);
		}

	}

	/**
	 * The bean that says the capabilities were settled, which every Spring AI server bean
	 * depends on through {@link ServerDependsOnCapabilitiesPostProcessor}.
	 */
	static final class GAToolServerCapabilitiesMarker {

	}

	/**
	 * Stops startup where the per-tool scope check is absent from the chain serving the
	 * MCP endpoint.
	 *
	 * <p>
	 * {@link GAToolMcpSecurityAutoConfiguration} installs that check, and it carries its
	 * own richer check of the same chain. Excluding it with
	 * {@code spring.autoconfigure.exclude} removes both, and Spring Boot's own resource
	 * server then serves the endpoint under a rule that asks for a valid token alone.
	 */
	// The guard beside this one probes the classpath, which an exclusion leaves
	// untouched, so it stays quiet while every scoped tool opens to any token for the
	// audience. This looks at the live chain instead of at a bean, so an application that
	// excludes the auto-configuration and applies McpServerSecurityConfigurer in a chain
	// of its own is left alone, which is the supported way to take the endpoint's
	// security over.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = { "org.springframework.security.config.annotation.web.builders.HttpSecurity",
			"org.springframework.security.web.SecurityFilterChain" })
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class ScopeCheck {

		@Bean
		SmartInitializingSingleton gaToolMcpScopeCheckStartupCheck(
				ObjectProvider<GAToolMcpSecurityAutoConfiguration.ResourceServerSettingsCheck> securityAutoConfiguration,
				@Qualifier(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME) ObjectProvider<Filter> springSecurityFilterChain,
				GAToolProperties properties, Environment environment) {
			return () -> McpScopeCheckAtStartup.run(securityAutoConfiguration, springSecurityFilterChain, properties,
					environment);
		}

	}

	/**
	 * Stops startup where an MCP server bean was built without
	 * {@code immediateExecution(true)}.
	 *
	 * <p>
	 * GATool reads the caller of a tool call from the thread the call runs on. The MCP
	 * SDK subscribes a synchronous tool handler on Reactor's bounded elastic scheduler
	 * unless the server carries that setting, and Spring AI sets it on the stateless
	 * server itself and on the stateful server through its own
	 * {@code McpSyncServerCustomizer} bean. An application that replaces that customizer,
	 * or builds a server bean of its own, drops it, and every tool call then runs on a
	 * pooled thread whose security context belongs to whichever request created it.
	 *
	 * <p>
	 * The check runs beside the scope check, once every singleton exists and before the
	 * web server opens its port. It is skipped over stdio, where one process is the only
	 * caller and a request thread does not exist.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class RequestThreadCheck {

		@Bean
		SmartInitializingSingleton gaToolMcpRequestThreadStartupCheck(ConfigurableListableBeanFactory beanFactory,
				Environment environment) {
			return () -> {
				if (SpringAiMcpKeys.servesStdio(environment)) {
					return;
				}
				ImmediateExecutionCheck.requireImmediateExecution(beanFactory, McpSyncServer.class,
						(beanName, ownCustomizers) -> ImmediateExecutionCheck.overHttp(beanName, true, ownCustomizers));
				ImmediateExecutionCheck.requireImmediateExecution(beanFactory, McpStatelessSyncServer.class, (beanName,
						ownCustomizers) -> ImmediateExecutionCheck.overHttp(beanName, false, ownCustomizers));
			};
		}

	}

	/**
	 * The tools that a stateless Streamable HTTP server serves, which is the default over
	 * HTTP.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STATELESS")
	static class Stateless {

		// Eager under spring.main.lazy-initialization=true as well, because the checks
		// this method runs stop startup, and a lazy bean would move them to the first
		// request, where a stop reads as a failed call.
		@Bean
		@Lazy(false)
		List<McpStatelessServerFeatures.SyncToolSpecification> gaToolMcpStatelessToolSpecifications(
				GAToolCatalog catalog, GAToolProperties properties, ToolRateLimiter rateLimiter,
				ObjectProvider<ObservationRegistry> observationRegistry, ConfigurableListableBeanFactory beanFactory,
				@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper,
				ApplicationContext applicationContext) {
			// The context's environment is the Environment bean, so reading it here keeps
			// this method inside the parameter limit.
			Environment environment = applicationContext.getEnvironment();
			McpStartupStops.stopOnStatelessStdio(environment);
			McpStartupStops.stopWhenTheToolCapabilityIsOff(catalog, environment);
			McpStartupStops.stopWhenCallersArriveUnauthenticated(properties, environment);
			// Bound once, so the check below and the settings each call runs under read
			// the same map.
			Map<String, String> mimeTypes = McpCallPolicies.readToolResponseMimeTypes(environment);
			McpCallPolicies.requireImageToolsWithoutAnOutputSchema(catalog, mimeTypes);
			logger.info("GATool serves MCP over stateless Streamable HTTP.");
			McpStartupWarnings.warnOnUnsafeSwitches(properties);
			McpStartupWarnings.warnWhenOpenOnEveryInterface(properties, environment);
			Map<String, List<String>> springAiToolNames = SpringAiToolNames.read(beanFactory);
			McpStartupWarnings.warnWhenScopedToolsRunOpen(catalog, properties);
			McpStartupWarnings.warnWhenStdioScopesAreSetOverHttp(properties);
			McpCallSettings policy = McpCallPolicies.buildCallPolicy(properties, mimeTypes, rateLimiter,
					observationRegistry, beanFactory, applicationContext);
			List<McpStatelessServerFeatures.SyncToolSpecification> specifications = catalog.mcpTools()
				.stream()
				.map((tool) -> McpToolSpecifications.specification(tool, mcpServerJsonMapper, policy))
				.toList();
			SpringAiToolNames.warnOnClashingToolNames(catalog.mcpTools().stream().map(GATool::name).toList(),
					springAiToolNames);
			return specifications;
		}

		/**
		 * The transport of stateless Streamable HTTP, built here so that GATool's router
		 * function is the one that serves it.
		 *
		 * <p>
		 * Spring AI's router function installs its transport's routes, and its
		 * {@code @ConditionalOnMissingBean(name = "webMvcStatelessServerRouterFunction")}
		 * backs off for GATool's bean of that name because this auto-configuration is
		 * ordered before {@code McpServerStatelessWebMvcAutoConfiguration}. The transport
		 * is built here as well, the way Spring AI builds it, so Spring AI's own
		 * transport bean backs off by its type and one transport serves the endpoint,
		 * with every {@code McpError} body it answers rewritten by
		 * {@link McpErrorBodies}. The class conditions match those of
		 * {@link Stateful.StreamableHttp}: a context with the SDK and without
		 * {@code mcp-spring-webmvc} would otherwise meet a {@code NoClassDefFoundError}
		 * while Spring reflects on these bean methods.
		 */
		@Configuration(proxyBeanMethods = false)
		@ConditionalOnWebApplication(type = Type.SERVLET)
		@ConditionalOnClass(name = "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport")
		static class StreamableHttp {

			/**
			 * Builds the transport with the same two settings Spring AI's own bean method
			 * gives it: the MCP mapper and the endpoint.
			 *
			 * <p>
			 * The bean is a fallback, because Spring AI's server takes its transport by
			 * type. Among the candidates without a primary, Spring picks the one that is
			 * not a fallback, so an application's own {@code McpStatelessServerTransport}
			 * bean is the one the server is built on while this one stays the transport
			 * the router function serves. With the wrapper below in place, the wrapper is
			 * primary and this bean is what it wraps. An application that builds this
			 * transport itself, with a context extractor of its own, declares a bean of
			 * this type under any name: this one backs off, and the router function and
			 * the wrapper take the application's.
			 * @param mcpServerJsonMapper the mapper Spring AI's own bean method uses
			 * @param environment carries Spring AI's endpoint key
			 * @return the transport that serves the endpoint
			 */
			@Bean
			@Fallback
			@ConditionalOnMissingBean
			WebMvcStatelessServerTransport webMvcStatelessServerTransport(
					@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper, Environment environment) {
				return WebMvcStatelessServerTransport.builder()
					.jsonMapper(new JacksonMcpJsonMapper(mcpServerJsonMapper))
					.messageEndpoint(bindStreamableHttp(environment).getMcpEndpoint())
					.build();
			}

			// Spring AI's router bean backs off by this name, and this auto-configuration
			// is ordered before it, so the routes served carry the rewritten error
			// bodies.
			@Bean
			@ConditionalOnMissingBean(name = "webMvcStatelessServerRouterFunction")
			RouterFunction<ServerResponse> webMvcStatelessServerRouterFunction(WebMvcStatelessServerTransport transport,
					@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper) {
				return McpErrorBodies.withoutStackTraces(transport.getRouterFunction(), mcpServerJsonMapper);
			}

			/**
			 * Hands the SDK the revisions GATool serves, so the handshake can only agree
			 * to one the compliance filter accepts.
			 *
			 * <p>
			 * Spring AI's transport is final, so this wraps the bean above and publishes
			 * the wrapper as the primary {@code McpStatelessServerTransport}. The router
			 * function keeps the wrapped instance and this wrapper forwards to it.
			 *
			 * <p>
			 * The wrapper backs off for an application's own
			 * {@code McpStatelessServerTransport} bean, the WebMvc transport above left
			 * aside, so that bean is the one the server is built on and the SDK installs
			 * its handler there. Such a transport answers the handshake from whatever
			 * revision list it carries, so a client may agree to a revision GATool does
			 * not serve, 2025-03-26 among them. The compliance filter still refuses every
			 * request that names such a revision, at the HTTP layer, so the session
			 * cannot be used under it.
			 * @param transport the WebMvc transport, built above or by the application
			 * @param properties the GATool properties, for the superseded-revision switch
			 * @return the transport the MCP server reads its revisions from
			 */
			@Bean
			@Primary
			@ConditionalOnMissingBean(value = McpStatelessServerTransport.class,
					ignored = WebMvcStatelessServerTransport.class)
			McpStatelessServerTransport gaToolStatelessServerTransport(WebMvcStatelessServerTransport transport,
					GAToolProperties properties) {
				return new RevisionLimitedStatelessTransport(transport,
						properties.getMcp().getSecurity().getUnsafe().isAllowSupersededMcpRevisions());
			}

		}

	}

	/**
	 * The tools that a stateful server serves: stdio, and Streamable HTTP with a session
	 * per client under {@code spring.ai.mcp.server.protocol=STREAMABLE}.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STREAMABLE")
	static class Stateful {

		/**
		 * GATool builds the same stdio transport Spring AI builds and wraps it in one
		 * small class, so a stdio client cannot agree to the superseded MCP revision
		 * 2025-03-26, which the SDK serves in part. Over HTTP the compliance filter
		 * refuses a request naming that revision, and stdio runs without a filter. Spring
		 * AI's own bean cannot be wrapped through a condition, because GATool's
		 * configuration runs first, so GATool builds the provider itself and Spring AI
		 * backs off. The wrapper passes every message through unchanged, and the thread
		 * each answer leaves on is set on the server bean by
		 * {@link #gaToolStdioMcpSyncServerCustomizer()}.
		 *
		 * <p>
		 * The wrapper is the stdio counterpart of the stateless one above, so a stdio
		 * client negotiates the same revisions an HTTP client does.
		 *
		 * <p>
		 * This builds the SDK's provider the way Spring AI builds it and wraps the
		 * result, which is what the two HTTP configurations do for their transports.
		 * Spring AI's {@code stdioServerTransport} belongs to
		 * {@code McpServerAutoConfiguration}, which GATool is ordered before, and Boot
		 * evaluates {@code @ConditionalOnBean} at the {@code REGISTER_BEAN} phase against
		 * the definitions registered so far. So a condition on that name cannot match,
		 * and a wrapper it guards would not reach a running server. Building the provider
		 * here reverses the dependency: Spring AI's bean method carries
		 * {@code @ConditionalOnMissingBean} on {@code McpServerTransportProviderBase},
		 * which this bean satisfies, so Spring AI backs off and the server reads its
		 * revisions from the wrapper.
		 *
		 * <p>
		 * The property condition holds it to stdio. Stateful HTTP reaches this
		 * configuration too, and there Spring AI's own
		 * {@code webMvcStreamableServerTransportProvider} is the transport, so an
		 * unconditional stdio provider would leave the server choosing between two of
		 * them. The bean condition backs off for an application's own provider of any
		 * base type, for the same reason: Spring AI's server takes one
		 * {@code McpServerTransportProviderBase} by type, and two candidates stop startup
		 * with {@code NoUniqueBeanDefinitionException}.
		 * @param mcpServerJsonMapper spring AI's MCP mapper, which its own bean uses
		 * @param properties the GATool properties, for the superseded-revision switch
		 * @return the provider the MCP server reads its revisions from
		 */
		@Bean
		@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
		@ConditionalOnMissingBean(McpServerTransportProviderBase.class)
		McpServerTransportProvider gaToolStdioServerTransportProvider(
				@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper, GAToolProperties properties) {
			return new RevisionLimitedServerTransportProvider(
					new StdioServerTransportProvider(new JacksonMcpJsonMapper(mcpServerJsonMapper)),
					properties.getMcp().getSecurity().getUnsafe().isAllowSupersededMcpRevisions());
		}

		/**
		 * Runs each stdio tool call on the thread that reads stdin.
		 *
		 * <p>
		 * Over stdio the MCP SDK writes every answer through one queue that takes one
		 * answer at a time. A tool call runs on a pool of threads unless the server is
		 * told otherwise, so two answers can reach that queue at once, and the SDK then
		 * drops the second and closes the transport in silence. The process stays up with
		 * stdout quiet, and the client waits for an answer that has already been given
		 * up. This bean tells the SDK to run each tool call on the thread that reads
		 * stdin, which is the setting Spring AI applies to an HTTP server through a
		 * customizer of its own. Spring AI's customizer carries a servlet condition, and
		 * a stdio server is not a web application, so it is absent here.
		 *
		 * <p>
		 * The MCP Java SDK repaired the queue for
		 * <a href="https://github.com/modelcontextprotocol/java-sdk/issues/686">issue
		 * 686</a>. This bean goes away once the SDK release carrying that repair ships,
		 * and {@code McpSdkVersionTests} fails GATool's build on the first release after
		 * 2.0.1 to say so.
		 *
		 * <p>
		 * Spring AI's server bean takes one {@code McpSyncServerCustomizer}, so an
		 * application's own bean of that type takes the place of this one, and the
		 * condition below backs off for it. The startup check beside this bean stops an
		 * application whose own customizer leaves the setting out.
		 * @return the customizer that keeps every stdio answer on the reader thread
		 */
		// The web condition holds this bean to a context without a web application, which
		// is where Spring AI's own customizer is absent. An application that sets
		// spring.ai.mcp.server.stdio=true in a servlet application keeps both, because
		// GATool's configuration is ordered before McpServerAutoConfiguration and the
		// missing-bean condition is evaluated against the definitions registered so far,
		// so it cannot see Spring AI's. Spring AI's server bean injects one
		// McpSyncServerCustomizer, and two of them would stop startup with
		// NoUniqueBeanDefinitionException. Spring AI's customizer sets immediate
		// execution too, so the check below passes in a servlet application that serves
		// stdio.
		@Bean
		@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
		@ConditionalOnNotWebApplication
		@ConditionalOnMissingBean(McpSyncServerCustomizer.class)
		McpSyncServerCustomizer gaToolStdioMcpSyncServerCustomizer() {
			return (server) -> server.immediateExecution(true);
		}

		/**
		 * Stops startup where the stdio server bean was built without
		 * {@code immediateExecution(true)}.
		 *
		 * <p>
		 * {@link RequestThreadCheck} carries the same check for a servlet application and
		 * skips stdio, where GATool reads the caller from the one process that started
		 * the server. This check covers stdio for a second reason: an answer emitted
		 * beside another is dropped and the transport closes, so every answer stays on
		 * the thread that reads stdin.
		 *
		 * <p>
		 * The stateful sync server is the one shape stdio runs on, because Spring AI
		 * serves stdio on a stateful session and {@code stopOnAsyncServer} refuses the
		 * asynchronous servers.
		 * @param beanFactory holds the server beans and the customizer beans
		 * @return the check that runs once every singleton exists
		 */
		@Bean
		@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
		SmartInitializingSingleton gaToolStdioImmediateExecutionStartupCheck(
				ConfigurableListableBeanFactory beanFactory) {
			return () -> ImmediateExecutionCheck.requireImmediateExecution(beanFactory, McpSyncServer.class,
					ImmediateExecutionCheck::overStdio);
		}

		// Eager under lazy initialization for the reason the stateless list is.
		@Bean
		@Lazy(false)
		List<McpServerFeatures.SyncToolSpecification> gaToolMcpStatefulToolSpecifications(GAToolCatalog catalog,
				GAToolProperties properties, ToolRateLimiter rateLimiter,
				ObjectProvider<ObservationRegistry> observationRegistry, ConfigurableListableBeanFactory beanFactory,
				@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper,
				ApplicationContext applicationContext) {
			// The context's environment is the Environment bean, so reading it here keeps
			// this method inside the parameter limit.
			Environment environment = applicationContext.getEnvironment();
			McpStartupStops.stopWhenTheToolCapabilityIsOff(catalog, environment);
			Set<String> grantedScopes = null;
			if (SpringAiMcpKeys.servesStdio(environment)) {
				logger.info("GATool serves MCP over stdio.");
				grantedScopes = McpCallPolicies.readStdioScopes(catalog, properties);
			}
			else {
				McpStartupStops.stopWhenCallersArriveUnauthenticated(properties, environment);
				McpStartupWarnings.warnWhenScopedToolsRunOpen(catalog, properties);
				McpStartupWarnings.warnWhenStdioScopesAreSetOverHttp(properties);
				GAToolMcpProperties.Sessions sessions = properties.getMcp().getSessions();
				logger.info("GATool serves MCP over stateful Streamable HTTP, keeping at most " + sessions.getMaxCount()
						+ " sessions and evicting one idle for " + sessions.getIdleTimeout() + ".");
				McpStartupWarnings.warnWhenOpenOnEveryInterface(properties, environment);
				McpStartupWarnings.warnOnUnauthenticatedSessions(properties);
				McpStartupWarnings.warnOnMissingPerCallerSessionCap(properties);
			}
			McpStartupWarnings.warnOnUnsafeSwitches(properties);
			// stdio serves the same tools, so the same clash check runs here. Without it,
			// an @McpTool method sharing a name with an operation file would leave one of
			// the two unreachable, and startup would stay silent about it.
			SpringAiToolNames.warnOnClashingToolNames(catalog.mcpTools().stream().map(GATool::name).toList(),
					SpringAiToolNames.read(beanFactory));
			// Bound once, so the check below and the settings each call runs under read
			// the same map.
			Map<String, String> mimeTypes = McpCallPolicies.readToolResponseMimeTypes(environment);
			McpCallPolicies.requireImageToolsWithoutAnOutputSchema(catalog, mimeTypes);
			McpCallSettings policy = McpCallPolicies
				.buildCallPolicy(properties, mimeTypes, rateLimiter, observationRegistry, beanFactory,
						applicationContext)
				.withGrantedScopes(grantedScopes);
			return catalog.mcpTools()
				.stream()
				.map((tool) -> McpToolSpecifications.statefulSpecification(tool, mcpServerJsonMapper, policy))
				.toList();
		}

		/**
		 * The transport of stateful Streamable HTTP, built here so that the session cap
		 * and the idle timeout are set.
		 *
		 * <p>
		 * Spring AI 2.0.1's auto-configuration builds
		 * {@code WebMvcStreamableServerTransportProvider} with the endpoint, the
		 * keep-alive interval and the delete switch alone, which leaves
		 * {@code mcp-spring-webmvc} 2.0.1's builder at its own defaults: 100,000 sessions
		 * without idle eviction. This class builds the bean with the same three settings,
		 * read through Spring AI's own properties class, and adds
		 * {@code gatool.mcp.sessions.max-count} and
		 * {@code gatool.mcp.sessions.idle-timeout}. Spring AI's bean carries
		 * {@code @ConditionalOnMissingBean}, and this auto-configuration is ordered
		 * before it, so Spring AI backs off and its router function serves this instance.
		 * An application's own bean of the type still wins.
		 *
		 * <p>
		 * The stdio condition is the one Spring AI's own HTTP configuration carries
		 * through {@code McpServerStdioDisabledCondition}. A servlet application may
		 * serve MCP over stdio beside pages of its own. Without the condition this
		 * transport would register there as well, the stdio provider below would back off
		 * for it, and the server would answer on the HTTP port while every check that
		 * reads the stdio property takes the application for a stdio server and skips the
		 * authentication stop.
		 */
		@Configuration(proxyBeanMethods = false)
		@ConditionalOnWebApplication(type = Type.SERVLET)
		@ConditionalOnClass(
				name = "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider")
		@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "false",
				matchIfMissing = true)
		static class StreamableHttp {

			// This is a fallback for the reason the stateless transport is one: Spring
			// AI's server takes the provider by type. An application's own provider bean
			// of another type is then the one it takes, while this bean stays the one the
			// router function serves. See
			// Stateless.StreamableHttp.webMvcStatelessServerTransport.
			@Bean
			@Fallback
			@ConditionalOnMissingBean
			WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
					@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper, GAToolProperties properties,
					Environment environment) {
				McpServerStreamableHttpProperties streamableHttp = bindStreamableHttp(environment);
				GAToolMcpProperties.Sessions sessions = McpStartupStops.requireValidSessions(properties,
						streamableHttp);
				// The builder reads a null keep-alive interval as keep-alive off, which
				// is what Spring AI's own bean method hands it for the unset key.
				return WebMvcStreamableServerTransportProvider.builder()
					.jsonMapper(new JacksonMcpJsonMapper(mcpServerJsonMapper))
					.mcpEndpoint(streamableHttp.getMcpEndpoint())
					.keepAliveInterval(streamableHttp.getKeepAliveInterval())
					.disallowDelete(streamableHttp.isDisallowDelete())
					.maxSessions(sessions.getMaxCount())
					.sessionIdleTimeout(sessions.getIdleTimeout())
					.build();
			}

			/**
			 * Ends the sessions of the stateful transport before the web server starts
			 * its graceful shutdown.
			 *
			 * <p>
			 * A client that listens on {@code GET /mcp} holds one request open. Spring
			 * Boot's graceful shutdown waits for open requests before the beans are
			 * destroyed, and the transport ends its streams only when it is destroyed, so
			 * every stop with a connected client would wait out
			 * {@code spring.lifecycle.timeout-per-shutdown-phase}, 30 seconds by default.
			 * Spring AI has the wait on record as
			 * <a href="https://github.com/spring-projects/spring-ai/issues/4002">issue
			 * 4002</a>, and this bean goes away once a release closes the streams itself.
			 *
			 * <p>
			 * The bean keeps the default phase of a {@code SmartLifecycle}, the highest,
			 * so it stops ahead of Boot's {@code webServerGracefulShutdown}.
			 * @param provider the stateful transport provider that serves the endpoint
			 * @return the lifecycle that closes the transport first at shutdown
			 */
			@Bean
			SmartLifecycle gaToolStreamableTransportShutdown(WebMvcStreamableServerTransportProvider provider) {
				return new StreamableTransportShutdown(provider);
			}

			@Bean
			@ConditionalOnMissingBean(name = "webMvcStreamableServerRouterFunction")
			RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
					WebMvcStreamableServerTransportProvider provider,
					@Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper) {
				return McpErrorBodies.withoutStackTraces(provider.getRouterFunction(), mcpServerJsonMapper);
			}

			/**
			 * Hands the stateful HTTP server the revisions GATool serves, the way the
			 * stateless wrapper does for its transport.
			 *
			 * <p>
			 * The provider is final, so this wraps the bean above and publishes the
			 * wrapper as the primary provider. Spring AI's router function keeps the
			 * instance itself, and {@code mcpSyncServer} takes the primary, so one
			 * transport serves the endpoint with GATool's revision list.
			 *
			 * <p>
			 * The wrapper backs off for an application's own
			 * {@code McpStreamableServerTransportProvider} bean, the WebMvc provider
			 * above left aside, so that bean is the one the server is built on. Such a
			 * provider answers the handshake from whatever revision list it carries, so a
			 * client may agree to a revision GATool does not serve. The compliance filter
			 * still refuses every request that names such a revision, at the HTTP layer,
			 * so the session cannot be used under it.
			 * @param provider the stateful transport provider, built above or by the
			 * application
			 * @param properties the GATool properties, for the superseded-revision switch
			 * @return the provider the MCP server reads its revisions from
			 */
			@Bean
			@Primary
			@ConditionalOnMissingBean(value = McpStreamableServerTransportProvider.class,
					ignored = WebMvcStreamableServerTransportProvider.class)
			McpStreamableServerTransportProvider gaToolStreamableServerTransportProvider(
					WebMvcStreamableServerTransportProvider provider, GAToolProperties properties) {
				return new RevisionLimitedStreamableServerTransportProvider(provider,
						properties.getMcp().getSecurity().getUnsafe().isAllowSupersededMcpRevisions());
			}

		}

	}

}
