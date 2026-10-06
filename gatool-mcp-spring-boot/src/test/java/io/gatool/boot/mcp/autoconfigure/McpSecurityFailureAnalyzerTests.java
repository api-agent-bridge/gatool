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

import java.util.List;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup report for a security setup GATool cannot serve: the exception's message is
 * the description, its action is the line to change, and Boot finds the analyzer through
 * the factories file, so the report appears where Boot's own do.
 */
class McpSecurityFailureAnalyzerTests {

	private final McpSecurityFailureAnalyzer analyzer = new McpSecurityFailureAnalyzer();

	@Test
	void analyze_exceptionWrappedInABeanCreationFailure_shouldReportTheMessageAndTheAction() {
		McpSecurityException cause = new McpSecurityException(
				"The security filter chain that serves POST /mcp lacks the resource server GATool configures.",
				"Give the endpoint a chain of its own: http.securityMatcher(\"/mcp\").with(mcpServer()).");
		BeanCreationException failure = new BeanCreationException("gaToolMcpChainStartupCheck", "failed", cause);

		FailureAnalysis analysis = this.analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).isEqualTo(cause.getMessage());
		assertThat(analysis.getAction()).isEqualTo(cause.action());
		assertThat(analysis.getCause()).isSameAs(cause);
	}

	@Test
	void analyze_failureWithoutTheException_shouldLeaveTheReportToOtherAnalyzers() {
		assertThat(this.analyzer.analyze(new IllegalStateException("something else"))).isNull();
	}

	@Test
	void factories_shouldRegisterTheAnalyzerForBoot() {
		// Boot's own analyzers on this classpath need a bean factory to instantiate, so
		// the ones that fail to are logged and skipped, and this one has to be among
		// the rest.
		List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
			.load(FailureAnalyzer.class, SpringFactoriesLoader.FailureHandler.logging(LogFactory.getLog(getClass())));

		assertThat(analyzers).anyMatch(McpSecurityFailureAnalyzer.class::isInstance);
	}

}
