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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.catalina.core.ApplicationContext;
import org.apache.catalina.core.ApplicationContextFacade;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.insight.InsightUtility;
import prerna.web.conf.DBLoader;

class SessionResourceTest {

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static Object output(Response response) throws IOException {
		return new Gson().fromJson(body(response), Map.class).get("output");
	}

	private static void setField(Object target, Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static HttpSession sessionWithActiveSessions(int active, boolean isNew) throws Exception {
		StandardManager manager = mock(StandardManager.class);
		when(manager.getActiveSessions()).thenReturn(active);
		StandardContext standardContext = mock(StandardContext.class);
		when(standardContext.getManager()).thenReturn(manager);
		ApplicationContext applicationContext = mock(ApplicationContext.class);
		setField(applicationContext, ApplicationContext.class, "context", standardContext);
		ApplicationContextFacade facade = mock(ApplicationContextFacade.class);
		setField(facade, ApplicationContextFacade.class, "context", applicationContext);

		HttpSession session = mock(HttpSession.class);
		when(session.getServletContext()).thenReturn(facade);
		when(session.isNew()).thenReturn(isNew);
		return session;
	}

	private static HttpServletRequest requestWith(HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		when(request.getSession(true)).thenReturn(session);
		when(request.getSession()).thenReturn(session);
		return request;
	}

	private static Response cleanWithoutWaiting(SessionResource resource, HttpServletRequest request) {
		// cleanSession waits 10 seconds before invalidating; an interrupted thread skips the wait
		Thread.currentThread().interrupt();
		try {
			return resource.cleanSession(request);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void countsActiveSessionsWithoutTheCallersExistingSession() throws Exception {
		HttpSession session = sessionWithActiveSessions(7, false);
		Response response = new SessionResource().getActiveSessions(requestWith(session));

		assertEquals(200, response.getStatus());
		assertEquals(7.0, new Gson().fromJson(body(response), Map.class).get("activeSessions"));
		verify(session, never()).invalidate();
	}

	@Test
	void leavesOutAndDropsTheSessionTheCountItselfCreated() throws Exception {
		HttpSession session = sessionWithActiveSessions(7, true);
		Response response = new SessionResource().getActiveSessions(requestWith(session));

		assertEquals(6.0, new Gson().fromJson(body(response), Map.class).get("activeSessions"));
		verify(session).invalidate();
	}

	@Test
	void getManagerReadsTheTomcatManagerBehindTheServletContext() throws Exception {
		HttpSession session = sessionWithActiveSessions(3, false);
		StandardManager manager = SessionResource.getManager(session);
		assertEquals(3, manager.getActiveSessions());
	}

	@Test
	void cleanSessionAnswers400WithoutASession() throws Exception {
		Response response = new SessionResource().cleanSession(mock(HttpServletRequest.class));
		assertEquals(400, response.getStatus());
		assertEquals("Invalid session", output(response));
	}

	@Test
	void cleanSessionDropsTheSessionInsightsAndInvalidatesTheSession() throws Exception {
		String sessionId = UUID.randomUUID().toString();
		Insight kept = new Insight();
		InsightStore store = InsightStore.getInstance();
		store.put(kept);
		store.addToSessionHash(sessionId, kept.getInsightId());
		store.addToSessionHash(sessionId, "already-gone");

		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		HttpServletRequest request = requestWith(session);
		when(request.isRequestedSessionIdValid()).thenReturn(true);
		try (MockedStatic<InsightUtility> insightUtility = mockStatic(InsightUtility.class)) {
			Response response = cleanWithoutWaiting(new SessionResource(), request);

			assertEquals(200, response.getStatus());
			assertEquals("invalidated", output(response));
			insightUtility.verify(() -> InsightUtility.dropInsight(kept));
			insightUtility.verify(() -> InsightUtility.dropInsight(any()), times(1));
			assertTrue(store.getInsightIDsForSession(sessionId).isEmpty());
			verify(session).invalidate();
		} finally {
			store.remove(kept.getInsightId());
		}
	}

	@Test
	void cleanSessionKeepsTheSessionWhenTheClientCancelsAfterTheCleanStarted() throws Exception {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(UUID.randomUUID().toString());
		when(session.getAttribute("cancelInvalidation")).thenReturn(new Date(System.currentTimeMillis() + 60_000));
		HttpServletRequest request = requestWith(session);
		when(request.isRequestedSessionIdValid()).thenReturn(true);

		Response response = cleanWithoutWaiting(new SessionResource(), request);

		assertEquals("cancelled", output(response));
		verify(session, never()).invalidate();
	}

	@Test
	void cleanSessionIgnoresACancelFromBeforeTheCleanStarted() throws Exception {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(UUID.randomUUID().toString());
		when(session.getAttribute("cancelInvalidation")).thenReturn(new Date(System.currentTimeMillis() - 60_000));
		HttpServletRequest request = requestWith(session);
		when(request.isRequestedSessionIdValid()).thenReturn(true);

		Response response = cleanWithoutWaiting(new SessionResource(), request);

		assertEquals("invalidated", output(response));
		verify(session).invalidate();
	}

	@Test
	void cleanSessionReportsASessionAlreadyEndedElsewhereWithoutInvalidatingAgain() throws Exception {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(UUID.randomUUID().toString());
		HttpServletRequest request = requestWith(session);
		when(request.isRequestedSessionIdValid()).thenReturn(false);

		Response response = cleanWithoutWaiting(new SessionResource(), request);

		assertEquals("invalidated", output(response));
		verify(session, never()).invalidate();
	}

	@Test
	void cleanSessionKeepsTheInterruptFlagOfAnInterruptedWait() {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(UUID.randomUUID().toString());
		HttpServletRequest request = requestWith(session);
		Thread.currentThread().interrupt();
		try {
			new SessionResource().cleanSession(request);
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void cancelCleanSessionAnswers400WithoutASession() throws Exception {
		Response response = new SessionResource().cancelCleanSession(mock(HttpServletRequest.class));
		assertEquals(400, response.getStatus());
		assertEquals("Invalid session", output(response));
	}

	@Test
	void cancelCleanSessionStampsTheCancelTime() throws Exception {
		HttpSession session = mock(HttpSession.class);
		long before = System.currentTimeMillis();
		Response response = new SessionResource().cancelCleanSession(requestWith(session));

		assertEquals(200, response.getStatus());
		assertEquals("cancel", output(response));
		ArgumentCaptor<Object> stamp = ArgumentCaptor.forClass(Object.class);
		verify(session).setAttribute(eq("cancelInvalidation"), stamp.capture());
		assertTrue(((Date) stamp.getValue()).getTime() >= before);
	}

	@Test
	void invalidateSessionRedirectsToACustomLogoutUrl() throws Exception {
		HttpSession session = mock(HttpSession.class);
		HttpServletRequest request = requestWith(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::useLogoutPage).thenReturn(true);
			loader.when(DBLoader::getCustomLogoutUrl).thenReturn("https://sso.example.com/logout");
			new SessionResource().invalidateSession(request, response);
		}

		verify(response).setHeader("redirect", "https://sso.example.com/logout");
		verify(response).sendError(302, "Need to redirect to https://sso.example.com/logout");
		verify(session).invalidate();
	}

	@Test
	void invalidateSessionBuildsTheLogoutPageUrlAndLeavesOutDefaultPorts() throws Exception {
		HttpServletRequest request = requestWith(null);
		when(request.getScheme()).thenReturn("https");
		when(request.getServerName()).thenReturn("app.example.com");
		when(request.getServerPort()).thenReturn(443);
		when(request.getContextPath()).thenReturn("/Monolith");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::useLogoutPage).thenReturn(true);
			new SessionResource().invalidateSession(request, response);
		}

		verify(response).setHeader("redirect", "https://app.example.com/Monolith/logout/");
		verify(response).sendError(302, "Need to redirect to https://app.example.com/Monolith/logout/");
	}

	@Test
	void invalidateSessionKeepsANonDefaultPortInTheLogoutPageUrl() throws Exception {
		HttpServletRequest request = requestWith(null);
		when(request.getScheme()).thenReturn("http");
		when(request.getServerName()).thenReturn("localhost");
		when(request.getServerPort()).thenReturn(8080);
		when(request.getContextPath()).thenReturn("/Monolith");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::useLogoutPage).thenReturn(true);
			new SessionResource().invalidateSession(request, response);
		}

		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/logout/");
	}

	@Test
	void invalidateSessionSendsTheClientBackToItsLoginPage() throws Exception {
		HttpSession session = mock(HttpSession.class);
		HttpServletRequest request = requestWith(session);
		when(request.getHeader("referer")).thenReturn("https://app.example.com/semoss-ui/");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::useLogoutPage).thenReturn(false);
			new SessionResource().invalidateSession(request, response);
		}

		verify(response).setStatus(302);
		verify(response).setHeader("redirect", "https://app.example.com/semoss-ui/#!/login");
		verify(response).sendError(302, "Need to redirect to https://app.example.com/semoss-ui/#!/login");
		verify(session).invalidate();
	}

	@Test
	void invalidateSessionHtmlEncodesTheRefererItEchoes() throws Exception {
		HttpServletRequest request = requestWith(null);
		when(request.getHeader("referer")).thenReturn("https://evil.example.com/\"><script>x</script>");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::useLogoutPage).thenReturn(false);
			new SessionResource().invalidateSession(request, response);
		}

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(response).sendError(anyInt(), message.capture());
		assertFalse(message.getValue().contains("<script>"));
		verify(response).setHeader(eq("redirect"), anyString());
	}

	@Test
	void getInsightsAnswers400WithoutASession() throws Exception {
		Response response = new SessionResource().getInsights(mock(HttpServletRequest.class));
		assertEquals(400, response.getStatus());
		assertEquals("Invalid session", output(response));
	}

	@Test
	void getInsightsListsTheInsightsOfTheCallersSession() throws Exception {
		String sessionId = UUID.randomUUID().toString();
		InsightStore.getInstance().addToSessionHash(sessionId, "i-1");
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		try {
			Response response = new SessionResource().getInsights(requestWith(session));
			assertEquals(200, response.getStatus());
			assertEquals(List.of("i-1"), new Gson().fromJson(body(response), List.class));
		} finally {
			InsightStore.getInstance().removeFromSessionHash(sessionId, "i-1");
		}
	}
}
