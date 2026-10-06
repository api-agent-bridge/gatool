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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classpath of a stdio server that logs through Log4j2 or through
 * {@code java.util.logging}.
 *
 * <p>
 * An application reaches either logging system by excluding
 * {@code spring-boot-starter-logging}, which brings Logback, the two bridges that lead to
 * it, and the Log4j API one of the bridges implements. On Log4j2 it adds
 * {@code spring-boot-starter-log4j2}, which brings the Log4j API back beside its
 * implementation, and on {@code java.util.logging} it adds the SLF4J provider for that
 * system. The classpath built here is the one of the tests with the same jars taken off
 * and the same jars added.
 *
 * <p>
 * The jars that are added come from two folders under {@code target}, which the
 * skeleton's pom fills. They stay off the classpath of the tests, because SLF4J warns
 * about a second provider at the start of every test that logs, and Spring Boot keeps
 * choosing Logback while Logback is present, so the jars would change the output of the
 * other tests and leave their logging system as it is.
 */
final class LoggingSystemClasspath {

	private static final Path LOG4J2_JARS = Path.of("target", "log4j2-classpath");

	private static final Path JAVA_UTIL_LOGGING_JARS = Path.of("target", "java-util-logging-classpath");

	// The jars spring-boot-starter-logging brings, by the names their files have in a
	// Maven repository.
	private static final List<String> LOGBACK_AND_ITS_BRIDGES = List.of("logback-classic", "logback-core",
			"log4j-to-slf4j", "jul-to-slf4j");

	// The Log4j API arrives with log4j-to-slf4j, so it leaves with the starter. On
	// Log4j2 it comes back with the implementation, so it keeps its place there.
	private static final String LOG4J_API = "log4j-api";

	private LoggingSystemClasspath() {
	}

	/**
	 * The classpath of the tests with Log4j2 in the place of Logback.
	 * @param ahead folders that stand ahead of every other entry, where an application
	 * keeps a Log4j2 file of its own
	 * @return the classpath of the child JVM
	 * @throws IOException when the folder of the jars cannot be read
	 */
	static String onLog4j2(Path... ahead) throws IOException {
		List<String> entries = new ArrayList<>();
		for (Path folder : ahead) {
			entries.add(folder.toAbsolutePath().toString());
		}
		entries.addAll(without(LOGBACK_AND_ITS_BRIDGES));
		entries.addAll(jarsIn(LOG4J2_JARS));
		return String.join(File.pathSeparator, entries);
	}

	/**
	 * The classpath of the tests with {@code java.util.logging} in the place of Logback.
	 * @return the classpath of the child JVM
	 * @throws IOException when the folder of the jars cannot be read
	 */
	static String onJavaUtilLogging() throws IOException {
		List<String> leftOut = new ArrayList<>(LOGBACK_AND_ITS_BRIDGES);
		leftOut.add(LOG4J_API);
		List<String> entries = new ArrayList<>(without(leftOut));
		entries.addAll(jarsIn(JAVA_UTIL_LOGGING_JARS));
		return String.join(File.pathSeparator, entries);
	}

	// Every name has to match one entry. A jar that changed its name would stay on the
	// classpath otherwise, and the child would run on Logback while its test reads the
	// streams as those of another logging system.
	private static List<String> without(List<String> names) {
		List<String> entries = new ArrayList<>(
				List.of(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))));
		for (String name : names) {
			Pattern jar = Pattern.compile(Pattern.quote(name) + "-\\d[^/\\\\]*\\.jar$");
			assertThat(entries.removeIf((entry) -> jar.matcher(entry).find()))
				.as("the classpath of the tests holds " + name)
				.isTrue();
		}
		return entries;
	}

	private static List<String> jarsIn(Path folder) throws IOException {
		assertThat(folder).as("the folder the pom copies the jars into; run the Maven build once").isDirectory();
		try (Stream<Path> files = Files.list(folder)) {
			List<String> jars = files.filter((file) -> file.toString().endsWith(".jar"))
				.map(Path::toAbsolutePath)
				.map(Path::toString)
				.sorted()
				.toList();
			assertThat(jars).as("jars in " + folder).isNotEmpty();
			return jars;
		}
	}

}
