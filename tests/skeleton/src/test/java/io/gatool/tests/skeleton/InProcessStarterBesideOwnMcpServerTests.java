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

package io.gatool.tests.skeleton;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.gatool.tests.hosts.OwnMcpServerHostApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-process starter leaves an application's own Spring AI MCP server as it was.
 *
 * <p>
 * The MCP environment post-processor and every MCP auto-configuration live in
 * {@code gatool-mcp-spring-boot}, which the in-process starter leaves out, so an
 * application that adds GATool for a {@code ChatClient} keeps its own server's name,
 * instructions and transport. The skeleton's test classpath carries both modules, so the
 * host runs in a JVM of its own with the {@code gatool-mcp-spring-boot} entries filtered
 * out, which is the in-process starter's classpath, and once more with them present as
 * the control that shows what the MCP module does.
 *
 * <p>
 * Spring Security sits on the skeleton classpath for other tests, so Boot's own chains
 * are excluded in both runs, and GATool's MCP security auto-configuration in the control
 * run, which leaves the server's own transport as the one thing under test.
 */
class InProcessStarterBesideOwnMcpServerTests {

	private static final String EXCLUDE_BOOT_SECURITY = "--spring.autoconfigure.exclude="
			+ "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
			+ "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
			+ "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
			+ "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration,"
			+ "org.springframework.boot.security.oauth2.client.autoconfigure.servlet"
			+ ".OAuth2ClientWebSecurityAutoConfiguration";

	@Test
	void ownMcpServer_inProcessStarterAlone_shouldKeepItsTransportNameAndInstructions() throws Exception {
		HostProcess.Run run = run(false, EXCLUDE_BOOT_SECURITY);

		String line = run.line(OwnMcpServerHostApplication.LINE);
		assertThat(run.exitCode()).as(line).isZero();
		assertThat(line).contains("mcp-module=false")
			.contains("protocol=null")
			.contains("name=mcp-server")
			.contains("instructions=unset")
			.contains("in-process-tools=1")
			.contains("port-held-on-loopback=true")
			// Spring AI 2.0.1 with the protocol unset serves SSE: /sse answers, /mcp is
			// unmapped.
			.contains("POST/mcp=404")
			.contains("GET/sse=200");
	}

	@Test
	void ownMcpServer_withTheMcpModulePresent_shouldTakeGAToolsServerDefaults() throws Exception {
		// Excluding Boot's web security and GATool's security auto-configuration together
		// leaves the endpoint without a security filter chain, which startup refuses
		// unless the switch says the host serves it without authentication on purpose.
		HostProcess.Run run = run(true,
				EXCLUDE_BOOT_SECURITY + ",io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration",
				"--gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

		// The MCP module's purpose: the server introduces itself as the application,
		// carries GATool's instructions and serves stateless Streamable HTTP.
		String line = run.line(OwnMcpServerHostApplication.LINE);
		assertThat(run.exitCode()).as(line).isZero();
		assertThat(line).contains("mcp-module=true")
			.contains("protocol=STATELESS")
			.contains("name=orders-service")
			.contains("instructions=set")
			.contains("port-held-on-loopback=true")
			.contains("POST/mcp=200")
			.contains("GET/sse=404")
			.contains("\"name\":\"orders-service\"");
	}

	private static HostProcess.Run run(boolean withMcpModule, String... extraArguments) throws Exception {
		String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
			.filter((entry) -> withMcpModule || !entry.contains("gatool-mcp-spring-boot"))
			.collect(Collectors.joining(File.pathSeparator));
		// The host opens an SSE request against its own server and exits with it open.
		// A graceful shutdown, Boot's default, waits 30 seconds for that request, so
		// the host stops at once.
		List<String> arguments = new ArrayList<>(
				List.of("--server.port=0", "--spring.main.banner-mode=off", "--server.shutdown=immediate",
						"--spring.application.name=orders-service", "--gatool.api.url=http://127.0.0.1:1/graphql",
						"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls"));
		arguments.addAll(List.of(extraArguments));
		return HostProcess.run(OwnMcpServerHostApplication.class, classpath, arguments, Duration.ofSeconds(120));
	}

}
