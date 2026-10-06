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

package io.gatool.core.check;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.gatool.core.model.RequestLimits;
import io.gatool.core.naming.ToolNamingStrategy;

/**
 * The application settings a build-time check has to match.
 *
 * <p>
 * The check exists to make a broken operation file fail the build that changed it instead
 * of the deployment that follows, and it can only do that while it reads the schema the
 * application publishes. Every setting that changes that schema belongs here, because one
 * the check cannot see makes it report a different input schema and a different set of
 * warnings from the application it guards. The mismatch goes in both directions: a
 * warning that has already been answered, and a misconfiguration that stops startup and
 * passes the check.
 *
 * <p>
 * The settings travel as one object, so a new input reaches the check without another
 * parameter.
 *
 * @param namingStrategy how a tool takes its name, matching
 * {@code gatool.naming.strategy}
 * @param publishesOutputSchema whether tools publish an output schema, matching
 * {@code gatool.results.publish-output-schema}
 * @param scalarSchemas the JSON Schema fragment configured for each custom scalar,
 * matching {@code gatool.inputs.scalar-schemas}
 * @param resultScalarSchemas the JSON Schema fragment the output schema publishes for a
 * custom scalar that returns another form than it accepts, matching
 * {@code gatool.results.scalar-schemas}
 * @param requestLimits the limits the application expects its API to apply to a request,
 * matching {@code gatool.api.request-limits}
 * @author Željko Kozina
 */
public record CheckSettings(ToolNamingStrategy namingStrategy, boolean publishesOutputSchema,
		Map<String, ?> scalarSchemas, Map<String, ?> resultScalarSchemas, RequestLimits requestLimits) {

	/**
	 * Copies both maps of scalar schemas in the order the caller gave them, so the
	 * settings keep what they were given however the caller changes its own maps
	 * afterwards.
	 */
	public CheckSettings {
		// An ordered copy, because Map.copyOf leaves iteration order unspecified and the
		// problems a check prints would come out in a different order on every run.
		scalarSchemas = Collections.unmodifiableMap(new LinkedHashMap<>(scalarSchemas));
		resultScalarSchemas = Collections.unmodifiableMap(new LinkedHashMap<>(resultScalarSchemas));
	}

	/**
	 * Returns the settings of an application that leaves all of them unset.
	 * @return the defaults, which are camel case names, output schemas off, both maps of
	 * scalar fragments empty and the request limits of graphql-java
	 */
	public static CheckSettings defaults() {
		return new CheckSettings(ToolNamingStrategy.camelCase(), false, Map.of(), Map.of(), RequestLimits.defaults());
	}

	/**
	 * Returns these settings with another naming strategy.
	 * @param strategy how a tool takes its name
	 * @return the settings carrying that strategy
	 */
	public CheckSettings withNamingStrategy(ToolNamingStrategy strategy) {
		return new CheckSettings(strategy, this.publishesOutputSchema, this.scalarSchemas, this.resultScalarSchemas,
				this.requestLimits);
	}

	/**
	 * Returns these settings with output schemas on or off.
	 * @param publish whether tools publish an output schema
	 * @return the settings carrying that choice
	 */
	public CheckSettings withPublishedOutputSchema(boolean publish) {
		return new CheckSettings(this.namingStrategy, publish, this.scalarSchemas, this.resultScalarSchemas,
				this.requestLimits);
	}

	/**
	 * Returns these settings with one custom scalar described.
	 * @param scalarName the name the schema declares
	 * @param scalarSchema the JSON Schema for the scalar, as the property holds it
	 * @return the settings carrying that schema beside the others
	 */
	public CheckSettings withScalarSchema(String scalarName, Map<String, ?> scalarSchema) {
		Map<String, Object> schemas = new LinkedHashMap<>(this.scalarSchemas);
		schemas.put(scalarName, scalarSchema);
		return new CheckSettings(this.namingStrategy, this.publishesOutputSchema, schemas, this.resultScalarSchemas,
				this.requestLimits);
	}

	/**
	 * Returns these settings with the schema the output schema publishes for one custom
	 * scalar, which takes the place of what the output schema would reuse from
	 * {@link #withScalarSchema(String, Map)}.
	 * @param scalarName the name the schema declares
	 * @param scalarSchema the JSON Schema for the scalar in a result, as
	 * {@code gatool.results.scalar-schemas} holds it
	 * @return the settings carrying that schema beside the others
	 */
	public CheckSettings withResultScalarSchema(String scalarName, Map<String, ?> scalarSchema) {
		Map<String, Object> schemas = new LinkedHashMap<>(this.resultScalarSchemas);
		schemas.put(scalarName, scalarSchema);
		return new CheckSettings(this.namingStrategy, this.publishesOutputSchema, this.scalarSchemas, schemas,
				this.requestLimits);
	}

	/**
	 * Returns these settings with the limits the application expects its API to apply to
	 * a request, so the check warns about the documents startup warns about.
	 * @param limits the limits, as {@code gatool.api.request-limits} holds them
	 * @return the settings carrying those limits
	 */
	public CheckSettings withRequestLimits(RequestLimits limits) {
		return new CheckSettings(this.namingStrategy, this.publishesOutputSchema, this.scalarSchemas,
				this.resultScalarSchemas, limits);
	}
}
