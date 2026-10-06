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

package io.gatool.boot.internal.execution;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitNullsTests {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	@Test
	void mapperKeepingNulls_shouldReadAFloatAsABigDecimalSoEveryDigitSurvives() {
		// The API's decimals arrive through this copy, and a float read into a double
		// loses everything past the seventeenth significant digit, so a Decimal scalar
		// the schema describes to the model would reach it rounded.
		JsonMapper mapper = ExplicitNulls.mapperKeepingNulls(JsonMapper.builder().build());

		Map<String, Object> read = mapper.readValue("{\"value\":1234567890.123456789,\"exp\":1E+2}", MAP_TYPE);

		assertThat(read).containsEntry("value", new BigDecimal("1234567890.123456789"));
		assertThat(mapper.writeValueAsString(read)).isEqualTo("{\"value\":1234567890.123456789,\"exp\":1E+2}");
	}

}
