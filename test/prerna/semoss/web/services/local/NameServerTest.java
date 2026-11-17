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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.engine.api.IDatabaseEngine;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.reactor.IReactor;
import prerna.reactor.ReactorFactory;
import prerna.reactor.agent.mcp.MCPErrorCode;
import prerna.reactor.agent.mcp.MCPUtility;
import prerna.reactor.agent.run.AgentRunService;
import prerna.reactor.agent.stream.AgentRunStreamService;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.PixelUtility;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.execptions.SemossMCPException;
import prerna.util.ChromeDriverUtility;
import prerna.util.Constants;
import prerna.util.Utility;

class NameServerTest {

	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();
	private static final String NO_CACHE = "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0";

	private final String sessionId = UUID.randomUUID().toString();
	private final List<String> storedInsights = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (String id : storedInsights) {
			InsightStore.getInstance().remove(id);
			InsightStore.getInstance().removeFromSessionHash(sessionId, id);
		}
		ThreadStore.remove();
	}

	private static User user() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		user.setAccessToken(token);
		return user;
	}

	private Insight storedInsight() {
		Insight insight = new Insight();
		InsightStore.getInstance().put(insight);
		storedInsights.add(insight.getInsightId());
		return insight;
	}

	private HttpSession session(User user) {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return session;
	}

	private HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = session(user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getSession(true)).thenReturn(session);
		when(request.getSession()).thenReturn(session);
		return request;
	}

	private HttpServletRequest formRequest(User user, String... params) {
		HttpServletRequest request = request(user);
		for (int i = 0; i < params.length; i += 2) {
			when(request.getParameter(params[i])).thenReturn(params[i + 1]);
		}
		return request;
	}

	private HttpServletRequest jsonRequest(User user, String body) throws IOException {
		HttpServletRequest request = request(user);
		when(request.getContentType()).thenReturn("application/json;charset=UTF-8");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
		return request;
	}

	private static String body(Object entity) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) entity).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> json(Response response) throws IOException {
		return new Gson().fromJson(body(response.getEntity()), MAP_TYPE);
	}

	private static MultivaluedMap<String, String> form(String... pairs) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			form.add(pairs[i], pairs[i + 1]);
		}
		return form;
	}

	private final class Jobs implements AutoCloseable {
		final MockedStatic<PixelJobManager> managerStatic = mockStatic(PixelJobManager.class);
		final MockedStatic<PixelStreamUtility> streamStatic = mockStatic(PixelStreamUtility.class);
		final PixelJobManager manager = mock(PixelJobManager.class);
		final PixelJobRunner jobRunner = mock(PixelJobRunner.class);
		final PixelRunner pixelRunner = mock(PixelRunner.class);
		final StreamingOutput stream = out -> {
		};
		final ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);
		final ArgumentCaptor<String> route = ArgumentCaptor.forClass(String.class);

		Jobs() {
			managerStatic.when(PixelJobManager::getManager).thenReturn(manager);
			when(manager.makeJob(anyString(), insight.capture(), any(), route.capture())).thenReturn(jobRunner);
			when(manager.makeJob(insight.capture(), any(), route.capture())).thenReturn(jobRunner);
			when(jobRunner.getJobId()).thenReturn("job-1");
			when(jobRunner.getRunner()).thenReturn(pixelRunner);
			streamStatic.when(() -> PixelStreamUtility.collectPixelData(pixelRunner, jobRunner)).thenReturn(stream);
		}

		Insight ranIn() {
			Insight in = insight.getValue();
			storedInsights.add(in.getInsightId());
			return in;
		}

		@Override
		public void close() {
			for (Insight in : insight.getAllValues()) {
				storedInsights.add(in.getInsightId());
			}
			streamStatic.close();
			managerStatic.close();
		}
	}

	@Test
	void runPixelRejectsARequestWithoutASessionWith401() throws Exception {
		Response response = new NameServer().runPixelSync(mock(HttpServletRequest.class));
		assertEquals(401, response.getStatus());
		assertEquals("User session is invalid", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void runPixelDropsANewSessionWithoutAUser() {
		HttpServletRequest request = request(null);
		HttpSession session = request.getSession(false);
		when(session.isNew()).thenReturn(true);

		assertEquals(401, new NameServer().runPixelSync(request).getStatus());
		verify(session).invalidate();
	}

	@Test
	void runPixelRejectsAMissingOrBlankExpressionWith400() throws Exception {
		for (HttpServletRequest request : List.of(formRequest(user()), formRequest(user(), "expression", "   "),
				jsonRequest(user(), "{\"insightId\":\"new\"}"), jsonRequest(user(), "{\"expression\":null}"))) {
			Response response = new NameServer().runPixelSync(request);
			assertEquals(400, response.getStatus());
			assertEquals("EXPRESSION_NOT_FOUND", json(response).get("errorType"));
		}
	}

	@Test
	void runPixelRejectsMalformedJsonWith400() throws Exception {
		Response response = new NameServer().runPixelSync(jsonRequest(user(), "{\"expression\":"));
		assertEquals(400, response.getStatus());
		assertEquals("Invalid JSON syntax in request body", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void runPixelRejectsAnUnknownInsightWith400() throws Exception {
		try (Jobs jobs = new Jobs()) {
			Response response = new NameServer()
					.runPixelSync(formRequest(user(), "expression", "1", "insightId", "no-such-insight"));

			assertEquals(400, response.getStatus());
			assertEquals("INSIGHT_NOT_FOUND", json(response).get("errorType"));
			jobs.managerStatic.verifyNoInteractions();
		}
	}

	@Test
	void runPixelRunsAFormPixelInANewInsightForTheSession() {
		User user = user();
		HttpServletRequest request = formRequest(user, "expression", " GetEngines() ", "insightId", "new", "tz",
				"Europe/Paris");
		when(request.getHeader("referer")).thenReturn("https://app.example.com/semoss-ui/");
		try (Jobs jobs = new Jobs()) {
			Response response = new NameServer().runPixelSync(request);

			assertEquals(200, response.getStatus());
			assertSame(jobs.stream, response.getEntity());
			assertEquals(NO_CACHE, response.getHeaderString("Cache-Control"));
			assertEquals("no-cache", response.getHeaderString("Pragma"));
			verify(jobs.jobRunner).addPixel("GetEngines();");
			verify(jobs.jobRunner).run();

			Insight insight = jobs.ranIn();
			assertSame(user, insight.getUser());
			assertEquals("https://app.example.com/semoss-ui/#!/", insight.getBaseURL());
			assertTrue(InsightStore.getInstance().getInsightIDsForSession(sessionId).contains(insight.getInsightId()));
			assertEquals(ZoneId.of("Europe/Paris"), user.getZoneId());
			assertNull(jobs.route.getValue());
			verify(jobs.jobRunner).setStatus(PixelJobStatus.COMPLETE);
			verify(jobs.manager).clearJob("job-1");
			verify(jobs.manager).removeJob("job-1");
		}
	}

	@Test
	void runPixelReadsTheJsonBodyAndKeepsTheJobWhenLoggingIsKept() throws Exception {
		User user = user();
		Insight existing = storedInsight();
		try (Jobs jobs = new Jobs()) {
			Response response = new NameServer().runPixelSync(jsonRequest(user, "{\"expression\":\"Sum(1, 2)\","
					+ "\"insightId\":\"" + existing.getInsightId() + "\",\"tz\":\"Asia/Tokyo\",\"dropLogging\":false}"));

			assertEquals(200, response.getStatus());
			verify(jobs.manager).makeJob(eq(existing.getInsightId()), eq(existing), eq(sessionId), isNull());
			verify(jobs.jobRunner).addPixel("Sum(1, 2);");
			assertSame(user, existing.getUser());
			assertEquals(ZoneId.of("Asia/Tokyo"), user.getZoneId());
			verify(jobs.manager, never()).clearJob(anyString());
			verify(jobs.manager, never()).removeJob(anyString());
		}
	}

	@Test
	void runPixelFallsBackToTheApplicationTimeZoneForAnInvalidOne() {
		User user = user();
		try (Jobs jobs = new Jobs()) {
			new NameServer().runPixelSync(formRequest(user, "expression", "1", "tz", "Not/AZone"));
			assertEquals(ZoneId.of(Utility.getApplicationTimeZoneId()), user.getZoneId());
		}
	}

	@Test
	void runPixelCarriesTheSchedulerModeOfTheThread() {
		try (Jobs jobs = new Jobs()) {
			ThreadStore.setSchedulerMode(true);
			new NameServer().runPixelSync(formRequest(user(), "expression", "1"));
			assertTrue(jobs.ranIn().isSchedulerMode());
		}
	}

	@Test
	void runPixelPassesTheLoadBalancerRouteCookie() {
		HttpServletRequest request = formRequest(user(), "expression", "1");
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("other", "x"), new Cookie("ROUTE", "node-2") });
		try (Jobs jobs = new Jobs();
				MockedStatic<Utility> utility = mockStatic(Utility.class, withSettings()
						.defaultAnswer(Mockito.CALLS_REAL_METHODS));
				MockedStatic<ChromeDriverUtility> chrome = mockStatic(ChromeDriverUtility.class)) {
			utility.when(() -> Utility.getDIHelperProperty(Constants.LOAD_BALANCER_COOKIE_NAME)).thenReturn("ROUTE");
			new NameServer().runPixelSync(request);

			assertEquals("node-2", jobs.route.getValue());
			chrome.verify(() -> ChromeDriverUtility.setRouteCookieValue("node-2"));
		}
	}

	@Test
	void runPixelJobAnswers500AndStillClearsTheJobWhenTheResponseCannotBeBuilt() throws Exception {
		Insight insight = storedInsight();
		try (Jobs jobs = new Jobs()) {
			jobs.streamStatic.when(() -> PixelStreamUtility.collectPixelData(any(), any()))
					.thenThrow(new IllegalStateException("no stream"));
			Response response = NameServer.runPixelJob(user(), insight, "1;", insight.getInsightId(), sessionId, null,
					true);

			assertEquals(500, response.getStatus());
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getEntity();
			assertEquals("unknown", error.get("errorType"));
			assertEquals("no stream", error.get("message"));
			verify(jobs.jobRunner).setStatus(PixelJobStatus.COMPLETE);
			verify(jobs.manager).removeJob("job-1");
		}
	}

	@Test
	void runPythonRejectsARequestWithoutAUserWith401() {
		assertEquals(401, new NameServer().runPython(request(null)).getStatus());
	}

	@Test
	void runPythonRejectsMissingCodeWith400() throws Exception {
		for (HttpServletRequest request : List.of(formRequest(user()), formRequest(user(), "code", " "),
				jsonRequest(user(), "{}"))) {
			Response response = new NameServer().runPython(request);
			assertEquals(400, response.getStatus());
			assertEquals("EXPRESSION_NOT_FOUND", json(response).get("errorType"));
		}
	}

	@Test
	void runPythonRejectsMalformedJsonWith400() throws Exception {
		assertEquals(400, new NameServer().runPython(jsonRequest(user(), "{\"code\"")).getStatus());
	}

	@Test
	void runPythonWrapsTheCodeAsAnEscapedPyPixel() {
		try (Jobs jobs = new Jobs()) {
			new NameServer().runPython(formRequest(user(), "code", "print(\"a\\b\")\nx = '<y>'"));
			verify(jobs.jobRunner).addPixel("Py(code=\"print(\\\"a\\\\b\\\")\\nx = '<y>'\");");
		}
	}

	@Test
	void runPythonReadsTheCodeFromAJsonBodyIntoAnExistingInsight() throws Exception {
		Insight existing = storedInsight();
		try (Jobs jobs = new Jobs()) {
			new NameServer().runPython(
					jsonRequest(user(), "{\"code\":\"1+1\",\"insightId\":\"" + existing.getInsightId() + "\"}"));
			verify(jobs.manager).makeJob(eq(existing.getInsightId()), eq(existing), eq(sessionId), isNull());
			verify(jobs.jobRunner).addPixel("Py(code=\"1+1\");");
		}
	}

	@Test
	void runPythonRejectsAnUnknownInsightWith400() throws Exception {
		Response response = new NameServer()
				.runPython(formRequest(user(), "code", "1", "insightId", "no-such-insight"));
		assertEquals(400, response.getStatus());
		assertEquals("INSIGHT_NOT_FOUND", json(response).get("errorType"));
	}

	private static JSONObject mcpBody(Response response) {
		return new JSONObject((String) response.getEntity());
	}

	private static String mcpCall(String insightId) {
		JSONObject call = new JSONObject().put("jsonrpc", "2.0").put("id", "1").put("method", "tools/call")
				.put("params", new JSONObject().put("name", "GetEngines").put("arguments",
						new JSONObject().put("filter", "db")));
		if (insightId != null) {
			call.put("_meta", new JSONObject().put("insightId", insightId).put("tz", "UTC"));
		}
		return call.toString();
	}

	private static final class Mcp implements AutoCloseable {
		final MockedStatic<ReactorFactory> factory = mockStatic(ReactorFactory.class);
		final MockedStatic<MCPUtility> mcp = mockStatic(MCPUtility.class);
		final IReactor reactor = mock(IReactor.class);
		final JSONObject properties = new JSONObject().put("filter", new JSONObject().put("type", "string"));

		Mcp() {
			factory.when(() -> ReactorFactory.getReactor(any(Insight.class), eq("GetEngines"), any(), any()))
					.thenReturn(reactor);
			when(reactor.asMcpTool())
					.thenReturn(new JSONObject().put("inputSchema", new JSONObject().put("properties", properties)));
		}

		@Override
		public void close() {
			mcp.close();
			factory.close();
		}
	}

	@Test
	void runReactorMcpAnswersAJsonRpcError401WithoutAUser() {
		Response response = new NameServer().runReactorMCP(request(null));

		assertEquals(401, response.getStatus());
		JSONObject error = mcpBody(response).getJSONObject("error");
		assertEquals(MCPErrorCode.RESOURCE_ACCESS_DENIED.getCode(), error.getInt("code"));
		assertEquals(NO_CACHE, response.getHeaderString("Cache-Control"));
	}

	@Test
	void runReactorMcpAnswersAParseError400ForInvalidJson() throws Exception {
		Response response = new NameServer().runReactorMCP(jsonRequest(user(), "{not json"));

		assertEquals(400, response.getStatus());
		JSONObject body = mcpBody(response);
		assertEquals("2.0", body.getString("jsonrpc"));
		assertEquals(MCPErrorCode.PARSE_ERROR.getCode(), body.getJSONObject("error").getInt("code"));
	}

	@Test
	void runReactorMcpRejectsAnUnknownInsightWith400() throws Exception {
		Response response = new NameServer().runReactorMCP(jsonRequest(user(), mcpCall("no-such-insight")));
		assertEquals(400, response.getStatus());
		assertEquals("INSIGHT_NOT_FOUND", json(response).get("errorType"));
	}

	@Test
	void runReactorMcpRunsTheReactorAsAToolAndWrapsTheTextResult() throws Exception {
		User user = user();
		Insight existing = storedInsight();
		try (Mcp mcp = new Mcp()) {
			mcp.mcp.when(() -> MCPUtility.runPixelTool(isNull(), eq(existing), eq("GetEngines"), eq(mcp.properties),
					eq(Map.of("filter", "db")))).thenReturn("[\"db1\"]");

			Response response = new NameServer().runReactorMCP(jsonRequest(user, mcpCall(existing.getInsightId())));

			assertEquals(200, response.getStatus());
			JSONObject result = mcpBody(response).getJSONObject("result");
			assertFalse(result.getBoolean("isError"));
			JSONObject content = result.getJSONArray("content").getJSONObject(0);
			assertEquals("text", content.getString("type"));
			assertEquals("[\"db1\"]", content.getString("text"));
			assertSame(user, existing.getUser());
			assertNull(ThreadStore.getUser(), "the thread is cleared once the tool has run");
			assertEquals(ZoneId.of("UTC"), user.getZoneId());
			assertTrue(InsightStore.getInstance().getInsightIDsForSession(sessionId).contains(existing.getInsightId()));
		}
	}

	@Test
	void runReactorMcpMapsAToolErrorTo400WithItsCode() throws Exception {
		try (Mcp mcp = new Mcp()) {
			mcp.mcp.when(() -> MCPUtility.runPixelTool(any(), any(), anyString(), any(), any()))
					.thenThrow(new SemossMCPException("filter is required", MCPErrorCode.PARSE_ERROR));
			Response response = new NameServer().runReactorMCP(jsonRequest(user(), mcpCall(null)));

			assertEquals(400, response.getStatus());
			JSONObject error = mcpBody(response).getJSONObject("error");
			assertEquals(MCPErrorCode.PARSE_ERROR.getCode(), error.getInt("code"));
			assertEquals("filter is required", error.getString("message"));
			trackSessionInsights();
		}
	}

	private void trackSessionInsights() {
		Set<String> ids = InsightStore.getInstance().getInsightIDsForSession(sessionId);
		if (ids != null) {
			storedInsights.addAll(ids);
		}
	}

	@Test
	void runReactorMcpMapsAnyOtherFailureToAToolExecutionError() throws Exception {
		try (Mcp mcp = new Mcp()) {
			mcp.mcp.when(() -> MCPUtility.runPixelTool(any(), any(), anyString(), any(), any()))
					.thenThrow(new IllegalStateException());
			Response response = new NameServer().runReactorMCP(jsonRequest(user(), mcpCall(null)));

			assertEquals(400, response.getStatus());
			JSONObject error = mcpBody(response).getJSONObject("error");
			assertEquals(MCPErrorCode.TOOL_EXECUTION_FAILED.getCode(), error.getInt("code"));
			assertEquals(MCPErrorCode.TOOL_EXECUTION_FAILED.getDescription(), error.getString("message"));
			trackSessionInsights();
		}
	}

	@Test
	void getPipelineRejectsARequestWithoutAUserWith401() {
		assertEquals(401, new NameServer().getPixelPipelinePlan(request(null)).getStatus());
	}

	@Test
	void getPipelineRejectsAnUnknownInsightWith400() throws Exception {
		Response response = new NameServer()
				.getPixelPipelinePlan(formRequest(user(), "insightId", "no-such-insight"));
		assertEquals(400, response.getStatus());
		assertEquals("INSIGHT_NOT_FOUND", json(response).get("errorType"));
	}

	@Test
	void getPipelineReturnsThePipelineOfTheInsightAsJson() {
		User user = user();
		Insight insight = storedInsight();
		try (MockedStatic<PixelUtility> pixel = mockStatic(PixelUtility.class)) {
			pixel.when(() -> PixelUtility.generatePipeline(insight)).thenReturn(Map.of("steps", List.of("a")));
			Response response = new NameServer()
					.getPixelPipelinePlan(formRequest(user, "insightId", insight.getInsightId()));

			assertEquals(200, response.getStatus());
			assertEquals("{\"steps\":[\"a\"]}", response.getEntity());
			assertSame(user, insight.getUser());
			assertNull(ThreadStore.getUser(), "the thread is cleared once the pipeline is read");
		}
	}

	@Test
	void getInsightPipelineAnswers400WhenThePipelineCannotBeBuilt() throws Exception {
		Insight insight = storedInsight();
		try (MockedStatic<PixelUtility> pixel = mockStatic(PixelUtility.class)) {
			pixel.when(() -> PixelUtility.generatePipeline(insight)).thenThrow(new IllegalStateException("bad"));
			Response response = NameServer.getInsightPipeline(insight);

			assertEquals(400, response.getStatus());
			assertEquals("bad", json(response).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void runPixelAsyncRejectsARequestWithoutAUserWith401() {
		assertEquals(401, new NameServer().runPixelAsync(request(null)).getStatus());
	}

	@Test
	void runPixelAsyncRejectsAMissingExpressionWith400() throws Exception {
		Response response = new NameServer().runPixelAsync(jsonRequest(user(), "{}"));
		assertEquals(400, response.getStatus());
		assertEquals("EXPRESSION_NOT_FOUND", json(response).get("errorType"));
	}

	@Test
	void runPixelAsyncStartsTheJobAndTiesItsIdToTheSession() throws Exception {
		User user = user();
		HttpServletRequest request = formRequest(user, "expression", "Slow()");
		try (Jobs jobs = new Jobs()) {
			Response response = new NameServer().runPixelAsync(request);

			assertEquals(200, response.getStatus());
			assertEquals("job-1", json(response).get("jobId"));
			verify(request.getSession(false)).setAttribute("job-1", "TRUE");
			verify(jobs.jobRunner).addPixel("Slow();");
			verify(jobs.jobRunner, timeout(5_000)).run();
			assertSame(user, jobs.ranIn().getUser());
		}
	}

	@Test
	void resultIsNullForAJobTheSessionDidNotStart() throws Exception {
		HttpServletRequest request = request(user());
		StreamingOutput result = new NameServer().result(form("jobId", "job-1"), request);
		assertEquals("\"NULL\"", body(result));
	}

	@Test
	void resultStreamsTheOutputOnceAndForgetsTheJob() {
		HttpServletRequest request = request(user());
		HttpSession session = request.getSession(true);
		when(session.getAttribute("job-1")).thenReturn("TRUE");
		try (Jobs jobs = new Jobs()) {
			when(jobs.manager.getJob("job-1")).thenReturn(jobs.jobRunner);
			when(jobs.manager.getOutput("job-1")).thenReturn(jobs.pixelRunner);
			StreamingOutput result = new NameServer().result(form("jobId", "job-1"), request);

			assertSame(jobs.stream, result);
			verify(session).removeAttribute("job-1");
			verify(jobs.manager).clearJob("job-1");
			verify(jobs.manager).removeJob("job-1");
		}
	}

	@Test
	void statusReportsOnlyJobsTheSessionStarted() throws Exception {
		HttpServletRequest request = request(user());
		HttpSession session = request.getSession(true);
		when(session.getAttribute("mine")).thenReturn("TRUE");
		when(session.getAttribute("gone")).thenReturn("TRUE");
		try (Jobs jobs = new Jobs()) {
			when(jobs.manager.getJob("mine")).thenReturn(jobs.jobRunner);
			when(jobs.jobRunner.getStatus()).thenReturn("Streaming");

			Response mine = new NameServer().status(form("jobId", "mine"), request);
			assertEquals("\"Streaming\"", body(mine.getEntity()));
			assertEquals("\"" + PixelJobStatus.UNKNOWN_JOB.getValue() + "\"",
					body(new NameServer().status(form("jobId", "gone"), request).getEntity()));
			assertEquals("\"NULL\"", body(new NameServer().status(form("jobId", "other"), request).getEntity()));
		}
	}

	@Test
	void consoleErrorPartialAndStreamingReportTheJobStatusAndMessages() throws Exception {
		HttpServletRequest request = request(user());
		try (Jobs jobs = new Jobs()) {
			when(jobs.manager.getJob("job-1")).thenReturn(jobs.jobRunner);
			when(jobs.jobRunner.getStatus()).thenReturn("InProgress");
			when(jobs.manager.getStdOut("job-1")).thenReturn(List.of("out"));
			when(jobs.manager.getError("job-1")).thenReturn(List.of("err"));
			when(jobs.manager.getPartial("job-1")).thenReturn(Map.of("p", "1"));
			when(jobs.manager.getStreamOut("job-1")).thenReturn(List.of(Map.of("s", "1")));
			NameServer nameServer = new NameServer();

			Map<String, Object> console = json(nameServer.console(form("jobId", "job-1"), request));
			assertEquals(Map.of("status", "InProgress", "message", List.of("out")), console);
			Map<String, Object> error = json(nameServer.error(form("jobId", "job-1"), request));
			assertEquals(Map.of("status", "InProgress", "message", List.of("err")), error);
			Map<String, Object> partial = json(nameServer.partial(form("jobId", "job-1"), request));
			assertEquals(Map.of("status", "InProgress", "message", Map.of("p", "1")), partial);
			Map<String, Object> streaming = json(nameServer.pixelJobStreaming(form("jobId", "job-1"), request));
			assertEquals(Map.of("status", "InProgress", "message", List.of(Map.of("s", "1"))), streaming);

			Map<String, Object> unknown = json(nameServer.console(form("jobId", "nope"), request));
			assertEquals(PixelJobStatus.UNKNOWN_JOB.getValue(), unknown.get("status"));
		}
	}

	@Test
	void terminateClearsTheJob() throws Exception {
		try (Jobs jobs = new Jobs()) {
			StreamingOutput result = new NameServer().terminate(form("jobId", "job-1"), request(user()));
			assertEquals("\"success\"", body(result));
			verify(jobs.manager).clearJob("job-1");
		}
	}

	@Test
	void agentRunStreamingRequiresARunId() throws Exception {
		for (MultivaluedMap<String, String> form : List.of(form(), form("runId", "  "))) {
			Response response = new NameServer().agentRunStreaming(form, request(user()));
			assertEquals(400, response.getStatus());
			assertEquals("runId is required", json(response).get("errorMessage"));
		}
	}

	@Test
	void agentRunStreamingRequiresASignedInUser() {
		assertEquals(401, new NameServer().agentRunStreaming(form("runId", "run-1"), request(null)).getStatus());
		assertEquals(401, new NameServer().agentRunStreaming(form("runId", "run-1"), mock(HttpServletRequest.class))
				.getStatus());
	}

	private static final class Agents implements AutoCloseable {
		final MockedStatic<AgentRunService> runStatic = mockStatic(AgentRunService.class);
		final MockedStatic<AgentRunStreamService> streamStatic = mockStatic(AgentRunStreamService.class);
		final AgentRunService runs = mock(AgentRunService.class);
		final AgentRunStreamService streams = mock(AgentRunStreamService.class);

		Agents() {
			runStatic.when(AgentRunService::get).thenReturn(runs);
			streamStatic.when(AgentRunStreamService::get).thenReturn(streams);
		}

		@Override
		public void close() {
			streamStatic.close();
			runStatic.close();
		}
	}

	@Test
	void agentRunStreamingReturnsTheOwnersRunAndDrainedEvents() throws Exception {
		User user = user();
		try (Agents agents = new Agents()) {
			ArgumentCaptor<Insight> owner = ArgumentCaptor.forClass(Insight.class);
			when(agents.runs.getRunSnapshot(eq("run-1"), owner.capture())).thenReturn(Map.of("state", "RUNNING"));
			AgentRunStreamService.DrainResult drained = mock(AgentRunStreamService.DrainResult.class);
			when(drained.getEvents()).thenReturn(List.of(Map.of("type", "token")));
			when(drained.getDroppedEvents()).thenReturn(2L);
			when(agents.streams.drain("run-1")).thenReturn(drained);

			Response response = new NameServer().agentRunStreaming(form("runId", " run-1 "), request(user));

			assertEquals(200, response.getStatus());
			Map<String, Object> body = json(response);
			assertEquals(Map.of("state", "RUNNING"), body.get("run"));
			assertEquals(List.of(Map.of("type", "token")), body.get("events"));
			assertEquals(2.0, body.get("droppedEvents"));
			assertSame(user, owner.getValue().getUser());
		}
	}

	@Test
	void agentRunStreamingAnswers401ForADeniedRunAnd404ForAnUnknownOne() throws Exception {
		try (Agents agents = new Agents()) {
			when(agents.runs.getRunSnapshot(eq("denied"), any())).thenThrow(new SecurityException("no"));
			when(agents.runs.getRunSnapshot(eq("unknown"), any())).thenThrow(new IllegalArgumentException("no"));

			assertEquals(401, new NameServer().agentRunStreaming(form("runId", "denied"), request(user())).getStatus());
			Response unknown = new NameServer().agentRunStreaming(form("runId", "unknown"), request(user()));
			assertEquals(404, unknown.getStatus());
			assertEquals("No agent run found for runId=unknown", json(unknown).get("errorMessage"));
			verify(agents.streams, never()).drain(anyString());
		}
	}

	@Test
	void getInsightDataFrameRejectsAnUnknownInsightWith400() throws Exception {
		Object response = new NameServer().getInsightDataFrame("no-such-insight", null, request(user()));
		assertEquals(400, ((Response) response).getStatus());
		assertEquals("Existing insight based on passed insightID is not found",
				json((Response) response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void getInsightDataFrameStartsANewInsightForTheSessionUser() {
		User user = user();
		Object resource = new NameServer().getInsightDataFrame("new", null, request(user));

		DataframeResource dataframe = assertInstanceOf(DataframeResource.class, resource);
		assertNotNull(dataframe.insight);
		storedInsights.add(dataframe.insight.getInsightId());
		assertSame(user, dataframe.insight.getUser());
		assertSame(dataframe.insight, InsightStore.getInstance().get(dataframe.insight.getInsightId()));
	}

	@Test
	void getInsightDataFrameWrapsAnExistingInsight() {
		Insight insight = storedInsight();
		Object resource = new NameServer().getInsightDataFrame(insight.getInsightId(), "H2Frame", request(user()));
		assertSame(insight, assertInstanceOf(DataframeResource.class, resource).insight);
	}

	@Test
	void downloadFileRejectsAnUnknownInsightWith400() throws Exception {
		Response response = new NameServer().downloadFile("no-such-insight", "key");
		assertEquals(400, response.getStatus());
		assertEquals("Could not find the insight id", json(response).get(Constants.ERROR_MESSAGE));
	}

	private Insight insightWithExport(String key, String path) {
		Insight insight = mock(Insight.class);
		String id = UUID.randomUUID().toString();
		when(insight.getInsightId()).thenReturn(id);
		InsightStore.getInstance().put(insight);
		storedInsights.add(id);
		if (path == null) {
			when(insight.getExportFileLocation(key))
					.thenThrow(new IllegalArgumentException("The unique key '" + key + "' is an incorrect identifier"));
		} else {
			when(insight.getExportFileLocation(key)).thenReturn(path);
		}
		return insight;
	}

	@Test
	void downloadFileSendsTheExportedFileAsAnAttachment(@TempDir Path dir) throws Exception {
		Path file = Files.writeString(dir.resolve("report.xlsx"), "data");
		Insight insight = insightWithExport("key", file.toString());

		Response response = new NameServer().downloadFile(insight.getInsightId(), "key");

		assertEquals(200, response.getStatus());
		assertEquals(file.toFile().getCanonicalPath(), ((File) response.getEntity()).getCanonicalPath());
		assertEquals("attachment; filename=\"report.xlsx\"", response.getHeaderString("Content-Disposition"));
	}

	@Test
	void downloadFileAnswers400ForAFileThatIsGone(@TempDir Path dir) throws Exception {
		Insight insight = insightWithExport("key", dir.resolve("deleted.csv").toString());
		Response response = new NameServer().downloadFile(insight.getInsightId(), "key");
		assertEquals(400, response.getStatus());
		assertEquals("Could not find the file for given file id", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void downloadFileAnswers400ForAnUnknownFileKey() throws Exception {
		Insight insight = insightWithExport("bad", null);
		Response response = new NameServer().downloadFile(insight.getInsightId(), "bad");
		assertEquals(400, response.getStatus());
		assertTrue(((String) json(response).get(Constants.ERROR_MESSAGE)).contains("incorrect identifier"));
	}

	@Test
	void getLocalDatabaseNeedsASignedInUser() throws Exception {
		assertEquals("\"Not properly authenticated\"",
				body(new NameServer().getLocalDatabase(mock(HttpServletRequest.class), "e1", null)));
		assertEquals("\"Not properly authenticated\"", body(new NameServer().getLocalDatabase(request(null), "e1", null)));
	}

	@Test
	void getLocalDatabaseRejectsAnEngineTheUserCannotView() throws Exception {
		User user = user();
		try (MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			engines.when(() -> SecurityEngineUtils.userCanViewEngine(user, "e1")).thenReturn(false);

			Object response = new NameServer().getLocalDatabase(request(user), "e1", null);

			assertEquals(400, ((Response) response).getStatus());
			assertEquals("Database e1 does not exist or user does not have access to database",
					json((Response) response).get(Constants.ERROR_MESSAGE));
			utility.verify(() -> Utility.getDatabase(anyString()), never());
		}
	}

	@Test
	void getLocalDatabaseDoesNotResolveAnEngineName() throws Exception {
		User user = user();
		try (MockedStatic<SecurityQueryUtils> query = mockStatic(SecurityQueryUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			query.when(() -> SecurityQueryUtils.testUserEngineIdForAlias(user, "My Engine")).thenReturn("e1");
			engines.when(() -> SecurityEngineUtils.userCanViewEngine(user, "e1")).thenReturn(true);

			Object response = new NameServer().getLocalDatabase(request(user), "My Engine", null);

			assertEquals(400, ((Response) response).getStatus());
			query.verify(() -> SecurityQueryUtils.testUserEngineIdForAlias(any(), anyString()), never());
			utility.verify(() -> Utility.getDatabase(anyString()), never());
		}
	}

	@Test
	void getLocalDatabaseHandsAViewableEngineToTheEngineResource() throws Exception {
		User user = user();
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		try (MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			engines.when(() -> SecurityEngineUtils.userCanViewEngine(user, "e1")).thenReturn(true);
			utility.when(() -> Utility.getDatabase("e1")).thenReturn(engine);

			Object resource = new NameServer().getLocalDatabase(request(user), "e1", null);

			Field coreEngine = OldEngineResource.class.getDeclaredField("coreEngine");
			coreEngine.setAccessible(true);
			assertSame(engine, coreEngine.get(assertInstanceOf(OldEngineResource.class, resource)));
		}
	}

	@Test
	void printEnginesListsTheUsersEnginesOnlyForASignedInUser() throws Exception {
		User user = user();
		assertEquals("\"Not properly authenticated\"", body(new NameServer().printEngines(request(null))));
		try (MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			engines.when(() -> SecurityEngineUtils.getUserEngineList(eq(user), isNull(), isNull(), anyBoolean(),
					isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of(Map.of("id", "e1")));
			assertEquals("[{\"id\":\"e1\"}]", body(new NameServer().printEngines(request(user))));
		}
	}

	@Test
	void getSearchInsightsResultsRejectsAnInvalidFilter() throws Exception {
		StreamingOutput result = new NameServer()
				.getSearchInsightsResults(form("searchString", "sales", "filterData", "{not json"), request(user()));
		assertEquals("{\"errorMessage\":\"Invalid filter map\"}", body(result));
	}

	@Test
	void getSearchInsightsResultsSearchesTheUsersInsightsWithTheProjectFilterAndPaging() throws Exception {
		User user = user();
		try (MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class)) {
			insights.when(() -> SecurityInsightUtils.searchUserInsights(user, List.of("p1"), "sales", false, null,
					null, "10", "20")).thenReturn(List.of(Map.of("id", "i1")));
			StreamingOutput result = new NameServer().getSearchInsightsResults(form("searchString", "sales", "limit",
					"10", "offset", "20", "filterData", "{\"app_id\":[\"p1\"]}"), request(user));

			assertEquals("[{\"id\":\"i1\"}]", body(result));
		}
	}

	@Test
	void getAutoCompleteResultsPredictsFromTheUsersInsights() throws Exception {
		User user = user();
		try (MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class)) {
			insights.when(() -> SecurityInsightUtils.predictUserInsightSearch(user, "sal", "15", "0"))
					.thenReturn(List.of("sales"));
			assertEquals("[\"sales\"]", body(new NameServer().getAutoCompleteResults("sal", request(user))));
		}
	}

	@Test
	void mediaWikiTagsAreEmptyWithoutASearchTerm() throws Exception {
		assertEquals("{}", body(new NameServer().getMediaWikiTagsForSearchTerm(null, 5)));
		assertEquals("{}", body(new NameServer().getMediaWikiTagsForSearchTerm("", 5)));
	}
}
