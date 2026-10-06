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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The scalar specifications GATool has read, looked up by the URL a schema declares with
 * {@code @specifiedBy}.
 *
 * <p>
 * GraphQL asks that URL to "link to a human-readable specification of the data format,
 * serialization, and coercion rules", so GATool cannot derive the rules from it at
 * runtime. What it can serve as is a key: a person reads the page once and writes the
 * row, and every schema citing that page gets the mapping without configuring anything.
 * The specification supports exactly that use by asking a service to pin the URL: it
 * "should not be changed once defined. Doing so would likely disrupt tooling."
 *
 * <p>
 * The same specification is cited under several spellings, so the lookup normalises the
 * URL first. {@code https://tools.ietf.org/html/rfc4122} answers 301 to
 * {@code https://datatracker.ietf.org/doc/html/rfc4122}, and both spellings are in
 * circulation, including in the GraphQL specification's own examples;
 * {@code scalars.graphql.org/chillicream/long} and the same URL with {@code .html} both
 * answer 200. An exact string match would read those as different pages and map one of
 * them.
 *
 * <p>
 * The table holds every one of the 31 specifications the registry publishes, each mapped
 * from its own page. The scalar {@code chillicream/long} travels as a JSON number. The
 * scalar {@code chillicream/any} takes any JSON value, so its entry leaves {@code type}
 * out entirely, and {@code chillicream/uri} accepts a relative reference, which the
 * {@code uri} format refuses.
 *
 * <p>
 * A {@code format} is published where its grammar accepts everything the page accepts, so
 * a value the page accepts always passes it. Nine of the 33 entries clear that bar: two
 * {@code uuid}, three {@code date-time} and four {@code date}. The rest carry the shape
 * in the description, because the format would refuse a value the API takes:
 * {@code duration} lacks a sign and fractional seconds, which three of the duration
 * scalars allow, and {@code uri} refuses the relative reference {@code chillicream/uri}
 * accepts. A format that merely accepts more than the page does is kept, since
 * {@code format} annotates and the description states the narrowing, which is the
 * {@code andimarek/date-time} case: exactly three fractional digits, and {@code -00:00}
 * refused.
 *
 * <p>
 * A configured fragment wins over this table, because the table holds what GATool read
 * from a published page and the API decides what it accepts. A schema that cites a page
 * and answers with something else is the case that needs the override.
 *
 * @author Željko Kozina
 */
final class ScalarSpecifications {

	private static final String HTML = ".html";

	// The scheme and the host, which are the parts a URL may spell in another case. The
	// path keeps its case, because the registry serves AlexandreCarlton and answers 404
	// for alexandrecarlton. [^/]++ is possessive, so the host is taken once and kept. It
	// is the pattern's only backtracking point: [A-Za-z]+ is pinned by the colon that
	// follows it.
	private static final Pattern SCHEME_AND_HOST = Pattern.compile("^([A-Za-z]+://[^/]++)(.*)$");

	private static final Pattern RFC = Pattern
		.compile("^https?://(?:tools\\.ietf\\.org/html|datatracker\\.ietf\\.org/doc/html|www\\.rfc-editor\\.org/rfc)"
				+ "/(rfc\\d+)$");

	private static final Map<String, Specification> SPECIFICATIONS_BY_URL = buildTable();

	private ScalarSpecifications() {
	}

	/**
	 * Returns the specification a schema cites, if GATool has read it.
	 * @param specifiedByUrl the URL the scalar declares, or {@code null}
	 * @return the mapping to publish, or {@code null} for a page GATool has yet to read
	 */
	static @Nullable Specification of(@Nullable String specifiedByUrl) {
		if (specifiedByUrl == null) {
			return null;
		}
		String key = normalise(specifiedByUrl);
		Specification found = SPECIFICATIONS_BY_URL.get(key);
		// The table is written with https, and a schema citing the same page over http
		// means the same page.
		return (found != null || !key.startsWith("http://")) ? found
				: SPECIFICATIONS_BY_URL.get("https://" + key.substring("http://".length()));
	}

	/**
	 * Returns the key a URL is looked up under: the scheme and host lowercased, a
	 * trailing slash and a trailing .html dropped, and the three spellings of an RFC
	 * brought together.
	 * @param url the URL as the schema or the configuration wrote it
	 * @return the key the URL is looked up under
	 */
	// The path keeps its case, because scalars.graphql.org serves AlexandreCarlton and
	// alexandrecarlton is a 404.
	static String normalise(String url) {
		String normalised = url.strip();
		// A citation carries the section it means, and the page is the same page.
		int mark = normalised.indexOf('#');
		if (mark >= 0) {
			normalised = normalised.substring(0, mark);
		}
		mark = normalised.indexOf('?');
		if (mark >= 0) {
			normalised = normalised.substring(0, mark);
		}
		Matcher schemeAndHost = SCHEME_AND_HOST.matcher(normalised);
		if (schemeAndHost.matches()) {
			normalised = schemeAndHost.group(1).toLowerCase(Locale.ROOT) + schemeAndHost.group(2);
		}
		if (normalised.endsWith("/")) {
			normalised = normalised.substring(0, normalised.length() - 1);
		}
		if (normalised.regionMatches(true, normalised.length() - HTML.length(), HTML, 0, HTML.length())) {
			normalised = normalised.substring(0, normalised.length() - HTML.length());
		}
		Matcher rfc = RFC.matcher(normalised);
		return rfc.matches() ? "rfc:" + rfc.group(1) : normalised;
	}

	private static Map<String, Specification> buildTable() {
		Map<String, Specification> table = new LinkedHashMap<>();
		putRfcPages(table);
		putAndimarekPages(table);
		putApollographqlPages(table);
		putAlexandreCarltonPages(table);
		putChillicreamNumberPages(table);
		putChillicreamTimePages(table);
		putChillicreamTextPages(table);
		putJakobmerrildPages(table);
		return Map.copyOf(table);
	}

	// The two URLs the GraphQL specification writes in its own @specifiedBy examples.
	private static void putRfcPages(Map<String, Specification> table) {
		table.put("rfc:rfc4122",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_UUID, null));
		// RFC 3986 keeps the format off: OpenAI's strict subset omits uri from the
		// formats it reads, and a tool carrying one is refused whole. The property then
		// carries the URL, which is what tells the model which kind of string this is.
		table.put("rfc:rfc3986", new Specification(JsonSchemaKeywords.TYPE_STRING, null, null));
	}

	// The pages of andimarek in the registry.
	private static void putAndimarekPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/andimarek/date-time",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE_TIME,
						"An RFC 3339 date-time string with exactly three fractional-second digits and "
								+ "a UTC offset, where -00:00 is rejected, such as \"2011-08-30T13:22:53.108Z\"."));
		table.put("https://scalars.graphql.org/andimarek/local-date",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE,
						"A calendar date written as YYYY-MM-DD, such as \"2023-04-01\". The pattern "
								+ "covers the date alone, so an offset or zone is rejected."));
	}

	// The pages of apollographql in the registry.
	private static void putApollographqlPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/apollographql/instant-v0.1",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE_TIME,
						"An RFC 3339 date-time string carrying a required UTC offset and at most nine "
								+ "fractional-second digits, such as \"1983-10-20T23:59:59.123+02:00\"."));
		table.put("https://scalars.graphql.org/apollographql/localdate-v0.1",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE,
						"An RFC 3339 full-date string of four-digit year, two-digit month and "
								+ "two-digit day, such as \"1983-10-20\"."));
		table.put("https://scalars.graphql.org/apollographql/localdatetime-v0.1",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"An RFC 3339 local date and time string, full-date then \"T\" then "
								+ "partial-time, which forbids any UTC offset, such as "
								+ "\"1983-10-20T23:59:59.123\"."));
		table.put("https://scalars.graphql.org/apollographql/localtime-v0.1",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"An RFC 3339 partial-time string of hours, minutes and seconds with up to "
								+ "nine fractional digits, which forbids any offset, such as \"23:59:59.123\"."));
		table.put("https://scalars.graphql.org/apollographql/long-v0.1",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"A bare JSON integer in the signed 64-bit range -9223372036854775808 to "
								+ "9223372036854775807, written without leading zeros, such as 42. A JSON "
								+ "reader using IEEE 754 binary64 changes a value outside -9007199254740991 to "
								+ "9007199254740991."));
		table.put("https://scalars.graphql.org/apollographql/yearmonth-v0.1", new Specification(
				JsonSchemaKeywords.TYPE_STRING, null,
				"A string holding a four-digit year, a hyphen and a two-digit month, such as " + "\"1983-10\"."));
	}

	// The pages of AlexandreCarlton in the registry.
	private static void putAlexandreCarltonPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/AlexandreCarlton/accurate-duration",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"An ISO 8601 duration string limited to days, hours, minutes and seconds, "
								+ "such as \"PT1H30M\" or \"-P1D\"; seconds take up to 9 fractional digits."));
		table.put("https://scalars.graphql.org/AlexandreCarlton/nominal-duration",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"An ISO 8601 duration string limited to calendar years, months, weeks and "
								+ "days, such as \"P1Y-2M\"; the whole value or a single component may take a "
								+ "minus."));
	}

	// The pages of chillicream in the registry that describe a number.
	private static void putChillicreamNumberPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/chillicream/byte",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"A signed 8-bit integer sent as an unquoted JSON number without a fractional "
								+ "part, from -128 to 127. Example: -42."));
		table.put("https://scalars.graphql.org/chillicream/decimal",
				new Specification(JsonSchemaKeywords.TYPE_NUMBER, null,
						"A high-precision decimal number sent as an unquoted JSON number, excluding "
								+ "NaN and Infinity. Example: 1234567890.123456789. A JSON reader using IEEE "
								+ "754 binary64 rounds it."));
		table.put("https://scalars.graphql.org/chillicream/long",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"A signed 64-bit integer sent as an unquoted JSON number, from "
								+ "-9223372036854775808 to 9223372036854775807. A JSON reader using IEEE 754 "
								+ "binary64 changes a value outside -9007199254740991 to 9007199254740991."));
		table.put("https://scalars.graphql.org/chillicream/short",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"A signed 16-bit integer sent as an unquoted JSON number without a fractional "
								+ "part, from -32768 to 32767. Example: 32767."));
		table.put("https://scalars.graphql.org/chillicream/unsigned-byte",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"An unsigned 8-bit integer sent as an unquoted JSON number without a fractional "
								+ "part, from 0 to 255. Example: 255."));
		table.put("https://scalars.graphql.org/chillicream/unsigned-int",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"An unsigned 32-bit integer sent as an unquoted JSON number without a "
								+ "fractional part, from 0 to 4294967295. Example: 4294967295."));
		table.put("https://scalars.graphql.org/chillicream/unsigned-long",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"An unsigned 64-bit integer sent as an unquoted JSON number without a "
								+ "fractional part, from 0 to 18446744073709551615. Example: "
								+ "18446744073709551615. A JSON reader using IEEE 754 binary64 changes a value "
								+ "outside -9007199254740991 to 9007199254740991."));
		table.put("https://scalars.graphql.org/chillicream/unsigned-short",
				new Specification(JsonSchemaKeywords.TYPE_INTEGER, null,
						"An unsigned 16-bit integer sent as an unquoted JSON number without a "
								+ "fractional part, from 0 to 65535. Example: 8080."));
	}

	// The pages of chillicream in the registry that describe a date, a time or a
	// duration.
	private static void putChillicreamTimePages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/chillicream/date",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE,
						"RFC 3339 full-date string, YYYY-MM-DD, carrying the UTC calendar date of the "
								+ "underlying value, e.g. \"2023-12-24\"."));
		table.put("https://scalars.graphql.org/chillicream/date-time",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE_TIME,
						"RFC 3339 date-time string carrying a required UTC offset and up to 9 "
								+ "fractional-second digits, e.g. \"2023-12-24T15:30:00.123Z\"."));
		table.put("https://scalars.graphql.org/chillicream/duration",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"ISO 8601 duration string, optionally signed and with fractional seconds, "
								+ "e.g. \"P1DT2H30M\", \"PT0.5S\" or \"-PT15M\"."));
		table.put("https://scalars.graphql.org/chillicream/local-date", new Specification(
				JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_DATE,
				"RFC 3339 full-date string, YYYY-MM-DD, carrying the calendar date alone, " + "e.g. \"2023-12-24\"."));
		table.put("https://scalars.graphql.org/chillicream/local-date-time",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"Calendar date and wall-clock time as YYYY-MM-DDTHH:mm:ss with up to 9 "
								+ "optional fractional digits, serialized without an offset, e.g. "
								+ "\"2023-12-24T15:30:00\"."));
		table.put("https://scalars.graphql.org/chillicream/local-time",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"Time of day as HH:mm:ss with up to 9 optional fractional digits, serialized "
								+ "without a time zone offset, e.g. \"09:00:00.123456789\"."));
		table.put("https://scalars.graphql.org/chillicream/time-span",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"ISO 8601 duration string, deprecated in favour of Duration, optionally "
								+ "signed and with fractional seconds, e.g. \"PT2H30M\" or \"-PT15M\"."));
	}

	// The pages of chillicream in the registry that describe a string, or any JSON value.
	private static void putChillicreamTextPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/chillicream/any",
				new Specification(null, null,
						"Any valid GraphQL value: an object, list, string, number, boolean or null, "
								+ "passed through as-is. Example: {\"name\": \"John\", \"age\": 30}."));
		table.put("https://scalars.graphql.org/chillicream/base64-string",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"A quoted Base64 string carrying bytes, RFC 4648 standard alphabet with "
								+ "padding. Example: \"SGVsbG8gV29ybGQ=\"."));
		table.put("https://scalars.graphql.org/chillicream/uri",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"A quoted RFC 3986 URI, absolute or relative, including URNs. Example: "
								+ "\"urn:isbn:0451450523\" or \"../parent/resource\"."));
		table.put("https://scalars.graphql.org/chillicream/url",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"A quoted absolute RFC 3986 URL carrying a scheme and a hierarchical part. "
								+ "Example: \"https://example.com:8080/api?key=value\"."));
		table.put("https://scalars.graphql.org/chillicream/uuid",
				new Specification(JsonSchemaKeywords.TYPE_STRING, JsonSchemaKeywords.FORMAT_UUID,
						"A quoted UUID in the 8-4-4-4-12 hexadecimal form, upper or lower case. "
								+ "Example: \"123e4567-e89b-12d3-a456-426614174000\"."));
	}

	// The pages of jakobmerrild in the registry.
	private static void putJakobmerrildPages(Map<String, Specification> table) {
		table.put("https://scalars.graphql.org/jakobmerrild/long",
				new Specification(JsonSchemaKeywords.TYPE_STRING, null,
						"A 64-bit signed integer carried as a base-10 string, from -2^63 to 2^63-1, "
								+ "written with digits and an optional leading minus. Example: "
								+ "\"9223372036854775807\"."));
	}

	// A published specification GATool has read, as the JSON the model should be told to
	// send. A null jsonType leaves the property empty, which is the accurate description
	// of a scalar that takes any JSON value.
	record Specification(@Nullable String jsonType, @Nullable String format, @Nullable String description) {
	}

}
