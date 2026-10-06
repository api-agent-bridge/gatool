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

package io.gatool.boot.inprocess.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.internal.GAToolCallbackFactory;
import io.gatool.core.model.GATool;

/**
 * Publishes the {@link GAToolCallbacks} bean that attaches the in-process tools to a
 * {@code ChatClient}.
 *
 * <p>
 * The class condition names {@code ToolCallback}, which says that Spring AI's tool types
 * are present, and the bean condition names the catalog the shared auto-configuration
 * publishes. This class ships in {@code gatool-in-process-spring-boot}, which the
 * in-process starter alone brings, so this class and its imports file stay out of an
 * application that added the MCP starter alone, and the condition on {@code ToolCallback}
 * is enough to tell the two flavours apart.
 *
 * @author Željko Kozina
 */
@AutoConfiguration(after = GAToolAutoConfiguration.class)
@ConditionalOnClass(ToolCallback.class)
@ConditionalOnBean(GAToolCatalog.class)
public final class GAToolInProcessAutoConfiguration {

	private static final Log logger = LogFactory.getLog(GAToolInProcessAutoConfiguration.class);

	/**
	 * Creates the auto-configuration, which Spring Boot instantiates at startup.
	 */
	public GAToolInProcessAutoConfiguration() {
		// The beans come from the methods below; Spring Boot instantiates the class.
	}

	// Returns the tool callbacks one provider lists, or an empty list where listing them
	// failed.
	//
	// A provider decides for itself what listing its tools costs. Spring AI's MCP client
	// provider lists them on a remote server, so an unreachable server would fail this
	// application's startup over a name check. The clash check then covers the providers
	// that answered, and the log names the one that did not.
	private static List<ToolCallback> toolCallbacksOf(ToolCallbackProvider provider) {
		try {
			return List.of(provider.getToolCallbacks());
		}
		catch (RuntimeException ex) {
			logger.warn("The tool names of " + provider.getClass().getName() + " stayed out of the clash check, "
					+ "because listing its tools failed", ex);
			return List.of();
		}
	}

	/**
	 * Publishes the handle for the application's own {@code ChatClient} beans.
	 *
	 * <p>
	 * GATool checks its own tool names against the tool beans an application declares,
	 * because a {@code ChatClient} given two tools of one name calls whichever it
	 * resolved, and the model reads a description that belongs to the other. The check
	 * uses Spring AI's public {@code ToolUtils.getDuplicateToolNames}, which is the
	 * method Spring AI's own duplicate check calls.
	 * @param catalog the configuration and tool catalog that both exposure types share
	 * @param applicationCallbacks the {@code ToolCallback} beans the application
	 * declares, which stay out of GATool's own catalog
	 * @param applicationProviders the {@code ToolCallbackProvider} beans it declares,
	 * which Spring AI's MCP converter collects as well
	 * @param environment says whether that converter is active
	 * @return the handle that attaches the in-process tools to a {@code ChatClient}
	 */
	@Bean
	@ConditionalOnMissingBean
	GAToolCallbacks gaToolCallbacks(GAToolCatalog catalog, ObjectProvider<ToolCallback> applicationCallbacks,
			ObjectProvider<ToolCallbackProvider> applicationProviders, Environment environment) {
		List<ToolCallback> declared = new ArrayList<>(applicationCallbacks.orderedStream().toList());
		applicationProviders.orderedStream().forEach((provider) -> declared.addAll(toolCallbacksOf(provider)));
		stopOnClashingNames(catalog, declared, environment);
		return new GAToolCallbacks(catalog);
	}

	// Fails startup where two in-process tool names clash, comparing the application's
	// own tool beans with the tools GATool serves.
	//
	// A clash stops startup, because the alternative is a ChatClient that answers from
	// one of two tools without saying which. Where the clashing name is one GATool
	// serves, the bean is carrying GATool's own callbacks, and the action below hands
	// back the code a ChatClient wants. The exception carries a description and an action
	// apart, so the failure analyzer prints them where Boot's other reports appear.
	private static void stopOnClashingNames(GAToolCatalog catalog, List<ToolCallback> applicationCallbacks,
			Environment environment) {
		if (applicationCallbacks.isEmpty()) {
			return;
		}
		List<ToolCallback> everyCallback = new ArrayList<>(applicationCallbacks);
		everyCallback.addAll(List.of(GAToolCallbackFactory.callbacksFor(catalog.inProcessTools())));
		List<String> duplicates = ToolUtils.getDuplicateToolNames(everyCallback);
		if (duplicates.isEmpty()) {
			return;
		}
		Set<String> gaToolNames = catalog.inProcessTools().stream().map(GATool::name).collect(Collectors.toSet());
		List<String> gaToolClashes = duplicates.stream().filter(gaToolNames::contains).toList();
		if (!gaToolClashes.isEmpty()) {
			throw new InProcessToolNameClashException(inProcessToolsOnTheMcpServer(gaToolClashes, environment),
					attachToAChatClient());
		}
		throw new InProcessToolNameClashException(
				"These in-process tool names clash: " + String.join(", ", duplicates)
						+ ". A ChatClient holds one tool per name.",
				"Rename one of each pair in the bean that declares it.");
	}

	// Returns the description of the startup failure for a bean of the application that
	// declares GATool's own in-process tools.
	//
	// GATool keeps the in-process tools off the MCP server by handing them out as a bean
	// of its own type. A ToolCallback or ToolCallbackProvider bean carrying them
	// undoes that, because Spring AI's converter collects both types and publishes them
	// on the MCP server, so the same tools arrive twice for an agent and once more for
	// every ChatClient.
	private static String inProcessToolsOnTheMcpServer(List<String> names, Environment environment) {
		StringBuilder message = new StringBuilder("A bean of the application declares the GATool tools ")
			.append(String.join(", ", names))
			.append('.');
		if (isConverterActive(environment)) {
			message.append(
					" Spring AI's MCP converter is active here, so those beans reach the MCP server as " + "tools.");
		}
		return message.toString();
	}

	// Returns the action for that failure: the code that attaches GATool's tools to a
	// ChatClient.
	private static String attachToAChatClient() {
		return "Attach them to a ChatClient instead, and GATool keeps them off the MCP server:" + System.lineSeparator()
				+ System.lineSeparator() + "    @Bean" + System.lineSeparator()
				+ "    ChatClient assistant(ChatClient.Builder builder, GAToolCallbacks tools) {"
				+ System.lineSeparator() + "        return builder.defaultTools(tools.toolCallbackProvider()).build();"
				+ System.lineSeparator() + "    }" + System.lineSeparator();
	}

	// Returns whether Spring AI's MCP tool callback converter is active in this
	// application.
	//
	// Both conditions on Spring AI's converter carry matchIfMissing, so the converter
	// runs until an application turns one of them off.
	private static boolean isConverterActive(Environment environment) {
		return environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class, true)
				&& environment.getProperty("spring.ai.mcp.server.tool-callback-converter", Boolean.class, true);
	}

}
