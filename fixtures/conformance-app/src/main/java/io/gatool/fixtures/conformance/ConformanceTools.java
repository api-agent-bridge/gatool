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

package io.gatool.fixtures.conformance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * The tools the MCP conformance suite calls.
 *
 * <p>
 * Each scenario names the tool it calls and describes the result it expects, so every
 * method here answers one scenario. The names and the result shapes come from the suite
 * itself and belong to it, which is why they read differently from the tools an
 * application would write.
 *
 * <p>
 * A method that returns a {@link McpSchema.CallToolResult} has it passed to the client
 * unchanged, so these methods build the content types the scenarios ask for. Each one
 * uses the canonical constructor, the form that ends with the meta map, because MCP Java
 * SDK 2.0.0 deprecates the shorter forms and the build treats a deprecation warning as an
 * error. The tools an application builds from GraphQL operation files sit beside these,
 * and {@code tools/list} carries both.
 *
 * @author Željko Kozina
 */
@Component
public class ConformanceTools {

	// The suite asks for a minimal image and a minimal audio file. It checks the
	// content type, the data and the media type, so the smallest valid file of each
	// kind serves: a one pixel PNG and a WAV header without samples.
	private static final String ONE_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	private static final String SILENT_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=";

	// The suite waits for several notifications during one call, so each step pauses
	// long enough for the client to read them apart.
	private static final long STEP_PAUSE_MILLIS = 50;

	/**
	 * Returns one piece of text.
	 * @return the text result the scenario expects
	 */
	@McpTool(name = "test_simple_text", description = "Returns a simple text response.")
	public McpSchema.CallToolResult simpleText() {
		return McpSchema.CallToolResult.builder().addTextContent("This is a simple text response for testing.").build();
	}

	/**
	 * Returns an image.
	 * @return a PNG as base64 image content
	 */
	@McpTool(name = "test_image_content", description = "Returns image content.")
	public McpSchema.CallToolResult imageContent() {
		return McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.ImageContent(null, ONE_PIXEL_PNG, "image/png", null))
			.build();
	}

	/**
	 * Returns audio.
	 * @return a WAV as base64 audio content
	 */
	@McpTool(name = "test_audio_content", description = "Returns audio content.")
	public McpSchema.CallToolResult audioContent() {
		return McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.AudioContent(null, SILENT_WAV, "audio/wav", null))
			.build();
	}

	/**
	 * Returns a resource inside the result.
	 * @return text contents carried as an embedded resource
	 */
	@McpTool(name = "test_embedded_resource", description = "Returns an embedded resource.")
	public McpSchema.CallToolResult embeddedResource() {
		McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents("test://embedded-resource",
				"text/plain", "This is an embedded resource content.", null);
		return McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.EmbeddedResource(null, contents, null))
			.build();
	}

	/**
	 * Returns text, an image and a resource together.
	 * @return the three content types in one result
	 */
	@McpTool(name = "test_multiple_content_types", description = "Returns several content types in one result.")
	public McpSchema.CallToolResult multipleContentTypes() {
		McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents("test://mixed-content-resource",
				"application/json", "{\"test\":\"data\",\"value\":123}", null);
		return McpSchema.CallToolResult.builder()
			.addTextContent("Multiple content types test:")
			.addContent(new McpSchema.ImageContent(null, ONE_PIXEL_PNG, "image/png", null))
			.addContent(new McpSchema.EmbeddedResource(null, contents, null))
			.build();
	}

	/**
	 * Answers with a tool error.
	 *
	 * <p>
	 * The result carries {@code isError}, which is how MCP reports a failure the model
	 * can read and act on. A thrown exception reaches the client the same way, and this
	 * returns the flag so that the text stays exactly what the scenario expects.
	 * @return a result marked as an error
	 */
	@McpTool(name = "test_error_handling", description = "Always answers with a tool error.")
	public McpSchema.CallToolResult errorHandling() {
		return McpSchema.CallToolResult.builder()
			.isError(true)
			.addTextContent("This tool intentionally returns an error for testing")
			.build();
	}

	/**
	 * Reports progress while it runs.
	 *
	 * <p>
	 * A stateless server leaves this tool unregistered. Spring AI 2.0.1 skips any tool
	 * method that takes a request context there, and its log says so: "Stateless servers
	 * doesn't support bidirectional parameters." The scenario then looks for a tool that
	 * is missing from {@code tools/list}, which is why the baseline carries it. The
	 * method stays here for the release that serves stateful Streamable HTTP.
	 * @param context carries the progress token of the call
	 * @return the text result that follows the notifications
	 */
	@McpTool(name = "test_tool_with_progress", description = "Reports progress while it runs.")
	public McpSchema.CallToolResult toolWithProgress(McpSyncRequestContext context) {
		context.progress((step) -> step.progress(0).total(100));
		pause();
		context.progress((step) -> step.progress(50).total(100));
		pause();
		context.progress((step) -> step.progress(100).total(100));
		return McpSchema.CallToolResult.builder().addTextContent("Progress tool completed successfully.").build();
	}

	/**
	 * Sends log messages while it runs.
	 *
	 * <p>
	 * A stateless server leaves this tool unregistered as well, for the reason given
	 * above, so the baseline carries its scenario too.
	 * @param context sends each message to the client
	 * @return the text result that follows the messages
	 */
	@McpTool(name = "test_tool_with_logging", description = "Sends log messages while it runs.")
	public McpSchema.CallToolResult toolWithLogging(McpSyncRequestContext context) {
		context.info("Tool execution started");
		pause();
		context.info("Tool processing data");
		pause();
		context.info("Tool execution completed");
		return McpSchema.CallToolResult.builder().addTextContent("Logging tool completed successfully.").build();
	}

	/**
	 * Asks the client's model for a completion, which is sampling.
	 *
	 * <p>
	 * A stateless server leaves this tool unregistered too, because the call back to the
	 * client needs a session, so the stateless baseline carries its scenario and the
	 * stateful run passes it.
	 * @param context reaches the client
	 * @param prompt what to ask the model
	 * @return the model's answer as text
	 */
	@McpTool(name = "test_sampling", description = "Asks the client's model to answer a prompt.")
	public McpSchema.CallToolResult sampling(McpSyncRequestContext context,
			@McpToolParam(description = "The prompt to send to the model.", required = true) String prompt) {
		if (!context.sampleEnabled()) {
			return McpSchema.CallToolResult.builder()
				.isError(true)
				.addTextContent("The client did not declare the sampling capability.")
				.build();
		}
		McpSchema.CreateMessageResult answer = context.sample(prompt);
		String text = (answer.content() instanceof McpSchema.TextContent content) ? content.text()
				: String.valueOf(answer.content());
		return McpSchema.CallToolResult.builder().addTextContent("Sampling response: " + text).build();
	}

	/**
	 * Asks the user for two values, which is elicitation.
	 * @param context reaches the client
	 * @param message what to show the user
	 * @return the user's answer as text
	 */
	@McpTool(name = "test_elicitation", description = "Asks the user for a username and an email address.")
	public McpSchema.CallToolResult elicitation(McpSyncRequestContext context,
			@McpToolParam(description = "The message to show the user.", required = true) String message) {
		if (!context.elicitEnabled()) {
			return McpSchema.CallToolResult.builder()
				.isError(true)
				.addTextContent("The client did not declare the elicitation capability.")
				.build();
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", Map.of("username", Map.of("type", "string", "description", "User's response"), "email",
				Map.of("type", "string", "description", "User's email address")));
		schema.put("required", List.of("username", "email"));
		McpSchema.ElicitResult answer = context.elicit(McpSchema.ElicitFormRequest.builder(message, schema).build());
		return McpSchema.CallToolResult.builder()
			.addTextContent("User response: action=" + answer.action() + ", content=" + answer.content())
			.build();
	}

	/**
	 * Asks the user with a default for every primitive type, which SEP-1034 added.
	 * @param context reaches the client
	 * @return the user's answer as text
	 */
	@McpTool(name = "test_elicitation_sep1034_defaults",
			description = "Asks the user with a default value for every primitive type.")
	public McpSchema.CallToolResult elicitationWithDefaults(McpSyncRequestContext context) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("name", Map.of("type", "string", "default", "John Doe"));
		properties.put("age", Map.of("type", "integer", "default", 30));
		properties.put("score", Map.of("type", "number", "default", 95.5));
		properties.put("status",
				Map.of("type", "string", "enum", List.of("active", "inactive", "pending"), "default", "active"));
		properties.put("verified", Map.of("type", "boolean", "default", true));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		McpSchema.ElicitResult answer = context
			.elicit(McpSchema.ElicitFormRequest.builder("Confirm or change the defaults.", schema).build());
		return McpSchema.CallToolResult.builder()
			.addTextContent("Elicitation completed: action=" + answer.action() + ", content=" + answer.content())
			.build();
	}

	/**
	 * Asks the user with the five enum shapes SEP-1330 describes.
	 * @param context reaches the client
	 * @return the user's answer as text
	 */
	@McpTool(name = "test_elicitation_sep1330_enums",
			description = "Asks the user with every enum shape the specification describes.")
	public McpSchema.CallToolResult elicitationWithEnums(McpSyncRequestContext context) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("untitledSingle", Map.of("type", "string", "enum", List.of("option1", "option2", "option3")));
		properties.put("titledSingle",
				Map.of("type", "string", "oneOf",
						List.of(Map.of("const", "value1", "title", "First Option"),
								Map.of("const", "value2", "title", "Second Option"),
								Map.of("const", "value3", "title", "Third Option"))));
		properties.put("legacyEnum", Map.of("type", "string", "enum", List.of("opt1", "opt2", "opt3"), "enumNames",
				List.of("Option One", "Option Two", "Option Three")));
		properties.put("untitledMulti", Map.of("type", "array", "items",
				Map.of("type", "string", "enum", List.of("option1", "option2", "option3"))));
		properties.put("titledMulti",
				Map.of("type", "array", "items",
						Map.of("anyOf",
								List.of(Map.of("const", "value1", "title", "First Choice"),
										Map.of("const", "value2", "title", "Second Choice"),
										Map.of("const", "value3", "title", "Third Choice")))));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		McpSchema.ElicitResult answer = context
			.elicit(McpSchema.ElicitFormRequest.builder("Pick from each list.", schema).build());
		return McpSchema.CallToolResult.builder()
			.addTextContent("Elicitation completed: action=" + answer.action() + ", content=" + answer.content())
			.build();
	}

	private static void pause() {
		try {
			Thread.sleep(STEP_PAUSE_MILLIS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
