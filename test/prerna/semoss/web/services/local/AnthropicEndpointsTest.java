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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.engine.impl.model.responses.AskStringModelEngineResponse;
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

class AnthropicEndpointsTest {

	private static final String ENGINE = "engine-1";
	private static final String JOB = "job-1";
	private static final String SIDECAR = "thought_signatures.jsonl";

	@TempDir
	Path base;

	private User user;
	private HttpSession session;
	private HttpServletResponse servletResponse;
	private IModelEngine engine;
	private Insight insight;
	private InsightStore store;
	private Room room;
	private Path roomFolder;
	private PixelJobManager manager;
	private PixelJobRunner runner;
	private final AtomicReference<Map<String, Object>> sentToModel = new AtomicReference<>();

	private MockedStatic<SecurityEngineUtils> security;
	private MockedStatic<Utility> utility;
	private MockedStatic<InsightStore> insightStore;
	private MockedStatic<RoomUtils> roomUtils;
	private MockedStatic<ModelInferenceLogsUtils> inferenceLogs;
	private MockedStatic<ModelPixelInvoker> invoker;
	private MockedStatic<PixelJobManager> jobManager;
	private MockedStatic<ModelUsageGuard> usageGuard;
	private MockedStatic<UserAuditTrailUtils> audit;

	@BeforeEach
	void mockTheSemossRuntime() throws IOException {
		user = mock(User.class);
		session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		servletResponse = mock(HttpServletResponse.class);
		engine = mock(IModelEngine.class);
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn("insight-1");
		store = mock(InsightStore.class);
		when(store.get("insight-1")).thenReturn(insight);
		roomFolder = Files.createDirectories(base.resolve("room").resolve("room-1"));
		room = mock(Room.class);
		when(room.getRoomFolderPath()).thenReturn(roomFolder.toString());
		manager = mock(PixelJobManager.class);
		runner = mock(PixelJobRunner.class);
		when(manager.getJob(JOB)).thenReturn(runner);

		security = mockStatic(SecurityEngineUtils.class);
		security.when(() -> SecurityEngineUtils.userCanViewEngine(user, ENGINE)).thenReturn(true);
		utility = mockStatic(Utility.class);
		utility.when(Utility::getApplicationZoneId).thenReturn("UTC");
		utility.when(Utility::getBaseFolder).thenReturn(base.toString());
		utility.when(() -> Utility.getModel(ENGINE)).thenReturn(engine);
		insightStore = mockStatic(InsightStore.class);
		insightStore.when(InsightStore::getInstance).thenReturn(store);
		roomUtils = mockStatic(RoomUtils.class);
		roomUtils.when(() -> RoomUtils.createRoomIfNotExists(any(), any(), any(), any())).thenReturn(room);
		inferenceLogs = mockStatic(ModelInferenceLogsUtils.class);
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
		inferenceLogs.close();
		roomUtils.close();
		insightStore.close();
		utility.close();
		security.close();
		ModelUsageLimitException.clearLastOnThread();
	}

	@Test
	void rejectsARequestWithoutASignedInUserWith401() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);

		Response response = new AnthropicEndpoints().createMessage(request, servletResponse);

		assertEquals(401, response.getStatus());
	}

	@Test
	void rejectsInvalidJsonWithAnInvalidRequestError() throws Exception {
		Response response = new AnthropicEndpoints().createMessage(request("{\"model\": oops"), servletResponse);

		assertEquals(400, response.getStatus());
		assertError(response, "invalid_request_error", "Invalid JSON in request body");
	}

	@Test
	void rejectsAMissingModelWithAnInvalidRequestError() throws Exception {
		Response response = new AnthropicEndpoints().createMessage(request("{\"messages\":[]}"), servletResponse);

		assertEquals(400, response.getStatus());
		assertError(response, "invalid_request_error", "model is required");
	}

	@Test
	void rejectsAModelTheUserCannotViewWithAPermissionError() throws Exception {
		Response response = new AnthropicEndpoints().createMessage(request("{\"model\":\"secret\",\"messages\":[]}"),
				servletResponse);

		assertEquals(403, response.getStatus());
		assertError(response, "permission_error", "User does not have access to model: secret");
		utility.verify(() -> Utility.getModel("secret"), never());
	}

	@Test
	void answersAModelThatDoesNotLoadWithANotFoundError() throws Exception {
		security.when(() -> SecurityEngineUtils.userCanViewEngine(user, "gone")).thenReturn(true);

		Response response = new AnthropicEndpoints().createMessage(request("{\"model\":\"gone\",\"messages\":[]}"),
				servletResponse);

		assertEquals(404, response.getStatus());
		assertError(response, "not_found_error", "Model not found: gone");
	}

	@Test
	void rejectsARoomIdThatCouldLeaveTheRoomFolder() throws Exception {
		HttpServletRequest request = request("{\"model\":\"engine-1\",\"messages\":[]}");
		when(request.getHeader("x-semoss-room-id")).thenReturn("..");

		Response response = new AnthropicEndpoints().createMessage(request, servletResponse);

		assertEquals(400, response.getStatus());
		assertError(response, "invalid_request_error", "Invalid room id");
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists(any(), any(), any(), any()), never());
	}

	@Test
	void rejectsARequestWithoutMessages() throws Exception {
		Response response = new AnthropicEndpoints()
				.createMessage(request("{\"model\":\"engine-1\",\"insight_id\":\"insight-1\"}"), servletResponse);

		assertEquals(400, response.getStatus());
		assertError(response, "invalid_request_error", "messages is required");
	}

	@Test
	@SuppressWarnings("unchecked")
	void answersANonStreamingMessageAndSendsTheWholeConversationToTheModel() throws Exception {
		AskStringModelEngineResponse reply = new AskStringModelEngineResponse("Bonjour", 20, 4);
		reply.setMessageId("msg_1");
		answerSynchronouslyWith(reply);

		Response response = new AnthropicEndpoints().createMessage(request("{\"model\":\"engine-1\","
				+ "\"insight_id\":\"insight-1\",\"room_id\":\"room-1\",\"max_tokens\":1024,"
				+ "\"system\":[{\"type\":\"text\",\"text\":\"Be French\"}],"
				+ "\"metadata\":{\"user_id\":\"u\"},\"context_management\":{},\"output_config\":{},\"safeguards\":{},"
				+ "\"tools\":[{\"name\":\"t\",\"description\":\"d\",\"input_schema\":{\"type\":\"object\"}}],"
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}"), servletResponse);

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("msg_1", body.get("id").getAsString());
		assertEquals("message", body.get("type").getAsString());
		assertEquals("end_turn", body.get("stop_reason").getAsString());
		assertEquals("Bonjour", body.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		assertEquals(20, body.getAsJsonObject("usage").get("input_tokens").getAsInt());

		Map<String, Object> dataMap = sentToModel.get();
		List<Map<String, Object>> prompt = (List<Map<String, Object>>) dataMap.get(AbstractModelEngine.FULL_PROMPT);
		assertEquals(List.of(Map.of("role", "system", "content", "Be French"), Map.of("role", "user", "content", "Hello")),
				prompt);
		assertEquals(true, dataMap.get("append_full_prompt"));
		List<Map<String, Object>> tools = (List<Map<String, Object>>) dataMap.get("tools");
		assertEquals("t", tools.get(0).get("name"));
		assertEquals(Map.of("type", "object"), tools.get(0).get("inputSchema"));
		assertEquals(1024L, dataMap.get("max_tokens"));
		for (String dropped : List.of("model", "system", "messages", "stream", "metadata", "context_management",
				"output_config", "safeguards", "insight_id", "room_id")) {
			assertFalse(dataMap.containsKey(dropped), dropped + " is not forwarded");
		}
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-1", insight, engine, null));
		verify(insight).setUser(user);
	}

	@Test
	@SuppressWarnings("unchecked")
	void clampsAThinkingBudgetAboveTheCeilingOrBelowZero() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));

		new AnthropicEndpoints().createMessage(request(messageBody("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":64000}")),
				servletResponse);
		assertEquals(16384, ((Map<String, Object>) sentToModel.get().get("thinking")).get("budget_tokens"));

		new AnthropicEndpoints().createMessage(request(messageBody("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":-1}")),
				servletResponse);
		assertEquals(16384, ((Map<String, Object>) sentToModel.get().get("thinking")).get("budget_tokens"));

		new AnthropicEndpoints().createMessage(request(messageBody("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}")),
				servletResponse);
		Map<String, Object> kept = (Map<String, Object>) sentToModel.get().get("thinking");
		assertEquals(2048L, kept.get("budget_tokens"));
		assertEquals("enabled", kept.get("type"));
	}

	@Test
	void surfacesAnEmptyCompletionAsA502ApiError() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("", 3, 0));

		Response response = new AnthropicEndpoints().createMessage(request(messageBody(null)), servletResponse);

		assertEquals(502, response.getStatus());
		assertError(response, "api_error", "Model returned an empty response (no content or tool calls).");
	}

	@Test
	void reportsAFailedCallAsA500ApiError() throws Exception {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap()))
				.thenThrow(new IllegalStateException("upstream down"));

		Response response = new AnthropicEndpoints().createMessage(request(messageBody(null)), servletResponse);

		assertEquals(500, response.getStatus());
		assertError(response, "api_error", "Error processing request: upstream down");
	}

	@Test
	void reportsAUsageLimitRefusalAsARateLimitError() throws Exception {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap()))
				.thenThrow(new ModelUsageLimitException("Weekly limit reached", null, false, null));

		Response response = new AnthropicEndpoints().createMessage(request(messageBody(null)), servletResponse);

		assertEquals(429, response.getStatus());
		JsonObject body = json(response);
		assertEquals("error", body.get("type").getAsString());
		assertEquals("rate_limit_error", body.getAsJsonObject("error").get("type").getAsString());
		assertEquals("Weekly limit reached", body.getAsJsonObject("error").get("message").getAsString());
		assertEquals(ModelUsageLimitException.ERROR_CODE, body.getAsJsonObject("error").get("code").getAsString());
	}

	@Test
	void refusesAStreamWhenUsageLimitsCannotBeChecked() throws Exception {
		usageGuard.when(() -> ModelUsageGuard.check(user, engine)).thenThrow(ModelUsageLimitException.unavailable());

		Response response = new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")),
				servletResponse);

		assertEquals(503, response.getStatus());
		assertEquals("api_error", json(response).getAsJsonObject("error").get("type").getAsString());
	}

	@Test
	void picksTheRoomFromTheKeyAttributeThenTheRoomHeaderThenTheSessionHeader() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));

		HttpServletRequest fromKey = request(messageBody(null));
		when(fromKey.getAttribute("roomId")).thenReturn("key-room");
		when(fromKey.getHeader("x-semoss-room-id")).thenReturn("header-room");
		new AnthropicEndpoints().createMessage(fromKey, servletResponse);
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("key-room", insight, engine, null));

		HttpServletRequest fromHeader = request(messageBody(null));
		when(fromHeader.getHeader("x-semoss-room-id")).thenReturn("header-room");
		when(fromHeader.getHeader("x-claude-code-session-id")).thenReturn("session-room");
		new AnthropicEndpoints().createMessage(fromHeader, servletResponse);
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("header-room", insight, engine, null));

		HttpServletRequest fromSession = request(messageBody(null));
		when(fromSession.getHeader("x-claude-code-session-id")).thenReturn("session-room");
		new AnthropicEndpoints().createMessage(fromSession, servletResponse);
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("session-room", insight, engine, null));
	}

	@Test
	void reusesTheInsightOfTheCallersExistingRoom() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));
		AccessToken token = mock(AccessToken.class);
		when(token.getId()).thenReturn("user-1");
		when(user.getPrimaryLoginToken()).thenReturn(token);
		Room existing = mock(Room.class);
		when(existing.getInsight()).thenReturn(insight);
		inferenceLogs.when(() -> ModelInferenceLogsUtils.getRoomById("room-1", "user-1")).thenReturn(existing);

		Response response = new AnthropicEndpoints().createMessage(
				request("{\"model\":\"engine-1\",\"room_id\":\"room-1\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}"),
				servletResponse);

		assertEquals(200, response.getStatus());
		verify(store, never()).put(any(Insight.class));
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-1", insight, engine, null));
	}

	@Test
	@SuppressWarnings("unchecked")
	void replaysStoredThoughtSignaturesOntoTheirToolCalls() throws Exception {
		ThoughtSignatureSidecar.append(roomFolder.toString(), "toolu_1", "sig-1");
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));

		new AnthropicEndpoints().createMessage(request("{\"model\":\"engine-1\",\"insight_id\":\"insight-1\","
				+ "\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
				+ "\"name\":\"t\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
				+ "\"tool_use_id\":\"toolu_1\",\"content\":\"done\"}]}]}"), servletResponse);

		List<Map<String, Object>> prompt = (List<Map<String, Object>>) sentToModel.get()
				.get(AbstractModelEngine.FULL_PROMPT);
		Map<String, Object> toolCall = ((List<Map<String, Object>>) prompt.get(0).get("tool_calls")).get(0);
		assertEquals("sig-1", toolCall.get("thought_signature"));
		assertEquals(Map.of("role", "tool", "tool_call_id", "toolu_1", "content", "done"), prompt.get(1));
	}

	@Test
	void streamsTextAsMessageEventsAndReleasesTheJob() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Hel"))),
				List.of(chunk("content", Map.of("content", "lo")),
						chunk("usage", Map.of("input_tokens", 50, "output_tokens", 7, "cache_read_input_tokens", 40))),
				List.of(chunk("content", Map.of("finish_reason", "stop"))));

		Response response = new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")),
				servletResponse);

		assertEquals(200, response.getStatus());
		assertEquals("text/event-stream", response.getHeaderString("Content-Type"));
		verify(servletResponse).setBufferSize(0);
		List<Event> events = events(entity(response));
		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_delta",
				"content_block_stop", "message_delta", "message_stop"), names(events));
		assertTrue(events.get(0).data.getAsJsonObject("message").get("id").getAsString().startsWith("msg_"));
		assertEquals("text", events.get(1).data.getAsJsonObject("content_block").get("type").getAsString());
		assertEquals("Hel", events.get(2).data.getAsJsonObject("delta").get("text").getAsString());
		assertEquals("lo", events.get(3).data.getAsJsonObject("delta").get("text").getAsString());
		JsonObject delta = events.get(5).data;
		assertEquals("end_turn", delta.getAsJsonObject("delta").get("stop_reason").getAsString());
		assertEquals(50, delta.getAsJsonObject("usage").get("input_tokens").getAsInt());
		assertEquals(7, delta.getAsJsonObject("usage").get("output_tokens").getAsInt());
		assertEquals(40, delta.getAsJsonObject("usage").get("cache_read_input_tokens").getAsInt());
		verify(manager).clearJob(JOB);
		verify(manager).removeJob(JOB);
	}

	@Test
	void closesTheTextBlockAndGivesEachStreamedToolTheNextIndex() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		Map<String, Object> secondTool = new HashMap<>(toolDelta("call_2", "get_time", "{}"));
		secondTool.put("index", 1);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Checking")),
				chunk("tool", toolDelta("call_1", "get_weather", "{\"city\":\"Oslo\"}")), chunk("tool", secondTool),
				chunk("tool", Map.of("finish_reason", "tool_calls"))));

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		List<String> blocks = new ArrayList<>();
		for (Event event : events) {
			if (event.name.startsWith("content_block_")) {
				blocks.add(event.name + " " + event.data.get("index").getAsInt());
			}
		}
		assertEquals(List.of("content_block_start 0", "content_block_delta 0", "content_block_stop 0",
				"content_block_start 1", "content_block_delta 1", "content_block_start 2", "content_block_delta 2",
				"content_block_stop 1", "content_block_stop 2"), blocks);
		JsonObject firstTool = events.get(4).data.getAsJsonObject("content_block");
		assertEquals("tool_use", firstTool.get("type").getAsString());
		assertEquals("get_weather", firstTool.get("name").getAsString());
		assertEquals("tool_use", events.get(events.size() - 2).data.getAsJsonObject("delta").get("stop_reason")
				.getAsString());
	}

	@Test
	void opensNoInsightForARejectedRequest() throws Exception {
		Response response = new AnthropicEndpoints().createMessage(request("{\"model\":\"engine-1\"}"), servletResponse);

		assertEquals(400, response.getStatus());
		verify(store, never()).put(any(Insight.class));
		verify(store, never()).addToSessionHash(any(), any());
	}

	@Test
	void addsTheInsightItOpensToTheSession() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));

		Response response = new AnthropicEndpoints().createMessage(
				request("{\"model\":\"engine-1\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"),
				servletResponse);

		assertEquals(200, response.getStatus());
		verify(store).addToSessionHash(eq("session-1"), anyString());
	}

	@Test
	@Timeout(10)
	void endsTheStreamWithAnErrorEventWhenTheJobFails() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.ERROR);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Part"))));

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "error"), names(events));
		assertEquals("Part", events.get(2).data.getAsJsonObject("delta").get("text").getAsString());
		assertEquals("The model request failed",
				events.get(3).data.getAsJsonObject("error").get("message").getAsString());
		// the status is read first, so the chunks read after it are all the job sent
		InOrder order = inOrder(runner, manager);
		order.verify(runner).getPixelJobStatus();
		order.verify(manager).getStreamOut(JOB);
		verify(manager).removeJob(JOB);
	}

	@Test
	@Timeout(10)
	void endsTheStreamWhenTheJobIsCanceledOrGone() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.CANCELED);
		List<Event> canceled = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));
		Event last = canceled.get(canceled.size() - 1);
		assertEquals("error", last.name);
		assertEquals("The model request was canceled", last.data.getAsJsonObject("error").get("message").getAsString());

		when(manager.getJob(JOB)).thenReturn(null);
		List<Event> gone = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));
		last = gone.get(gone.size() - 1);
		assertEquals("error", last.name);
		assertEquals("The model request is no longer running",
				last.data.getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void reportsToolUseWheneverAToolCallWasStreamed() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		for (String finish : new String[] { "tool_use", "tool_calls", "stop", "end_turn" }) {
			when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Checking")),
					chunk("tool", toolDelta("call_1", "get_weather", "{}")),
					chunk("tool", Map.of("finish_reason", finish))));

			List<Event> events = events(entity(
					new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

			assertEquals("tool_use", events.get(events.size() - 2).data.getAsJsonObject("delta").get("stop_reason")
					.getAsString(), finish);
		}
	}

	@Test
	void translatesTheStopReasonOfATextReplyFromEitherVocabulary() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		Map<String, String> expected = Map.of("end_turn", "end_turn", "stop", "end_turn", "max_tokens", "max_tokens",
				"length", "max_tokens");
		for (Map.Entry<String, String> reason : expected.entrySet()) {
			when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Hi")),
					chunk("content", Map.of("finish_reason", reason.getKey()))));

			List<Event> events = events(entity(
					new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

			assertEquals(reason.getValue(), events.get(events.size() - 2).data.getAsJsonObject("delta")
					.get("stop_reason").getAsString(), reason.getKey());
		}
	}

	@Test
	void sendsPingsWhileTheModelHasNothingNew() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(null, List.of(chunk("content", Map.of("content", "x")),
				chunk("content", Map.of("finish_reason", "length"))));

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals("message_start", events.get(0).name);
		assertEquals("ping", events.get(1).name);
		Event messageDelta = events.get(events.size() - 2);
		assertEquals("max_tokens", messageDelta.data.getAsJsonObject("delta").get("stop_reason").getAsString());
	}

	@Test
	void leadsWithAThinkingBlockAndClosesItBeforeTheText() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("thinking", Map.of("thinking", "Let me see")),
				chunk("thinking", Map.of("thinking", "...")), chunk("content", Map.of("content", "Answer")),
				chunk("thinking", Map.of("thinking", "late, dropped")), chunk("content", Map.of("finish_reason", "stop"))));

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_delta",
				"content_block_stop", "content_block_start", "content_block_delta", "content_block_stop",
				"message_delta", "message_stop"), names(events));
		assertEquals("thinking", events.get(1).data.getAsJsonObject("content_block").get("type").getAsString());
		assertEquals(0, events.get(1).data.get("index").getAsInt());
		assertEquals("Let me see", events.get(2).data.getAsJsonObject("delta").get("thinking").getAsString());
		assertEquals(0, events.get(4).data.get("index").getAsInt());
		assertEquals("text", events.get(5).data.getAsJsonObject("content_block").get("type").getAsString());
		assertEquals(1, events.get(5).data.get("index").getAsInt());
		assertEquals(1, events.get(6).data.get("index").getAsInt());
		assertEquals(1, events.get(7).data.get("index").getAsInt());
	}

	@Test
	void streamsAToolCallAndKeepsItsThoughtSignatureForTheNextTurn() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		Map<String, Object> first = toolDelta("toolu_9", "get_weather", "{\"city\":");
		first.put("thought_signature", "c2ln");
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("tool", first),
				chunk("tool", toolDelta(null, null, "\"Oslo\"}")), chunk("tool", Map.of("finish_reason", "tool_calls"))));

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_delta",
				"content_block_stop", "message_delta", "message_stop"), names(events));
		JsonObject block = events.get(1).data.getAsJsonObject("content_block");
		assertEquals("tool_use", block.get("type").getAsString());
		assertEquals("toolu_9", block.get("id").getAsString());
		assertEquals("get_weather", block.get("name").getAsString());
		StringBuilder json = new StringBuilder();
		json.append(events.get(2).data.getAsJsonObject("delta").get("partial_json").getAsString());
		json.append(events.get(3).data.getAsJsonObject("delta").get("partial_json").getAsString());
		assertEquals("Oslo", JsonParser.parseString(json.toString()).getAsJsonObject().get("city").getAsString());
		assertEquals("tool_use", events.get(5).data.getAsJsonObject("delta").get("stop_reason").getAsString());
		assertEquals(Map.of("toolu_9", "c2ln"), ThoughtSignatureSidecar.load(roomFolder.toString()));
	}

	@Test
	void sendsAToolResultThatWasNotStreamedOnceTheJobCompletes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		Map<String, Object> output = new HashMap<>();
		output.put("messageType", "TOOL");
		output.put("response", List.of(Map.of("id", "toolu_1", "name", "a", "arguments", Map.of("k", "v")),
				Map.of("id", "toolu_2", "name", "b", "arguments", "", "thought_signature", "sig-2")));
		PixelRunner completed = finalRunner(output);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta",
				"content_block_stop", "content_block_start", "content_block_delta", "content_block_stop",
				"message_delta", "message_stop"), names(events));
		assertEquals("toolu_1", events.get(2).data.getAsJsonObject("content_block").get("id").getAsString());
		assertEquals("{\"k\":\"v\"}", events.get(3).data.getAsJsonObject("delta").get("partial_json").getAsString());
		assertEquals(1, events.get(5).data.get("index").getAsInt());
		assertEquals("{}", events.get(6).data.getAsJsonObject("delta").get("partial_json").getAsString(),
				"empty arguments go out as an empty object");
		assertEquals("tool_use", events.get(8).data.getAsJsonObject("delta").get("stop_reason").getAsString());
		assertEquals(Map.of("toolu_2", "sig-2"), ThoughtSignatureSidecar.load(roomFolder.toString()));
	}

	@Test
	void sendsATextResultThatWasNotStreamedOnceTheJobCompletes() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		Map<String, Object> output = new HashMap<>();
		output.put("messageType", "CHAT");
		output.put("response", "All at once");
		PixelRunner completed = finalRunner(output);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta",
				"content_block_stop", "message_delta", "message_stop"), names(events));
		assertEquals("All at once", events.get(3).data.getAsJsonObject("delta").get("text").getAsString());
		assertEquals("end_turn", events.get(5).data.getAsJsonObject("delta").get("stop_reason").getAsString());
	}

	@Test
	void reportsACompletedJobWithNoOutputAsAnErrorEvent() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.PROGRESS_COMPLETE);
		Map<String, Object> output = new HashMap<>();
		output.put("messageType", "CHAT");
		output.put("response", "");
		PixelRunner completed = finalRunner(output);
		when(manager.getOutput(JOB)).thenReturn(completed);

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "ping", "error"), names(events));
		assertEquals("api_error", events.get(2).data.getAsJsonObject("error").get("type").getAsString());
		verify(manager).removeJob(JOB);
	}

	@Test
	void closesAStreamedTextBlockWhenTheJobCompletesWithoutAFinishChunk() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS, PixelJobStatus.PROGRESS_COMPLETE);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "partial"))), null);

		List<Event> events = events(entity(
				new AnthropicEndpoints().createMessage(request(messageBody("\"stream\":true")), servletResponse)));

		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "ping",
				"content_block_stop", "message_delta", "message_stop"), names(events));
		assertEquals("end_turn", events.get(5).data.getAsJsonObject("delta").get("stop_reason").getAsString());
	}

	private void answerSynchronouslyWith(Object reply) {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenAnswer(invocation -> {
			sentToModel.set(invocation.getArgument(3));
			return reply;
		});
	}

	private static String messageBody(String extra) {
		return "{\"model\":\"engine-1\",\"insight_id\":\"insight-1\"," + (extra == null ? "" : extra + ",")
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
	}

	private static void assertError(Response response, String type, String message) throws IOException {
		JsonObject body = json(response);
		assertEquals("error", body.get("type").getAsString());
		assertEquals(type, body.getAsJsonObject("error").get("type").getAsString());
		assertEquals(message, body.getAsJsonObject("error").get("message").getAsString());
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

	private static final class Event {
		private final String name;
		private final JsonObject data;

		private Event(String name, JsonObject data) {
			this.name = name;
			this.data = data;
		}
	}

	private static List<Event> events(String sse) {
		List<Event> events = new ArrayList<>();
		for (String frame : sse.split("\n\n")) {
			if (frame.isEmpty()) {
				continue;
			}
			String[] lines = frame.split("\n");
			events.add(new Event(lines[0].substring("event: ".length()),
					JsonParser.parseString(lines[1].substring("data: ".length())).getAsJsonObject()));
		}
		return events;
	}

	private static List<String> names(List<Event> events) {
		List<String> names = new ArrayList<>();
		for (Event event : events) {
			names.add(event.name);
		}
		return names;
	}
}
