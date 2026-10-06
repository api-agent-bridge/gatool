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

package io.gatool.boot.inprocess.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;

import io.gatool.core.model.GATool;

/**
 * Builds one Spring AI {@link ToolCallback} for each in-process tool, so a
 * {@code ChatClient} calls the same operation the MCP server serves, with the same name,
 * description and input schema.
 *
 * <p>
 * The definition comes from {@code FunctionToolCallback}'s builder, which fills the
 * description fallback and the input schema the way a hand-written tool gets them. The
 * callback itself is {@link GAToolCallback}, because {@code FunctionToolCallback} parses
 * the argument text ahead of its own try block: a model that sends malformed arguments
 * would otherwise reach the caller of the {@code ChatClient} instead of reading a tool
 * error it can correct. Wrapping the parse is what a hand-written tool gets from
 * {@code MethodToolCallback}, so a generated tool answers the same way.
 *
 * <p>
 * The input type is {@code Map<String, Object>}, which is the type the tool handler
 * takes. With {@code spring-ai-model} 2.0.1 and Jackson 3.1.5,
 * {@code FunctionToolCallback} parses the argument text ahead of the function whether the
 * input type is a {@code String}, an {@code Object} or a {@code Map<String, Object>},
 * which is the behaviour {@link GAToolCallback} replaces.
 *
 * <p>
 * The result text reaches the model as the tool wrote it, because {@link GAToolCallback}
 * returns it without {@code DefaultToolCallResultConverter}. That converter calls
 * {@code JsonHelper.toJson(result, true)}, which forwards a {@code String} holding valid
 * JSON and quotes any other, so it would wrap a refusal's plain sentence in quotes. Both
 * exposure types therefore send the same characters: a GraphQL envelope for an answer,
 * and a sentence for a refusal.
 *
 * <p>
 * Spring AI's {@code ToolMetadata} carries {@code returnDirect} alone, so the read-only
 * flag of the tool model reaches MCP only.
 *
 * @author Željko Kozina
 */
public final class GAToolCallbackFactory {

	// The element type matches GATool.handler(), which takes
	// Map<String, @Nullable Object> because a GraphQL variable may be an explicit
	// null. The MCP adapter carries the same type.
	private static final ParameterizedTypeReference<Map<String, @Nullable Object>> ARGUMENTS_TYPE = new ParameterizedTypeReference<>() {
	};

	private GAToolCallbackFactory() {
	}

	/**
	 * Builds the callbacks for one reading of the tool catalog.
	 * @param tools the in-process tools
	 * @return one callback per tool, in the order of the tools
	 */
	public static ToolCallback[] callbacksFor(List<GATool> tools) {
		return tools.stream().map(GAToolCallbackFactory::callbackFor).toArray(ToolCallback[]::new);
	}

	private static ToolCallback callbackFor(GATool tool) {
		return new GAToolCallback(tool, definitionFor(tool));
	}

	// Returns the callback Spring AI's own builder produces for one tool.
	//
	// Spring AI's builder owns the description fallback and the schema handling, so the
	// definition it produces is the one a hand-written tool would carry. The callback it
	// would build is left behind, and its definition travels on.
	private static ToolCallback definitionSource(GATool tool) {
		// A local variable of the function type picks the builder(String, Function)
		// overload, because a lambda written inline also fits builder(String, Consumer).
		Function<Map<String, @Nullable Object>, String> call = (
				arguments) -> tool.callHandler().apply(arguments).text();
		FunctionToolCallback.Builder<Map<String, @Nullable Object>, String> callback = FunctionToolCallback
			.builder(tool.name(), call)
			.inputSchema(tool.inputSchema())
			.inputType(ARGUMENTS_TYPE);
		// Spring AI's builder takes a non-null description, and a tool whose file and
		// schema both lack one keeps Spring AI's own default instead.
		String description = tool.description();
		if (description != null) {
			callback.description(description);
		}
		return callback.build();
	}

	private static ToolDefinition definitionFor(GATool tool) {
		return definitionSource(tool).getToolDefinition();
	}

}
