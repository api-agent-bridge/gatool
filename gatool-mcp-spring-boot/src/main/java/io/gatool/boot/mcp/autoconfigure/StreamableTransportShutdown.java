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

import java.time.Duration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.SmartLifecycle;

/**
 * The lifecycle {@link GAToolMcpAutoConfiguration.Stateful.StreamableHttp} publishes to
 * end the sessions of the stateful transport before the web server starts its graceful
 * shutdown.
 *
 * <p>
 * The class stands on its own so that the auto-configuration keeps its bean methods and
 * little else. The log line keeps the auto-configuration's category, so a level set on
 * that class still covers it.
 *
 * @author Željko Kozina
 */
final class StreamableTransportShutdown implements SmartLifecycle {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	// How long the shutdown waits for the stateful transport to end its sessions, each
	// of which closes one stream. Past it, the web server's own graceful shutdown
	// waits for what stayed open.
	private static final Duration TRANSPORT_CLOSE_DEADLINE = Duration.ofSeconds(5);

	private final WebMvcStreamableServerTransportProvider provider;

	private volatile boolean running;

	StreamableTransportShutdown(WebMvcStreamableServerTransportProvider provider) {
		this.provider = provider;
	}

	@Override
	public void start() {
		this.running = true;
	}

	@Override
	public void stop() {
		this.running = false;
		try {
			this.provider.closeGracefully().block(TRANSPORT_CLOSE_DEADLINE);
		}
		catch (RuntimeException ex) {
			// The shutdown carries on: the streams that stayed open are what
			// the web server's own graceful shutdown then waits for.
			logger.warn("GATool could not close the MCP sessions ahead of the web server's graceful "
					+ "shutdown, which now waits for the open streams.", ex);
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

}
