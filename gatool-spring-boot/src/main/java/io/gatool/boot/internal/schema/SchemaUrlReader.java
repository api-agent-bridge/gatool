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

package io.gatool.boot.internal.schema;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.gatool.boot.internal.CauseChain;
import io.gatool.boot.internal.execution.BoundedResponseRequestFactory;
import io.gatool.boot.internal.execution.ResponseTooLargeException;

/**
 * Fetches the schema definition language text of the API from a URL, and keeps a copy
 * beside the application.
 *
 * <p>
 * A schema registry serves the SDL at a URL with a key in a header, which Spring's own
 * {@code UrlResource} cannot send, so this reader makes the request itself through the
 * application's {@link RestClient} settings. The key travels in the request and stays out
 * of every message this class writes, and so do the userinfo and the query of the URL,
 * because a registry key can travel in either. Every message prints the scheme, the host,
 * the port and the path alone, through {@link #printable(String)}.
 *
 * <p>
 * The reader follows redirects itself, up to five, and the client it is given leaves them
 * unfollowed. The key is sent to the configured URL and to a {@code Location} on its
 * scheme, host and port, and a redirect to another origin is followed without it: the JDK
 * copies a custom header such as {@code X-Hive-CDN-Key} to whatever host a redirect
 * names, and RFC 9110 section 15.4 asks a client to consider removing such a header.
 * Hive's CDN is the case in the field: it answers {@code 302} with a pre-signed URL on
 * its storage host, valid for a minute, which serves the SDL without the key. A redirect
 * loop and a sixth hop both stop startup naming the URL.
 *
 * <p>
 * The body is read as bytes and decoded as UTF-8, because a GraphQL document is UTF-8. A
 * registry that answers {@code text/plain} without a charset would otherwise be decoded
 * as ISO-8859-1 by the {@code String} converter of a plain {@code RestClient.builder()},
 * which is the client this reader gets when Boot's builder bean is absent. The body is
 * bounded by {@code gatool.api.schema.max-size}, which a public schema of a few megabytes
 * fits in while the response cap of a tool call stays where the deployment set it.
 *
 * <p>
 * The copy on disk is what lets the application start while the registry is down. The
 * fetched text is written under the cache directory once the caller commits it, which the
 * auto-configuration does after every operation file validated against the text, so a
 * fetched schema that breaks an operation leaves the last copy that validated in place.
 * The copy is one file whose first line names the {@code ETag} the server sent, the next
 * fetch sends {@code If-None-Match}, and a {@code 304} reuses the copy. A registry that
 * cannot be reached starts the application on the copy with a warning naming its age, and
 * so does a {@code 5xx}, which is the registry down behind a gateway. A {@code 4xx} such
 * as {@code 401} or {@code 404} is a setting to fix, so it stops startup even with a copy
 * on disk.
 *
 * @author Željko Kozina
 */
public final class SchemaUrlReader {

	// scheme://anything@ is userinfo, and a password can sit in it.
	private static final Pattern USERINFO = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s\"]*@");

	private static final Log logger = LogFactory.getLog(SchemaUrlReader.class);

	// One more than the JDK client follows itself, and enough for a registry whose
	// CDN redirects to a signed storage URL.
	static final int MAX_REDIRECTS = 5;

	// The first line of a cached copy, which says the file is GATool's and carries the
	// ETag the server sent. The line is a GraphQL comment, so the file stays readable
	// as SDL. A file without it is not GATool's, and it reads as a miss.
	private static final String CACHE_MARKER = "# GATool schema cache";

	private static final String ETAG_FIELD = "; ETag: ";

	private final RestClient client;

	private final DataSize maxSize;

	private final @Nullable Path cacheDirectory;

	/**
	 * Creates the reader.
	 * @param client the client to fetch with, built from the application's own HTTP
	 * client settings, leaving redirects unfollowed and capping the body
	 * @param maxSize the bound the client's factory applies, which the refusal names
	 * @param cacheDirectory where the copy is kept, or {@code null} to fetch at every
	 * startup and stop when the fetch fails
	 */
	public SchemaUrlReader(RestClient client, DataSize maxSize, @Nullable Path cacheDirectory) {
		this.client = client;
		this.maxSize = maxSize;
		this.cacheDirectory = cacheDirectory;
	}

	/**
	 * Returns the SDL at the URL, or the cached copy where the URL cannot be reached.
	 * @param url the URL that serves the schema
	 * @param headerName the header that carries the registry key, or {@code null} to send
	 * the request bare
	 * @param headerValue the key
	 * @return the schema definition language text
	 * @throws SchemaFetchException if the fetch fails and the cache cannot help
	 */
	public FetchedSchema read(String url, @Nullable String headerName, @Nullable String headerValue) {
		String printable = printable(url);
		CachedSchema cached = readCache(url);
		URI configured = parse(url);
		String target = url;
		URI targetUri = configured;
		Set<String> visited = new HashSet<>();
		visited.add(url);
		for (int redirects = 0;; redirects++) {
			// The key goes to the configured URL, and to a redirect target on the same
			// scheme, host and port. Another origin gets the request without it.
			boolean sendKey = headerName != null && headerValue != null
					&& (redirects == 0 || sameOrigin(configured, targetUri));
			Fetched fetched = fetch(target, printable, sendKey ? headerName : null, headerValue, cached);
			ResponseEntity<byte[]> response = fetched.response();
			if (response == null) {
				return new FetchedSchema(useCachedCopy(printable, String.valueOf(fetched.fallbackReason()), cached),
						null);
			}
			HttpStatusCode status = response.getStatusCode();
			if (status.value() == 304) {
				return reuseConfirmedCopy(printable, cached);
			}
			if (status.is3xxRedirection()) {
				URI next = nextRedirect(response, targetUri, printable, redirects, visited);
				target = next.toString();
				targetUri = next;
				continue;
			}
			String text = requireSchemaText(response, printable);
			// The copy on disk is written by the caller's commit, once every operation
			// file validated against the text, because the copy is what a startup with
			// the registry down runs on.
			String etag = response.getHeaders().getETag();
			return new FetchedSchema(text, () -> writeCache(url, text, etag));
		}
	}

	// Returns the copy on disk the server confirmed unchanged with a 304, and stops where
	// the request went out without an ETag for the server to confirm.
	private static FetchedSchema reuseConfirmedCopy(String printable, @Nullable CachedSchema cached) {
		if (cached == null) {
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered 304 Not Modified to a request without an ETag, so the copy to reuse is " + "missing.");
		}
		logger.info("GATool reuses the schema cached at " + cached.file() + ", which " + printable
				+ " confirmed unchanged.");
		touch(cached.file());
		return new FetchedSchema(cached.text(), null);
	}

	/**
	 * Returns the URL a redirect points to, and stops where the redirects exceed their
	 * limit or loop.
	 * @param response the answer that carries the redirect
	 * @param current the URL the answer came from, which a relative Location is resolved
	 * against, or {@code null} where it could not be parsed
	 * @param printable the URL as the messages print it, without its userinfo
	 * @param redirects the number of redirects followed so far
	 * @param visited the URLs requested so far, which the target joins
	 * @return the URL the Location header points to
	 */
	private static URI nextRedirect(ResponseEntity<byte[]> response, @Nullable URI current, String printable,
			int redirects, Set<String> visited) {
		HttpStatusCode status = response.getStatusCode();
		URI next = redirectTarget(response, current, status, printable);
		if (redirects + 1 > MAX_REDIRECTS) {
			// The number is written out, as the sentence reads it; the constant
			// above is the same five.
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "redirected more than five times, the last time to " + printable(next.toString()) + ".");
		}
		if (!visited.add(next.toString())) {
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered " + describe(status) + " with a Location it had already redirected to, "
					+ printable(next.toString()) + ", so the redirects loop.");
		}
		return next;
	}

	// Returns the schema text of a 2xx answer, and stops on every other status and on an
	// empty body.
	private static String requireSchemaText(ResponseEntity<byte[]> response, String printable) {
		HttpStatusCode status = response.getStatusCode();
		if (!status.is2xxSuccessful()) {
			// A 1xx carries a body that is anything but the schema.
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered " + describe(status) + ", and a schema arrives with 200.");
		}
		byte[] body = response.getBody();
		String text = (body != null) ? new String(body, StandardCharsets.UTF_8) : "";
		if (text.isBlank()) {
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered " + describe(status) + " with an empty body.");
		}
		return text;
	}

	private Fetched fetch(String target, String printable, @Nullable String headerName, @Nullable String headerValue,
			@Nullable CachedSchema cached) {
		try {
			return new Fetched(request(target, headerName, headerValue, cached), null);
		}
		catch (RestClientResponseException ex) {
			// A 5xx is the registry down, which the copy on disk is for. A 4xx is the
			// registry answering, and the answer says what to fix: the key, the URL, or
			// the target. The body stays out either way, because a registry's error page
			// can be long and can echo the request.
			if (ex.getStatusCode().is5xxServerError() && cached != null) {
				return new Fetched(null, "it answered " + describe(ex.getStatusCode()));
			}
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered " + describe(ex.getStatusCode()) + ".");
		}
		catch (ResourceAccessException ex) {
			String reason = withoutUserinfo(String.valueOf(ex.getMessage()));
			if (cached != null) {
				return new Fetched(null, reason);
			}
			String message = "The schema at " + printable + " could not be fetched: " + reason
					+ ". Nothing is cached yet, so the application has to reach it once.";
			// The cause carries Spring's own message, userinfo and all, and a startup
			// failure prints the whole chain. It travels where the URL carries the host
			// alone, because the stack of a TLS or DNS failure is worth reading.
			throw carriesUserinfo(reason, ex) ? new SchemaFetchException(message)
					: new SchemaFetchException(message, ex);
		}
		catch (IllegalArgumentException ex) {
			// The JDK refuses a header it cannot send, and Spring's URL parser refuses a
			// URL with a character it cannot carry; both name the whole value in their
			// message, so the message stays out of what startup prints: the header value
			// is a registry key, and the URL can hold a password.
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the request "
					+ "could not be built from the URL or the header. Check both for a character a request "
					+ "cannot carry, such as a line break at the end of a secret.");
		}
		catch (RuntimeException ex) {
			// The counting stream refuses past the cap while the body is read, and the
			// client may carry that refusal as a cause. A schema above the cap is a
			// setting to fix, so it stops startup even with a copy on disk. The refusal
			// itself stays out of the chain, because its sentence is written for a model
			// asking for a smaller page, and Boot's analysis prints the root cause.
			if (findResponseTooLarge(ex) == null) {
				throw ex;
			}
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server sent "
					+ "more than " + BoundedResponseRequestFactory.describeLimit(this.maxSize) + ", which is the "
					+ "limit gatool.api.schema.max-size sets, so GATool stopped reading. Set that property to a "
					+ "size the schema fits in.");
		}
	}

	// Sends the request for the schema, with the registry key where the caller passes it
	// and the ETag of the copy on disk where there is one.
	private ResponseEntity<byte[]> request(String target, @Nullable String headerName, @Nullable String headerValue,
			@Nullable CachedSchema cached) {
		return this.client.get().uri(target).headers((headers) -> {
			if (headerName != null && headerValue != null) {
				headers.set(headerName, headerValue);
			}
			if (cached != null && cached.etag() != null) {
				headers.setIfNoneMatch(cached.etag());
			}
		}).retrieve().toEntity(byte[].class);
	}

	// The Location is resolved against the URL that answered, because a registry can
	// send a relative one, and a Location that cannot be read as a URL stops startup
	// the way an answer without one does.
	private static URI redirectTarget(ResponseEntity<byte[]> response, @Nullable URI current, HttpStatusCode status,
			String printable) {
		URI location;
		try {
			location = response.getHeaders().getLocation();
		}
		catch (IllegalArgumentException ex) {
			location = null;
		}
		URI next = (location != null && current != null) ? current.resolve(location) : location;
		if (next == null || !next.isAbsolute()) {
			throw new SchemaFetchException("The schema at " + printable + " could not be fetched: the server "
					+ "answered " + describe(status) + ", and a schema arrives with 200.");
		}
		return next;
	}

	/**
	 * Tells whether the two URLs share a scheme, a host and a port, where a port left out
	 * is the scheme's own.
	 * @param configured the URL the application configured
	 * @param candidate the URL a redirect points to
	 * @return whether both URLs share a scheme, a host and a port
	 */
	static boolean sameOrigin(@Nullable URI configured, @Nullable URI candidate) {
		if (configured == null || candidate == null) {
			return false;
		}
		if (!namesASchemeAndAHost(configured) || !namesASchemeAndAHost(candidate)) {
			return false;
		}
		return configured.getScheme().equalsIgnoreCase(candidate.getScheme())
				&& configured.getHost().equalsIgnoreCase(candidate.getHost())
				&& portOf(configured) == portOf(candidate);
	}

	private static boolean namesASchemeAndAHost(URI uri) {
		return uri.getScheme() != null && uri.getHost() != null;
	}

	private static int portOf(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static @Nullable URI parse(String url) {
		try {
			return new URI(url);
		}
		catch (URISyntaxException ex) {
			// The client builds the first request from the text, and a redirect from a
			// URL that does not parse is followed without the key.
			return null;
		}
	}

	private static @Nullable ResponseTooLargeException findResponseTooLarge(Throwable failure) {
		return CauseChain.first(failure, ResponseTooLargeException.class);
	}

	/**
	 * Returns the URL as every message prints it: the scheme, the host, the port and the
	 * path, without the userinfo and the query.
	 *
	 * <p>
	 * A registry key can travel in the query of a URL as well as in a header, and a URL
	 * can carry a password in its userinfo, so the configured value is not printed as it
	 * is. The cache keeps hashing the full URL, because two URLs that differ in their
	 * query serve two schemas.
	 * @param url the URL as configured
	 * @return the printable form, or the URL cut at its query where it does not parse
	 */
	public static String printable(String url) {
		try {
			URI uri = new URI(url);
			StringBuilder text = new StringBuilder();
			if (uri.getScheme() != null) {
				text.append(uri.getScheme()).append("://");
			}
			if (uri.getHost() != null) {
				text.append(uri.getHost());
				if (uri.getPort() != -1) {
					text.append(':').append(uri.getPort());
				}
			}
			else if (uri.getRawAuthority() != null) {
				// A host the parser refuses, such as one with an underscore, leaves
				// getHost() null and the whole authority, userinfo and port included, in
				// getRawAuthority(), so the host is read from there.
				text.append(withoutAuthorityUserinfo(uri.getRawAuthority()));
			}
			if (uri.getRawPath() != null) {
				text.append(uri.getRawPath());
			}
			return text.toString();
		}
		catch (URISyntaxException ex) {
			// A value the URI parser refuses is one the client refuses too, and the
			// message that says so still keeps the query and the userinfo out.
			int query = url.indexOf('?');
			String withoutQuery = (query >= 0) ? url.substring(0, query) : url;
			int schemeEnd = withoutQuery.indexOf("://");
			if (schemeEnd < 0) {
				return withoutQuery;
			}
			int authorityStart = schemeEnd + "://".length();
			int pathStart = withoutQuery.indexOf('/', authorityStart);
			String authority = (pathStart >= 0) ? withoutQuery.substring(authorityStart, pathStart)
					: withoutQuery.substring(authorityStart);
			String path = (pathStart >= 0) ? withoutQuery.substring(pathStart) : "";
			return withoutQuery.substring(0, authorityStart) + withoutAuthorityUserinfo(authority) + path;
		}
	}

	// The userinfo ends at the last @ of the authority, because a password can hold one.
	private static String withoutAuthorityUserinfo(String authority) {
		int at = authority.lastIndexOf('@');
		return (at >= 0) ? authority.substring(at + 1) : authority;
	}

	/**
	 * Returns a reason with the userinfo of every URL in it removed.
	 *
	 * <p>
	 * A registry URL can carry a password before the host, and Spring names the URL it
	 * tried inside its own message. {@link #printable(String)} covers the half GATool
	 * writes, and this covers the half it quotes.
	 * @param reason the text a failure reported
	 * @return the same text, with any {@code user:password@} taken out
	 */
	private static String withoutUserinfo(String reason) {
		return USERINFO.matcher(reason).replaceAll("$1");
	}

	// Says whether a failure's own message still holds userinfo the scrubbed reason no
	// longer has.
	private static boolean carriesUserinfo(String scrubbedReason, Throwable failure) {
		return !scrubbedReason.equals(String.valueOf(failure.getMessage()));
	}

	private static String useCachedCopy(String printable, String reason, @Nullable CachedSchema cached) {
		if (cached == null) {
			throw new IllegalStateException("The copy on disk is used only where one was read");
		}
		logger.warn("GATool could not reach the schema at " + printable + " (" + reason + "), so it starts on the "
				+ "copy cached at " + cached.file() + ", written or last confirmed " + describeAge(cached.age())
				+ " ago.");
		return cached.text();
	}

	// A 304 confirms the copy, so its age counts from that confirmation, and the
	// warning on the day the registry is down says so.
	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
		}
		catch (IOException ex) {
			// The age then counts from the last write, which the warning also names.
		}
	}

	private @Nullable CachedSchema readCache(String url) {
		Path sdl = sdlFile(url);
		if (sdl == null || !Files.isRegularFile(sdl)) {
			return null;
		}
		try {
			String content = Files.readString(sdl, StandardCharsets.UTF_8);
			int lineEnd = content.indexOf('\n');
			if (!content.startsWith(CACHE_MARKER) || lineEnd < 0) {
				// A file that is not GATool's is a miss, and the fetch writes the copy
				// over it.
				return null;
			}
			String header = content.substring(0, lineEnd);
			String etag = header.startsWith(CACHE_MARKER + ETAG_FIELD)
					? header.substring(CACHE_MARKER.length() + ETAG_FIELD.length()).strip() : "";
			Duration age = Duration.between(Files.getLastModifiedTime(sdl).toInstant(), Instant.now());
			return new CachedSchema(sdl, content.substring(lineEnd + 1), etag.isEmpty() ? null : etag, age);
		}
		catch (IOException ex) {
			// A copy that cannot be read is a miss, and the fetch decides what happens.
			return null;
		}
	}

	private void writeCache(String url, String text, @Nullable String etag) {
		Path sdl = sdlFile(url);
		if (sdl == null) {
			return;
		}
		// The ETag and the SDL travel in one file that lands through one rename, so a
		// reader meets a whole copy or the old one, and two instances sharing a
		// directory cannot leave one instance's SDL beside the other's ETag. The
		// staging file takes a name of its own from createTempFile, because two
		// applications on one host, or two contexts in one test, would otherwise write
		// one staging file at once, and one of them would move the other's half-written
		// text into place.
		Path staging = null;
		try {
			Files.createDirectories(sdl.getParent());
			staging = Files.createTempFile(sdl.getParent(), sdl.getFileName().toString(), ".tmp");
			Files.writeString(staging, cacheContent(text, etag), StandardCharsets.UTF_8);
			Files.move(staging, sdl, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			// A cache that cannot be written costs the next startup a fetch, and this one
			// has its schema, so the application goes on.
			deleteQuietly(staging);
			logger.warn("GATool fetched the schema at " + printable(url) + " and could not cache it under "
					+ this.cacheDirectory + ": " + ex.getMessage());
		}
	}

	// An ETag is a quoted string of visible characters, so the header stays one line;
	// one that carries a line break anyway is dropped, and the next startup refetches.
	static String cacheContent(String text, @Nullable String etag) {
		if (etag == null || etag.isBlank() || etag.indexOf('\n') >= 0 || etag.indexOf('\r') >= 0) {
			return CACHE_MARKER + "\n" + text;
		}
		return CACHE_MARKER + ETAG_FIELD + etag.strip() + "\n" + text;
	}

	// A staging file left behind by a failed write would otherwise sit in the directory
	// for good, one per failed startup.
	private static void deleteQuietly(@Nullable Path staging) {
		if (staging == null) {
			return;
		}
		try {
			Files.deleteIfExists(staging);
		}
		catch (IOException ex) {
			// The warning about the write already names the directory.
		}
	}

	// One file per URL, named by a hash of it, so two applications sharing a directory
	// keep their own copies and a URL with a key in it stays out of a file name.
	private @Nullable Path sdlFile(String url) {
		if (this.cacheDirectory == null) {
			return null;
		}
		return this.cacheDirectory.resolve(hash(url) + ".graphqls");
	}

	private static String hash(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JDK ships SHA-256", ex);
		}
	}

	private static String describe(HttpStatusCode status) {
		return status.toString();
	}

	private static String describeAge(Duration age) {
		if (age.toDays() > 0) {
			return age.toDays() + ((age.toDays() == 1) ? " day" : " days");
		}
		if (age.toHours() > 0) {
			return age.toHours() + ((age.toHours() == 1) ? " hour" : " hours");
		}
		if (age.toMinutes() > 0) {
			return age.toMinutes() + ((age.toMinutes() == 1) ? " minute" : " minutes");
		}
		return "less than a minute";
	}

	/**
	 * The schema text, and the cache write that waits for the caller.
	 *
	 * <p>
	 * The write waits because the copy on disk is what a startup with the registry down
	 * runs on. The auto-configuration commits after every operation file validated
	 * against the text, so a fetched schema that breaks an operation leaves the last copy
	 * that validated in place for that day.
	 *
	 * @param text the schema definition language text
	 * @param pendingCacheWrite the write of the fetched text to the cache, or
	 * {@code null} where this read reused the copy or fetched without a cache
	 */
	public record FetchedSchema(String text, @Nullable Runnable pendingCacheWrite) {

		/**
		 * Writes the fetched text to the cache, where this read fetched one and a cache
		 * directory is set.
		 */
		public void commit() {
			if (this.pendingCacheWrite != null) {
				this.pendingCacheWrite.run();
			}
		}
	}

	/**
	 * One request's answer, or the reason the copy on disk is to be used instead.
	 *
	 * @param response the answer, or {@code null} where the registry could not be reached
	 * or is down
	 * @param fallbackReason why the copy is used, or {@code null} where the answer is
	 * there
	 */
	private record Fetched(@Nullable ResponseEntity<byte[]> response, @Nullable String fallbackReason) {
	}

	/**
	 * What is on disk for one URL.
	 *
	 * @param file the SDL file
	 * @param text its content
	 * @param etag the ETag the server sent with it, or {@code null}
	 * @param age how long ago it was written
	 */
	private record CachedSchema(Path file, String text, @Nullable String etag, Duration age) {
	}

	/**
	 * A fetch that failed while the cache could not help, which stops startup.
	 */
	public static final class SchemaFetchException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		SchemaFetchException(String message) {
			super(message);
		}

		SchemaFetchException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
