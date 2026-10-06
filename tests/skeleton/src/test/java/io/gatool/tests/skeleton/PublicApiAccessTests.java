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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

// This module has all three Spring modules on its test classpath, so it reads every
// registration file of GATool at once and reaches every registered class by name.
// Spring Boot creates these classes itself, and application code leaves their names
// alone, which is why the access of each one is pinned here.
class PublicApiAccessTests {

	private static final String FACTORIES = "META-INF/spring.factories";

	private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private static final String GATOOL = "io.gatool.";

	// The seven classes of spring.factories: three failure analyzers, three listeners and
	// one environment post-processor. A registration added or lost fails this list.
	private static final List<String> FACTORIES_CLASSES = List.of(
			"io.gatool.boot.autoconfigure.OperationFileProblemsFailureAnalyzer",
			"io.gatool.boot.mcp.autoconfigure.GAToolEnvironmentPostProcessor",
			"io.gatool.boot.mcp.autoconfigure.McpSecurityFailureAnalyzer",
			"io.gatool.boot.mcp.autoconfigure.McpStdioLog4j2Listener",
			"io.gatool.boot.mcp.autoconfigure.McpStdioLogListener",
			"io.gatool.boot.mcp.autoconfigure.McpStdioStartupFailureReporter",
			"io.gatool.boot.inprocess.autoconfigure.InProcessToolNameClashFailureAnalyzer");

	// The six auto-configurations, which stay public because an application excludes one
	// by name and orders its own configuration against it.
	private static final List<String> AUTO_CONFIGURATION_CLASSES = List.of(
			"io.gatool.boot.autoconfigure.GAToolAutoConfiguration",
			"io.gatool.boot.autoconfigure.GAToolCredentialsAutoConfiguration",
			"io.gatool.boot.mcp.autoconfigure.GAToolMcpJsonMapperAutoConfiguration",
			"io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration",
			"io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration",
			"io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration");

	@Test
	void factoriesClasses_ofEveryModule_shouldBePackagePrivate() throws Exception {
		List<String> registered = gaToolNamesInFactories();
		assertThat(registered).containsExactlyInAnyOrderElementsOf(FACTORIES_CLASSES);

		List<String> reachable = new ArrayList<>();
		for (String name : registered) {
			Class<?> type = Class.forName(name);
			if (Modifier.isPublic(type.getModifiers())) {
				reachable.add(name + " is public");
			}
		}

		assertThat(reachable).as("classes Spring Boot instantiates that an application can reach").isEmpty();
	}

	@Test
	void beanMethods_ofEveryAutoConfiguration_shouldBePackagePrivate() throws Exception {
		List<String> imported = gaToolNamesInImports();
		assertThat(imported).containsExactlyInAnyOrderElementsOf(AUTO_CONFIGURATION_CLASSES);

		List<String> publicMethods = new ArrayList<>();
		for (String name : imported) {
			collectPublicBeanMethods(Class.forName(name), publicMethods);
		}

		assertThat(publicMethods).as("@Bean methods an application can call itself").isEmpty();
	}

	// A nested configuration class holds bean methods of its own, so the walk goes down.
	private static void collectPublicBeanMethods(Class<?> type, List<String> found) {
		for (Method method : type.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Bean.class) && Modifier.isPublic(method.getModifiers())) {
				found.add(type.getSimpleName() + "." + method.getName() + " is public");
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			collectPublicBeanMethods(nested, found);
		}
	}

	// Every value of every spring.factories on the classpath, filtered to GATool's own
	// classes. The file is a properties file whose values are comma-separated lists.
	private static List<String> gaToolNamesInFactories() throws IOException {
		List<String> names = new ArrayList<>();
		for (URL file : resources(FACTORIES)) {
			Properties registrations = new Properties();
			try (InputStream stream = file.openStream()) {
				registrations.load(stream);
			}
			for (String key : registrations.stringPropertyNames()) {
				for (String name : registrations.getProperty(key).split(",")) {
					add(names, name);
				}
			}
		}
		return names;
	}

	// Every line of every AutoConfiguration.imports on the classpath, filtered the same
	// way. The file holds one class name per line and allows a comment behind a hash.
	private static List<String> gaToolNamesInImports() throws IOException {
		List<String> names = new ArrayList<>();
		for (URL file : resources(IMPORTS)) {
			String content;
			try (InputStream stream = file.openStream()) {
				content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}
			for (String line : content.split("\\R")) {
				int comment = line.indexOf('#');
				add(names, (comment < 0) ? line : line.substring(0, comment));
			}
		}
		return names;
	}

	private static void add(List<String> names, String candidate) {
		String trimmed = candidate.trim();
		if (trimmed.startsWith(GATOOL)) {
			names.add(trimmed);
		}
	}

	private static List<URL> resources(String path) throws IOException {
		List<URL> files = new ArrayList<>();
		Enumeration<URL> found = PublicApiAccessTests.class.getClassLoader().getResources(path);
		while (found.hasMoreElements()) {
			files.add(found.nextElement());
		}
		return files;
	}

}
