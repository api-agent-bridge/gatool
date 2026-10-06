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

package io.gatool.core.internal.schema;

/**
 * The JSON Schema names GATool writes: the keywords the two schema writers publish, the
 * values those keywords take, and the same names {@link ScalarSchemas} refuses a
 * configured fragment against. One declaration holds them, so what GATool publishes and
 * what it refuses stay in step.
 *
 * <p>
 * A value carries the keyword it belongs to, so {@code TYPE_STRING} is the {@code string}
 * that {@code type} takes and {@code FORMAT_DATE_TIME} is the {@code date-time} that
 * {@code format} takes. {@code SchemaKeyword} in the victools generator, which this
 * classpath already carries through Spring AI, holds its vocabulary the same way.
 *
 * @author Željko Kozina
 */
public final class JsonSchemaKeywords {

	// The keywords GATool writes into a schema.

	/**
	 * The keyword that names the JSON type of a value.
	 */
	public static final String TYPE = "type";

	/**
	 * The keyword that narrows a string to a named format.
	 */
	public static final String FORMAT = "format";

	/**
	 * The keyword that holds the regular expression a string has to match.
	 */
	public static final String PATTERN = "pattern";

	/**
	 * The keyword that lists the values a position accepts.
	 */
	public static final String ENUM = "enum";

	/**
	 * The keyword that carries the text a model reads about a position.
	 */
	public static final String DESCRIPTION = "description";

	/**
	 * The keyword that lists alternative schemas, one of which the value has to match.
	 */
	public static final String ANY_OF = "anyOf";

	/**
	 * The keyword that holds the schema of every element of an array.
	 */
	public static final String ITEMS = "items";

	/**
	 * The keyword that holds the schema of each named property of an object.
	 */
	public static final String PROPERTIES = "properties";

	/**
	 * The keyword that lists the properties an object has to carry.
	 */
	public static final String REQUIRED = "required";

	/**
	 * The keyword that says whether an object accepts properties the schema does not
	 * name.
	 */
	public static final String ADDITIONAL_PROPERTIES = "additionalProperties";

	/**
	 * The keyword that holds the value a position takes when the caller leaves it out.
	 */
	public static final String DEFAULT = "default";

	/**
	 * The keyword that pins a position to a single value.
	 */
	public static final String CONST = "const";

	/**
	 * The keyword that declares the dialect a schema is written in.
	 */
	public static final String SCHEMA = "$schema";

	/**
	 * The dialect every schema GATool publishes declares under {@link #SCHEMA}. It sits
	 * beside the names above because those names are this dialect's: moving to another
	 * draft changes both together.
	 */
	public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

	// The values the type keyword takes. TYPE_NULL is one of them: it holds the text
	// "null", the way TYPE_STRING holds "string", and a nullable position writes it as
	// the type of its own anyOf branch.

	/**
	 * The type of a string value.
	 */
	public static final String TYPE_STRING = "string";

	/**
	 * The type of an integer value.
	 */
	public static final String TYPE_INTEGER = "integer";

	/**
	 * The type of a number value.
	 */
	public static final String TYPE_NUMBER = "number";

	/**
	 * The type of a boolean value.
	 */
	public static final String TYPE_BOOLEAN = "boolean";

	/**
	 * The type of an object value.
	 */
	public static final String TYPE_OBJECT = "object";

	/**
	 * The type of an array value.
	 */
	public static final String TYPE_ARRAY = "array";

	/**
	 * The type of the null value, written as the type of a nullable position's own anyOf
	 * branch.
	 */
	public static final String TYPE_NULL = "null";

	// The values the format keyword takes, which travel beside type: string.

	/**
	 * The format of an RFC 3339 date and time.
	 */
	public static final String FORMAT_DATE_TIME = "date-time";

	/**
	 * The format of an RFC 3339 time.
	 */
	public static final String FORMAT_TIME = "time";

	/**
	 * The format of an RFC 3339 full date.
	 */
	public static final String FORMAT_DATE = "date";

	/**
	 * The format of an ISO 8601 duration.
	 */
	public static final String FORMAT_DURATION = "duration";

	/**
	 * The format of an email address.
	 */
	public static final String FORMAT_EMAIL = "email";

	/**
	 * The format of an internet host name.
	 */
	public static final String FORMAT_HOSTNAME = "hostname";

	/**
	 * The format of a URI.
	 */
	public static final String FORMAT_URI = "uri";

	/**
	 * The format of an IPv4 address.
	 */
	public static final String FORMAT_IPV4 = "ipv4";

	/**
	 * The format of an IPv6 address.
	 */
	public static final String FORMAT_IPV6 = "ipv6";

	/**
	 * The format of a UUID.
	 */
	public static final String FORMAT_UUID = "uuid";

	private JsonSchemaKeywords() {
	}

}
