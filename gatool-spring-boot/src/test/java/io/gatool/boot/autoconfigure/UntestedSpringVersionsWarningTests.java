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

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A startup warning names the Spring Boot and the Spring AI line this release was tested
 * on where this application runs a different one, and says to move to those lines or to
 * run the untested combination knowingly.
 *
 * <p>
 * The check takes both versions as parameters, the way
 * {@link DynamicToolsInANativeImageTests} hands the native-image detector's answer to
 * {@code requireTheJvmForDynamicTools}, so a test hands it every case without a classpath
 * built for it.
 */
class UntestedSpringVersionsWarningTests {

	private static final String TESTED_LINES = "GATool tested this release on Spring Boot 4.1.x and Spring AI "
			+ "2.0.x from 2.0.1. This application runs ";

	private static final String ACTION = " Move it to those lines, or continue on this combination knowing "
			+ "GATool's tests did not cover it: the README's Requirements section says what that means.";

	@Test
	void warnWhereSpringVersionsAreUntested_bothOnTheTestedLine_shouldStaySilent() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1", "2.0.1");

		assertThat(warnings(lines)).isEmpty();
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springBootBelowTheTestedLine_shouldWarnNamingBothVersions() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.0.8", "2.0.1");

		assertThat(warnings(lines))
			.containsExactly(TESTED_LINES + "Spring Boot 4.0.8 and Spring AI 2.0.1, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springBootAboveTheTestedLine_shouldWarnNamingBothVersions() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.2.0", "2.0.1");

		assertThat(warnings(lines))
			.containsExactly(TESTED_LINES + "Spring Boot 4.2.0 and Spring AI 2.0.1, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springAiBelowTheFixedPatch_shouldWarnNamingBothVersions() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1", "2.0.0");

		assertThat(warnings(lines))
			.containsExactly(TESTED_LINES + "Spring Boot 4.1.1 and Spring AI 2.0.0, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springAiOnAnOlderLine_shouldWarnNamingBothVersions() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1", "1.1.0");

		assertThat(warnings(lines))
			.containsExactly(TESTED_LINES + "Spring Boot 4.1.1 and Spring AI 1.1.0, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springBootVersionUnreadable_shouldWarnThatItCouldNotBeRead() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested(null, "2.0.1");

		assertThat(warnings(lines)).containsExactly(TESTED_LINES + "a version of Spring Boot that GATool could "
				+ "not read and Spring AI 2.0.1, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springAiVersionUnreadable_shouldWarnThatItCouldNotBeRead() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1", null);

		assertThat(warnings(lines)).containsExactly(TESTED_LINES + "Spring Boot 4.1.1 and a version of Spring AI "
				+ "that GATool could not read, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springBootCarriesAQualifier_shouldWarnNamingTheVersionFound() {
		// 4.1 matches the tested line's numbers, and the qualifier still takes the build
		// outside it: a
		// SNAPSHOT of 4.1.1 is not the released 4.1.1 this release was tested against.
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1-SNAPSHOT", "2.0.1");

		assertThat(warnings(lines)).containsExactly(
				TESTED_LINES + "Spring Boot 4.1.1-SNAPSHOT and Spring AI 2.0.1, an untested combination." + ACTION);
	}

	@Test
	void warnWhereSpringVersionsAreUntested_springAiCarriesAQualifier_shouldWarnNamingTheVersionFound() {
		ListAppender<ILoggingEvent> lines = watchTheAutoConfiguration();

		SpringVersionChecks.warnWhereSpringVersionsAreUntested("4.1.1", "2.0.1-M1");

		assertThat(warnings(lines)).containsExactly(
				TESTED_LINES + "Spring Boot 4.1.1 and Spring AI 2.0.1-M1, an untested combination." + ACTION);
	}

	private static ListAppender<ILoggingEvent> watchTheAutoConfiguration() {
		Logger logger = (Logger) LoggerFactory.getLogger(GAToolAutoConfiguration.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream()
			.filter((event) -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

}
