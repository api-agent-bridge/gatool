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

package io.gatool.boot.inprocess.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Writes the startup report for an in-process tool name declared twice, so the team reads
 * the {@code ChatClient} code to write, or the bean to rename, where Boot's other reports
 * appear.
 *
 * <p>
 * Boot loads this class from {@code META-INF/spring.factories} before the application
 * context exists, so it reads the exception alone and leaves beans to the
 * auto-configuration.
 *
 * @author Željko Kozina
 */
class InProcessToolNameClashFailureAnalyzer extends AbstractFailureAnalyzer<InProcessToolNameClashException> {

	/**
	 * Creates the analyzer, which Spring Boot instantiates from
	 * {@code META-INF/spring.factories}.
	 */
	InProcessToolNameClashFailureAnalyzer() {
	}

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, InProcessToolNameClashException cause) {
		return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
	}

}
