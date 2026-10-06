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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.gatool.tests.hosts.WebFluxHostApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a WebFlux application reads when it adds the MCP starter.
 *
 * <p>
 * The starter brings Spring MVC and Tomcat, and Boot runs the servlet stack while both
 * stacks are present, so the application starts on Tomcat with its {@code WebFilter},
 * {@code SecurityWebFilterChain} and reactive {@code RouterFunction} beans unserved, and
 * GATool serves MCP there. {@code GAToolMcpAutoConfiguration} warns, naming the beans and
 * the two ways out, and an application that sets the type to {@code reactive} meets a
 * stop at startup.
 *
 * <p>
 * The host runs in a JVM of its own, with the WebFlux and Reactor Netty jars that the
 * skeleton's pom copies into {@code target/webflux-classpath} appended to the test
 * classpath. They stay off the test classpath itself, because with Reactor Netty present
 * Boot's client builder detection would move every test's outbound client onto Reactor.
 */
class McpStarterInAWebFluxApplicationTests {

	private static final Path WEBFLUX_JARS = Path.of("target", "webflux-classpath");

	@Test
	void webFluxApplication_typeLeftToBoot_shouldStartOnTomcatAndWarnNamingTheUnservedBeans() throws Exception {
		HostProcess.Run run = run(List.of());

		String line = run.line(WebFluxHostApplication.LINE);
		assertThat(run.exitCode()).as(line).isZero();
		assertThat(line).contains("ServletWebServerApplicationContext")
			.contains("web-server=TomcatWebServer")
			.contains("dispatcher-handler-bean=false")
			.contains("port-held-on-loopback=true")
			.contains("POST/mcp=200")
			.contains("web-filter-hits=0");
		assertThat(run.stdout()).contains("Tomcat started on port");
		List<String> warnings = run.stdout()
			.lines()
			.filter((candidate) -> candidate.contains("WARN") && candidate.contains("countingWebFilter"))
			.toList();
		assertThat(warnings).as("the warning about reactive beans in a servlet application").hasSize(1);
		assertThat(warnings.get(0)).contains("countingWebFilter (WebFilter)")
			.contains("the MCP starter brings Spring MVC and Tomcat")
			.contains("spring.main.web-application-type=reactive")
			.contains("gatool-in-process-spring-boot-starter");
	}

	@Test
	void webFluxApplication_typeSetToReactive_shouldStopStartupNamingTheInProcessStarter() throws Exception {
		HostProcess.Run run = run(List.of("--spring.main.web-application-type=reactive"));

		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(run.stdout()).contains("APPLICATION FAILED TO START")
			.contains("GATool does not serve its MCP endpoint over WebFlux yet")
			.contains("gatool-in-process-spring-boot-starter");
	}

	private static HostProcess.Run run(List<String> extraArguments) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off",
				"--gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"--gatool.api.url=http://127.0.0.1:1/graphql",
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls"));
		arguments.addAll(extraArguments);
		String classpath = System.getProperty("java.class.path") + File.pathSeparator
				+ String.join(File.pathSeparator, webFluxJars());
		return HostProcess.run(WebFluxHostApplication.class, classpath, arguments, Duration.ofSeconds(120));
	}

	private static List<String> webFluxJars() throws IOException {
		assertThat(WEBFLUX_JARS).as("the folder the pom copies the WebFlux jars into; run the Maven build once")
			.isDirectory();
		try (Stream<Path> files = Files.list(WEBFLUX_JARS)) {
			List<String> jars = files.filter((file) -> file.toString().endsWith(".jar"))
				.map(Path::toAbsolutePath)
				.map(Path::toString)
				.sorted()
				.toList();
			assertThat(jars).as("WebFlux jars in " + WEBFLUX_JARS).isNotEmpty();
			return jars;
		}
	}

}
