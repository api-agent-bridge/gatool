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
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpSyncServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import io.gatool.boot.mcp.internal.server.ImmediateExecution;

/**
 * The startup check that every MCP server bean was built with
 * {@code immediateExecution(true)}, and the reports it writes.
 *
 * <p>
 * {@link GAToolMcpAutoConfiguration.RequestThreadCheck} runs it for a servlet
 * application, and {@link GAToolMcpAutoConfiguration.Stateful} runs it over stdio. The
 * class holds the check and its reports so that the auto-configuration keeps its bean
 * methods and little else. The log line keeps the auto-configuration's category, so a
 * level set on that class still covers it.
 *
 * @author Željko Kozina
 */
final class ImmediateExecutionCheck {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	// The McpSyncServerCustomizer bean Spring AI contributes, which is the one that sets
	// immediateExecution(true) on the stateful server. The startup report names every
	// bean of that type beside it, because one of those is what the server took.
	private static final String SPRING_AI_SERVER_CUSTOMIZER = "servletMcpSyncServerCustomizer";

	// The McpSyncServerCustomizer bean GATool contributes over stdio, where Spring AI's
	// own carries a servlet condition and stays absent. The startup report leaves this
	// name out beside Spring AI's, so the names that remain belong to the application,
	// and one of those is what the server took.
	private static final String GATOOL_STDIO_SERVER_CUSTOMIZER = "gaToolStdioMcpSyncServerCustomizer";

	private ImmediateExecutionCheck() {
	}

	/**
	 * Stops startup for every server bean of the type that was built without immediate
	 * execution, and warns once for a bean whose setting could not be read.
	 * @param beanFactory the bean factory that holds the server beans and the customizers
	 * @param serverType the server type to check every bean of
	 * @param reports builds the report for a bean name and the customizer beans of the
	 * application
	 */
	// The beans exist by now, because a SmartInitializingSingleton runs after every
	// singleton was created and both server beans are excluded from lazy
	// initialization. The loop asks for every bean of the type, so a server bean an
	// application declared lazily is created here, at startup instead of on its first
	// use. A read that fails leaves the server as it is: the refusal a call meets
	// when it arrives off the request thread is the guard underneath this one.
	static void requireImmediateExecution(ConfigurableListableBeanFactory beanFactory, Class<?> serverType,
			BiFunction<String, List<String>, ImmediateExecutionReport> reports) {
		for (String beanName : beanFactory.getBeanNamesForType(serverType, true, false)) {
			requireImmediateExecution(beanFactory, beanName, reports);
		}
	}

	private static void requireImmediateExecution(ConfigurableListableBeanFactory beanFactory, String beanName,
			BiFunction<String, List<String>, ImmediateExecutionReport> reports) {
		Boolean immediate = ImmediateExecution.of(beanFactory.getBean(beanName));
		if (Boolean.TRUE.equals(immediate)) {
			return;
		}
		ImmediateExecutionReport report = reports.apply(beanName, customizersOfTheApplication(beanFactory));
		if (immediate == null) {
			logger.warn(report.unreadable());
			return;
		}
		throw new McpSecurityException(report.description(), report.action());
	}

	/**
	 * The report a servlet application reads, where GATool reads the caller of each tool
	 * call on the request thread.
	 * @param beanName the name of the server bean
	 * @param stateful whether the bean is a stateful server, which Spring AI configures
	 * through a customizer
	 * @param ownCustomizers the names of the customizer beans of the application
	 * @return the report for that bean
	 */
	static ImmediateExecutionReport overHttp(String beanName, boolean stateful, List<String> ownCustomizers) {
		return new ImmediateExecutionReport(
				unreadableWarning(beanName,
						"each tool call runs on the request thread, which is where it reads "
								+ "the caller. A call that arrives on another thread is refused when it arrives."),
				describeWorkerThreadServer(beanName, stateful, ownCustomizers),
				actOnWorkerThreadServer(beanName, stateful, ownCustomizers));
	}

	/**
	 * The report a stdio server reads, where every answer leaves through one queue of the
	 * MCP SDK.
	 * @param beanName the name of the server bean
	 * @param ownCustomizers the names of the customizer beans of the application
	 * @return the report for that bean
	 */
	static ImmediateExecutionReport overStdio(String beanName, List<String> ownCustomizers) {
		return new ImmediateExecutionReport(
				unreadableWarning(beanName,
						"each tool call runs on the thread that reads stdin. Two answers "
								+ "emitted at once cost this server the second answer and the transport."),
				describeStdioWorkerThreadServer(beanName, ownCustomizers),
				actOnStdioWorkerThreadServer(beanName, ownCustomizers));
	}

	// The warning GATool writes where the setting could not be read off a server bean,
	// with the sentences that say what the read would have told it. Both SDK server
	// classes ship in one jar, so one class names the version for either check.
	private static String unreadableWarning(String beanName, String whatGAToolCannotTell) {
		return "GATool could not read whether the MCP server bean '" + beanName + "' was built with "
				+ "immediateExecution(true). The MCP Java SDK keeps that setting in a private field, and this "
				+ "application runs against SDK version " + ImmediateExecution.versionOf(McpSyncServer.class)
				+ ", where GATool could not reach it. GATool therefore cannot tell at startup whether "
				+ whatGAToolCannotTell + " Keep immediateExecution(true) in this application's "
				+ "McpSyncServerCustomizer bean.";
	}

	// What the startup report says happened over stdio.
	// The first four sentences hold for every shape: what the bean was built without,
	// what the SDK's one queue does with two answers at once, what that costs, and what
	// GATool asks for. The last one says where the setting was dropped.
	private static String describeStdioWorkerThreadServer(String beanName, List<String> ownCustomizers) {
		return "The MCP server bean '" + beanName
				+ "' was built without immediateExecution(true), so the MCP SDK would run each tool call on a "
				+ "worker thread of Reactor's bounded elastic scheduler. Over stdio the SDK writes every "
				+ "answer through one queue that takes one answer at a time, and an answer emitted beside "
				+ "another is dropped. The SDK then closes the transport, and the server stops answering "
				+ "while its process stays up. GATool asks for immediate execution so that every answer "
				+ "leaves on the thread that reads stdin. "
				+ customizersFound(ownCustomizers,
						"This bean was built elsewhere, so the builder that built it left the setting out.",
						"This context holds the McpSyncServerCustomizer bean '",
						"This context holds the McpSyncServerCustomizer beans '");
	}

	// What the startup report says to change over stdio.
	private static String actOnStdioWorkerThreadServer(String beanName, List<String> ownCustomizers) {
		if (ownCustomizers.isEmpty()) {
			return "Add immediateExecution(true) to the builder that builds '" + beanName + "', or remove that "
					+ "server bean, and GATool's own customizer sets it.";
		}
		return "Add immediateExecution(true) to "
				+ ((ownCustomizers.size() == 1) ? "that customizer" : "the customizer the server took")
				+ ", as in server -> server.immediateExecution(true), chained with whatever else it sets, or "
				+ "remove the bean, and GATool's own customizer sets it.";
	}

	// Returns the clause that names the customizer beans of the application, built from
	// the three openings a report carries: one for an empty list, one for a single bean,
	// and one for several.
	private static String customizersFound(List<String> ownCustomizers, String forNone, String forOne,
			String forSeveral) {
		if (ownCustomizers.isEmpty()) {
			return forNone;
		}
		if (ownCustomizers.size() == 1) {
			return forOne + ownCustomizers.get(0) + "', which the server took.";
		}
		return forSeveral + String.join("', '", ownCustomizers) + "', one of which the server took.";
	}

	// Returns the names of the McpSyncServerCustomizer beans of the application, which
	// are the beans of that type beside the one Spring AI contributes over HTTP and the
	// one GATool contributes over stdio.
	private static List<String> customizersOfTheApplication(ConfigurableListableBeanFactory beanFactory) {
		List<String> names = new ArrayList<>(
				List.of(beanFactory.getBeanNamesForType(McpSyncServerCustomizer.class, true, false)));
		names.remove(SPRING_AI_SERVER_CUSTOMIZER);
		names.remove(GATOOL_STDIO_SERVER_CUSTOMIZER);
		return names;
	}

	// What the startup report says happened.
	// The first three sentences hold for every shape: what the bean was built without,
	// what GATool reads on the request thread, and what a worker thread costs. The last
	// one says where the setting was dropped, which differs between a stateful server
	// Spring AI built under a customizer of the application's own and a server bean the
	// application built itself.
	private static String describeWorkerThreadServer(String beanName, boolean stateful, List<String> ownCustomizers) {
		StringBuilder description = new StringBuilder("The MCP server bean '").append(beanName)
			.append("' was built without immediateExecution(true), so the MCP SDK would run each tool call on a "
					+ "worker thread of Reactor's bounded elastic scheduler. GATool reads the caller of a tool "
					+ "call on the request thread: the rate limiter counts per caller there, and token-exchange "
					+ "and the forwarded token take the caller's token from there. On a worker thread every caller "
					+ "shares one rate limit, and under MODE_INHERITABLETHREADLOCAL a call can reach the API with "
					+ "the token of another caller. ");
		if (!stateful) {
			return description
				.append("Spring AI sets the setting on the stateless server bean it builds, and this "
						+ "bean was built elsewhere, so the builder that built it left the setting out.")
				.toString();
		}
		return description
			.append("Spring AI asks for immediate execution through its McpSyncServerCustomizer " + "bean '")
			.append(SPRING_AI_SERVER_CUSTOMIZER)
			.append("', and ")
			.append(customizersFound(ownCustomizers,
					"this bean was built elsewhere, so the builder that built it left the setting out.",
					"this context holds a second bean of that type, '",
					"this context holds further beans of that type, '"))
			.toString();
	}

	// What the startup report says to change.
	private static String actOnWorkerThreadServer(String beanName, boolean stateful, List<String> ownCustomizers) {
		if (stateful && !ownCustomizers.isEmpty()) {
			return "Add immediateExecution(true) to "
					+ ((ownCustomizers.size() == 1) ? "that customizer" : "the customizer the server took")
					+ ", as in server -> server.immediateExecution(true)"
					+ ".requestTimeout(Duration.ofSeconds(30)), or remove the bean, and Spring AI's own customizer "
					+ "sets it.";
		}
		return "Add immediateExecution(true) to the builder that builds '" + beanName + "', or remove that bean, "
				+ "and Spring AI builds the server with the setting on.";
	}

	/**
	 * What a startup check of immediate execution has to say about one server bean. It
	 * holds the warning for a setting the check could not read, and the description and
	 * the action of the stop for a server built on a worker thread.
	 *
	 * @param unreadable the warning for a setting the check could not read
	 * @param description what the startup report says happened
	 * @param action what the startup report says to change
	 */
	// One bean reaches at most one of the three, and the other two cost the string
	// building alone, for a server that is already wrong.
	record ImmediateExecutionReport(String unreadable, String description, String action) {
	}

}
