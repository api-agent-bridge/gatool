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

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.model.GATool;

/**
 * Hands one GATool tool to a {@code ChatClient} as a Spring AI tool.
 *
 * <p>
 * A tool written by hand with {@code @Tool} answers a model that sends malformed
 * arguments with a tool error, which the model reads and corrects. Spring AI does that by
 * catching the failure and rethrowing it as {@link ToolExecutionException}, the one type
 * {@code DefaultToolCallingManager} catches. A failure of any other type reaches whoever
 * called the {@code ChatClient}, which leaves the model waiting.
 *
 * <p>
 * {@code FunctionToolCallback} parses the argument text ahead of its own try block, so a
 * malformed argument leaves as an {@code IllegalStateException} and misses that catch.
 * This class exists for that reason: it parses inside the guard, so a generated tool and
 * a hand-written one answer a model the same way. It also puts
 * {@code spring.ai.tools.throw-exception-on-error} back in charge, because that property
 * configures Spring AI's exception processor and the processor reads this one type.
 *
 * <p>
 * The definition comes from {@code FunctionToolCallback}'s own builder, so the name, the
 * description fallback and the input schema stay exactly as they were. Tool metadata
 * keeps the default, which leaves {@code returnDirect} false, and the result text travels
 * unchanged: a GraphQL envelope for an answer, and a plain sentence for a refusal, as the
 * README's in-process section says.
 *
 * @author Željko Kozina
 */
final class GAToolCallback implements ToolCallback {

	// Jackson 3 arrives through the version Spring Boot manages, and this module already
	// reads JSON with it. Spring AI's own parsing utility carries a deprecation for
	// removal in 2.0.1, so reading the arguments here stays with the mapper the rest of
	// the starter uses. A float is read as a BigDecimal, because a model that sends a
	// Decimal scalar past binary64 would otherwise reach the API rounded.
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	// The tool handler takes Map<String, Object>, and a GraphQL variable may be an
	// explicit null, so the parse targets that type.
	private static final TypeReference<Map<String, @Nullable Object>> ARGUMENTS_TYPE = new TypeReference<>() {
	};

	private final GATool tool;

	private final ToolDefinition definition;

	GAToolCallback(GATool tool, ToolDefinition definition) {
		this.tool = tool;
		this.definition = definition;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.definition;
	}

	@Override
	public String call(String toolInput) {
		try {
			return this.tool.call(arguments(toolInput)).text();
		}
		catch (RuntimeException ex) {
			// ToolExecutionException takes the cause's message as its own, and that
			// message is what Spring AI's processor answers the model with.
			throw new ToolExecutionException(this.definition, ex);
		}
	}

	/**
	 * Runs the tool with the argument text alone, leaving the context to the application.
	 *
	 * <p>
	 * Spring AI's default for this method logs at INFO on every call that carries a
	 * context, saying the callback ignores it. A GATool tool takes its arguments from the
	 * text: the context is the application's own, passed for the tools the application
	 * writes, so this override delegates and the log stays quiet.
	 * @param toolInput the argument text the model sent
	 * @param toolContext the context the application passed, which this tool leaves
	 * unread
	 * @return the result text
	 */
	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		return call(toolInput);
	}

	// Reads the argument text a model sent as the map the tool handler takes. Blank text
	// and the text null become an empty map.
	//
	// A model that calls a tool without arguments sends an empty string, and the tools
	// built from operation files take an empty map for that. The text "null" parses to
	// null, and a null map would fail inside the handler with a message about the
	// handler; it means the same as an empty string. The catch inside call() covers a
	// failure here, because a model that sends broken argument text is the case this
	// class exists for, and the parser wraps whatever Jackson raises.
	private static Map<String, @Nullable Object> arguments(String toolInput) {
		if (toolInput.isBlank()) {
			return Map.of();
		}
		Map<String, @Nullable Object> parsed = JSON_MAPPER.readValue(toolInput, ARGUMENTS_TYPE);
		return (parsed != null) ? parsed : Map.of();
	}

}
