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

package io.gatool.boot.inprocess;

import org.springframework.ai.tool.ToolCallbackProvider;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.inprocess.internal.GAToolCallbackFactory;

/**
 * Attaches the in-process tools to a Spring AI {@code ChatClient}:
 *
 * <pre>
 *   &#64;Bean
 *   ChatClient assistant(ChatClient.Builder builder, GAToolCallbacks tools) {
 *       return builder.defaultTools(tools.toolCallbackProvider()).build();
 *   }
 * </pre>
 *
 * <p>
 * Options considered for this handle: a bean of this project's own type, which is this
 * class; a {@code ToolCallbackProvider} bean; ordinary {@code ToolCallback} beans with
 * {@code spring.ai.mcp.server.tool-callback-converter=false} in the application. GATool
 * serves the in-process tools through this type, because Spring AI 2.0.1's MCP converters
 * collect every {@code ToolCallback} and {@code ToolCallbackProvider} bean, and every
 * list of both, and publish them on the MCP server. A bean of this type sits outside that
 * collection and outside Spring AI's global tool resolver, so the in-process tools reach
 * the {@code ChatClient} the application attaches them to, and they stay off the MCP
 * server. Switching the converter off would also remove the application's other tool
 * beans from its MCP server.
 *
 * <p>
 * Each {@code ChatClient} gets the tools its own code attaches, which leaves token use
 * with the application. Tools passed per request with {@code .tools(...)} join the
 * builder's default tools, which is what Spring AI 2.0.1 does.
 *
 * @author Željko Kozina
 */
public final class GAToolCallbacks {

	private final GAToolCatalog catalog;

	/**
	 * Creates the handle for one configuration.
	 * @param catalog the configuration and tool catalog that both exposure types share
	 */
	public GAToolCallbacks(GAToolCatalog catalog) {
		this.catalog = catalog;
	}

	/**
	 * Returns a provider that builds one Spring AI callback per in-process tool.
	 *
	 * <p>
	 * The provider reads the tool catalog on every call, so a later release that reloads
	 * operation files reaches every {@code ChatClient} that holds this provider.
	 * @return the provider to pass to {@code ChatClient.Builder.defaultTools}
	 */
	public ToolCallbackProvider toolCallbackProvider() {
		return () -> GAToolCallbackFactory.callbacksFor(this.catalog.inProcessTools());
	}

}
