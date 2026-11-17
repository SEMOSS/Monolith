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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.lexer.Lexer;
import prerna.sablecc2.parser.Parser;
import prerna.util.Constants;
import prerna.util.insight.InsightUtility;

class ModelEngineResourceTest {

	/**
	 * Records the pixel each endpoint hands to {@link ResourceUtility#runPixel}
	 * while the rest of {@link ResourceUtility} runs for real.
	 */
	private static final class PixelCapture implements AutoCloseable {

		private final List<String> pixels = new ArrayList<>();
		private final MockedStatic<ResourceUtility> mock;

		private PixelCapture() {
			this.mock = Mockito.mockStatic(ResourceUtility.class, invocation -> {
				if (invocation.getMethod().getName().equals("runPixel")) {
					this.pixels.add(invocation.getArgument(1));
					return Response.ok().build();
				}
				return invocation.callRealMethod();
			});
		}

		@Override
		public void close() {
			this.mock.close();
		}
	}

	private static HttpServletRequest jsonRequest(String json) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn("application/json");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
		return request;
	}

	private static HttpServletRequest signedInJsonRequest(String json, User user) throws IOException {
		HttpServletRequest request = jsonRequest(json);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void assertError(Response response, int status, String message) throws IOException {
		assertEquals(status, response.getStatus());
		String body = body(response);
		assertTrue(body.contains("\"" + Constants.ERROR_MESSAGE + "\":\"" + message), body);
	}

	private static void parsePixel(String pixel) throws Exception {
		new Parser(new Lexer(new PushbackReader(new StringReader(pixel), pixel.length()))).parse();
	}

	@Test
	void runsTheLlmPixelWithEveryOptionalFieldInOrder() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			String json = """
					{"command":"Hi \\"there\\"","context":"Be brief","useHistory":false,
					"paramValues":{"temperature":0.2,"max_tokens":10},"roomId":"r1","image":"img","url":"http://u"}""";

			Response response = new ModelEngineResource().llm(jsonRequest(json), "m1");

			assertEquals(200, response.getStatus());
			assertEquals(List.of("LLM(engine=\"m1\", command=\"Hi \\\"there\\\"\", context=\"Be brief\""
					+ ", useHistory=false, paramValues={\"temperature\":0.2,\"max_tokens\":10}, roomId=\"r1\""
					+ ", image=\"img\", url=\"http://u\")"), capture.pixels);
		}
	}

	@Test
	void leavesOutMissingAndEmptyOptionalFields() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new ModelEngineResource().llm(jsonRequest("{\"command\":\"Hi\",\"context\":\"\",\"roomId\":null}"), "m1");

			assertEquals(List.of("LLM(engine=\"m1\", command=\"Hi\")"), capture.pixels);
		}
	}

	@Test
	void buildsAnLlmPixelThePixelParserAccepts() throws Exception {
		try (PixelCapture capture = new PixelCapture()) {
			String json = "{\"command\":\"line one\\nline \\\"two\\\" 'three'\",\"context\":\"c\","
					+ "\"paramValues\":{\"k\":\"v\"}}";
			new ModelEngineResource().llm(jsonRequest(json), "m1");

			parsePixel(capture.pixels.get(0) + ";");
		}
	}

	@Test
	void requiresACommandForTheLlm() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			ModelEngineResource resource = new ModelEngineResource();

			assertError(resource.llm(jsonRequest("{}"), "m1"), 400, "Must pass in 'command'");
			assertError(resource.llm(jsonRequest("{\"command\":\"   \"}"), "m1"), 400, "Must pass in 'command'");
			assertError(resource.llm(jsonRequest("{oops"), "m1"), 400, "Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void runsTheEmbeddingsPixel() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new ModelEngineResource().embeddings(
					jsonRequest("{\"values\":[\"a\",\"b\"],\"paramValues\":{\"dimensions\":3}}"), "m1");

			assertEquals(List.of("Embeddings(engine=\"m1\", values=[\"a\",\"b\"], paramValues={\"dimensions\":3})"),
					capture.pixels);
		}
	}

	@Test
	void requiresValuesForEmbeddings() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			assertError(new ModelEngineResource().embeddings(jsonRequest("{}"), "m1"), 400,
					"Must pass in 'values'");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void runsTheVisionPixel() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new ModelEngineResource().vision(jsonRequest("{\"command\":\"Describe\",\"image\":\"data:abc\"}"), "m1");

			assertEquals(List.of("Vision(engine=\"m1\", command=\"Describe\", image=\"data:abc\")"), capture.pixels);
		}
	}

	@Test
	void requiresACommandAndAnImageForVision() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			ModelEngineResource resource = new ModelEngineResource();

			assertError(resource.vision(jsonRequest("{\"image\":\"x\"}"), "m1"), 400, "Must pass in 'command'");
			assertError(resource.vision(jsonRequest("{\"command\":\"x\",\"image\":\" \"}"), "m1"), 400,
					"Must pass in 'image'");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void rejectsAStreamingCallWithoutASignedInUser() throws IOException {
		HttpServletRequest noSession = jsonRequest("{\"command\":\"Hi\"}");
		HttpServletRequest noUser = jsonRequest("{\"command\":\"Hi\"}");
		when(noUser.getSession(false)).thenReturn(mock(HttpSession.class));

		assertError(new ModelEngineResource().llmStreaming(noSession, "m1"), 401, "User session is invalid");
		assertError(new ModelEngineResource().llmStreaming(noUser, "m1"), 401, "User session is invalid");
	}

	@Test
	void requiresACommandForAStreamingCall() throws IOException {
		User user = mock(User.class);

		assertError(new ModelEngineResource().llmStreaming(signedInJsonRequest("{}", user), "m1"), 400,
				"Must pass in 'command'");
		assertError(new ModelEngineResource().llmStreaming(signedInJsonRequest("{bad", user), "m1"), 400,
				"Invalid request body");
	}

	@Test
	void streamsEachChunkThenDoneAndCleansUp() throws Exception {
		User user = mock(User.class);
		PixelJobManager manager = mock(PixelJobManager.class);
		PixelJobRunner job = mock(PixelJobRunner.class);
		InsightStore store = mock(InsightStore.class);
		when(job.getJobId()).thenReturn("job-1");
		when(manager.makeJob(any(Insight.class), eq("session-1"), eq(null))).thenReturn(job);
		when(manager.getJob("job-1")).thenReturn(job);
		when(job.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS, PixelJobStatus.PROGRESS_COMPLETE);
		when(manager.getStreamOut("job-1")).thenReturn(List.of(Map.of("response", "Hel")),
				List.of(Map.of("response", "lo")), List.of(Map.of("response", "!")));

		try (MockedStatic<PixelJobManager> managers = Mockito.mockStatic(PixelJobManager.class);
				MockedStatic<InsightStore> stores = Mockito.mockStatic(InsightStore.class);
				MockedStatic<InsightUtility> insights = Mockito.mockStatic(InsightUtility.class)) {
			managers.when(PixelJobManager::getManager).thenReturn(manager);
			stores.when(InsightStore::getInstance).thenReturn(store);

			Response response = new ModelEngineResource().llmStreaming(signedInJsonRequest(
					"{\"command\":\"Hi\",\"paramValues\":{\"temperature\":0.1,\"stream\":false}}", user), "m1");

			assertEquals(200, response.getStatus());
			assertEquals("text/event-stream", response.getHeaderString("Content-Type"));
			assertEquals("no-cache", response.getHeaderString("Cache-Control"));
			ArgumentCaptor<String> pixel = ArgumentCaptor.forClass(String.class);
			verify(job).addPixel(pixel.capture());
			assertEquals("LLM(engine=\"m1\", command=\"Hi\", paramValues={\"temperature\":0.1,\"stream\":true});",
					pixel.getValue());
			ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);
			verify(store).put(insight.capture());
			verify(store).addToSessionHash("session-1", insight.getValue().getInsightId());
			assertEquals(user, insight.getValue().getUser());
			verify(user).setZoneId(any(ZoneId.class));

			String events = body(response);

			assertEquals("data: {\"response\":\"Hel\"}\n\ndata: {\"response\":\"lo\"}\n\n"
					+ "data: {\"response\":\"!\"}\n\ndata: [DONE]\n\n", events);
			verify(job).setStatus(PixelJobStatus.COMPLETE);
			verify(manager).removeJob("job-1");
			insights.verify(() -> InsightUtility.dropInsight(insight.getValue()));
		}
	}

	@ParameterizedTest
	@CsvSource({ "ERROR, The model request failed", "CANCELED, The model request was canceled",
			"UNKNOWN_JOB, The model request is no longer running" })
	@Timeout(10)
	void endsTheStreamWithAnErrorWhenTheJobStopsWithoutFinishing(PixelJobStatus status, String message)
			throws Exception {
		User user = mock(User.class);
		PixelJobManager manager = mock(PixelJobManager.class);
		PixelJobRunner job = mock(PixelJobRunner.class);
		when(job.getJobId()).thenReturn("job-1");
		when(manager.makeJob(any(Insight.class), eq("session-1"), eq(null))).thenReturn(job);
		if (status == PixelJobStatus.UNKNOWN_JOB) {
			when(manager.getJob("job-1")).thenReturn(job, (PixelJobRunner) null);
			when(job.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		} else {
			when(manager.getJob("job-1")).thenReturn(job);
			when(job.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS, status);
		}
		when(manager.getStreamOut("job-1")).thenReturn(List.of(Map.of("response", "Hel")),
				List.of(Map.of("response", "lo")), null);

		try (MockedStatic<PixelJobManager> managers = Mockito.mockStatic(PixelJobManager.class);
				MockedStatic<InsightStore> stores = Mockito.mockStatic(InsightStore.class);
				MockedStatic<InsightUtility> insights = Mockito.mockStatic(InsightUtility.class)) {
			managers.when(PixelJobManager::getManager).thenReturn(manager);
			stores.when(InsightStore::getInstance).thenReturn(mock(InsightStore.class));

			String events = body(
					new ModelEngineResource().llmStreaming(signedInJsonRequest("{\"command\":\"Hi\"}", user), "m1"));

			assertEquals("data: {\"response\":\"Hel\"}\n\ndata: {\"response\":\"lo\"}\n\n" + "data: {\"error\":\""
					+ message + "\"}\n\ndata: [DONE]\n\n", events);
			verify(manager).removeJob("job-1");
		}
	}
}
