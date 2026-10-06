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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.gatool.boot.internal.SpringAiMcpKeys;

/**
 * Contributes the MCP server defaults, so that an application that adds the MCP starter
 * serves the transport GATool documents and introduces itself as the application it is.
 *
 * <p>
 * Over HTTP the protocol becomes {@code STATELESS}, because Spring AI 2.0.1 starts the
 * deprecated SSE transport while {@code spring.ai.mcp.server.protocol} stays unset. Under
 * {@code spring.ai.mcp.server.stdio=true} the protocol becomes {@code STREAMABLE}, which
 * stdio runs in process, and three further defaults keep the stream clean: the console
 * log goes off, the banner goes off, and the application runs without a web server.
 * Spring Boot 4.1.1 sends console logging to stdout, where stdio carries MCP messages
 * alone, and it does not offer a property that sends the console to stderr. The log goes
 * to stderr through {@link McpStdioLogListener}, which attaches an appender of GATool's
 * own once Boot has initialised the log, for the lines at WARN and above and without
 * making the server wait on the host. The listener acts only where the console went off
 * through the default contributed here, so an application that set
 * {@code logging.console.enabled} itself keeps the log where it put it. A default and an
 * application's own value read the same in the {@link ConfigurableEnvironment}, so the
 * console default is contributed beside a marker of its own,
 * {@code gatool.mcp.stdio.console-default-applied}, which the listener reads. The server
 * name becomes {@code spring.application.name}, because an application that leaves these
 * settings unset would introduce itself to every client as Spring AI's default,
 * {@code mcp-server}, and the application's own name is the one GATool can know. The
 * server version becomes {@code spring.application.version} while the application has
 * one, because a server named after the application and versioned {@code 1.0.0} whatever
 * the application's version says would tell a client a version the application does not
 * carry. The instructions become the sentence that says what every GATool tool result
 * holds, because MCP passes instructions to the model beside the tool list and what every
 * tool shares belongs there once, where each tool's own description would repeat it.
 * Under {@code gatool.dev.experimental.generate-tools=dynamic-three-step} the text names
 * the search, read, run and correct loop the three dynamic tools serve instead, because
 * three tools then run GraphQL the model wrote.
 *
 * <p>
 * Every value lands in {@code defaultProperties}, Boot's lowest-precedence source, so an
 * application's own value wins. Each key is checked with
 * {@link ConfigurableEnvironment#containsProperty} first, because
 * {@link DefaultPropertiesPropertySource#addOrMerge} copies the existing entries and then
 * the new map. Without the check, a value an application set through
 * {@code SpringApplication.setDefaultProperties} would be replaced by this one.
 *
 * <p>
 * The whole class backs off while Spring AI's MCP server auto-configuration is off the
 * classpath. Boot asks a starter not to make assumptions about the project it is added
 * to. An application that depends on {@code gatool-mcp-spring-boot} directly, without the
 * Spring AI server the MCP starter brings, runs without a server that would read a
 * protocol or a server name.
 *
 * <p>
 * This class stays silent. A post-processor runs before the logging system starts, so the
 * line naming the effective transport belongs to {@link GAToolMcpAutoConfiguration},
 * which runs once the log is ready.
 *
 * @author Željko Kozina
 */
class GAToolEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PROTOCOL = "spring.ai.mcp.server.protocol";

	static final String SERVER_NAME = "spring.ai.mcp.server.name";

	static final String INSTRUCTIONS = "spring.ai.mcp.server.instructions";

	static final String SERVER_VERSION = "spring.ai.mcp.server.version";

	static final String CONSOLE_LOGGING = "logging.console.enabled";

	/**
	 * The marker contributed beside the console default, which says that the console went
	 * off through GATool's default and the application left
	 * {@code logging.console.enabled} unset. It is an internal key, so the configuration
	 * metadata leaves it out.
	 */
	static final String CONSOLE_DEFAULT_APPLIED = "gatool.mcp.stdio.console-default-applied";

	/**
	 * The instructions every GATool server publishes until the application writes its
	 * own.
	 */
	static final String INSTRUCTIONS_TEXT = "These tools run trusted GraphQL documents against one API. A result is "
			+ "a GraphQL response: the data entry holds the result of the executed operation, and the errors "
			+ "array holds any errors the API reported. A tool error covers both a GraphQL error, where the "
			+ "data may still be partial, and a call that could not reach the API.";

	/**
	 * The instructions under
	 * {@code gatool.dev.experimental.generate-tools=dynamic-three-step}, where three of
	 * the tools run GraphQL the model writes, so the text names the loop those three
	 * serve, where the default text claims a trusted document for every tool.
	 */
	static final String DYNAMIC_INSTRUCTIONS_TEXT = "Three of these tools let you write GraphQL against one API: "
			+ "search the schema for the fields that answer the question, read any type the search result names "
			+ "that you need to look inside, such as an input type you pass, then run the operation. A refusal names what was wrong: read the "
			+ "types it names and correct the document before running it again. Every other tool runs a trusted "
			+ "GraphQL document against the same API. A result is a GraphQL response: the data entry holds the "
			+ "result of the executed operation, and the errors array holds any errors the API reported. A tool "
			+ "error covers both a GraphQL error, where the data may still be partial, and a call that could not "
			+ "reach the API.";

	// The name resolves through the Environment, so an application name set anywhere
	// reaches it, and Spring AI's own default stands in while the application is unnamed.
	private static final String SERVER_NAME_DEFAULT = "${spring.application.name:mcp-server}";

	private static final String APPLICATION_VERSION = "spring.application.version";

	// The version resolves through the Environment as the name does. It is contributed
	// only while the application has a version, because Spring AI's properties class
	// refuses an empty one and its own default, 1.0.0, stands otherwise.
	private static final String SERVER_VERSION_DEFAULT = "${" + APPLICATION_VERSION + "}";

	private static final String GENERATE_TOOLS = "gatool.dev.experimental.generate-tools";

	// The value as Boot binds it to the enum: case and every character outside letters
	// and digits are ignored, so dynamic-three-step, DYNAMIC_THREE_STEP and
	// dynamicThreeStep all read the same.
	private static final String DYNAMIC_THREE_STEP = "dynamicthreestep";

	// The class the MCP starter brings and the in-process starter leaves out. Its
	// presence says the application has a server for these defaults.
	private static final String MCP_SERVER_AUTO_CONFIGURATION = "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration";

	/**
	 * Creates the post-processor, which Spring Boot instantiates from
	 * {@code META-INF/spring.factories}.
	 */
	GAToolEnvironmentPostProcessor() {
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!ClassUtils.isPresent(MCP_SERVER_AUTO_CONFIGURATION, application.getClassLoader())) {
			return;
		}
		Map<String, Object> defaults = new LinkedHashMap<>();
		addDefault(environment, defaults, SERVER_NAME, SERVER_NAME_DEFAULT);
		if (StringUtils.hasText(environment.getProperty(APPLICATION_VERSION))) {
			addDefault(environment, defaults, SERVER_VERSION, SERVER_VERSION_DEFAULT);
		}
		addDefault(environment, defaults, INSTRUCTIONS,
				servesDynamicTools(environment) ? DYNAMIC_INSTRUCTIONS_TEXT : INSTRUCTIONS_TEXT);
		if (SpringAiMcpKeys.servesStdio(environment)) {
			addDefault(environment, defaults, PROTOCOL, "STREAMABLE");
			if (!environment.containsProperty(CONSOLE_LOGGING)) {
				defaults.put(CONSOLE_LOGGING, "false");
				defaults.put(CONSOLE_DEFAULT_APPLIED, "true");
			}
			addDefault(environment, defaults, "spring.main.banner-mode", "off");
			addDefault(environment, defaults, "spring.main.web-application-type", "none");
		}
		else {
			addDefault(environment, defaults, PROTOCOL, "STATELESS");
		}
		if (!defaults.isEmpty()) {
			DefaultPropertiesPropertySource.addOrMerge(defaults, environment.getPropertySources());
		}
	}

	// Running last leaves every other source in place, so containsProperty sees the
	// values that configuration files and the command line contributed.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	private static void addDefault(ConfigurableEnvironment environment, Map<String, Object> defaults, String key,
			String value) {
		if (!environment.containsProperty(key)) {
			defaults.put(key, value);
		}
	}

	// Whether the three dynamic tools are on, read the way Boot's lenient enum binding
	// reads the value.
	private static boolean servesDynamicTools(ConfigurableEnvironment environment) {
		String value = environment.getProperty(GENERATE_TOOLS);
		if (value == null) {
			return false;
		}
		StringBuilder canonical = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isLetterOrDigit(character)) {
				canonical.append(Character.toLowerCase(character));
			}
		}
		return DYNAMIC_THREE_STEP.contentEquals(canonical);
	}

}
