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
package prerna.remoteviewer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.microsoft.playwright.Page;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.reactor.playwright.PlaywrightUtility;
import prerna.reactor.playwright.RecordingMeta;
import prerna.reactor.playwright.StepsEnvelope;
import prerna.remoteviewer.model.RemoteBrowserRecordedStep;
import prerna.remoteviewer.security.RemoteBrowserUrlSafetyValidator;
import prerna.remoteviewer.service.RemoteBrowserRecordingService;
import prerna.remoteviewer.service.RemoteBrowserSession;
import prerna.remoteviewer.service.RemoteBrowserSessionManager;
import prerna.util.Constants;

class RemoteBrowserSessionControllerTest {

	@TempDir
	Path temp;

	private final RemoteBrowserSessionController controller = new RemoteBrowserSessionController();
	private RemoteBrowserSessionManager manager;
	private MockedStatic<RemoteBrowserSessionManager> managerStatic;
	private RemoteBrowserSession browser;
	private User owner;

	@BeforeEach
	void prepare() {
		manager = mock(RemoteBrowserSessionManager.class);
		managerStatic = mockStatic(RemoteBrowserSessionManager.class);
		managerStatic.when(RemoteBrowserSessionManager::getInstance).thenReturn(manager);
		owner = user("owner");
		browser = mock(RemoteBrowserSession.class);
		when(browser.getSessionId()).thenReturn("s1");
		when(browser.getUserId()).thenReturn("owner");
		when(browser.getViewportWidth()).thenReturn(1365);
		when(browser.getViewportHeight()).thenReturn(768);
		when(manager.getSession("s1")).thenReturn(Optional.of(browser));
		when(manager.getSession("missing")).thenReturn(Optional.empty());
	}

	@AfterEach
	void release() {
		managerStatic.close();
	}

	private static User user(String id) {
		AccessToken token = new AccessToken();
		token.setId(id);
		User user = mock(User.class);
		when(user.getPrimaryLoginToken()).thenReturn(token);
		return user;
	}

	private static HttpServletRequest signedIn(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private static JSONObject json(Response response) {
		return new JSONObject((String) response.getEntity());
	}

	private static String error(Response response) {
		return json(response).getString("error");
	}

	private StepsEnvelope envelope() {
		Map<String, List<List<prerna.reactor.playwright.PlaywrightStep>>> steps = new HashMap<>();
		steps.put("tab-1", List.of());
		return new StepsEnvelope("1.0", new RecordingMeta("rec", null, null, 1L, 1L, null), steps);
	}

	@Test
	void everyEndpointRequiresASignedInUser() {
		HttpServletRequest request = signedIn(null);

		assertEquals(401, controller.createSession(request, "{}").getStatus());
		assertEquals(401, controller.getRemoteBrowserRecordedSteps(request, "s1").getStatus());
		assertEquals(401, controller.getRecordingEnvelope(request, "s1").getStatus());
		assertEquals(401, controller.saveRecording(request, "s1", "{}").getStatus());
		assertEquals(401, controller.closeSession(request, "s1").getStatus());
		verify(manager, never()).getSession(anyString());
	}

	@Test
	void createSessionRejectsABodyThatIsNotARequest() {
		assertEquals("Invalid request body", error(controller.createSession(signedIn(owner), "{not json")));
		assertEquals("Invalid request body", error(controller.createSession(signedIn(owner), "")));
	}

	@Test
	void createSessionRequiresAUrlUnlessReopeningTheExistingSession() {
		Response response = controller.createSession(signedIn(owner), "{\"url\":\"   \"}");

		assertEquals(400, response.getStatus());
		assertEquals("Field 'url' is required", error(response));
	}

	@Test
	void createSessionRejectsAnUnsafeUrlBeforeOpeningABrowser() {
		Response response = controller.createSession(signedIn(owner), "{\"url\":\"file:///etc/passwd\"}");

		assertEquals(400, response.getStatus());
		assertEquals("URL scheme must be http or https", error(response));
		verify(manager, never()).createSession(any(), anyString(), anyInt(), anyInt());
	}

	@Test
	void createSessionReturnsTheSocketPathAndViewport() {
		Page page = mock(Page.class);
		when(page.url()).thenReturn("https://example.com/");
		when(browser.getActivePage()).thenReturn(page);
		when(manager.createSession(owner, "https://example.com", 1024, 0)).thenReturn(browser);
		try (MockedStatic<RemoteBrowserUrlSafetyValidator> validator = mockStatic(RemoteBrowserUrlSafetyValidator.class)) {
			Response response = controller.createSession(signedIn(owner),
					"{\"url\":\" https://example.com \",\"viewportWidth\":1024}");

			assertEquals(200, response.getStatus());
			JSONObject body = json(response);
			assertEquals("s1", body.getString("sessionId"));
			assertEquals("/browserSocket/s1", body.getString("webSocketUrl"));
			assertEquals(1365, body.getJSONObject("viewport").getInt("width"));
			assertEquals(768, body.getJSONObject("viewport").getInt("height"));
			assertEquals("https://example.com/", body.getString("currentUrl"));
			validator.verify(() -> RemoteBrowserUrlSafetyValidator.validate("https://example.com"));
		}
	}

	@Test
	void createSessionReopensWithoutAUrlWhenPreservingTheExistingSession() {
		when(browser.getActivePage()).thenThrow(new IllegalStateException("closed"));
		when(manager.createSession(owner, "", 0, 0)).thenReturn(browser);

		Response response = controller.createSession(signedIn(owner), "{\"preserveExisting\":true}");

		assertEquals(200, response.getStatus());
		assertEquals("", json(response).getString("currentUrl"));
	}

	@Test
	void createSessionMapsTheSessionLimitTo429AndOtherFailuresTo500() {
		when(manager.createSession(owner, "", 0, 0)).thenThrow(new IllegalStateException("Too many sessions"));
		Response limited = controller.createSession(signedIn(owner), "{\"preserveExisting\":true}");

		User other = user("other");
		when(manager.createSession(other, "", 0, 0)).thenThrow(new RuntimeException("browser crashed"));
		Response failed = controller.createSession(signedIn(other), "{\"preserveExisting\":true}");

		assertEquals(429, limited.getStatus());
		assertEquals("Too many sessions", error(limited));
		assertEquals(500, failed.getStatus());
		assertEquals("Could not create browser session", error(failed));
	}

	@Test
	void sessionEndpointsAnswer404ForAnUnknownSession() {
		HttpServletRequest request = signedIn(owner);

		assertEquals(404, controller.getRemoteBrowserRecordedSteps(request, "missing").getStatus());
		assertEquals(404, controller.getRecordingEnvelope(request, "missing").getStatus());
		assertEquals(404, controller.saveRecording(request, "missing", "{}").getStatus());
	}

	@Test
	void sessionEndpointsForbidAnotherUsersSession() {
		HttpServletRequest request = signedIn(user("intruder"));

		assertEquals(403, controller.getRemoteBrowserRecordedSteps(request, "s1").getStatus());
		assertEquals(403, controller.getRecordingEnvelope(request, "s1").getStatus());
		assertEquals(403, controller.saveRecording(request, "s1", "{\"project\":\"p\",\"name\":\"n\"}").getStatus());
		assertEquals(403, controller.closeSession(request, "s1").getStatus());
		verify(manager, never()).finishSession(browser);
	}

	@Test
	void getRemoteBrowserRecordedStepsReturnsTheOwnersSteps() {
		when(browser.getRemoteBrowserRecordedSteps()).thenReturn(List.of(new RemoteBrowserRecordedStep()));

		Response response = controller.getRemoteBrowserRecordedSteps(signedIn(owner), "s1");

		assertEquals(200, response.getStatus());
		assertEquals(1, new JSONArray((String) response.getEntity()).length());
	}

	@Test
	void getRecordingEnvelopeReturnsTheBufferAndHidesSerializationFailures() {
		when(browser.getRecordingHistory()).thenReturn(envelope());
		Response ok = controller.getRecordingEnvelope(signedIn(owner), "s1");

		when(browser.getRecordingHistory()).thenThrow(new IllegalStateException("closed"));
		Response failed = controller.getRecordingEnvelope(signedIn(owner), "s1");

		assertEquals(200, ok.getStatus());
		assertEquals("rec", json(ok).getJSONObject("meta").getString("id"));
		assertEquals(500, failed.getStatus());
		assertEquals("Could not load recording", error(failed));
	}

	@Test
	void saveRecordingValidatesTheBody() {
		HttpServletRequest request = signedIn(owner);

		assertEquals("Invalid request body", error(controller.saveRecording(request, "s1", "[")));
		assertEquals("Field 'project' is required", error(controller.saveRecording(request, "s1", "")));
		assertEquals("Field 'project' is required",
				error(controller.saveRecording(request, "s1", "{\"project\":\" \",\"name\":\"n\"}")));
		assertEquals("Field 'name' is required", error(controller.saveRecording(request, "s1", "{\"project\":\"p\"}")));
	}

	@Test
	void saveRecordingWritesASanitizedFileAndClearsTheBuffer() throws Exception {
		when(browser.getRecordingHistory()).thenReturn(envelope());
		when(browser.isRecordingEnabled()).thenReturn(true);
		try (MockedStatic<PlaywrightUtility> playwright = mockStatic(PlaywrightUtility.class, inv -> {
			if ("initRecordingsDir".equals(inv.getMethod().getName())) {
				return temp;
			}
			return inv.callRealMethod();
		}); MockedStatic<RemoteBrowserRecordingService> recording = mockStatic(RemoteBrowserRecordingService.class)) {
			Response response = controller.saveRecording(signedIn(owner), "s1",
					"{\"project\":\"p1\",\"name\":\"../login flow\",\"title\":\"Login\",\"intent\":\"sign in\"}");

			assertEquals(200, response.getStatus());
			JSONObject body = json(response);
			assertEquals(".._login_flow.json", body.getString("fileName"));
			assertEquals("p1", body.getString("project"));
			assertTrue(body.getBoolean("saved"));
			Path saved = temp.resolve(".._login_flow.json");
			StepsEnvelope written = PlaywrightUtility.readStepsEnvelope(saved.toFile());
			assertEquals("s1", written.meta().id());
			assertEquals("Login", written.meta().title());
			assertEquals("sign in", written.meta().intent());
			verify(browser).clearRecordingBuffer();
			recording.verify(() -> RemoteBrowserRecordingService.recordCurrentNavigation(browser));
			playwright.verify(() -> PlaywrightUtility.initRecordingsDir("p1"));
		}
	}

	@Test
	void saveRecordingKeepsTheIdAndCreationTimeOfAnExistingRecording() throws Exception {
		when(browser.getRecordingHistory()).thenReturn(envelope());
		Path existing = temp.resolve("flow.json");
		PlaywrightUtility.writeStepsEnvelope(existing.toFile(),
				new StepsEnvelope("1.0", new RecordingMeta("original", "Old", null, 42L, 42L, null), new HashMap<>()));
		try (MockedStatic<PlaywrightUtility> playwright = mockStatic(PlaywrightUtility.class, inv -> {
			if ("initRecordingsDir".equals(inv.getMethod().getName())) {
				return temp;
			}
			return inv.callRealMethod();
		})) {
			Response response = controller.saveRecording(signedIn(owner), "s1",
					"{\"project\":\"p1\",\"name\":\"flow.json\",\"title\":\"New\"}");

			assertEquals(200, response.getStatus());
			RecordingMeta meta = PlaywrightUtility.readStepsEnvelope(existing.toFile()).meta();
			assertEquals("original", meta.id());
			assertEquals(42L, meta.createdAt());
			assertEquals("New", meta.title());
			assertTrue(meta.updatedAt() > 42L);
		}
	}

	@Test
	void saveRecordingReportsAWriteFailureAs500() {
		when(browser.getRecordingHistory()).thenReturn(envelope());
		try (MockedStatic<PlaywrightUtility> playwright = mockStatic(PlaywrightUtility.class, inv -> {
			if ("initRecordingsDir".equals(inv.getMethod().getName())) {
				throw new RuntimeException("Cannot create recordings directory");
			}
			return inv.callRealMethod();
		})) {
			Response response = controller.saveRecording(signedIn(owner), "s1", "{\"project\":\"p1\",\"name\":\"n\"}");

			assertEquals(500, response.getStatus());
			assertEquals("Could not save recording", error(response));
			verify(browser, never()).clearRecordingBuffer();
		}
	}

	@Test
	void closeSessionFinishesTheOwnersSession() {
		Response response = controller.closeSession(signedIn(owner), "s1");

		assertEquals(200, response.getStatus());
		assertEquals("Session closed", json(response).getString("message"));
		verify(manager).finishSession(browser);
	}

	@Test
	void closeSessionTreatsAnUnknownSessionAsAlreadyClosed() {
		Response response = controller.closeSession(signedIn(owner), "missing");

		assertEquals(200, response.getStatus());
		assertEquals("Session not found or already closed", json(response).getString("message"));
	}
}
