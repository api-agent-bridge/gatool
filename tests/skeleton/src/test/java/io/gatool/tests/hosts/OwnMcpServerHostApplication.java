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

package io.gatool.tests.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.gatool.boot.inprocess.GAToolCallbacks;

/**
 * An application that serves its own Spring AI MCP server, with one {@code @McpTool}, and
 * adds GATool for a {@code ChatClient}.
 *
 * <p>
 * The test that launches it runs it in a JVM of its own, once on a classpath without
 * {@code gatool-mcp-spring-boot}, which is what the in-process starter alone brings, and
 * once with it. It prints one line, opened by {@code OWN-MCP-SERVER}, that says whether
 * GATool's MCP module is present, what the MCP server properties resolved to, how many
 * in-process tools GATool published, whether the server holds its port on 127.0.0.1, and
 * what {@code POST /mcp} and {@code GET /sse} answered.
 *
 * <p>
 * It lives outside {@code io.gatool.tests.skeleton}, whose {@code @SpringBootApplication}
 * classes scan their package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(OwnMcpServerHostApplication.OwnTools.class)
public class OwnMcpServerHostApplication {

	/** The prefix of the line this host prints. */
	public static final String LINE = "OWN-MCP-SERVER ";

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"own-server-host\",\"version\":\"1\"}}}";

	public static void main(String[] args) throws Exception {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(OwnMcpServerHostApplication.class)
			.web(WebApplicationType.SERVLET)
			.run(args);
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		Environment environment = context.getEnvironment();
		boolean mcpModule = ClassUtils.isPresent("io.gatool.boot.mcp.autoconfigure.GAToolEnvironmentPostProcessor",
				OwnMcpServerHostApplication.class.getClassLoader());
		McpServerProperties server = context.getBean(McpServerProperties.class);
		int callbacks = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks().length;
		String[] initialize = post(port, "/mcp", INITIALIZE);
		int sse = getStatus(port, "/sse");
		System.out.println(LINE + "mcp-module=" + mcpModule + " protocol="
				+ String.valueOf(environment.getProperty("spring.ai.mcp.server.protocol")) + " name=" + server.getName()
				+ " instructions=" + ((server.getInstructions() != null) ? "set" : "unset") + " in-process-tools="
				+ callbacks + " port-held-on-loopback=" + heldOnLoopback(port) + " POST/mcp=" + initialize[0]
				+ " GET/sse=" + sse + " initialize-body=" + initialize[1].replace('\n', ' '));
		context.close();
		System.exit(0);
	}

	// A second bind of 127.0.0.1 and the server's port. The operating system refuses it
	// while the server holds the port on that address, and macOS accepts it beside a
	// server bound to every address, where another program can answer the two calls
	// below.
	private static boolean heldOnLoopback(int port) {
		try {
			new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close();
			return false;
		}
		catch (IOException ex) {
			return true;
		}
	}

	private static String[] post(int port, String path, String body) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
			.toURL()
			.openConnection();
		connection.setRequestMethod("POST");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json, text/event-stream");
		connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25");
		connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		int status = connection.getResponseCode();
		InputStream stream = (status < 400) ? connection.getInputStream() : connection.getErrorStream();
		String text = (stream != null) ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
		connection.disconnect();
		return new String[] { String.valueOf(status), text };
	}

	// The status alone: an SSE stream stays open, and the headers arrive before it.
	private static int getStatus(int port, String path) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
			.toURL()
			.openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
		connection.setRequestProperty("Accept", "text/event-stream");
		int status = connection.getResponseCode();
		connection.disconnect();
		return status;
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnTools {

		@Bean
		Echo echo() {
			return new Echo();
		}

	}

	/**
	 * The application's own MCP tool, the kind Spring AI's annotation scanner publishes.
	 */
	public static class Echo {

		@McpTool(name = "appEcho", description = "Echoes text")
		public String appEcho(@McpToolParam(description = "text") String text) {
			return "echo " + text;
		}

	}

}
