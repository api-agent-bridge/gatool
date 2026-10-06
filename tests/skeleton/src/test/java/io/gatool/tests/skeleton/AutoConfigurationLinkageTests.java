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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An auto-configuration class links on a class path that lacks one of its optional jars.
 *
 * <p>
 * Spring Boot deduces the bean type of a condition through
 * {@code Class.getDeclaredMethods()}, and the JVM resolves the parameter and return types
 * of every declared method there, private and synthetic ones included. One method of the
 * outer class that names an optional type in its signature therefore fails the whole
 * class with a {@code NoClassDefFoundError}, ahead of the startup message that names the
 * jar to add.
 *
 * <p>
 * A {@code FilteredClassLoader} cannot show this: it filters the lookups that go through
 * it, and the auto-configuration is defined by the parent loader, which still finds the
 * jar. Each case here loads the class in a loader built from the test class path without
 * the jar.
 */
class AutoConfigurationLinkageTests {

	@ParameterizedTest(name = "{0} without {1}")
	@CsvSource({ "io.gatool.boot.autoconfigure.GAToolAutoConfiguration, spring-boot-http-client|spring-boot-restclient",
			"io.gatool.boot.autoconfigure.GAToolAutoConfiguration, spring-ai-",
			"io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration, micrometer-core" })
	void declaredMethods_onAClassPathWithoutAnOptionalJar_shouldLink(String autoConfiguration, String jars)
			throws Exception {
		try (URLClassLoader loader = classPathWithout(jars.split("\\|"))) {
			Class<?> type = loader.loadClass(autoConfiguration);

			assertThatCode(type::getDeclaredMethods).doesNotThrowAnyException();
		}
	}

	private static URLClassLoader classPathWithout(String... jarNames) throws Exception {
		List<URL> urls = new ArrayList<>();
		int dropped = 0;
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			String fileName = new File(entry).getName();
			boolean drop = false;
			for (String jarName : jarNames) {
				drop |= fileName.startsWith(jarName);
			}
			if (drop) {
				dropped++;
				continue;
			}
			urls.add(new File(entry).toURI().toURL());
		}
		assertThat(dropped).as("jars dropped from the class path for %s", List.of(jarNames)).isPositive();
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}

}
