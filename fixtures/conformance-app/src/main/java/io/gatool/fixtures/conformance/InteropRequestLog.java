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

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.mcp.internal.transport.BufferedBodyRequest;

/**
 * Writes one line for every request that reaches the MCP endpoint, naming the headers
 * that an interop run compares between clients.
 *
 * <p>
 * The interop run answers what each MCP client puts on the wire: which protocol revision
 * it asks for, whether it sends the version header on the calls that follow the
 * handshake, what it accepts, and whether it carries a session. Reading that from a log
 * turns a run with any client into a record, so a client that a person drives by hand
 * still produces evidence.
 *
 * <p>
 * The filter reads headers alone and leaves the body untouched, because the starter's own
 * filter buffers the body and a reader here would take the stream before it.
 *
 * @author Željko Kozina
 */
@Configuration(proxyBeanMethods = false)
public class InteropRequestLog {

	private static final Log logger = LogFactory.getLog(InteropRequestLog.class);

	/**
	 * Registers the log ahead of everything else, so a refused request appears too.
	 *
	 * <p>
	 * The method name differs from the class name on purpose. A scanned configuration
	 * class is itself a bean named after the class, and a bean method of that same name
	 * collides with it.
	 * @return the filter registration
	 */
	@Bean
	public FilterRegistrationBean<Filter> interopRequestLogFilter() {
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new HeaderLoggingFilter());
		registration.setOrder(Integer.MIN_VALUE);
		registration.setName("interopRequestLog");
		return registration;
	}

	private static final class HeaderLoggingFilter implements Filter {

		// The starter caps a real request at the same size, so the copy this log keeps
		// takes at most the memory of the one the endpoint already holds.
		private static final int MAX_BODY_BYTES = 262144;

		private static final JsonMapper JSON = JsonMapper.builder().build();

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			if (!(request instanceof HttpServletRequest http) || !http.getRequestURI().startsWith("/mcp")) {
				chain.doFilter(request, response);
				return;
			}
			// The body is buffered here so the line can name the JSON-RPC method, and the
			// servlet reads the buffer afterwards. A run that shows the method and the
			// status answers which rule a client met, which headers alone leave open.
			BufferedBodyRequest buffered = new BufferedBodyRequest(http, MAX_BODY_BYTES);
			chain.doFilter(buffered, response);
			int status = (response instanceof HttpServletResponse httpResponse) ? httpResponse.getStatus() : 0;
			logger.info("interop " + http.getMethod() + " " + http.getRequestURI() + " -> " + status + " | method="
					+ methodOf(buffered) + " | MCP-Protocol-Version=" + header(http, "MCP-Protocol-Version")
					+ " | Accept=" + header(http, "Accept") + " | Mcp-Session-Id=" + header(http, "Mcp-Session-Id")
					+ " | Origin=" + header(http, "Origin") + " | User-Agent=" + header(http, "User-Agent") + " | body="
					+ summary(buffered));
		}

		private static String methodOf(BufferedBodyRequest request) {
			try {
				JsonNode message = JSON.readTree(request.bodyAsText());
				return message.isObject() ? stringOr(message.path("method"), "absent") : "batch";
			}
			catch (RuntimeException ex) {
				return "unparsed";
			}
		}

		private static String stringOr(JsonNode value, String fallback) {
			return (value.isMissingNode() || value.isNull()) ? fallback : value.asString();
		}

		// Returns the start of the request body on one line, cut to 160 characters. A run
		// shows what a client asked for without filling the log with a whole tool list.
		private static String summary(BufferedBodyRequest request) {
			String text = request.bodyAsText().replace('\n', ' ');
			return (text.length() > 160) ? text.substring(0, 160) + "..." : text;
		}

		private static String header(HttpServletRequest request, String name) {
			@Nullable String value = request.getHeader(name);
			return (value != null) ? value : "absent";
		}

	}

}
