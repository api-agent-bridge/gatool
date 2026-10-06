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

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Settles the three capability switches of Spring AI's server for
 * {@link GAToolMcpAutoConfiguration#gaToolServerCapabilitiesMarker}.
 *
 * <p>
 * The class holds the settling so that the auto-configuration keeps its bean methods and
 * little else. The log line keeps the auto-configuration's category, so a level set on
 * that class still covers it.
 *
 * @author Željko Kozina
 */
final class McpServerCapabilities {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	private McpServerCapabilities() {
	}

	/**
	 * Switches off each capability the application does not serve, where the property was
	 * left unset.
	 * @param serverProperties spring AI's own properties, which its server reads, and
	 * which a context without an MCP server leaves out
	 * @param environment carries the properties an application set by hand
	 * @param beanFactory holds the specification beans an application publishes
	 */
	static void settle(ObjectProvider<McpServerProperties> serverProperties, Environment environment,
			ConfigurableListableBeanFactory beanFactory) {
		// A context without Spring AI's own properties does not have a server to
		// advertise anything, which is the shape of a slice test.
		McpServerProperties properties = serverProperties.getIfAvailable();
		if (properties == null) {
			return;
		}
		List<String> switchedOff = new ArrayList<>();
		if (isCapabilityPropertyUnset(environment, "resource")
				&& none(beanFactory, McpStatelessServerFeatures.SyncResourceSpecification.class,
						McpStatelessServerFeatures.SyncResourceTemplateSpecification.class,
						McpServerFeatures.SyncResourceSpecification.class,
						McpServerFeatures.SyncResourceTemplateSpecification.class)) {
			properties.getCapabilities().setResource(false);
			switchedOff.add("resources");
		}
		if (isCapabilityPropertyUnset(environment, "prompt")
				&& none(beanFactory, McpStatelessServerFeatures.SyncPromptSpecification.class,
						McpServerFeatures.SyncPromptSpecification.class)) {
			properties.getCapabilities().setPrompt(false);
			switchedOff.add("prompts");
		}
		if (isCapabilityPropertyUnset(environment, "completion")
				&& none(beanFactory, McpStatelessServerFeatures.SyncCompletionSpecification.class,
						McpServerFeatures.SyncCompletionSpecification.class)) {
			properties.getCapabilities().setCompletion(false);
			switchedOff.add("completions");
		}
		if (!switchedOff.isEmpty()) {
			logger.info("GATool leaves " + String.join(", ", switchedOff) + " out of the capabilities this server "
					+ "advertises, because nothing in this application serves them. Publish a specification bean, "
					+ "or set spring.ai.mcp.server.capabilities.<name>=true, to advertise one.");
		}
	}

	// Returns true when each of these specification types is absent from the bean
	// factory or published as an empty list.
	// Both the stateless and the stateful specification types are checked, because stdio
	// runs the stateful server and an application declares its resources in the shape
	// that server reads.
	private static boolean none(ConfigurableListableBeanFactory beanFactory, Class<?>... specificationTypes) {
		for (Class<?> specificationType : specificationTypes) {
			ResolvableType listOfSpecifications = ResolvableType.forClassWithGenerics(List.class, specificationType);
			for (String name : beanFactory.getBeanNamesForType(listOfSpecifications)) {
				if (!((List<?>) beanFactory.getBean(name)).isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isCapabilityPropertyUnset(Environment environment, String capability) {
		return !environment.containsProperty("spring.ai.mcp.server.capabilities." + capability);
	}

}
