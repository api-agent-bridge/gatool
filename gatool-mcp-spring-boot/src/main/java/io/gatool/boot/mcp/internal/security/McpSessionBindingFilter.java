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

package io.gatool.boot.mcp.internal.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.internal.credentials.TokenFingerprint;
import io.gatool.boot.mcp.internal.transport.BufferedBodyRequest;

/**
 * Binds each stateful MCP session to the caller that opened it.
 *
 * <p>
 * MCP's security best practices recommend binding a session id to the user it was issued
 * to. A caller who holds a valid token and another user's session id then cannot post
 * into that session, replay its stream or end it. The MCP Java SDK issues the id and
 * keeps the session, and it lacks a seat for the binding, so this filter keeps it. The
 * {@code Mcp-Session-Id} a response carries is recorded under the caller's key the moment
 * the SDK sets the header. A later request carrying that id from another caller reads
 * 404, the status MCP gives a session the server no longer serves, so that client starts
 * a session of its own.
 *
 * <p>
 * The filter runs on the endpoint alone, after the authorization filter of the chain the
 * configurer builds, so a request without a token or a scope reads its challenge first. A
 * session id the filter lacks a binding for reads the same 404, whatever the SDK would
 * say about it. The SDK evicts a session on its own timer and the filter sweeps its
 * bindings later than that, so an id without a binding is one this server did not issue
 * or one whose session is long gone. Binding it to whoever presents it would hand a live
 * session to the next caller with its id. The binding's last use is refreshed on every
 * request the filter forwards, because the SDK touches its session on every request and
 * the binding must outlive the session. An entry goes when the SDK confirms a deletion or
 * answers that the session is unknown. Every entry unused for three times the session
 * idle timeout is swept when a session is recorded, which is later than the SDK's latest
 * eviction at twice the timeout.
 *
 * <p>
 * The binding key is the caller's name, which is the JWT subject under Spring's
 * converter, and the token's own fingerprint where a converter leaves the name blank. A
 * caller lacking both reads 401 with {@code invalid_token}.
 *
 * @author Željko Kozina
 */
public final class McpSessionBindingFilter extends OncePerRequestFilter {

	/**
	 * The header the SDK issues a session id in, from
	 * {@code io.modelcontextprotocol.spec.HttpHeaders}.
	 */
	static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	// The code MCP SDKs use for a session the server does not serve.
	private static final int SESSION_NOT_FOUND = -32001;

	// The generic server error code, for a caller holding every session their cap allows.
	private static final int TOO_MANY_SESSIONS = -32000;

	// Reads one id and writes one error, so the mapper's settings do not matter.
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

	private final SecurityContextHolderStrategy contextHolderStrategy;

	private final String endpoint;

	private final Duration idleTimeout;

	private final int maxCountPerCaller;

	private final AuthenticationEntryPoint entryPoint;

	private final Clock clock;

	private final Map<String, Binding> bindings = new ConcurrentHashMap<>();

	// Openings in flight per caller, read and written under its own monitor. The SDK
	// issues the id inside the chain, so an opening admitted under the cap holds a
	// place here until the id is recorded, or until the chain returns without one.
	// Without it, two openings sent together by one caller would both pass a count
	// taken before either was recorded. An entry goes at zero, so the map holds the
	// callers opening a session right now. The binding of an opening is written
	// under this monitor as well, in the step that gives its place back.
	private final Map<String, Integer> openingsInFlight = new HashMap<>();

	/**
	 * Creates the filter.
	 * @param contextHolderStrategy where the caller's authentication is read
	 * @param endpoint the MCP endpoint path, the one path the filter reads
	 * @param idleTimeout the session idle timeout the SDK evicts by
	 * @param maxCountPerCaller the value of
	 * {@code gatool.mcp.sessions.max-count-per-caller}, or zero to leave the per-caller
	 * cap off
	 * @param entryPoint the challenge a caller without a key reads
	 */
	public McpSessionBindingFilter(SecurityContextHolderStrategy contextHolderStrategy, String endpoint,
			Duration idleTimeout, int maxCountPerCaller, AuthenticationEntryPoint entryPoint) {
		this(contextHolderStrategy, endpoint, idleTimeout, maxCountPerCaller, entryPoint, Clock.systemUTC());
	}

	McpSessionBindingFilter(SecurityContextHolderStrategy contextHolderStrategy, String endpoint, Duration idleTimeout,
			int maxCountPerCaller, AuthenticationEntryPoint entryPoint, Clock clock) {
		this.contextHolderStrategy = contextHolderStrategy;
		this.endpoint = endpoint;
		this.idleTimeout = idleTimeout;
		this.maxCountPerCaller = maxCountPerCaller;
		this.entryPoint = entryPoint;
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = this.contextHolderStrategy.getContext().getAuthentication();
		// The null test is repeated here, because NullAway reads a helper's answer as
		// a boolean and the caller key below needs the authentication itself.
		if (!this.endpoint.equals(PATH_HELPER.getPathWithinApplication(request)) || authentication == null
				|| !McpCallers.isSignedIn(authentication)) {
			// Another path, or a caller the chain ahead has already answered.
			chain.doFilter(request, response);
			return;
		}
		String callerKey = callerKeyOf(authentication);
		if (callerKey == null) {
			this.entryPoint.commence(request, response, new OAuth2AuthenticationException(BearerTokenErrors
				.invalidToken("The token lacks a subject, and a stateful MCP session is bound to its caller.")));
			return;
		}
		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId == null || sessionId.isBlank()) {
			// A request without a session id is the one that opens a session.
			openSession(request, response, chain, callerKey);
			return;
		}
		Binding binding = this.bindings.get(sessionId);
		if (binding == null) {
			logger.info("GATool answered 404 to a request whose Mcp-Session-Id is unknown to it.");
			writeNotFound(request, response);
			return;
		}
		if (!binding.callerKey().equals(callerKey)) {
			logger.info("GATool answered 404 to a request whose Mcp-Session-Id belongs to another caller.");
			writeNotFound(request, response);
			return;
		}
		// Refreshed ahead of the call, whatever the SDK answers, because the SDK
		// touches its session on every request that names it.
		this.bindings.put(sessionId, new Binding(callerKey, this.clock.instant()));
		chain.doFilter(request, response);
		int status = response.getStatus();
		// The SDK keeps the session behind a 405, a 500 or a 503, so the binding goes
		// only where the session went: a confirmed deletion, or a session the SDK
		// itself no longer serves.
		boolean deleted = "DELETE".equalsIgnoreCase(request.getMethod()) && status >= 200 && status < 300;
		if (deleted || status == HttpStatus.NOT_FOUND.value()) {
			this.bindings.remove(sessionId);
		}
	}

	// The per-caller cap is decided here, before the SDK issues an id, and the place
	// is taken with the decision.
	private void openSession(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			String callerKey) throws ServletException, IOException {
		Duration wait = reserveAPlace(callerKey);
		if (wait != null) {
			logger.info("GATool answered 429 to a caller holding every session "
					+ "gatool.mcp.sessions.max-count-per-caller allows them.");
			writeTooManySessions(request, response, wait);
			return;
		}
		// The id is recorded the moment the SDK sets the header, ahead of the body,
		// so a client cannot hold an id this filter has yet to record. The place
		// becomes a binding at that moment, and goes back where the SDK answered
		// without an id or failed.
		SessionIdRecordingResponse recording = new SessionIdRecordingResponse(response, callerKey);
		try {
			chain.doFilter(request, recording);
		}
		finally {
			recording.givePlaceBack();
		}
	}

	// The sweep runs at three times the idle timeout. The SDK evicts on a timer of the
	// timeout's period, so a session idle for just over one timeout lives until the
	// next tick, at twice the timeout at the latest. The binding outlives it by
	// one more period, so that a session the SDK still serves keeps its binding.
	//
	// The binding is written and the place given back in one step, under the monitor
	// the cap counts under, so a count sees an opening once: as a place up to that
	// step, as a binding from it on. With the binding written ahead of the monitor,
	// the opening would count twice until its place went back, and an opening the
	// same caller sent alongside would be refused while a place was free. The thread
	// giving the place back waits for the monitor behind the openings being counted,
	// so a burst would stretch that moment across every one of them. The sweep stays
	// outside the monitor, because the cap stopped counting the bindings it removes
	// one idle timeout earlier.
	private void bindAndSweep(String sessionId, SessionIdRecordingResponse opening) {
		Instant now = this.clock.instant();
		Instant cutoff = now.minus(this.idleTimeout.multipliedBy(3));
		this.bindings.values().removeIf((binding) -> binding.lastSeen().isBefore(cutoff));
		synchronized (this.openingsInFlight) {
			this.bindings.put(sessionId, new Binding(opening.callerKey, now));
			opening.givePlaceBack();
		}
	}

	// The name is the JWT subject under Spring's converter. A converter of the
	// application's own may leave it blank, and the token's fingerprint then identifies
	// the caller, the same digest the token exchange store uses to tell its entries
	// apart.
	private static @Nullable String callerKeyOf(Authentication authentication) {
		String name = authentication.getName();
		if (name != null && !name.isBlank()) {
			return name;
		}
		if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> bearer) {
			return TokenFingerprint.of(bearer.getToken().getTokenValue());
		}
		return null;
	}

	/**
	 * Takes a place for this caller, or returns how long they wait for one.
	 *
	 * <p>
	 * Counted against the horizon the SDK evicts by, which is twice the idle timeout, and
	 * deliberately shorter than the sweep at three times it. The sweep keeps a binding
	 * one period longer so a session the SDK still serves keeps its 404 check. Counting
	 * by the sweep's horizon would refuse a caller for sessions the SDK dropped an idle
	 * timeout ago. The openings this caller has in flight count as well, so two openings
	 * sent together take two places, and the second of them is refused where one place
	 * was left.
	 * @param callerKey the caller this request authenticated as
	 * @return {@code null} where the cap is off or the caller now holds a place; the wait
	 * until their oldest session falls out of that horizon otherwise, which is zero where
	 * the places are taken by openings still in flight
	 */
	private @Nullable Duration reserveAPlace(String callerKey) {
		if (this.maxCountPerCaller <= 0) {
			return null;
		}
		Instant now = this.clock.instant();
		Instant horizon = now.minus(this.idleTimeout.multipliedBy(2));
		synchronized (this.openingsInFlight) {
			int reserved = this.openingsInFlight.getOrDefault(callerKey, 0);
			Instant oldest = null;
			int held = 0;
			for (Binding binding : this.bindings.values()) {
				if (binding.callerKey().equals(callerKey) && binding.lastSeen().isAfter(horizon)) {
					held++;
					if (oldest == null || binding.lastSeen().isBefore(oldest)) {
						oldest = binding.lastSeen();
					}
				}
			}
			if (held + reserved < this.maxCountPerCaller) {
				this.openingsInFlight.put(callerKey, reserved + 1);
				return null;
			}
			if (held < this.maxCountPerCaller || oldest == null) {
				// The places are taken by openings in flight, which end within moments.
				return Duration.ZERO;
			}
			Duration wait = Duration.between(now, oldest.plus(this.idleTimeout.multipliedBy(2)));
			return wait.isNegative() ? Duration.ZERO : wait;
		}
	}

	// The counterpart of reserveAPlace, called once per admitted opening.
	private void givePlaceBack(String callerKey) {
		if (this.maxCountPerCaller <= 0) {
			return;
		}
		synchronized (this.openingsInFlight) {
			int reserved = this.openingsInFlight.getOrDefault(callerKey, 0);
			if (reserved <= 1) {
				this.openingsInFlight.remove(callerKey);
			}
			else {
				this.openingsInFlight.put(callerKey, reserved - 1);
			}
		}
	}

	// Answers a caller that holds every session their own cap allows.
	// 429 with Retry-After, the way the rate limiter refuses a caller who is over their
	// own limit, so a client reads the same shape for both. The global cap answers 503
	// from the SDK, because that one is about the server and not about the caller.
	private static void writeTooManySessions(HttpServletRequest request, HttpServletResponse response, Duration wait)
			throws IOException {
		long seconds = Math.max(1, wait.toSeconds());
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
		writeError(request, response, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_SESSIONS,
				"This caller holds every session gatool.mcp.sessions.max-count-per-caller allows. "
						+ "End one with DELETE, or wait " + seconds + " seconds for the oldest to expire.");
	}

	private static void writeNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
		writeError(request, response, HttpStatus.NOT_FOUND, SESSION_NOT_FOUND, "Session not found.");
	}

	// The error carries the id of the call the request sent, the way the transport's own
	// 404 does, so a client matches it to that call. The compliance filter hands the
	// chain a request that replays the body, found here by unwrapping; a GET or a
	// DELETE arrives without one and answers with a null id.
	private static void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			int code, String message) throws IOException {
		BufferedBodyRequest buffered = WebUtils.getNativeRequest(request, BufferedBodyRequest.class);
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);
		Map<String, @Nullable Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("id", (buffered != null) ? buffered.callId(JSON) : null);
		body.put("error", error);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(JSON.writeValueAsString(body));
	}

	int bindingCount() {
		return this.bindings.size();
	}

	boolean isBoundTo(String sessionId, String callerKey) {
		Binding binding = this.bindings.get(sessionId);
		return binding != null && binding.callerKey().equals(callerKey);
	}

	/**
	 * Records the session id the SDK sets on the response that opens a session.
	 */
	private final class SessionIdRecordingResponse extends HttpServletResponseWrapper {

		private final String callerKey;

		private final AtomicBoolean placeHeld = new AtomicBoolean(true);

		SessionIdRecordingResponse(HttpServletResponse response, String callerKey) {
			super(response);
			this.callerKey = callerKey;
		}

		// Once: the recorded id gives the place back first, and the filter's own call
		// after the chain then finds it given back already.
		void givePlaceBack() {
			if (this.placeHeld.compareAndSet(true, false)) {
				McpSessionBindingFilter.this.givePlaceBack(this.callerKey);
			}
		}

		@Override
		public void setHeader(String name, String value) {
			super.setHeader(name, value);
			recordIfSessionIdHeader(name, value);
		}

		@Override
		public void addHeader(String name, String value) {
			super.addHeader(name, value);
			recordIfSessionIdHeader(name, value);
		}

		private void recordIfSessionIdHeader(String name, @Nullable String value) {
			if (SESSION_ID_HEADER.equalsIgnoreCase(name) && value != null && !value.isBlank()) {
				// The place becomes the binding there, and counting both would refuse
				// an opening sent alongside for a place this caller has.
				bindAndSweep(value, this);
			}
		}

	}

	private record Binding(String callerKey, Instant lastSeen) {
	}

}
