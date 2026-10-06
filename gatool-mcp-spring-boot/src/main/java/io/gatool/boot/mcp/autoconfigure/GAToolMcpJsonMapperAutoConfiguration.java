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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.GAToolProperties;

/**
 * Declares the {@code mcpServerJsonMapper} bean that every Spring AI MCP server transport
 * reads request bodies with, so that a {@code null} argument reaches GATool as a
 * {@code null}.
 *
 * <p>
 * GraphQL gives a {@code null} a meaning of its own: a variable set to {@code null}
 * clears a value, and a variable left out applies its default. A model calling over MCP
 * can only send the first case when the transport keeps the {@code null} while reading
 * the request. Spring AI's own bean of this name sets value inclusion and content
 * inclusion to {@code NON_NULL}. Each transport reads a body by converting the parsed map
 * into the request record with that mapper, so a {@code null} map entry is dropped before
 * GATool sees the call. The bean here is built the way Spring AI builds its own, with two
 * changes. Content inclusion stays at {@code ALWAYS}, which is the setting that governs a
 * null map value. {@code USE_BIG_DECIMAL_FOR_FLOATS} is enabled too, so a decimal
 * argument reaches GATool as a {@code BigDecimal} with every digit the model sent, the
 * way the in-process surface and a result already do.
 *
 * <p>
 * Declaring the bean is the route Spring AI documents, through
 * {@code @ConditionalOnMissingBean(name = "mcpServerJsonMapper")} on its own
 * auto-configuration, and an application's own bean of that name still wins over this
 * one.
 *
 * <p>
 * What this means for the rest of the application: the SDK's message types carry their
 * own {@code @JsonInclude(NON_ABSENT)}, so the JSON every transport writes for the
 * protocol stays the same as under Spring AI's mapper, which a test compares. A tool of
 * the application's own whose result map holds a {@code null} value writes that value as
 * {@code null}, where Spring AI's mapper leaves the entry out. A {@code BigDecimal}
 * parameter of an application's own MCP tool receives every digit the model sent, where a
 * double holds seventeen at most. An application that wants Spring AI's behaviour
 * declares the bean itself.
 *
 * @author Željko Kozina
 */
@AutoConfiguration(after = GAToolAutoConfiguration.class,
		beforeName = "org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration")
@ConditionalOnClass(name = { "io.modelcontextprotocol.spec.McpSchema",
		"org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration" })
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(GAToolProperties.class)
@ConditionalOnMissingBean(name = "mcpServerJsonMapper")
public final class GAToolMcpJsonMapperAutoConfiguration {

	/**
	 * Creates the auto-configuration, which Spring Boot instantiates at startup.
	 */
	public GAToolMcpJsonMapperAutoConfiguration() {
		// The beans come from the methods below; Spring Boot instantiates the class.
	}

	/**
	 * Returns the mapper the MCP server transports read and write with, which keeps a
	 * null map value.
	 *
	 * <p>
	 * {@code defaultCandidate = false} matches Spring AI's own definition, so the bean
	 * takes part in injection only where a {@code Qualifier} names it and leaves the
	 * application's plain {@code JsonMapper} injection points alone.
	 * @return the mapper
	 */
	@Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
	JsonMapper mcpServerJsonMapper() {
		return JsonMapper.builder()
			.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
			.findAndAddModules()
			.changeDefaultPropertyInclusion((inclusion) -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
					JsonInclude.Include.ALWAYS))
			.build();
	}

}
