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

package io.gatool.boot.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Reads the Spring Boot and Spring AI versions this application runs and warns where
 * either is outside the line this release was tested on.
 *
 * <p>
 * The checks log under the name of {@link GAToolAutoConfiguration}, so a logging level an
 * operator set for it covers these lines.
 *
 * @author Željko Kozina
 */
final class SpringVersionChecks {

	private static final Log logger = LogFactory.getLog(GAToolAutoConfiguration.class);

	private SpringVersionChecks() {
	}

	/**
	 * Warns where this application runs Spring Boot outside 4.1.x, or Spring AI outside
	 * 2.0.x from 2.0.1, naming the tested lines and the versions found.
	 * @param springBootVersion the version {@code SpringBootVersion.getVersion()}
	 * returned
	 * @param springAiVersion the {@code Implementation-Version} of the Spring AI jar that
	 * carries {@code EmbeddingModel}, or {@code null} where spring-ai-model is missing
	 * from the classpath or its manifest leaves that entry out
	 */
	// A warning fits better than a stop: a team on a newer line runs an untested
	// combination, and a stop would block an application on Spring Boot 4.2 the day that
	// line ships. The check reads both versions as parameters instead of the classpath
	// itself, so a test hands it every case, the way requireTheJvmForDynamicTools takes
	// the native-image detector's answer.
	static void warnWhereSpringVersionsAreUntested(@Nullable String springBootVersion,
			@Nullable String springAiVersion) {
		boolean bootOnTheTestedLine = springBootVersion != null && onTheTestedBootLine(springBootVersion);
		boolean aiOnTheTestedLine = springAiVersion != null && onTheTestedAiLine(springAiVersion);
		if (bootOnTheTestedLine && aiOnTheTestedLine) {
			return;
		}
		String bootFound = (springBootVersion != null) ? "Spring Boot " + springBootVersion
				: "a version of Spring Boot that GATool could not read";
		String aiFound = (springAiVersion != null) ? "Spring AI " + springAiVersion
				: "a version of Spring AI that GATool could not read";
		logger.warn("GATool tested this release on Spring Boot 4.1.x and Spring AI 2.0.x from 2.0.1. This "
				+ "application runs " + bootFound + " and " + aiFound + ", an untested combination. Move it to "
				+ "those lines, or continue on this combination knowing GATool's tests did not cover it: the "
				+ "README's Requirements section says what that means.");
	}

	// Tells whether the version is on the 4.1.x line this release was tested on.
	//
	// A qualifier such as -SNAPSHOT, -M1 or -RC1 marks a pre-release build, which is not
	// the released line this check names, so a version carrying one counts as outside it
	// even where its leading numbers match.
	private static boolean onTheTestedBootLine(String version) {
		if (!isPlainDottedVersion(version)) {
			return false;
		}
		int[] numbers = leadingVersionNumbers(version, 2);
		return numbers[0] == 4 && numbers[1] == 1;
	}

	// Tells whether the version is on the 2.0.x line this release was tested on, at or
	// above the 2.0.1 patch that fixes the two advisories the README names.
	//
	// The same reasoning as onTheTestedBootLine: a qualifier takes the version outside
	// the tested line.
	private static boolean onTheTestedAiLine(String version) {
		if (!isPlainDottedVersion(version)) {
			return false;
		}
		int[] numbers = leadingVersionNumbers(version, 3);
		return numbers[0] == 2 && numbers[1] == 0 && numbers[2] >= 1;
	}

	// Tells whether every character of the version is a digit, or a single dot between
	// two digit runs, which rules out a qualifier such as -SNAPSHOT, -M1 or -RC1.
	private static boolean isPlainDottedVersion(String version) {
		if (version.isEmpty()) {
			return false;
		}
		boolean previousWasDot = false;
		for (int i = 0; i < version.length(); i++) {
			char character = version.charAt(i);
			if (character == '.') {
				if (i == 0 || previousWasDot) {
					return false;
				}
				previousWasDot = true;
				continue;
			}
			if (!Character.isDigit(character)) {
				return false;
			}
			previousWasDot = false;
		}
		return !previousWasDot;
	}

	// Returns the leading count numbers of a dot-separated version string that
	// isPlainDottedVersion accepts, treating a missing trailing number as zero.
	private static int[] leadingVersionNumbers(String version, int count) {
		int[] numbers = new int[count];
		int start = 0;
		for (int i = 0; i < count; i++) {
			if (start > version.length()) {
				numbers[i] = 0;
				continue;
			}
			int dot = version.indexOf('.', start);
			int end = (dot < 0) ? version.length() : dot;
			numbers[i] = Integer.parseInt(version.substring(start, end));
			start = end + 1;
		}
		return numbers;
	}

	/**
	 * Returns the {@code Implementation-Version} of the Spring AI jar that carries
	 * {@code EmbeddingModel}, read from its manifest, or {@code null} where
	 * spring-ai-model is missing from the classpath or its manifest leaves that entry
	 * out.
	 * @return the version, or {@code null} where the jar or the manifest entry is missing
	 */
	static @Nullable String springAiVersion() {
		ClassLoader classLoader = GAToolAutoConfiguration.class.getClassLoader();
		if (!ClassUtils.isPresent(GAToolAutoConfiguration.EMBEDDING_MODEL, classLoader)) {
			return null;
		}
		return ClassUtils.resolveClassName(GAToolAutoConfiguration.EMBEDDING_MODEL, classLoader)
			.getPackage()
			.getImplementationVersion();
	}

}
