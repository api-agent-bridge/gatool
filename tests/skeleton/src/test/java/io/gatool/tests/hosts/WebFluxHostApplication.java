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
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.WebFilter;

/**
 * A WebFlux application that added the MCP starter, as far as the skeleton's test module
 * can express one.
 *
 * <p>
 * {@code WebFilter} lives in {@code spring-web}, so the bean compiles here, and the test
 * that launches this class puts WebFlux and Reactor Netty on the classpath, beside the
 * Spring MVC and Tomcat the MCP starter brings. The web application type is left to Boot.
 * The class prints one line opened by {@code WEBFLUX-HOST} that says which context and
 * web server Boot chose, whether WebFlux's {@code DispatcherHandler} exists, whether the
 * server holds its port on 127.0.0.1, what {@code POST /mcp} answered and how often the
 * {@code WebFilter} ran.
 *
 * <p>
 * It lives outside {@code io.gatool.tests.skeleton}, whose {@code @SpringBootApplication}
 * classes scan their package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(WebFluxHostApplication.Reactive.class)
public class WebFluxHostApplication {

	/** The prefix of the line this host prints. */
	public static final String LINE = "WEBFLUX-HOST ";

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"webflux-host\",\"version\":\"1\"}}}";

	public static void main(String[] args) throws Exception {
		ConfigurableApplicationContext context = SpringApplication.run(WebFluxHostApplication.class, args);
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		String[] initialize = post(port, "/mcp", INITIALIZE);
		System.out.println(LINE + "context=" + context.getClass().getSimpleName() + " web-server="
				+ ((WebServerApplicationContext) context).getWebServer().getClass().getSimpleName()
				+ " dispatcher-handler-bean=" + context.containsBean("webHandler") + " port-held-on-loopback="
				+ heldOnLoopback(port) + " POST/mcp=" + initialize[0] + " web-filter-hits=" + Reactive.FILTER_HITS.get()
				+ " initialize-body=" + initialize[1].replace('\n', ' '));
		context.close();
		System.exit(0);
	}

	// A second bind of 127.0.0.1 and the server's port. The operating system refuses it
	// while the server holds the port on that address, and macOS accepts it beside a
	// server bound to every address, where another program can answer the call below.
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

	@Configuration(proxyBeanMethods = false)
	static class Reactive {

		static final AtomicInteger FILTER_HITS = new AtomicInteger();

		/** The kind of bean a reactive application puts its tenant or audit check in. */
		@Bean
		WebFilter countingWebFilter() {
			return (exchange, chain) -> {
				FILTER_HITS.incrementAndGet();
				return chain.filter(exchange);
			};
		}

	}

}
