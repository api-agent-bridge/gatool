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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.web.context.WebApplicationContext;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.internal.SpringAiMcpKeys;
import io.gatool.boot.mcp.internal.limit.RequestCaller;
import io.gatool.boot.mcp.internal.server.McpCallSettings;
import io.gatool.boot.mcp.limit.ToolRateLimiter;
import io.gatool.core.internal.operation.ScopeNames;
import io.gatool.core.model.GATool;

/**
 * Builds the policy every MCP tool call runs under, and reads the settings it is built
 * from.
 *
 * <p>
 * The two tool specification beans of {@link GAToolMcpAutoConfiguration} call these. The
 * class holds them so that the auto-configuration keeps its bean methods and little else.
 *
 * @author Željko Kozina
 */
final class McpCallPolicies {

	private McpCallPolicies() {
	}

	/**
	 * Builds the policy every MCP tool call runs under: the rate limiter, the caller, the
	 * observation registry, whether an observation carries the call's arguments and
	 * result, and the tool response mime types.
	 * @param properties the GATool properties
	 * @param mimeTypes the response mime type of each named tool, as bound from the
	 * environment
	 * @param rateLimiter the limiter every call passes
	 * @param observationRegistry the registry an application's actuator publishes, if any
	 * @param beanFactory holds the request caller bean where the security
	 * auto-configuration published one
	 * @param applicationContext says whether the application is a web application
	 * @return the settings every call runs under
	 */
	// Boot publishes the ObservationRegistry bean from its actuator, which an
	// application may leave out, so the registry falls back to NOOP and the observation
	// of each tool call costs that application a few field reads per call. The caller
	// is the bean the security auto-configuration publishes, which reads the
	// SecurityContextHolderStrategy bean an application declares; without Spring
	// Security's web support on the classpath, the static holder is what remains, and
	// every caller is then named by address.
	static McpCallSettings buildCallPolicy(GAToolProperties properties, Map<String, String> mimeTypes,
			ToolRateLimiter rateLimiter, ObjectProvider<ObservationRegistry> observationRegistry,
			ConfigurableListableBeanFactory beanFactory, ApplicationContext applicationContext) {
		RequestCaller caller = beanFactory.getBeanProvider(RequestCaller.class)
			.getIfAvailable(RequestCaller::throughStaticHolder);
		return new McpCallSettings(rateLimiter, caller,
				observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
				properties.getObservations().isIncludeContent(), mimeTypes)
			.requiringTheRequestThread(requiresTheRequestThread(applicationContext));
	}

	// Whether a tool call has to arrive on the thread that served its HTTP request.
	// The rule holds for a servlet application serving the MCP endpoint, where the
	// compliance filter marks the request thread. It is off over stdio, where one
	// process is the only caller and a request thread does not exist. It is off as well
	// in a context that is not a web application, where a test or an application calls a
	// specification's handler directly.
	private static boolean requiresTheRequestThread(ApplicationContext applicationContext) {
		return applicationContext instanceof WebApplicationContext
				&& !SpringAiMcpKeys.servesStdio(applicationContext.getEnvironment());
	}

	/**
	 * Returns the mime type each named tool's response carries, bound from
	 * {@code spring.ai.mcp.server.tool-response-mime-type}.
	 * @param environment the environment the property is bound from
	 * @return the mime type of each tool's response by tool name, trimmed by the reader
	 * and empty where the property is unset
	 */
	// Spring AI's own converter reads this property for an application's own tools, and
	// a tool built from an operation file answers the same setting. The value is read
	// from the Environment instead of from the bound McpServerProperties bean, which is
	// how this class reads Spring AI's other keys, and it keeps that bean optional.
	// Spring AI registers the bean from its server auto-configuration, so a context that
	// runs this class without that one is left without the bean, and a map read from
	// the bean would be empty there: the check of an image tool and the check of each
	// value would both pass over what the application set.
	static Map<String, String> readToolResponseMimeTypes(Environment environment) {
		Map<String, String> mimeTypes = Binder.get(environment)
			.bind("spring.ai.mcp.server.tool-response-mime-type", Bindable.mapOf(String.class, String.class))
			.orElseGet(Map::of);
		// Spring AI parses this property at startup for an application's own tools, where
		// a value without a subtype raises InvalidMimeTypeException. A GATool tool
		// reading the raw string would let image, a plausible typo for image/png, publish
		// an ImageContent whose mimeType a client cannot map to a decoder, so both fail
		// at the same moment.
		mimeTypes.forEach((toolName, mimeType) -> {
			try {
				MimeType.valueOf(mimeType.trim());
			}
			catch (InvalidMimeTypeException ex) {
				throw new InvalidConfigurationPropertyValueException(
						"spring.ai.mcp.server.tool-response-mime-type." + toolName, mimeType,
						"This is a media type, such as image/png, and this value cannot be parsed as one. "
								+ "Spring AI reads the same property for an application's own tools and refuses "
								+ "it the same way.");
			}
		});
		return mimeTypes;
	}

	/**
	 * Fails startup when a tool carries an image response mime type and an output schema.
	 *
	 * <p>
	 * The two contradict each other. An image response answers with image content alone,
	 * while a published output schema tells the SDK to expect structured content, so the
	 * SDK answers every call to that tool with its own missing-structured-content error.
	 * Both settings are documented, so a team can reach this pair by reading the
	 * documentation, and the tool then fails on every call, while startup passes without
	 * a line to explain it.
	 * @param catalog the tools this application publishes
	 * @param mimeTypes spring AI's mime type map, as bound from the environment
	 */
	static void requireImageToolsWithoutAnOutputSchema(GAToolCatalog catalog, Map<String, String> mimeTypes) {
		if (mimeTypes.isEmpty()) {
			return;
		}
		for (GATool tool : catalog.mcpTools()) {
			String mimeType = mimeTypes.get(tool.name());
			if (mimeType == null || !mimeType.trim().toLowerCase(Locale.ROOT).startsWith("image")
					|| tool.outputSchema() == null) {
				continue;
			}
			throw new InvalidConfigurationPropertyValueException(
					"spring.ai.mcp.server.tool-response-mime-type." + tool.name(), mimeType,
					"The tool " + tool.name() + " also publishes an output schema, and the two cannot both hold. "
							+ "An image response carries image content alone, while an output "
							+ "schema tells the MCP SDK to expect structured content, so every call to this tool "
							+ "would come back as the SDK's own missing-structured-content error. Drop this mime "
							+ "type, or stop the output schema for this tool with @gatool(outputSchema: false) in "
							+ "its operation file, or turn gatool.results.publish-output-schema off.");
		}
	}

	/**
	 * Reads the scopes the stdio process holds, and stops startup when tools require
	 * scopes and the environment leaves the list unset.
	 * @param catalog the tools this application publishes
	 * @param properties the GATool properties, which carry the granted scopes
	 * @return the granted scopes, or {@code null} where the list is unset and every tool
	 * runs without scopes
	 */
	// Over stdio the caller is the process that started the server, so the scopes it
	// holds are a fact of the environment, and a tool that requires a scope has to
	// be told what the environment grants. A list is read as it is, and a tool
	// needing more answers with a tool error, the way a 403 answers over HTTP.
	static @Nullable Set<String> readStdioScopes(GAToolCatalog catalog, GAToolProperties properties) {
		List<String> granted = properties.getMcp().getStdio().getGrantedScopes();
		List<String> toolsRequiringScopes = catalog.mcpTools()
			.stream()
			.filter((tool) -> tool.scopes() != null && !tool.scopes().isEmpty())
			.map((tool) -> tool.name() + " (" + String.join(" ", Objects.requireNonNull(tool.scopes())) + ")")
			.toList();
		if (granted != null) {
			for (String scope : granted) {
				String problem = ScopeNames.problemWith(scope);
				if (problem != null) {
					throw new InvalidConfigurationPropertyValueException("gatool.mcp.stdio.granted-scopes", scope,
							"The scope " + problem + ". A scope is printable ASCII without a space, a quote "
									+ "or a backslash.");
				}
			}
		}
		if (granted == null) {
			if (toolsRequiringScopes.isEmpty()) {
				return null;
			}
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.stdio.granted-scopes", null,
					"These tools require scopes: " + String.join(", ", toolsRequiringScopes)
							+ ". Over stdio the caller is "
							+ "the process that started this server, so set this property, in the "
							+ "environment, to the scopes that process holds. A tool needing a scope outside "
							+ "the list answers with a tool error.");
		}
		return new LinkedHashSet<>(granted);
	}

}
