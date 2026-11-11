/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;

class MCPResourceTest {

	private static final String TOOLBOX = "toolbox-1";
	private static final String URL = "https://host/Monolith/api/ext/mcp/toolbox-1/comms";

	private final List<String> cacheKeys = new ArrayList<>();
	private final List<HttpSession> sessions = new ArrayList<>();
	private final List<List<Object>> reaperArguments = new ArrayList<>();
	private User user;
	private InsightStore store;
	private Insight insight;
	private String insightId;
	private MockedStatic<InsightStore> insightStore;
	private MockedStatic<Utility> utility;
	private MockedConstruction<MCPReaper> reapers;

	@BeforeEach
	void mockTheSemossRuntime() {
		user = mock(User.class);
		insightId = "insight-" + UUID.randomUUID();
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn(insightId);
		store = mock(InsightStore.class);
		when(store.get(insightId)).thenReturn(insight);
		insightStore = mockStatic(InsightStore.class);
		insightStore.when(InsightStore::getInstance).thenReturn(store);
		utility = mockStatic(Utility.class);
		utility.when(Utility::getApplicationZoneId).thenReturn("UTC");
		reapers = mockConstruction(MCPReaper.class,
				(mock, context) -> reaperArguments.add(new ArrayList<>(context.arguments())));
	}

	@AfterEach
	void clearTheCachesAndCloseTheMocks() {
		for (HttpSession session : sessions) {
			if (session.getAttribute(MCPResource.MCP_AUTH_KEY) instanceof Set<?> keys) {
				keys.forEach(key -> MCPResource.clearInsight((String) key));
			}
		}
		for (String key : cacheKeys) {
			MCPResource.clearSessionState(key);
			MCPResource.clearInsight(key);
		}
		reapers.close();
		utility.close();
		insightStore.close();
	}

	@Test
	void runsEachSseCallOnANamedVirtualThread() throws Exception {
		Field field = MCPResource.class.getDeclaredField("SSE_EXECUTOR");
		field.setAccessible(true);
		Thread thread = ((ExecutorService) field.get(null)).submit(Thread::currentThread).get(5, TimeUnit.SECONDS);

		assertTrue(thread.isVirtual());
		assertTrue(thread.getName().startsWith("mcp-sse-"), thread.getName());
	}

	@Test
	void runsEverySseCallAtOnce() throws Exception {
		Field field = MCPResource.class.getDeclaredField("SSE_EXECUTOR");
		field.setAccessible(true);
		ExecutorService executor = (ExecutorService) field.get(null);
		int calls = 200;
		CountDownLatch started = new CountDownLatch(calls);
		CountDownLatch release = new CountDownLatch(1);
		try {
			for (int i = 0; i < calls; i++) {
				executor.submit(() -> {
					started.countDown();
					return release.await(30, TimeUnit.SECONDS);
				});
			}
			assertTrue(started.await(5, TimeUnit.SECONDS), "a call never waits for another call to finish");
		} finally {
			release.countDown();
		}
	}

	@Test
	void runsAStreamableHttpReaperForTheSessionInsight() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		HttpServletRequest request = request(session, null);

		new MCPResource().commsHttp(TOOLBOX, null, request, response);

		assertEquals(1, reapers.constructed().size());
		MCPReaper reaper = reapers.constructed().get(0);
		verify(reaper).run();
		verify(insight).setUser(user);
		verify(user).setZoneId(ZoneId.of("UTC"));
		assertEquals(insightId, session.getAttribute(Constants.INSIGHT));
	}

	@Test
	void capsTheIdleTimeoutAtThirtyMinutes() throws Exception {
		assertEquals(10L, idleTimeoutFor(600));
		assertEquals(30L, idleTimeoutFor(7200));
		assertEquals(30L, idleTimeoutFor(0));
		assertEquals(30L, idleTimeoutFor(-1));
	}

	@Test
	void passesTheRequestDetailsToTheReaper() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		HttpServletRequest request = request(session, null);
		InputStream body = request.getInputStream();

		new MCPResource().commsHttp(TOOLBOX, null, request, response);

		assertEquals(1, reaperArguments.size());
		List<Object> args = reaperArguments.get(0);
		assertSame(insight, args.get(0));
		assertEquals(session.getId(), args.get(1));
		assertSame(body, args.get(2));
		assertSame(response, args.get(3));
		assertEquals(TOOLBOX, args.get(4));
		assertEquals(URL, args.get(5));
		assertEquals(30L, args.get(7));
	}

	@Test
	void remembersEachCacheKeyOnTheSessionButNotTheHeader() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		String first = authKey();
		String second = authKey();

		new MCPResource().commsHttp(TOOLBOX, null, request(session, first), mock(HttpServletResponse.class));
		new MCPResource().commsHttp(TOOLBOX, null, request(session, second), mock(HttpServletResponse.class));

		Object remembered = session.getAttribute(MCPResource.MCP_AUTH_KEY);
		assertEquals(Set.of(MCPResource.insightCacheKey(session, first), MCPResource.insightCacheKey(session, second)),
				remembered);
		assertFalse(remembered.toString().contains(first.substring("Bearer ".length())), remembered.toString());
	}

	@Test
	void keysTheCacheOnThePersonAndADigestOfTheHeader() {
		String header = "Bearer secret-value";
		String key = MCPResource.insightCacheKey(session(1800, person("ada")), header);

		assertTrue(key.startsWith(AuthProvider.NATIVE.getLabel() + " ada "), key);
		assertFalse(key.contains("secret-value"), key);
		assertEquals(key, MCPResource.insightCacheKey(session(1800, person("ada")), header));
		assertNotEquals(key, MCPResource.insightCacheKey(session(1800, person("bob")), header));
	}

	@Test
	void neverSharesAnInsightBetweenTwoPeopleWhoSendTheSameHeader() throws Exception {
		String header = authKey();
		HttpSession first = session(1800, person("ada"));
		givenSessionInsight(first);
		HttpSession second = session(1800, person("bob"));
		Insight other = mock(Insight.class);
		String otherId = "insight-" + UUID.randomUUID();
		when(other.getInsightId()).thenReturn(otherId);
		when(store.get(otherId)).thenReturn(other);
		when(store.getInsightIDsForSession(second.getId())).thenReturn(new HashSet<>(Set.of(otherId)));

		new MCPResource().commsHttp(TOOLBOX, null, request(first, header), mock(HttpServletResponse.class));
		new MCPResource().commsHttp(TOOLBOX, null, request(second, header), mock(HttpServletResponse.class));

		assertSame(insight, reaperArguments.get(0).get(0));
		assertSame(other, reaperArguments.get(1).get(0));
	}

	@Test
	void reusesTheInsightForTheSamePersonInANewSession() throws Exception {
		// a client that keeps no cookies gets a new session on every request
		String header = authKey();
		HttpSession first = session(1800, person("ada"));
		givenSessionInsight(first);
		HttpSession second = session(1800, person("ada"));

		new MCPResource().commsHttp(TOOLBOX, null, request(first, header), mock(HttpServletResponse.class));
		new MCPResource().commsHttp(TOOLBOX, null, request(second, header), mock(HttpServletResponse.class));

		assertSame(insight, reaperArguments.get(1).get(0));
		verify(store, never()).getInsightIDsForSession(second.getId());
	}

	@Test
	void reusesTheCachedInsightForTheSameAuthorizationKey() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		String key = authKey();

		new MCPResource().commsHttp(TOOLBOX, null, request(session, key), mock(HttpServletResponse.class));
		new MCPResource().commsHttp(TOOLBOX, null, request(session, key), mock(HttpServletResponse.class));

		assertEquals(2, reapers.constructed().size());
		verify(insight, times(1)).setUser(user);
		verify(store, times(1)).getInsightIDsForSession(session.getId());
	}

	@Test
	void buildsTheInsightAgainAfterTheCacheEntryIsCleared() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		String key = authKey();

		new MCPResource().commsHttp(TOOLBOX, null, request(session, key), mock(HttpServletResponse.class));
		MCPResource.clearInsight(MCPResource.insightCacheKey(session, key));
		new MCPResource().commsHttp(TOOLBOX, null, request(session, key), mock(HttpServletResponse.class));

		verify(insight, times(2)).setUser(user);
	}

	@Test
	void keysTheCacheOnTheSessionIdWithoutAnAuthorizationHeader() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);

		new MCPResource().commsHttp(TOOLBOX, null, request(session, null), mock(HttpServletResponse.class));
		MCPResource.clearSessionState(session.getId());
		new MCPResource().commsHttp(TOOLBOX, null, request(session, null), mock(HttpServletResponse.class));

		verify(insight, times(2)).setUser(user);
		assertNull(session.getAttribute(MCPResource.MCP_AUTH_KEY));
	}

	@Test
	void usesTheInsightAlreadyNamedOnTheSession() throws Exception {
		HttpSession session = session(1800);
		session.setAttribute(Constants.INSIGHT, insightId);

		new MCPResource().commsHttp(TOOLBOX, null, request(session, null), mock(HttpServletResponse.class));

		verify(insight).setUser(user);
		verify(store, never()).getInsightIDsForSession(anyString());
		verify(user, never()).setZoneId(any());
	}

	@Test
	void doesNotRunAReaperWhenTheBodyCannotBeRead() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getInputStream()).thenThrow(new IOException("Connection reset by peer"));

		new MCPResource().commsHttp(TOOLBOX, null, request, mock(HttpServletResponse.class));

		assertTrue(reapers.constructed().isEmpty());
	}

	@Test
	void refusesAToolboxIdThatIsNotAnIdBeforeAnyStreamStarts() throws Exception {
		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		HttpServletResponse httpResponse = mock(HttpServletResponse.class);
		HttpServletRequest sseRequest = mock(HttpServletRequest.class);
		HttpServletResponse sseResponse = mock(HttpServletResponse.class);
		SseEventSink sink = mock(SseEventSink.class);

		new MCPResource().commsHttp("tool box", null, httpRequest, httpResponse);
		new MCPResource().commsSse("tool box", null, sink, mock(Sse.class), new ByteArrayInputStream("{}".getBytes()),
				sseRequest, sseResponse);

		verify(httpResponse).sendError(400, "Invalid toolbox id");
		verify(sseResponse).sendError(400, "Invalid toolbox id");
		verify(sink).close();
		verify(httpRequest, never()).getSession();
		verify(sseRequest, never()).getSession();
		verify(sseResponse, never()).flushBuffer();
		assertTrue(reapers.constructed().isEmpty());
	}

	@Test
	void opensAnSseStreamAndHandsTheReaperToAWorker() throws Exception {
		HttpSession session = session(1800);
		givenSessionInsight(session);
		HttpServletResponse response = mock(HttpServletResponse.class);

		new MCPResource().commsSse(TOOLBOX, null, mock(SseEventSink.class), mock(Sse.class),
				new ByteArrayInputStream("{}".getBytes()), request(session, authKey()), response);

		verify(response).setContentType(MediaType.SERVER_SENT_EVENTS);
		verify(response).setHeader("Cache-Control", "no-cache");
		verify(response).setHeader("X-Content-Type-Options", "nosniff");
		verify(response).setCharacterEncoding("UTF-8");
		verify(response).flushBuffer();
		assertEquals(1, reapers.constructed().size());
		verify(insight).setUser(user);
	}

	private long idleTimeoutFor(int sessionTimeoutSeconds) throws Exception {
		HttpSession session = session(sessionTimeoutSeconds);
		givenSessionInsight(session);
		new MCPResource().commsHttp(TOOLBOX, null, request(session, null), mock(HttpServletResponse.class));
		return (Long) reaperArguments.get(reaperArguments.size() - 1).get(7);
	}

	private void givenSessionInsight(HttpSession session) {
		when(store.getInsightIDsForSession(session.getId())).thenReturn(new HashSet<>(Set.of(insightId)));
	}

	private String authKey() {
		String key = "Bearer " + UUID.randomUUID();
		cacheKeys.add(key);
		return key;
	}

	private static User person(String id) {
		AccessToken login = new AccessToken();
		login.setProvider(AuthProvider.NATIVE);
		login.setId(id);
		User person = mock(User.class);
		when(person.getPrimaryLoginToken()).thenReturn(login);
		return person;
	}

	private HttpSession session(int maxInactiveSeconds) {
		return session(maxInactiveSeconds, user);
	}

	private HttpSession session(int maxInactiveSeconds, User sessionUser) {
		String id = "mcp-session-" + UUID.randomUUID();
		cacheKeys.add(id);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(Constants.SESSION_USER, sessionUser);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(id);
		when(session.getMaxInactiveInterval()).thenReturn(maxInactiveSeconds);
		when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
		doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
				.when(session).setAttribute(anyString(), any());
		sessions.add(session);
		return session;
	}

	private static HttpServletRequest request(HttpSession session, String authorization) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession()).thenReturn(session);
		when(request.getHeader("Authorization")).thenReturn(authorization);
		when(request.getRequestURL()).thenReturn(new StringBuffer(URL));
		ServletInputStream body = servletInput("{}");
		when(request.getInputStream()).thenReturn(body);
		return request;
	}

	private static ServletInputStream servletInput(String body) {
		ByteArrayInputStream in = new ByteArrayInputStream(body.getBytes());
		return new ServletInputStream() {
			@Override
			public int read() {
				return in.read();
			}

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				// not used by blocking reads
			}
		};
	}
}
