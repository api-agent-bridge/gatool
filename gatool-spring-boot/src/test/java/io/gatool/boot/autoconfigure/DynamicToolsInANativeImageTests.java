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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The tools a model writes GraphQL through stop startup inside a native image.
 *
 * <p>
 * The check takes the answer of Spring's {@code NativeDetector} as a parameter. The
 * detector reads a system property once, when its class loads, and a JVM that sets the
 * property makes Spring Boot start from generated AOT artifacts, which a test application
 * lacks. So the tests here hand the check each answer themselves.
 */
class DynamicToolsInANativeImageTests {

	@Test
	void requireTheJvmForDynamicTools_threeStepInANativeImage_shouldStopNamingThePropertyAndTheWaysOut() {
		assertThatExceptionOfType(InvalidConfigurationPropertyValueException.class)
			.isThrownBy(() -> DynamicToolBuilding.requireTheJvmForDynamicTools(ToolGeneration.DYNAMIC_THREE_STEP, true))
			.satisfies((refusal) -> {
				assertThat(refusal.getName()).isEqualTo("gatool.dev.experimental.generate-tools");
				assertThat(refusal.getValue()).isEqualTo(ToolGeneration.DYNAMIC_THREE_STEP);
				assertThat(refusal.getReason())
					.isEqualTo("The tools searchSchema, introspectType and executeGraphql run on the JVM in this "
							+ "release, and this application runs as a native image. Set the property to none and "
							+ "write operation files, or run the application on the JVM.");
			});
	}

	@ParameterizedTest
	@EnumSource(ToolGeneration.class)
	void requireTheJvmForDynamicTools_onTheJvm_shouldPassWhateverThePropertySelects(ToolGeneration generate) {
		assertThatCode(() -> DynamicToolBuilding.requireTheJvmForDynamicTools(generate, false))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@EnumSource(value = ToolGeneration.class, names = { "NONE", "ALL_ROOT_QUERIES", "ALL_ROOT_QUERIES_AND_MUTATIONS" })
	void requireTheJvmForDynamicTools_toolsFromFilesOrRootFieldsInANativeImage_shouldPass(ToolGeneration generate) {
		// These tools send a document GATool holds, as the tools of operation files do,
		// and the refusal covers the tools that rank the schema and run a model's
		// document.
		assertThatCode(() -> DynamicToolBuilding.requireTheJvmForDynamicTools(generate, true))
			.doesNotThrowAnyException();
	}

}
