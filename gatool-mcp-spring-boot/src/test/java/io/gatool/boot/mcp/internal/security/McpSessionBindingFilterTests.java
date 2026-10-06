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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.test.util.ReflectionTestUtils;

import io.gatool.boot.internal.credentials.TokenFingerprint;
import io.gatool.boot.mcp.internal.transport.BufferedBodyRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A session belongs to the caller that opened it: another caller's request carrying its
 * id reads 404, so does an id the filter lacks a binding for, every forwarded use
 * refreshes the binding, deletion forgets it once the SDK confirms, and entries idle for
 * three times the timeout are swept.
 */
class McpSessionBindingFilterTests {

	private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

	private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);

	private final AuthenticationEntryPoint entryPoint = (request, response, exception) -> response.setStatus(401);

	private final McpSessionBindingFilter filter = new McpSessionBindingFilter(
			SecurityContextHolder.getContextHolderStrategy(), "/mcp", Duration.ofMinutes(30), 0, this.entryPoint,
			new SettableClock(this.clock));

	// The same filter with the per-caller cap on, which the property leaves off.
	private final McpSessionBindingFilter capped = new McpSessionBindingFilter(
			SecurityContextHolder.getContextHolderStrategy(), "/mcp", Duration.ofMinutes(30), 2, this.entryPoint,
			new SettableClock(this.clock));

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void doFilter_sessionOpenedByOneCallerAndUsedByAnother_shouldAnswer404() throws Exception {
		signIn("ana");
		open("session-1");

		signIn("ben");
		MockHttpServletResponse response = send(request("POST", "session-1"), (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("-32001").contains("Session not found");
	}

	@Test
	void doFilter_sessionUsedByItsOwnCaller_shouldReachTheServer() throws Exception {
		signIn("ana");
		open("session-1");
		AtomicReference<Boolean> reached = new AtomicReference<>(false);

		MockHttpServletResponse response = send(request("GET", "session-1"), (request, reply) -> reached.set(true));

		assertThat(reached.get()).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_openingResponse_shouldBindTheMomentTheHeaderIsSet() throws Exception {
		// The SDK sets the header ahead of the body, and the record has to be there
		// before a client can hold the id.
		signIn("ana");
		AtomicReference<Boolean> boundWhileWriting = new AtomicReference<>(false);

		send(request("POST", null), (request, reply) -> {
			((HttpServletResponse) reply).setHeader("mcp-session-id", "session-1");
			boundWhileWriting.set(this.filter.isBoundTo("session-1", "ana"));
		});

		assertThat(boundWhileWriting.get()).isTrue();
	}

	@Test
	void doFilter_sessionIdWithoutABinding_shouldAnswer404WithoutReachingTheServer() throws Exception {
		// Binding an unknown id to whoever presents it would hand a session the SDK
		// still serves to the next caller with its id, so the id is refused instead.
		signIn("ana");
		AtomicReference<Boolean> reached = new AtomicReference<>(false);

		MockHttpServletResponse response = send(request("POST", "unknown-session"),
				(request, reply) -> reached.set(true));

		assertThat(reached.get()).isFalse();
		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("-32001").contains("Session not found");
		assertThat(this.filter.bindingCount()).isZero();
	}

	@Test
	void doFilter_streamRequestWithAnotherCallersSessionId_shouldAnswer404() throws Exception {
		// The filter reads the id whatever the method, so a GET, which opens the
		// session's event stream, is refused the same way as a POST.
		signIn("ana");
		open("session-1");
		signIn("ben");

		MockHttpServletResponse response = send(request("GET", "session-1"), (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(404);
	}

	@Test
	void doFilter_serverAnsweringSessionNotFoundForABoundSession_shouldForgetTheBinding() throws Exception {
		// The SDK evicted the session on its own timer, so the binding goes with it.
		signIn("ana");
		open("session-1");

		send(request("POST", "session-1"), (request, reply) -> ((HttpServletResponse) reply).setStatus(404));

		assertThat(this.filter.bindingCount()).isZero();
	}

	@Test
	void doFilter_useAnsweredWithAnError_shouldStillRefreshTheBinding() throws Exception {
		// The SDK touches its session on every request that names it, a malformed
		// body included, so the binding keeps pace with the session it guards.
		signIn("ana");
		open("session-1");
		this.clock.set(NOW.plus(Duration.ofMinutes(80)));
		send(request("POST", "session-1"), (request, reply) -> ((HttpServletResponse) reply).setStatus(400));

		this.clock.set(NOW.plus(Duration.ofMinutes(100)));
		open("session-2");

		assertThat(this.filter.isBoundTo("session-1", "ana")).isTrue();
	}

	@Test
	void doFilter_deleteConfirmedByTheServer_shouldForgetTheSession() throws Exception {
		signIn("ana");
		open("session-1");

		send(request("DELETE", "session-1"), (request, reply) -> {
		});

		assertThat(this.filter.bindingCount()).isZero();
	}

	@Test
	void doFilter_deleteTheServerRefused_shouldKeepTheBinding() throws Exception {
		// With deletion disallowed the SDK answers 405 and keeps the session, so the
		// binding stays with it.
		signIn("ana");
		open("session-1");

		send(request("DELETE", "session-1"), (request, reply) -> ((HttpServletResponse) reply).setStatus(405));

		assertThat(this.filter.isBoundTo("session-1", "ana")).isTrue();
	}

	@Test
	void doFilter_sessionUnusedForThreeTimesTheIdleTimeout_shouldBeSweptWhenTheNextOpens() throws Exception {
		// The SDK evicts at twice the timeout at the latest, and the binding has to
		// outlive the session, so a session idle for twice the timeout keeps its
		// binding and one idle for three times loses it.
		signIn("ana");
		open("session-1");
		this.clock.set(NOW.plus(Duration.ofMinutes(61)));
		open("session-2");
		assertThat(this.filter.bindingCount()).isEqualTo(2);
		assertThat(this.filter.isBoundTo("session-1", "ana")).isTrue();

		this.clock.set(NOW.plus(Duration.ofMinutes(91)));
		open("session-3");

		assertThat(this.filter.bindingCount()).isEqualTo(2);
		assertThat(this.filter.isBoundTo("session-1", "ana")).isFalse();
		assertThat(this.filter.isBoundTo("session-2", "ana")).isTrue();
	}

	@Test
	void doFilter_anonymousCaller_shouldPassWithoutAnyCheck() throws Exception {
		// The metadata path is open, and a request there carrying someone's session id
		// is outside this filter's business.
		signIn("ana");
		open("session-1");
		SecurityContextHolder.getContext()
			.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
		AtomicReference<Boolean> reached = new AtomicReference<>(false);

		MockHttpServletResponse response = send(request("GET", "session-1"), (request, reply) -> reached.set(true));

		assertThat(reached.get()).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_anotherPath_shouldReachTheServer() throws Exception {
		signIn("ana");
		open("session-1");
		signIn("ben");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.well-known/oauth-protected-resource/mcp");
		request.addHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-1");

		assertThat(send(request, (r, reply) -> {
		}).getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_callerWithoutANameOrAToken_shouldAnswer401() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("", "n/a", "ROLE_USER"));

		MockHttpServletResponse response = send(request("POST", null), (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void doFilter_bearerTokenWithoutASubject_shouldBindUnderTheTokensFingerprint() throws Exception {
		Jwt jwt = Jwt.withTokenValue("the-token")
			.header("alg", "none")
			.claim("scope", "mcp:tools")
			.issuedAt(NOW)
			.expiresAt(NOW.plusSeconds(300))
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		open("session-1");

		assertThat(this.filter.isBoundTo("session-1", TokenFingerprint.of("the-token"))).isTrue();
		assertThat(send(request("POST", "session-1"), (request, reply) -> {
		}).getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_capOffAndOneCallerOpeningManySessions_shouldServeEveryOne() throws Exception {
		// The property leaves the per-caller cap off, so an application that leaves
		// it unset keeps the behaviour it had.
		signIn("ana");

		open("session-1");
		open("session-2");
		open("session-3");

		assertThat(this.filter.bindingCount()).isEqualTo(3);
	}

	@Test
	void doFilter_callerHoldingTheirWholeCap_shouldAnswer429WithTheWait() throws Exception {
		signIn("ana");
		openCapped("session-1");
		openCapped("session-2");

		MockHttpServletResponse response = new MockHttpServletResponse();
		this.capped.doFilter(request("POST", null), response, (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isNotBlank();
		assertThat(response.getContentAsString()).contains("max-count-per-caller").contains("End one with DELETE");
	}

	@Test
	void doFilter_anotherCallerWhileOneHoldsTheirWholeCap_shouldStillOpenASession() throws Exception {
		signIn("ana");
		openCapped("session-1");
		openCapped("session-2");

		signIn("ben");
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.capped.doFilter(request("POST", null), response, (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_aBindingOlderThanTheSdkEvictsBy_shouldLeaveThatCallerAPlace() throws Exception {
		// The sweep keeps a binding for three times the idle timeout, one period longer
		// than the SDK serves the session, so a live session keeps its 404 check.
		// The cap counts by the SDK's horizon instead. Counting by the sweep's would
		// refuse a caller for a session the SDK dropped an idle timeout ago.
		signIn("ana");
		openCapped("session-1");
		openCapped("session-2");
		this.clock.set(NOW.plus(Duration.ofMinutes(61)));

		MockHttpServletResponse response = new MockHttpServletResponse();
		this.capped.doFilter(request("POST", null), response, (request, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(this.capped.bindingCount()).isEqualTo(2);
	}

	@Test
	void doFilter_oneCallerOpeningTwoSessionsAtOnceAtACapOfOne_shouldServeOneAndRefuseTheOther() throws Exception {
		// The SDK sets the id inside the chain, so a count taken ahead of the chain
		// misses both openings, and both would pass a cap of one. The place has to be
		// reserved with the decision. Both requests are held inside the chain, or
		// refused ahead of it, before either may set its id.
		McpSessionBindingFilter cappedAtOne = cappedAt(1);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger arrived = new AtomicInteger();
		AtomicInteger issued = new AtomicInteger();
		FilterChain chain = (request, reply) -> {
			arrived.incrementAndGet();
			await(go);
			((HttpServletResponse) reply).setHeader(McpSessionBindingFilter.SESSION_ID_HEADER,
					"session-" + issued.incrementAndGet());
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<MockHttpServletResponse>> openings = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				openings.add(pool.submit(() -> {
					signIn("ana");
					MockHttpServletResponse response = new MockHttpServletResponse();
					cappedAtOne.doFilter(request("POST", null), response, chain);
					if (response.getStatus() != 200) {
						arrived.incrementAndGet();
					}
					return response;
				}));
			}
			waitUntil(() -> arrived.get() == 2);
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<MockHttpServletResponse> opening : openings) {
				statuses.add(opening.get(5, TimeUnit.SECONDS).getStatus());
			}

			assertThat(statuses).containsExactlyInAnyOrder(200, 429);
			assertThat(cappedAtOne.bindingCount()).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void doFilter_openingSentWhileAnotherRecordsItsId_shouldCountTheRecordedOpeningOnce() throws Exception {
		// An opening holds a place until the SDK sets its id, and a binding from then on.
		// Counted as both for the moment between the two, it would take two places, and a
		// caller holding one session at a cap of two would be refused the second. The
		// filter does not call anything between the two steps, so the test holds the
		// monitor the count runs under. The first opening then stops on its way into
		// that monitor, and the second is sent from the thread holding it, because a
		// monitor admits the thread that holds it.
		McpSessionBindingFilter cappedAtTwo = cappedAt(2);
		Object counting = Objects.requireNonNull(ReflectionTestUtils.getField(cappedAtTwo, "openingsInFlight"));
		CountDownLatch insideTheChain = new CountDownLatch(1);
		CountDownLatch go = new CountDownLatch(1);
		FutureTask<MockHttpServletResponse> first = new FutureTask<>(() -> {
			signIn("ana");
			MockHttpServletResponse response = new MockHttpServletResponse();
			cappedAtTwo.doFilter(request("POST", null), response, (request, reply) -> {
				insideTheChain.countDown();
				await(go);
				((HttpServletResponse) reply).setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-1");
			});
			return response;
		});
		Thread firstOpening = new Thread(first, "first-opening");
		firstOpening.start();
		await(insideTheChain);
		signIn("ana");
		MockHttpServletResponse second = new MockHttpServletResponse();
		synchronized (counting) {
			go.countDown();
			waitUntil(() -> firstOpening.getState() == Thread.State.BLOCKED);
			cappedAtTwo.doFilter(request("POST", null), second, (request, reply) -> ((HttpServletResponse) reply)
				.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-2"));
		}

		assertThat(second.getStatus())
			.as("the second opening of a caller whose first was recording its id, at a cap of two")
			.isEqualTo(200);
		assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		assertThat(cappedAtTwo.bindingCount()).isEqualTo(2);
		MockHttpServletResponse third = new MockHttpServletResponse();
		cappedAtTwo.doFilter(request("POST", null), third, (request, reply) -> {
		});
		assertThat(third.getStatus()).isEqualTo(429);
	}

	@Test
	void doFilter_oneCallerOpeningSessionsMomentsApart_shouldServeTheirCapInEveryRound() throws Exception {
		// The count is exact, so one caller's openings are served up to the cap in
		// every round, whichever of them arrives while another is inside the SDK or
		// recording its id. Each round takes a filter of its own, and each opening waits
		// a random time under twenty microseconds, which spreads the arrivals over
		// the steps an opening passes through. Two openings leave a place for each, and
		// five leave three to refuse.
		int rounds = 20_000;
		ExecutorService pool = Executors.newFixedThreadPool(5);
		List<String> miscounted = new ArrayList<>();
		try {
			for (int round = 0; round < rounds; round++) {
				int openingsSent = (round % 2 == 0) ? 2 : 5;
				McpSessionBindingFilter cappedAtTwo = cappedAt(2);
				AtomicInteger issued = new AtomicInteger();
				FilterChain chain = (request, reply) -> ((HttpServletResponse) reply)
					.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-" + issued.incrementAndGet());
				CyclicBarrier together = new CyclicBarrier(openingsSent);
				List<Future<Integer>> openings = new ArrayList<>();
				for (int i = 0; i < openingsSent; i++) {
					openings.add(pool.submit(() -> {
						signIn("ana");
						MockHttpServletResponse response = new MockHttpServletResponse();
						together.await(5, TimeUnit.SECONDS);
						long arrival = System.nanoTime() + ThreadLocalRandom.current().nextLong(20_000);
						while (System.nanoTime() < arrival) {
							Thread.onSpinWait();
						}
						cappedAtTwo.doFilter(request("POST", null), response, chain);
						return response.getStatus();
					}));
				}
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> opening : openings) {
					statuses.add(opening.get(5, TimeUnit.SECONDS));
				}
				long served = statuses.stream().filter((status) -> status == 200).count();
				if (served != 2 || cappedAtTwo.bindingCount() != 2) {
					miscounted.add("round " + round + " answered " + statuses + " and kept "
							+ cappedAtTwo.bindingCount() + " bindings");
				}
			}

			assertThat(miscounted)
				.as("rounds of " + rounds + " that served one caller more or fewer sessions " + "than their cap of two")
				.isEmpty();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void doFilter_openingTheSdkAnsweredWithoutAnId_shouldGiveThePlaceBack() throws Exception {
		// A place reserved for an opening the SDK refused would otherwise stay taken.
		signIn("ana");
		McpSessionBindingFilter cappedAtOne = cappedAt(1);
		MockHttpServletResponse refused = new MockHttpServletResponse();
		cappedAtOne.doFilter(request("POST", null), refused,
				(request, reply) -> ((HttpServletResponse) reply).setStatus(400));

		MockHttpServletResponse opened = new MockHttpServletResponse();
		cappedAtOne.doFilter(request("POST", null), opened, (request, reply) -> ((HttpServletResponse) reply)
			.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-1"));

		assertThat(opened.getStatus()).isEqualTo(200);
		assertThat(cappedAtOne.isBoundTo("session-1", "ana")).isTrue();
	}

	@Test
	void doFilter_openingTheSdkFailedOn_shouldGiveThePlaceBack() throws Exception {
		signIn("ana");
		McpSessionBindingFilter cappedAtOne = cappedAt(1);
		assertThatExceptionOfType(ServletException.class).isThrownBy(
				() -> cappedAtOne.doFilter(request("POST", null), new MockHttpServletResponse(), (request, reply) -> {
					throw new ServletException("the SDK failed");
				}));

		MockHttpServletResponse opened = new MockHttpServletResponse();
		cappedAtOne.doFilter(request("POST", null), opened, (request, reply) -> ((HttpServletResponse) reply)
			.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, "session-1"));

		assertThat(opened.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_callerHoldingTheirWholeCap_shouldAnswer429WithTheCallsId() throws Exception {
		// The client matches the error to the call it sent, the way the 404 lets it.
		signIn("ana");
		openCapped("session-1");
		openCapped("session-2");
		MockHttpServletRequest request = request("POST", null);
		request.setContent("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8));

		MockHttpServletResponse response = new MockHttpServletResponse();
		this.capped.doFilter(new BufferedBodyRequest(request, 1024), response, (r, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getContentAsString())
			.startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32000,")
			.contains("max-count-per-caller");
	}

	private McpSessionBindingFilter cappedAt(int maxCountPerCaller) {
		return new McpSessionBindingFilter(SecurityContextHolder.getContextHolderStrategy(), "/mcp",
				Duration.ofMinutes(30), maxCountPerCaller, this.entryPoint, new SettableClock(this.clock));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("the test left the chain waiting");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("the state the test waits for did not come in time");
			}
			Thread.sleep(5);
		}
	}

	private void openCapped(String sessionId) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.capped.doFilter(request("POST", null), response, (request, reply) -> ((HttpServletResponse) reply)
			.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, sessionId));
	}

	private void open(String sessionId) throws Exception {
		send(request("POST", null), (request, reply) -> ((HttpServletResponse) reply)
			.setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, sessionId));
	}

	@Test
	void doFilter_sessionIdWithoutABinding_shouldAnswer404WithTheCallsId() throws Exception {
		// The compliance filter hands the chain a request that replays the body, so the
		// id is readable here and the client matches the error to the call it sent, the
		// way the transport's own 404 does.
		signIn("ana");
		MockHttpServletRequest request = request("POST", "unknown-session");
		request
			.setContent("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8));

		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(new BufferedBodyRequest(request, 1024), response, (r, reply) -> {
		});

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).isEqualTo(
				"{\"jsonrpc\":\"2.0\",\"id\":42,\"error\":{\"code\":-32001,\"message\":\"Session not found.\"}}");
	}

	private MockHttpServletResponse send(MockHttpServletRequest request, FilterChain chain) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);
		return response;
	}

	private static MockHttpServletRequest request(String method, String sessionId) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/mcp");
		request.setRequestURI("/mcp");
		if (sessionId != null) {
			request.addHeader(McpSessionBindingFilter.SESSION_ID_HEADER, sessionId);
		}
		return request;
	}

	private static void signIn(String name) {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken(name, "n/a", "SCOPE_mcp:tools"));
	}

	private static final class SettableClock extends Clock {

		private final AtomicReference<Instant> now;

		SettableClock(AtomicReference<Instant> now) {
			this.now = now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now.get();
		}

	}

}
