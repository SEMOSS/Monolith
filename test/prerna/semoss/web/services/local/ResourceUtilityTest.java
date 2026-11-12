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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.insight.InsightUtility;

class ResourceUtilityTest {

	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();

	private static User user(String id) {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId(id);
		user.setAccessToken(token);
		return user;
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/test");
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> json(Response response) throws IOException {
		return new Gson().fromJson(body(response), MAP_TYPE);
	}

	private static HttpServletRequest jsonRequest(String contentType, String content) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn(contentType);
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(content)));
		return request;
	}

	@Test
	void getUserRejectsARequestWithoutASession() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		assertThrows(IllegalAccessException.class, () -> ResourceUtility.getUser(request));
	}

	@Test
	void getUserRejectsASessionWithoutAUser() {
		assertThrows(IllegalAccessException.class, () -> ResourceUtility.getUser(request(null)));
	}

	@Test
	void getUserReturnsTheSessionUser() throws Exception {
		User user = user("jdoe");
		assertSame(user, ResourceUtility.getUser(request(user)));
	}

	@Test
	void respondAnswers401WithoutRunningTheCallWhenNoOneIsSignedIn() throws Exception {
		AtomicBoolean ran = new AtomicBoolean();
		Response response = ResourceUtility.respond(request(null), "do a thing", u -> {
			ran.set(true);
			return "done";
		});

		assertEquals(401, response.getStatus());
		assertEquals("User session is invalid", json(response).get(Constants.ERROR_MESSAGE));
		assertFalse(ran.get());
	}

	@Test
	void respondAnswers200WithTheCallResultForTheSignedInUser() throws Exception {
		User user = user("jdoe");
		AtomicReference<User> seen = new AtomicReference<>();
		Response response = ResourceUtility.respond(request(user), "do a thing", u -> {
			seen.set(u);
			return Map.of("value", "done");
		});

		assertEquals(200, response.getStatus());
		assertEquals("done", json(response).get("value"));
		assertSame(user, seen.get());
	}

	@Test
	void respondMapsIllegalAccessTo403WithItsMessage() throws Exception {
		Response response = ResourceUtility.respond(request(user("jdoe")), "do a thing", u -> {
			throw new IllegalAccessException("no access to engine e1");
		});

		assertEquals(403, response.getStatus());
		assertEquals("no access to engine e1", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void respondMapsIllegalArgumentTo400WithItsMessage() throws Exception {
		Response response = ResourceUtility.respond(request(user("jdoe")), "do a thing", u -> {
			throw new IllegalArgumentException("engineId is required");
		});

		assertEquals(400, response.getStatus());
		assertEquals("engineId is required", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void respondMapsAnyOtherFailureTo500WithAGenericAndATechnicalMessage() throws Exception {
		Response response = ResourceUtility.respond(request(user("jdoe")), "do a thing", u -> {
			throw new IllegalStateException("database is down");
		});

		assertEquals(500, response.getStatus());
		Map<String, Object> body = json(response);
		assertEquals("An unexpected error happened. Please reach out to an admin.", body.get(Constants.ERROR_MESSAGE));
		assertEquals("database is down", body.get(Constants.TECH_ERROR_MESSAGE));
	}

	@Test
	void respondAsAdminWithUtilitiesAnswers403WhenTheUtilitiesAreNull() throws Exception {
		AtomicBoolean ran = new AtomicBoolean();
		Response response = ResourceUtility.respondAsAdmin(request(user("jdoe")), "add a group", u -> null,
				(u, utils) -> {
					ran.set(true);
					return true;
				});

		assertEquals(403, response.getStatus());
		assertEquals("This functionality is limited to only admins", json(response).get(Constants.ERROR_MESSAGE));
		assertFalse(ran.get());
	}

	@Test
	void respondAsAdminWithUtilitiesHandsTheUtilitiesToTheCall() throws Exception {
		User user = user("admin");
		Object utils = new Object();
		AtomicReference<Object> seen = new AtomicReference<>();
		Response response = ResourceUtility.respondAsAdmin(request(user), "add a group", u -> utils, (u, adminUtils) -> {
			seen.set(adminUtils);
			return true;
		});

		assertEquals(200, response.getStatus());
		assertSame(utils, seen.get());
	}

	@Test
	void respondAsAdminWithUtilitiesStill401sBeforeLookingUpTheUtilities() {
		AtomicBoolean looked = new AtomicBoolean();
		Response response = ResourceUtility.respondAsAdmin(request(null), "add a group", u -> {
			looked.set(true);
			return new Object();
		}, (u, utils) -> true);

		assertEquals(401, response.getStatus());
		assertFalse(looked.get());
	}

	@Test
	void respondAsAdminAnswers403ForANonAdminWithoutRunningTheCall() throws Exception {
		User user = user("jdoe");
		AtomicBoolean ran = new AtomicBoolean();
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false);
			Response response = ResourceUtility.respondAsAdmin(request(user), "reset the cache", u -> {
				ran.set(true);
				return true;
			});

			assertEquals(403, response.getStatus());
			assertEquals("This functionality is limited to only admins", json(response).get(Constants.ERROR_MESSAGE));
		}
		assertFalse(ran.get());
	}

	@Test
	void respondAsAdminRunsTheCallForAnAdmin() throws Exception {
		User user = user("admin");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			Response response = ResourceUtility.respondAsAdmin(request(user), "reset the cache", u -> List.of("ok"));

			assertEquals(200, response.getStatus());
			assertEquals("[\"ok\"]", body(response));
		}
	}

	@Test
	void errorResponseCarriesTheMessageAndStatus() throws Exception {
		Response response = ResourceUtility.errorResponse("bad thing", 409);
		assertEquals(409, response.getStatus());
		assertEquals(Map.of(Constants.ERROR_MESSAGE, "bad thing"), json(response));
	}

	@Test
	void parseRequestBodyKeepsJsonTypes() throws Exception {
		HttpServletRequest request = jsonRequest("application/json",
				"{\"name\":\"a<b>\",\"count\":3,\"ratio\":1.5,\"flag\":true,\"tags\":[\"x\",\"y\"],\"nested\":{\"k\":\"v\"}}");

		Map<String, Object> params = ResourceUtility.parseRequestBody(request);

		assertEquals("a<b>", params.get("name"));
		assertEquals(3L, params.get("count"));
		assertEquals(1.5, params.get("ratio"));
		assertEquals(true, params.get("flag"));
		assertEquals(List.of("x", "y"), params.get("tags"));
		assertEquals(Map.of("k", "v"), params.get("nested"));
	}

	@Test
	void parseRequestBodyMatchesTheJsonContentTypeCaseInsensitivelyWithParameters() throws Exception {
		HttpServletRequest request = jsonRequest("Application/JSON; charset=UTF-8", "{\"a\":\"b\"}");
		assertEquals(Map.of("a", "b"), ResourceUtility.parseRequestBody(request));
	}

	@Test
	void parseRequestBodyReturnsAnEmptyMapForAnEmptyOrNullJsonBody() throws Exception {
		assertTrue(ResourceUtility.parseRequestBody(jsonRequest("application/json", "")).isEmpty());
		assertTrue(ResourceUtility.parseRequestBody(jsonRequest("application/json", "null")).isEmpty());
	}

	@Test
	void parseRequestBodyRejectsMalformedJsonWithAnIOException() throws Exception {
		HttpServletRequest request = jsonRequest("application/json", "{\"a\":");
		IOException e = assertThrows(IOException.class, () -> ResourceUtility.parseRequestBody(request));
		assertEquals("Invalid JSON syntax in request body", e.getMessage());
	}

	@Test
	void parseRequestBodyReadsFormValuesAsStringsAndListsSkippingEmptyOnes() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
		Map<String, String[]> form = new LinkedHashMap<>();
		form.put("single", new String[] { "1" });
		form.put("multi", new String[] { "a", "b" });
		form.put("empty", new String[0]);
		form.put("missing", null);
		when(request.getParameterMap()).thenReturn(form);

		Map<String, Object> params = ResourceUtility.parseRequestBody(request);

		assertEquals(Map.of("single", "1", "multi", List.of("a", "b")), params);
	}

	@Test
	void parseRequestBodyTreatsAMissingContentTypeAsAForm() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameterMap()).thenReturn(Map.of("a", new String[] { "b" }));
		assertEquals(Map.of("a", "b"), ResourceUtility.parseRequestBody(request));
		verify(request, never()).getReader();
	}

	@Test
	void runPixelAnswers401WithoutASignedInUser() throws Exception {
		Response response = ResourceUtility.runPixel(request(null), "1+1");
		assertEquals(401, response.getStatus());
		assertEquals("User session is invalid", json(response).get(Constants.ERROR_MESSAGE));
	}

	private static final class PixelMocks implements AutoCloseable {
		final MockedStatic<PixelJobManager> managerStatic = mockStatic(PixelJobManager.class);
		final MockedStatic<PixelStreamUtility> streamStatic = mockStatic(PixelStreamUtility.class);
		final MockedStatic<InsightUtility> insightUtility = mockStatic(InsightUtility.class);
		final PixelJobManager manager = mock(PixelJobManager.class);
		final PixelJobRunner jobRunner = mock(PixelJobRunner.class);
		final PixelRunner pixelRunner = mock(PixelRunner.class);
		final StreamingOutput stream = out -> {
		};
		final ArgumentCaptor<Runnable> afterWrite = ArgumentCaptor.forClass(Runnable.class);
		final ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);

		PixelMocks(List<NounMetadata> results, boolean nullStream) {
			managerStatic.when(PixelJobManager::getManager).thenReturn(manager);
			when(manager.makeJob(anyString(), insight.capture(), eq("session-1"), isNull())).thenReturn(jobRunner);
			when(jobRunner.getJobId()).thenReturn("job-1");
			when(jobRunner.getRunner()).thenReturn(pixelRunner);
			when(pixelRunner.getResults()).thenReturn(results);
			streamStatic.when(() -> PixelStreamUtility.collectPixelData(eq(pixelRunner), eq(jobRunner),
					afterWrite.capture())).thenReturn(nullStream ? null : stream);
		}

		@Override
		public void close() {
			if (!insight.getAllValues().isEmpty()) {
				Insight in = insight.getValue();
				InsightStore.getInstance().remove(in.getInsightId());
				InsightStore.getInstance().removeFromSessionHash("session-1", in.getInsightId());
			}
			insightUtility.close();
			streamStatic.close();
			managerStatic.close();
		}
	}

	private static NounMetadata result(PixelOperationType opType) {
		return new NounMetadata("value", PixelDataType.CONST_STRING, opType);
	}

	@Test
	void runPixelRunsTheExpressionInAFreshInsightForTheUserAndStreams200() throws Exception {
		User user = user("jdoe");
		try (PixelMocks mocks = new PixelMocks(List.of(result(PixelOperationType.OPERATION)), false)) {
			Response response = ResourceUtility.runPixel(request(user), "GetEngines()");

			assertEquals(200, response.getStatus());
			assertSame(mocks.stream, response.getEntity());
			assertEquals("no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0",
					response.getHeaderString("Cache-Control"));
			assertEquals("no-cache", response.getHeaderString("Pragma"));
			verify(mocks.jobRunner).addPixel("GetEngines();");
			verify(mocks.jobRunner).run();

			Insight insight = mocks.insight.getValue();
			assertSame(user, insight.getUser());
			assertSame(insight, InsightStore.getInstance().get(insight.getInsightId()));
			assertTrue(InsightStore.getInstance().getInsightIDsForSession("session-1").contains(insight.getInsightId()));
			verify(mocks.manager, never()).removeJob(anyString());
		}
	}

	@Test
	void runPixelKeepsAnExpressionThatAlreadyEndsWithASemicolon() throws Exception {
		try (PixelMocks mocks = new PixelMocks(List.of(), false)) {
			ResourceUtility.runPixel(request(user("jdoe")), "GetEngines();");
			verify(mocks.jobRunner).addPixel("GetEngines();");
		}
	}

	@Test
	void runPixelAnswers400WhenAPixelResultIsAnError() throws Exception {
		try (PixelMocks mocks = new PixelMocks(List.of(result(PixelOperationType.OPERATION),
				result(PixelOperationType.ERROR)), false)) {
			assertEquals(400, ResourceUtility.runPixel(request(user("jdoe")), "Bad()").getStatus());
		}
	}

	@Test
	void runPixelAnswers400WhenThePixelHasInvalidSyntax() throws Exception {
		try (PixelMocks mocks = new PixelMocks(List.of(result(PixelOperationType.INVALID_SYNTAX)), false)) {
			assertEquals(400, ResourceUtility.runPixel(request(user("jdoe")), "(((").getStatus());
		}
	}

	@Test
	void runPixelIgnoresNullResultsAndNullOpTypes() throws Exception {
		List<NounMetadata> results = new ArrayList<>();
		results.add(null);
		NounMetadata noOpType = mock(NounMetadata.class);
		results.add(noOpType);
		try (PixelMocks mocks = new PixelMocks(results, false)) {
			assertEquals(200, ResourceUtility.runPixel(request(user("jdoe")), "1").getStatus());
		}
		try (PixelMocks mocks = new PixelMocks(null, false)) {
			assertEquals(200, ResourceUtility.runPixel(request(user("jdoe")), "1").getStatus());
		}
	}

	@Test
	void runPixelCleansUpOnlyAfterTheStreamIsWritten() throws Exception {
		try (PixelMocks mocks = new PixelMocks(List.of(), false)) {
			ResourceUtility.runPixel(request(user("jdoe")), "1");
			verify(mocks.manager, never()).clearJob(anyString());
			Insight insight = mocks.insight.getValue();

			mocks.afterWrite.getValue().run();

			verify(mocks.jobRunner).setStatus(PixelJobStatus.COMPLETE);
			verify(mocks.manager).clearJob("job-1");
			verify(mocks.manager).removeJob("job-1");
			mocks.insightUtility.verify(() -> InsightUtility.dropInsight(insight));
		}
	}

	@Test
	void runPixelAnswers500AndCleansUpWhenNoStreamCanBeBuilt() throws Exception {
		try (PixelMocks mocks = new PixelMocks(List.of(), true)) {
			Response response = ResourceUtility.runPixel(request(user("jdoe")), "1");

			assertEquals(500, response.getStatus());
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getEntity();
			assertEquals("unknown", error.get("errorType"));
			assertEquals("Failed to build pixel response stream", error.get(Constants.ERROR_MESSAGE));
			verify(mocks.manager).clearJob("job-1");
			verify(mocks.manager).removeJob("job-1");
			Insight insight = mocks.insight.getValue();
			mocks.insightUtility.verify(() -> InsightUtility.dropInsight(insight));
		}
	}

	@Test
	void cleanupAfterPixelStillDropsTheInsightWhenClearingTheJobFails() {
		PixelJobManager manager = mock(PixelJobManager.class);
		PixelJobRunner jobRunner = mock(PixelJobRunner.class);
		doThrow(new IllegalStateException("boom")).when(manager).clearJob("job-1");
		Insight insight = mock(Insight.class);
		try (MockedStatic<InsightUtility> insightUtility = mockStatic(InsightUtility.class)) {
			ResourceUtility.cleanupAfterPixel(insight, manager, "job-1", jobRunner);
			insightUtility.verify(() -> InsightUtility.dropInsight(insight));
		}
		verify(manager, never()).removeJob(any());
	}

	@Test
	void cleanupAfterPixelToleratesNullPieces() {
		try (MockedStatic<InsightUtility> insightUtility = mockStatic(InsightUtility.class)) {
			ResourceUtility.cleanupAfterPixel(null, null, null, null);
			insightUtility.verifyNoInteractions();
		}
	}

	private static HttpServletRequest apiRequest(String pathInfo) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getPathInfo()).thenReturn(pathInfo);
		return request;
	}

	@Test
	void canAccessWithoutLoginAllowsTheLoginAndSessionEndpointsForEveryProvider() {
		assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/login")));
		assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/session/cleanSession")));
		assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/config/fetchCsrf")));
		for (AuthProvider provider : AuthProvider.values()) {
			String name = provider.toString().toLowerCase();
			assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/login/" + name)), name);
			assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/userinfo/" + name)), name);
			String key = provider.getSocialPrefix();
			assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/login/" + key)), key);
			assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/userinfo/" + key)), key);
		}
		assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/login/ms")));
	}

	@Test
	void canAccessWithoutLoginRejectsEverythingElse() {
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/engine/runPixel")));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/logout/ALL")));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/createAPIUser")));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/auth/login2/jira")));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest(null)));
	}

	@Test
	void canAccessWithoutLoginMatchesTheWholeServedPath() {
		// Tomcat hands the filters /engine/runPixel as the path info for /api/engine/runPixel;config
		HttpServletRequest pathParameter = apiRequest("/engine/runPixel");
		when(pathParameter.getRequestURL())
				.thenReturn(new StringBuffer("http://host/Monolith/api/engine/runPixel;config"));
		assertFalse(ResourceUtility.canAccessWithoutLogin(pathParameter));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/engine/config")));
		assertFalse(ResourceUtility.canAccessWithoutLogin(apiRequest("/x/auth/login")));
		assertTrue(ResourceUtility.canAccessWithoutLogin(apiRequest("/config/")));
	}

	@Test
	void canAccessWithoutUsersAllowsOnlyTheConfigEndpoints() {
		assertTrue(ResourceUtility.canAccessWithoutUsers(apiRequest("/config")));
		assertTrue(ResourceUtility.canAccessWithoutUsers(apiRequest("/config/fetchCsrf")));
		assertFalse(ResourceUtility.canAccessWithoutUsers(apiRequest("/auth/login")));
		assertFalse(ResourceUtility.canAccessWithoutUsers(apiRequest("/config/endpoints")));
		assertFalse(ResourceUtility.canAccessWithoutUsers(apiRequest("/engine/config")));
	}

	@Test
	void servletRelativePathTrimsTheSlashes() {
		assertEquals("auth/login", ResourceUtility.servletRelativePath(apiRequest("/auth/login/")));
		assertEquals("config", ResourceUtility.servletRelativePath(apiRequest("/config")));
		assertEquals("", ResourceUtility.servletRelativePath(apiRequest("/")));
		assertEquals("", ResourceUtility.servletRelativePath(apiRequest(null)));
	}
}
