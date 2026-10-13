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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class GAToolElicitationPropertiesTests {

	@Test
	void defaults_shouldHoldTheTwoSentencesAndLeaveTheIgnoreFlagAndTheStdioFlagOff() {
		GAToolProperties properties = new GAToolProperties();

		GAToolElicitationProperties elicitation = properties.getElicitation();

		assertThat(elicitation.isIgnoreWhereUnsupported()).isFalse();
		assertThat(elicitation.getDeclinedAnswer()).isEqualTo(
				"The user declined the call to {tool}, so the API was not called. Ask the user what to do next.");
		assertThat(elicitation.getCancelledAnswer()).isEqualTo("The user dismissed the confirmation for {tool} without "
				+ "answering, so the API was not called. Ask the user whether to try again.");
		assertThat(properties.getMcp().getStdio().isElicitation()).isFalse();
	}

	@Test
	void bind_thePropertiesOfTheGroupAndTheStdioFlag_shouldReachTheGetters() {
		// Spring Boot's binder reads the kebab-case names the README documents, so the
		// four properties are written the way an application.properties file spells them.
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(
				Map.of("gatool.elicitation.declined-answer", "{tool} was declined by the user.",
						"gatool.elicitation.cancelled-answer", "The user closed the dialog for {tool}.",
						"gatool.elicitation.ignore-where-unsupported", "true", "gatool.mcp.stdio.elicitation", "true"));

		GAToolProperties properties = new Binder(source).bind("gatool", GAToolProperties.class).get();

		GAToolElicitationProperties elicitation = properties.getElicitation();
		assertThat(elicitation.getDeclinedAnswer()).isEqualTo("{tool} was declined by the user.");
		assertThat(elicitation.getCancelledAnswer()).isEqualTo("The user closed the dialog for {tool}.");
		assertThat(elicitation.isIgnoreWhereUnsupported()).isTrue();
		assertThat(properties.getMcp().getStdio().isElicitation()).isTrue();
	}

}
