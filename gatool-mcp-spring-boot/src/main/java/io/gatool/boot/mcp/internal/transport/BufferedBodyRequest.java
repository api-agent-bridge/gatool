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

package io.gatool.boot.mcp.internal.transport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.jspecify.annotations.Nullable;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the request body once into memory, so a filter can look at it and the servlet
 * still receives it.
 *
 * <p>
 * {@link McpComplianceFilter} reads inside the JSON-RPC body to tell an
 * {@code initialize} call from the rest, and a servlet request body reads once. Spring's
 * {@code ContentCachingRequestWrapper} serves a different case: its own Javadoc says it
 * "only caches content as it is being read", so a filter reading through it hands the
 * servlet a consumed stream. This wrapper reads first and replays afterwards.
 *
 * <p>
 * The read stops at the configured cap, and {@link #isTooLarge()} says so, because a
 * filter that refuses the request needs an answer instead of an exception. Everything the
 * wrapper holds is bounded by that cap, so one request costs at most that much memory.
 *
 * <p>
 * The wrapper also rewrites the {@code Accept} header the servlet reads. MCP asks a
 * client to list {@code application/json} and {@code text/event-stream}, and the SDK's
 * transports read the header for those two literal media types: {@code MimeType.equals}
 * compares parameters, so {@code application/json;q=0.9} would miss the check, and
 * {@code *}{@code /*} or a missing header, both of which accept every answer, would be
 * refused with a 400 and an empty body. The header handed on has its parameters dropped,
 * and a wildcard of every type, or an absent header, reads as the two media types. A
 * header that leaves both types out, {@code text/plain} say, is handed on as it is, so
 * the SDK's refusal of it still applies.
 *
 * @author Željko Kozina
 */
public final class BufferedBodyRequest extends HttpServletRequestWrapper {

	private static final String ACCEPT = "Accept";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private final byte[] body;

	private final boolean tooLarge;

	private final String acceptHeader;

	/**
	 * Reads the body of one request.
	 * @param request the request as the container built it
	 * @param maxBytes the value of {@code gatool.mcp.transport.max-request-body-size}
	 * @throws IOException if the body cannot be read
	 */
	public BufferedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
		super(request);
		this.acceptHeader = normalizeAccept(Collections.list(request.getHeaders(ACCEPT)));
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		boolean overflowed = false;
		try (InputStream in = request.getInputStream()) {
			byte[] chunk = new byte[8192];
			int bytesRead;
			while ((bytesRead = in.read(chunk)) > 0) {
				if (buffer.size() + bytesRead > maxBytes) {
					overflowed = true;
					break;
				}
				buffer.write(chunk, 0, bytesRead);
			}
		}
		this.body = buffer.toByteArray();
		this.tooLarge = overflowed;
	}

	/**
	 * Says whether the body passed the cap.
	 * @return {@code true} when the body grew beyond the configured size, where the
	 * buffer holds the part read so far
	 */
	public boolean isTooLarge() {
		return this.tooLarge;
	}

	/**
	 * Returns the body as text.
	 * @return the buffered body, decoded as UTF-8
	 */
	public String bodyAsText() {
		return new String(this.body, StandardCharsets.UTF_8);
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream replay = new ByteArrayInputStream(this.body);
		return new ServletInputStream() {

			@Override
			public int read() {
				return replay.read();
			}

			@Override
			public int read(byte[] target, int offset, int length) {
				return replay.read(target, offset, length);
			}

			@Override
			public boolean isFinished() {
				return replay.available() == 0;
			}

			// The body sits in memory, so it is always ready. A container that sets a
			// read listener gets its first callback straight away.
			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				try {
					readListener.onDataAvailable();
					readListener.onAllDataRead();
				}
				catch (IOException ex) {
					readListener.onError(ex);
				}
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(this.body), StandardCharsets.UTF_8));
	}

	// The three header readers below agree on one Accept value, because Spring reads a
	// request's headers through getHeaderNames and getHeaders while other code asks
	// getHeader.
	@Override
	public @Nullable String getHeader(String name) {
		return ACCEPT.equalsIgnoreCase(name) ? this.acceptHeader : super.getHeader(name);
	}

	@Override
	public Enumeration<String> getHeaders(String name) {
		return ACCEPT.equalsIgnoreCase(name) ? Collections.enumeration(List.of(this.acceptHeader))
				: super.getHeaders(name);
	}

	@Override
	public Enumeration<String> getHeaderNames() {
		List<String> names = Collections.list(super.getHeaderNames());
		if (names.stream().noneMatch(ACCEPT::equalsIgnoreCase)) {
			names.add(ACCEPT);
		}
		return Collections.enumeration(names);
	}

	/**
	 * Returns the {@code Accept} value handed to the servlet for the values a request
	 * carried: each media type without its parameters, and both MCP media types where the
	 * request accepts every type or arrived without the header.
	 * @param values the {@code Accept} values the request carried, one per header line
	 * @return the one {@code Accept} value the servlet reads
	 */
	static String normalizeAccept(List<String> values) {
		if (values.isEmpty()) {
			return APPLICATION_JSON + ", " + TEXT_EVENT_STREAM;
		}
		List<String> mediaTypes = new ArrayList<>();
		boolean acceptsEveryType = false;
		for (String value : values) {
			for (String entry : value.split(",", -1)) {
				String mediaType = mediaTypeOf(entry);
				if ("*/*".equals(mediaType)) {
					acceptsEveryType = true;
				}
				else if (!mediaType.isEmpty()) {
					addOnce(mediaTypes, mediaType);
				}
			}
		}
		if (acceptsEveryType) {
			addOnce(mediaTypes, APPLICATION_JSON);
			addOnce(mediaTypes, TEXT_EVENT_STREAM);
		}
		return String.join(", ", mediaTypes);
	}

	// The media type of one Accept entry, without its parameters.
	private static String mediaTypeOf(String entry) {
		int parameters = entry.indexOf(';');
		return ((parameters < 0) ? entry : entry.substring(0, parameters)).trim();
	}

	private static void addOnce(List<String> mediaTypes, String mediaType) {
		if (mediaTypes.stream().noneMatch(mediaType::equalsIgnoreCase)) {
			mediaTypes.add(mediaType);
		}
	}

	/**
	 * Returns the id of the JSON-RPC message the body holds, so an error written after
	 * the transport spent its own read still answers the call that sent it.
	 * @param jsonMapper reads the body
	 * @return the id, a string or an integer that fits a long, which is what the SDK's
	 * request record accepts, or {@code null} where the body lacks a message object with
	 * a readable id
	 */
	public @Nullable Object callId(JsonMapper jsonMapper) {
		JsonNode message;
		try {
			message = jsonMapper.readTree(bodyAsText());
		}
		catch (JacksonException ex) {
			return null;
		}
		JsonNode id = message.path("id");
		if (id.isString()) {
			return id.asString();
		}
		return (id.isIntegralNumber() && id.canConvertToLong()) ? id.numberValue() : null;
	}

	/**
	 * Returns whether the body is JSON by its {@code Content-Type}.
	 * @return {@code true} where the header is absent, which the transport reads as JSON,
	 * or names one concrete JSON media type, {@code application/json} or one with the
	 * {@code +json} suffix. The answer is {@code false} for any other type, for a
	 * wildcard and for a header Spring cannot parse
	 */
	// A form or a multipart body would reach the MCP server and fail inside it with a
	// 500, after the scope check passed on the JSON this filter read out of it. A
	// wildcard, application/* or */*, is compatible with application/json in Spring's
	// reading and names a range of media types where the transport reads one. Spring's
	// router refuses it while it builds the request, ahead of every route, so the caller
	// would read Tomcat's HTML error page under a 500 where every other refusal on this
	// endpoint is a JSON-RPC error.
	public boolean isJson() {
		String contentType = getContentType();
		if (contentType == null) {
			return true;
		}
		try {
			MediaType declared = MediaType.parseMediaType(contentType);
			return declared.isConcrete() && (MediaType.APPLICATION_JSON.equalsTypeAndSubtype(declared)
					|| "json".equalsIgnoreCase(declared.getSubtypeSuffix()));
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	/**
	 * Returns whether the body is UTF-8, which is the encoding MCP's JSON has.
	 * @return {@code true} where the {@code Content-Type} header is absent, leaves the
	 * charset out, or names UTF-8; {@code false} for another charset and for a header
	 * Spring cannot parse
	 */
	// The charset is read from the header alone and not from getCharacterEncoding(),
	// because Spring Boot's encoding filter sets the latter to UTF-8 on every request
	// while Spring AI's transport decodes the body by the header. The header is parsed
	// with the parser that transport uses, so both sides read one charset out of it.
	public boolean isUtf8() {
		String contentType = getContentType();
		if (contentType == null) {
			return true;
		}
		try {
			Charset declared = MediaType.parseMediaType(contentType).getCharset();
			return declared == null || StandardCharsets.UTF_8.equals(declared);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

}
