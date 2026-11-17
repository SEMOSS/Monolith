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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.responses.AskStringModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.engine.impl.model.usage.ModelUsageGuard;
import prerna.engine.impl.model.usage.ModelUsageLimitException;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;
import prerna.util.Utility;

class OllamaEndpointsTest {

	private static final String ENGINE = "engine-1";
	private static final String JOB = "job-1";

	private User user;
	private HttpSession session;
	private IModelEngine engine;
	private Insight insight;
	private InsightStore store;
	private Room room;
	private PixelJobManager manager;
	private PixelJobRunner runner;
	private final AtomicReference<Map<String, Object>> sentToModel = new AtomicReference<>();

	private MockedStatic<SecurityEngineUtils> security;
	private MockedStatic<Utility> utility;
	private MockedStatic<InsightStore> insightStore;
	private MockedStatic<RoomUtils> roomUtils;
	private MockedStatic<ModelPixelInvoker> invoker;
	private MockedStatic<PixelJobManager> jobManager;
	private MockedStatic<ModelUsageGuard> usageGuard;
	private MockedStatic<UserAuditTrailUtils> audit;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void mockTheSemossRuntime() {
		user = mock(User.class);
		session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		engine = mock(IModelEngine.class);
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn("insight-1");
		store = mock(InsightStore.class);
		when(store.getInsightIDsForSession("session-1")).thenReturn(new HashSet<>(Set.of("insight-1")));
		when(store.get("insight-1")).thenReturn(insight);
		room = mock(Room.class);
		manager = mock(PixelJobManager.class);
		runner = mock(PixelJobRunner.class);
		when(manager.getJob(JOB)).thenReturn(runner);

		security = mockStatic(SecurityEngineUtils.class);
		security.when(() -> SecurityEngineUtils.userCanViewEngine(user, ENGINE)).thenReturn(true);
		utility = mockStatic(Utility.class);
		utility.when(Utility::getApplicationZoneId).thenReturn("UTC");
		utility.when(() -> Utility.getModel(ENGINE)).thenReturn(engine);
		insightStore = mockStatic(InsightStore.class);
		insightStore.when(InsightStore::getInstance).thenReturn(store);
		roomUtils = mockStatic(RoomUtils.class);
		roomUtils.when(() -> RoomUtils.createRoomIfNotExists(any(), any(), any(), any())).thenReturn(room);
		invoker = mockStatic(ModelPixelInvoker.class);
		invoker.when(() -> ModelPixelInvoker.startAsyncModelRequest(any(), any(), any(), anyMap(), any()))
				.thenAnswer(invocation -> {
					sentToModel.set(invocation.getArgument(3));
					return JOB;
				});
		jobManager = mockStatic(PixelJobManager.class);
		jobManager.when(PixelJobManager::getManager).thenReturn(manager);
		usageGuard = mockStatic(ModelUsageGuard.class);
		audit = mockStatic(UserAuditTrailUtils.class);
	}

	@AfterEach
	void closeTheStaticMocks() {
		audit.close();
		usageGuard.close();
		jobManager.close();
		invoker.close();
		roomUtils.close();
		insightStore.close();
		utility.close();
		security.close();
		ModelUsageLimitException.clearLastOnThread();
	}

	@Test
	void rejectsARequestWithoutASignedInUserWith401() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(null);

		Response response = new OllamaEndpoints().chat(request);

		assertEquals(401, response.getStatus());
		assertEquals("User session is invalid", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void rejectsABodyThatIsNotAJsonObjectWith400() throws Exception {
		Response response = new OllamaEndpoints().chat(request("[1, 2]"));

		assertEquals(400, response.getStatus());
		assertTrue(json(response).get(Constants.ERROR_MESSAGE).getAsString().startsWith("Error processing JSON data"));
	}

	@Test
	void rejectsAMissingModelWith400() throws Exception {
		Response response = new OllamaEndpoints().chat(request("{\"messages\":\"hi\"}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: Missing required field 'model'.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void rejectsAModelTheUserCannotViewWith403() throws Exception {
		Response response = new OllamaEndpoints().chat(request("{\"model\":\"secret\",\"messages\":\"hi\"}"));

		assertEquals(403, response.getStatus());
		utility.verify(() -> Utility.getModel("secret"), never());
	}

	@Test
	void answers404ForAModelThatDoesNotLoad() throws Exception {
		security.when(() -> SecurityEngineUtils.userCanViewEngine(user, "gone")).thenReturn(true);

		Response response = new OllamaEndpoints().generate(request("{\"model\":\"gone\",\"prompt\":\"hi\"}"));

		assertEquals(404, response.getStatus());
		assertEquals("Model not found: gone", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void rejectsAChatWithoutMessagesWith400() throws Exception {
		Response missing = new OllamaEndpoints().chat(request("{\"model\":\"engine-1\"}"));
		assertEquals(400, missing.getStatus());
		assertEquals("Bad Request: Missing required field 'messages'.",
				json(missing).get(Constants.ERROR_MESSAGE).getAsString());

		Response empty = new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":[]}"));
		assertEquals(400, empty.getStatus());
		assertEquals("Bad Request: 'messages' must contain at least one item.",
				json(empty).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void answersAnUnknownInsightIdWith400() throws Exception {
		Response response = new OllamaEndpoints()
				.chat(request("{\"model\":\"engine-1\",\"insight_id\":\"nope\",\"messages\":\"hi\"}"));

		assertEquals(400, response.getStatus());
		JsonObject body = json(response);
		assertEquals("INSIGHT_NOT_FOUND", body.get("errorType").getAsString());
		assertEquals("Could not resolve insight context", body.get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void answersANonStreamingChatWithTheWholeMessage() throws Exception {
		AskStringModelEngineResponse reply = new AskStringModelEngineResponse("Hello!", 4, 2);
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenAnswer(invocation -> {
			sentToModel.set(invocation.getArgument(3));
			return reply;
		});

		Response response = new OllamaEndpoints().apiChat(request("{\"model\":\"engine-1\",\"stream\":false,"
				+ "\"room_id\":\"room-9\",\"options\":{\"temperature\":0.2},\"think\":true,\"keep_alive\":\"5m\","
				+ "\"temperature\":0.5,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals(ENGINE, body.get("model").getAsString());
		assertTrue(body.get("done").getAsBoolean());
		assertEquals("stop", body.get("done_reason").getAsString());
		assertEquals("Hello!", body.getAsJsonObject("message").get("content").getAsString());
		assertEquals(4, body.get("prompt_eval_count").getAsInt());
		assertEquals(2, body.get("eval_count").getAsInt());

		Map<String, Object> dataMap = sentToModel.get();
		assertEquals(List.of(Map.of("role", "user", "content", "Hi")), dataMap.get(AbstractModelEngine.FULL_PROMPT));
		assertEquals(false, dataMap.get(AbstractModelEngine.APPEND_FULL_PROMPT));
		assertEquals(0.5, dataMap.get("temperature"));
		assertFalse(dataMap.containsKey("model"));
		assertFalse(dataMap.containsKey("stream"));
		assertFalse(dataMap.containsKey("room_id"));
		assertFalse(dataMap.containsKey("options"), "provider specific options are dropped for this model type");
		assertFalse(dataMap.containsKey("think"));
		assertFalse(dataMap.containsKey("keep_alive"));
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-9", insight, engine, null));
		verify(insight).setUser(user);
	}

	@Test
	void reportsAUsageLimitRefusalAs429WithAnErrorBody() throws Exception {
		ModelUsageLimitException refusal = new ModelUsageLimitException("Daily limit reached", null, false, null);
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenThrow(refusal);

		Response response = new OllamaEndpoints()
				.chat(request("{\"model\":\"engine-1\",\"stream\":false,\"messages\":\"hi\"}"));

		assertEquals(429, response.getStatus());
		JsonObject body = json(response);
		assertEquals("Daily limit reached", body.get("error").getAsString());
		assertEquals(ModelUsageLimitException.ERROR_CODE, body.get("code").getAsString());
		assertFalse(response.getHeaders().containsKey("Retry-After"), "a daily limit is not worth retrying soon");
	}

	@Test
	void refusesAStreamBeforeItStartsWhenAUsageLimitIsReached() throws Exception {
		usageGuard.when(() -> ModelUsageGuard.check(user, engine)).thenThrow(ModelUsageLimitException.unavailable());

		Response response = new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}"));

		assertEquals(503, response.getStatus());
		assertEquals("usage_limits_unavailable", json(response).get("code").getAsString());
		invoker.verify(() -> ModelPixelInvoker.startAsyncModelRequest(any(), any(), any(), anyMap(), any()), never());
	}

	@Test
	void reportsAFailedSynchronousCallAs400WithItsMessage() throws Exception {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap()))
				.thenThrow(new IllegalStateException("model offline"));

		Response response = new OllamaEndpoints()
				.generate(request("{\"model\":\"engine-1\",\"stream\":false,\"prompt\":\"hi\"}"));

		assertEquals(400, response.getStatus());
		assertEquals("model offline", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void streamsChatContentAsNdjsonEndingWithADoneChunk() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(
				List.of(chunk("content", Map.of("content", "Hel")), chunk("content", Map.of("content", ""))),
				List.of(chunk("content", Map.of("content", "lo")),
						chunk("usage", Map.of("input_tokens", 10, "output_tokens", 3)),
						chunk("content", Map.of("finish_reason", "stop"))));

		Response response = new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}"));

		assertEquals(200, response.getStatus());
		assertEquals("application/x-ndjson", response.getHeaderString("Content-Type"));
		List<JsonObject> lines = stream(response);
		assertEquals(3, lines.size());
		assertEquals("Hel", lines.get(0).getAsJsonObject("message").get("content").getAsString());
		assertFalse(lines.get(0).get("done").getAsBoolean());
		assertEquals("lo", lines.get(1).getAsJsonObject("message").get("content").getAsString());
		assertTrue(lines.get(2).get("done").getAsBoolean());
		assertEquals("stop", lines.get(2).get("done_reason").getAsString());
		assertEquals(10, lines.get(2).get("prompt_eval_count").getAsInt());
		assertEquals(3, lines.get(2).get("eval_count").getAsInt());
	}

	@Test
	void sendsEachStreamedToolCallAsOneAggregatedChunk() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(
				chunk("tool", toolDelta("call_1", "get_weather", "{\"city\":")),
				chunk("tool", toolDelta(null, null, "\"Oslo\"}")),
				chunk("tool", toolDelta("call_2", "get_time", "{}")),
				chunk("tool", Map.of("finish_reason", "tool_calls"))));

		List<JsonObject> lines = stream(
				new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"weather?\"}")));

		assertEquals(3, lines.size());
		JsonObject first = lines.get(0).getAsJsonObject("message").getAsJsonArray("tool_calls").get(0)
				.getAsJsonObject().getAsJsonObject("function");
		assertEquals("get_weather", first.get("name").getAsString());
		assertEquals("Oslo", first.getAsJsonObject("arguments").get("city").getAsString());
		JsonObject second = lines.get(1).getAsJsonObject("message").getAsJsonArray("tool_calls").get(0)
				.getAsJsonObject().getAsJsonObject("function");
		assertEquals("get_time", second.get("name").getAsString());
		assertTrue(lines.get(2).get("done").getAsBoolean());
		assertEquals("stop", lines.get(2).get("done_reason").getAsString());
	}

	@Test
	void sendsThinkingAsThinkingBeforeAStreamedToolCall() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("thinking", Map.of("thinking", "Need the weather")),
				chunk("tool", toolDelta("call_1", "get_weather", "{\"city\":\"Oslo\"}")),
				chunk("tool", Map.of("finish_reason", "tool_calls"))));

		List<JsonObject> lines = stream(
				new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"weather?\"}")));

		assertEquals(3, lines.size());
		JsonObject thinking = lines.get(0).getAsJsonObject("message");
		assertEquals("Need the weather", thinking.get("thinking").getAsString());
		assertEquals("", thinking.get("content").getAsString());
		JsonObject function = lines.get(1).getAsJsonObject("message").getAsJsonArray("tool_calls").get(0)
				.getAsJsonObject().getAsJsonObject("function");
		assertEquals("get_weather", function.get("name").getAsString());
		assertTrue(lines.get(2).get("done").getAsBoolean());
	}

	@Test
	void sendsAToolResultThatWasNotStreamedAfterStreamedThinking() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("thinking", Map.of("thinking", "Look it up"))));
		Map<String, Object> finalOutput = new HashMap<>();
		finalOutput.put("messageType", "TOOL");
		finalOutput.put("response", List.of(Map.of("id", "call_1", "type", "function", "name", "lookup", "arguments",
				Map.of("q", "x"))));
		PixelRunner completed = finalRunner(finalOutput);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<JsonObject> lines = stream(
				new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"look it up\"}")));

		assertEquals(3, lines.size());
		assertEquals("Look it up", lines.get(0).getAsJsonObject("message").get("thinking").getAsString());
		JsonObject function = lines.get(1).getAsJsonObject("message").getAsJsonArray("tool_calls").get(0)
				.getAsJsonObject().getAsJsonObject("function");
		assertEquals("lookup", function.get("name").getAsString());
		assertTrue(lines.get(2).get("done").getAsBoolean());
	}

	@Test
	void releasesTheJobOnceAChatOrGenerateStreamEnds() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Hi")),
				chunk("content", Map.of("finish_reason", "stop"))));

		stream(new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}")));
		stream(new OllamaEndpoints().generate(request("{\"model\":\"engine-1\",\"prompt\":\"hi\"}")));

		verify(manager, times(2)).clearJob(JOB);
		verify(manager, times(2)).removeJob(JOB);
	}

	@Test
	@Timeout(10)
	void endsAChatStreamWithAnErrorLineWhenTheJobFails() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.ERROR);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Part"))));

		List<JsonObject> lines = stream(new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}")));

		assertEquals(2, lines.size());
		assertEquals("Part", lines.get(0).getAsJsonObject("message").get("content").getAsString());
		assertEquals("The model request failed", lines.get(1).get("error").getAsString());
		// the status is read first, so the chunks read after it are all the job sent
		InOrder order = inOrder(runner, manager);
		order.verify(runner).getPixelJobStatus();
		order.verify(manager).getStreamOut(JOB);
		verify(manager).removeJob(JOB);
	}

	@Test
	@Timeout(10)
	void endsAGenerateStreamWithAnErrorLineWhenTheJobIsGone() throws Exception {
		when(manager.getJob(JOB)).thenReturn(null);

		List<JsonObject> lines = stream(
				new OllamaEndpoints().generate(request("{\"model\":\"engine-1\",\"prompt\":\"hi\"}")));

		assertEquals(1, lines.size());
		assertEquals("The model request is no longer running", lines.get(0).get("error").getAsString());
	}

	@Test
	void passesTheOllamaOptionsAndThinkingLevelToTheModel() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("finish_reason", "stop"))));

		stream(new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\",\"think\":\"high\","
				+ "\"keep_alive\":\"5m\",\"options\":{\"temperature\":0.2,\"top_p\":0.9,\"num_predict\":128,"
				+ "\"top_k\":40,\"stop\":[\"END\"],\"num_ctx\":4096}}")));

		Map<String, Object> sent = sentToModel.get();
		assertEquals(0.2, sent.get("temperature"));
		assertEquals(0.9, sent.get("top_p"));
		assertEquals(128L, sent.get("max_tokens"));
		assertEquals(List.of("END"), sent.get("stop"));
		assertEquals(true, sent.get("thinking"));
		assertEquals("high", sent.get("effort"));
		for (String dropped : List.of("options", "think", "keep_alive", "top_k", "num_ctx", "num_predict")) {
			assertFalse(sent.containsKey(dropped), dropped);
		}
	}

	@Test
	void keepsATopLevelSettingAndSkipsAnUnlimitedNumPredict() {
		Map<String, Object> dataMap = new HashMap<>();
		dataMap.put("temperature", 0.7);
		dataMap.put("think", false);
		dataMap.put("options", Map.of("temperature", 0.1, "num_predict", -1L));

		OllamaEndpoints.translateOllamaParams(dataMap);

		assertEquals(Map.of("temperature", 0.7, "thinking", false), dataMap);
	}

	@Test
	void reportsStopOrLengthAsTheDoneReasonFromEitherVocabulary() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		Map<String, String> expected = Map.of("tool_use", "stop", "end_turn", "stop", "tool_calls", "stop",
				"max_tokens", "length", "length", "length");
		for (Map.Entry<String, String> reason : expected.entrySet()) {
			when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Hi")),
					chunk("content", Map.of("finish_reason", reason.getKey()))));

			List<JsonObject> lines = stream(
					new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}")));

			assertEquals(reason.getValue(), lines.get(lines.size() - 1).get("done_reason").getAsString(),
					reason.getKey());
		}
	}

	@Test
	void sendsAToolResultThatWasNotStreamedOnceTheJobCompletes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		when(manager.getStreamOut(JOB)).thenReturn(null);
		Map<String, Object> finalOutput = new HashMap<>();
		finalOutput.put("messageType", "TOOL");
		finalOutput.put("response", List.of(Map.of("id", "call_1", "type", "function", "name", "lookup", "arguments",
				Map.of("q", "x"))));
		PixelRunner completed = finalRunner(finalOutput);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<JsonObject> lines = stream(
				new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"look it up\"}")));

		assertEquals(2, lines.size());
		JsonObject function = lines.get(0).getAsJsonObject("message").getAsJsonArray("tool_calls").get(0)
				.getAsJsonObject().getAsJsonObject("function");
		assertEquals("lookup", function.get("name").getAsString());
		assertEquals("x", function.getAsJsonObject("arguments").get("q").getAsString());
		assertEquals("stop", lines.get(1).get("done_reason").getAsString());
	}

	@Test
	void sendsATextResultThatWasNotStreamedOnceTheJobCompletes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		Map<String, Object> finalOutput = new HashMap<>();
		finalOutput.put("messageType", "CHAT");
		finalOutput.put("response", "All at once");
		PixelRunner completed = finalRunner(finalOutput);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<JsonObject> lines = stream(new OllamaEndpoints().chat(request("{\"model\":\"engine-1\",\"messages\":\"hi\"}")));

		assertEquals(2, lines.size());
		assertEquals("All at once", lines.get(0).getAsJsonObject("message").get("content").getAsString());
		assertTrue(lines.get(1).get("done").getAsBoolean());
	}

	@Test
	void closesAStreamThatProducedContentWhenTheJobCompletes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "partial"))));

		List<JsonObject> lines = stream(
				new OllamaEndpoints().generate(request("{\"model\":\"engine-1\",\"prompt\":\"hi\"}")));

		assertEquals(2, lines.size());
		assertEquals("partial", lines.get(0).get("response").getAsString());
		assertTrue(lines.get(1).get("done").getAsBoolean());
		assertEquals("stop", lines.get(1).get("done_reason").getAsString());
		verify(manager, never()).getOutput(JOB);
	}

	@Test
	void rejectsAGenerateWithoutAPromptWith400() throws Exception {
		Response response = new OllamaEndpoints().apigenerate(request("{\"model\":\"engine-1\",\"prompt\":\"  \"}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: Missing required field 'prompt'.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void sendsAGeneratePromptToTheModelAsOneUserMessage() throws Exception {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenAnswer(invocation -> {
			sentToModel.set(invocation.getArgument(3));
			return new AskStringModelEngineResponse("4", null, 1);
		});

		Response response = new OllamaEndpoints()
				.generate(request("{\"model\":\"engine-1\",\"stream\":false,\"input\":\"2+2?\"}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("4", body.get("response").getAsString());
		assertEquals("stop", body.get("done_reason").getAsString());
		assertFalse(body.has("prompt_eval_count"));
		assertEquals(List.of(Map.of("role", "user", "content", "2+2?")),
				sentToModel.get().get(AbstractModelEngine.FULL_PROMPT));
	}

	@Test
	void streamsGenerateTextChunksAndSkipsOtherStreamTypes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Once")),
				chunk("thinking", Map.of("thinking", "plotting")), chunk("content", Map.of("content", " upon")),
				chunk("content", Map.of("finish_reason", "length"))));

		List<JsonObject> lines = stream(
				new OllamaEndpoints().generate(request("{\"model\":\"engine-1\",\"prompt\":\"story\"}")));

		assertEquals(3, lines.size());
		assertEquals("Once", lines.get(0).get("response").getAsString());
		assertEquals(" upon", lines.get(1).get("response").getAsString());
		assertEquals("length", lines.get(2).get("done_reason").getAsString());
	}

	@Test
	void embedsASinglePromptAsOneVector() throws Exception {
		when(engine.embeddings(anyList(), eq(insight), anyMap()))
				.thenReturn(new EmbeddingsModelEngineResponse(List.of(List.of(0.5, 0.25)), 3, null));

		Response response = new OllamaEndpoints().embeddings(request("{\"model\":\"engine-1\",\"prompt\":\"hello\"}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals(0.5, body.getAsJsonArray("embedding").get(0).getAsDouble());
		assertEquals(3, body.get("prompt_eval_count").getAsInt());
		verify(engine).embeddings(eq(List.of("hello")), eq(insight), anyMap());
	}

	@Test
	void embedsSeveralInputsAsSeveralVectors() throws Exception {
		when(engine.embeddings(anyList(), eq(insight), anyMap()))
				.thenReturn(new EmbeddingsModelEngineResponse(List.of(List.of(1.0), List.of(2.0)), null, null));

		JsonObject body = json(
				new OllamaEndpoints().embeddings(request("{\"model\":\"engine-1\",\"input\":[\"a\",\"b\"]}")));

		assertEquals(2, body.getAsJsonArray("embeddings").size());
		assertFalse(body.has("embedding"));
	}

	@Test
	void rejectsAnEmbeddingRequestWithNothingToEmbedWith400() throws Exception {
		Response response = new OllamaEndpoints().embeddings(request("{\"model\":\"engine-1\",\"input\":[\" \"]}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: Missing required field 'prompt' or 'input'.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void reportsAFailedEmbeddingCallAs400() throws Exception {
		when(engine.embeddings(anyList(), eq(insight), anyMap())).thenThrow(new IllegalStateException("no vectors"));

		Response response = new OllamaEndpoints().embeddings(request("{\"model\":\"engine-1\",\"prompt\":\"x\"}"));

		assertEquals(400, response.getStatus());
		assertEquals("no vectors", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	private HttpServletRequest request(String body) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		when(request.getInputStream()).thenReturn(servletInput(body));
		return request;
	}

	private static ServletInputStream servletInput(String body) {
		ByteArrayInputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		return new ServletInputStream() {
			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return in.read(b, off, len);
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

	private static Map<String, Object> chunk(String streamType, Map<String, Object> data) {
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("stream_type", streamType);
		chunk.put("data", new HashMap<>(data));
		return chunk;
	}

	private static Map<String, Object> toolDelta(String id, String name, String arguments) {
		Map<String, Object> function = new HashMap<>();
		if (name != null) {
			function.put("name", name);
		}
		function.put("arguments", arguments);
		Map<String, Object> delta = new HashMap<>();
		if (id != null) {
			delta.put("id", id);
		}
		delta.put("index", 0);
		delta.put("function", function);
		return delta;
	}

	private static PixelRunner finalRunner(Object value) {
		PixelRunner finalRunner = mock(PixelRunner.class);
		List<NounMetadata> results = new ArrayList<>();
		results.add(new NounMetadata(value, PixelDataType.MAP));
		when(finalRunner.getResults()).thenReturn(results);
		return finalRunner;
	}

	private static String entity(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static JsonObject json(Response response) throws IOException {
		return JsonParser.parseString(entity(response)).getAsJsonObject();
	}

	private static List<JsonObject> stream(Response response) throws IOException {
		String ndjson = entity(response);
		List<JsonObject> lines = new ArrayList<>();
		for (String line : ndjson.split("\n")) {
			if (!line.isEmpty()) {
				lines.add(JsonParser.parseString(line).getAsJsonObject());
			}
		}
		return lines;
	}
}
