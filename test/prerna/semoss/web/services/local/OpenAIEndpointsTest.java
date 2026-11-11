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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import prerna.engine.impl.model.openai.OpenAIModelsHelper;
import prerna.engine.impl.model.responses.AskStringModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.engine.impl.model.usage.ModelUsageGuard;
import prerna.engine.impl.model.usage.ModelUsageLimitException;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;
import prerna.util.Utility;

class OpenAIEndpointsTest {

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
		utility.when(() -> Utility.getCurrentZonedDateTimeForUser(user))
				.thenReturn(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
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
	void rejectsARequestWithoutASignedInUserWith401AndEndsTheSession() throws Exception {
		HttpSession anonymous = mock(HttpSession.class);
		when(anonymous.isNew()).thenReturn(true);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(anonymous);

		Response response = new OpenAIEndpoints().runV1ModelChatCompletion(request);

		assertEquals(401, response.getStatus());
		verify(anonymous).invalidate();
		verify(request, never()).getInputStream();
	}

	@Test
	void rejectsAChatWithoutAModelWith400() throws Exception {
		Response response = new OpenAIEndpoints().runModelChatCompletion(request("{\"messages\":[]}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: The 'data' parameter is missing the required 'model' field.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void rejectsAChatWithoutMessagesWith400() throws Exception {
		Response response = new OpenAIEndpoints().runModelChatCompletion(request("{\"model\":\"engine-1\"}"));

		assertEquals(400, response.getStatus());
		assertEquals("Please provide 'messages'.", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void rejectsAModelTheUserCannotViewWith403() throws Exception {
		Response response = new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"secret\",\"messages\":[]}"));

		assertEquals(403, response.getStatus());
		assertEquals("Model secret does not exist or user does not have access to this model",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
		invoker.verify(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap()), never());
	}

	@Test
	void answersAnUnknownInsightIdWith400AndTheInsightNotFoundType() throws Exception {
		Response response = new OpenAIEndpoints().runModelChatCompletion(
				request("{\"model\":\"engine-1\",\"insight_id\":\"missing\",\"messages\":[]}"));

		assertEquals(400, response.getStatus());
		JsonObject body = json(response);
		assertEquals("INSIGHT_NOT_FOUND", body.get("errorType").getAsString());
		assertEquals("Could not find the Insight with an Insight ID of missing",
				body.get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void answersANonStreamingChatWithAChatCompletion() throws Exception {
		AskStringModelEngineResponse reply = new AskStringModelEngineResponse("Hi there", 8, 2);
		reply.setMessageId("msg-1");
		answerSynchronouslyWith(reply);

		Response response = new OpenAIEndpoints().runModelChatCompletion(request("{\"model\":\"engine-1\","
				+ "\"client_metadata\":{\"x\":1},\"room_id\":\"room-1\",\"append_full_prompt\":\"true\","
				+ "\"temperature\":0.3,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("chat.completion", body.get("object").getAsString());
		assertEquals("msg-1", body.get("id").getAsString());
		assertEquals(ENGINE, body.get("model").getAsString());
		JsonObject choice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
		assertEquals("stop", choice.get("finish_reason").getAsString());
		assertEquals("assistant", choice.getAsJsonObject("message").get("role").getAsString());
		assertEquals("Hi there", choice.getAsJsonObject("message").get("content").getAsString());
		assertEquals(10, body.getAsJsonObject("usage").get("total_tokens").getAsInt());

		Map<String, Object> dataMap = sentToModel.get();
		assertEquals(List.of(Map.of("role", "user", "content", "Hi")), dataMap.get(AbstractModelEngine.FULL_PROMPT));
		assertEquals(true, dataMap.get(AbstractModelEngine.APPEND_FULL_PROMPT));
		assertEquals(0.3, dataMap.get("temperature"));
		assertFalse(dataMap.containsKey("client_metadata"));
		assertFalse(dataMap.containsKey("messages"));
		assertFalse(dataMap.containsKey("model"));
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-1", insight, engine, null));
		verify(insight).setUser(user);
	}

	@Test
	void answersAToolCallWithToolCallsAndTheToolCallsFinishReason() throws Exception {
		List<Map<String, Object>> calls = new ArrayList<>();
		calls.add(new HashMap<>(Map.of("id", "call_1", "name", "lookup", "arguments", Map.of("q", "x"))));
		answerSynchronouslyWith(new AskToolModelEngineResponse(calls, null, null));

		JsonObject body = json(new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"engine-1\",\"messages\":[]}")));

		JsonObject choice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
		assertEquals("tool_calls", choice.get("finish_reason").getAsString());
		JsonObject call = choice.getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
		assertEquals("call_1", call.get("id").getAsString());
		assertEquals("function", call.get("type").getAsString());
		assertEquals("{\"q\":\"x\"}", call.getAsJsonObject("function").get("arguments").getAsString(),
				"arguments go out as a JSON string");
	}

	@Test
	void takesTheRoomIdFromTheRequestAttributeWhenTheBodyHasNone() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));
		HttpServletRequest request = request("{\"model\":\"engine-1\",\"messages\":[]}");
		when(request.getAttribute("roomId")).thenReturn("room-from-key");

		new OpenAIEndpoints().runModelChatCompletion(request);

		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-from-key", insight, engine, null));
	}

	@Test
	void reportsARateLimitRefusalAs429WithRetryAfterAndAnErrorObject() throws Exception {
		String resetsAt = Instant.now().plusSeconds(90).toString();
		ModelUsageLimitException refusal = new ModelUsageLimitException("Too many calls this minute", null, true,
				resetsAt);
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenThrow(refusal);

		Response response = new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"engine-1\",\"messages\":[]}"));

		assertEquals(429, response.getStatus());
		long retryAfter = Long.parseLong(response.getHeaderString("Retry-After"));
		assertTrue(retryAfter > 80 && retryAfter <= 90, "Retry-After was " + retryAfter);
		JsonObject error = json(response).getAsJsonObject("error");
		assertEquals("Too many calls this minute", error.get("message").getAsString());
		assertEquals("rate_limit_exceeded", error.get("type").getAsString());
		assertEquals("rate_limit_exceeded", error.get("code").getAsString());
	}

	@Test
	void reportsAFailedSynchronousChatAs400WithItsMessage() throws Exception {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap()))
				.thenThrow(new IllegalStateException("model offline"));

		Response response = new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"engine-1\",\"messages\":[]}"));

		assertEquals(400, response.getStatus());
		assertEquals("model offline", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void streamsAChatAsServerSentEventsEndingWithDone() throws Exception {
		when(runner.getPixelJobStatus()).thenReturn(PixelJobStatus.IN_PROGRESS);
		when(manager.getStreamOut(JOB)).thenReturn(List.of(chunk("content", Map.of("content", "Hel")),
				chunk("content", Map.of("content", "lo")), chunk("content", Map.of("finish_reason", "stop"))));

		Response response = new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"engine-1\",\"stream\":true,\"messages\":[]}"));

		assertEquals(200, response.getStatus());
		assertEquals("text/event-stream", response.getHeaderString("Content-Type"));
		assertEquals("no-cache", response.getHeaderString("Cache-Control"));
		List<String> data = sseData(entity(response));
		assertEquals("[DONE]", data.get(data.size() - 1));
		JsonObject first = JsonParser.parseString(data.get(0)).getAsJsonObject();
		assertEquals("chat.completion.chunk", first.get("object").getAsString());
		assertTrue(first.get("id").getAsString().startsWith("chatcmpl-"));
		StringBuilder text = new StringBuilder();
		String finishReason = null;
		for (String event : data.subList(0, data.size() - 1)) {
			JsonObject choice = JsonParser.parseString(event).getAsJsonObject().getAsJsonArray("choices").get(0)
					.getAsJsonObject();
			JsonObject delta = choice.getAsJsonObject("delta");
			if (delta != null && delta.has("content")) {
				text.append(delta.get("content").getAsString());
			}
			if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
				finishReason = choice.get("finish_reason").getAsString();
			}
		}
		assertEquals("Hello", text.toString());
		assertEquals("stop", finishReason);
		verify(manager).removeJob(JOB);
	}

	@Test
	void refusesAStreamBeforeItStartsWhenAUsageLimitIsReached() throws Exception {
		usageGuard.when(() -> ModelUsageGuard.check(user, engine))
				.thenThrow(new ModelUsageLimitException("Monthly budget used", null, false, null));

		Response response = new OpenAIEndpoints()
				.runModelChatCompletion(request("{\"model\":\"engine-1\",\"stream\":true,\"messages\":[]}"));

		assertEquals(429, response.getStatus());
		assertEquals(ModelUsageLimitException.ERROR_CODE,
				json(response).getAsJsonObject("error").get("code").getAsString());
		assertFalse(response.getHeaders().containsKey("Retry-After"));
	}

	@Test
	void sendsResponsesInputToTheModelAsAMessageList() throws Exception {
		AskStringModelEngineResponse reply = new AskStringModelEngineResponse("Sure", 1, 1);
		reply.setMessageId("resp-1");
		answerSynchronouslyWith(reply);

		Response response = new OpenAIEndpoints()
				.runV1Responses(request("{\"model\":\"engine-1\",\"input\":\"Do it\",\"room_id\":\"room-2\"}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("response", body.get("object").getAsString());
		assertEquals("resp-1", body.get("id").getAsString());
		assertTrue(body.toString().contains("Sure"));
		assertEquals(List.of(Map.of("role", "user", "content", "Do it")),
				sentToModel.get().get(AbstractModelEngine.FULL_PROMPT));
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("room-2", insight, engine, null));
	}

	@Test
	void takesTheResponsesRoomIdFromTheThreadOrSessionHeader() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("ok", null, null));

		HttpServletRequest threadRequest = request("{\"model\":\"engine-1\",\"input\":\"a\"}");
		when(threadRequest.getHeader("thread_id")).thenReturn("thread-7");
		new OpenAIEndpoints().runResponses(threadRequest);
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("thread-7", insight, engine, null));

		HttpServletRequest sessionRequest = request("{\"model\":\"engine-1\",\"input\":\"a\"}");
		when(sessionRequest.getHeader("session-id")).thenReturn("session-header-8");
		new OpenAIEndpoints().runResponses(sessionRequest);
		roomUtils.verify(() -> RoomUtils.createRoomIfNotExists("session-header-8", insight, engine, null));
	}

	@Test
	void rejectsAResponsesModelTheUserCannotViewBeforeLoadingIt() throws Exception {
		Response response = new OpenAIEndpoints().runResponses(request("{\"model\":\"secret\",\"input\":\"a\"}"));

		assertEquals(403, response.getStatus());
		assertEquals("Model secret inaccessible.", json(response).get(Constants.ERROR_MESSAGE).getAsString());
		utility.verify(() -> Utility.getModel("secret"), never());
	}

	@Test
	void sendsAnImagePromptAsOneUserMessage() throws Exception {
		Response missing = new OpenAIEndpoints().runV1ImagesGenerations(request("{\"model\":\"engine-1\"}"));
		assertEquals(400, missing.getStatus());
		assertEquals("Missing required field 'prompt'.", json(missing).get(Constants.ERROR_MESSAGE).getAsString());

		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenAnswer(invocation -> {
			sentToModel.set(invocation.getArgument(3));
			throw new IllegalStateException("no image model");
		});
		Response failed = new OpenAIEndpoints()
				.runImagesGenerationsAlias(request("{\"model\":\"engine-1\",\"prompt\":\"a cat\",\"size\":\"1024x1024\"}"));
		assertEquals(400, failed.getStatus());
		assertEquals(List.of(Map.of("role", "user", "content", "a cat")),
				sentToModel.get().get(AbstractModelEngine.FULL_PROMPT));
		assertEquals("1024x1024", sentToModel.get().get("size"));
	}

	@Test
	void answersALegacyCompletionWithTheTextAndUsage() throws Exception {
		AskStringModelEngineResponse reply = new AskStringModelEngineResponse("four", 3, 1);
		reply.setMessageId("cmpl-1");
		answerSynchronouslyWith(reply);

		Response response = new OpenAIEndpoints().runModelCompletion(request("{\"model\":\"engine-1\",\"prompt\":\"2+2\"}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("text_completion", body.get("object").getAsString());
		assertEquals("cmpl-1", body.get("id").getAsString());
		assertEquals(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC).toEpochSecond(),
				body.get("created").getAsLong());
		JsonObject choice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
		assertEquals("four", choice.get("text").getAsString());
		assertEquals(0, choice.get("index").getAsInt());
		assertEquals(4, body.getAsJsonObject("usage").get("total_tokens").getAsInt());
		assertEquals(List.of(Map.of("role", "user", "content", "2+2")),
				sentToModel.get().get(AbstractModelEngine.FULL_PROMPT));
	}

	@Test
	void rejectsALegacyCompletionWithoutAPromptWith400() throws Exception {
		Response response = new OpenAIEndpoints().runModelCompletion(request("{\"model\":\"engine-1\"}"));

		assertEquals(400, response.getStatus());
		assertEquals("Please provide 'prompt'.", json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void streamsALegacyCompletionAsOneChunkThenDone() throws Exception {
		answerSynchronouslyWith(new AskStringModelEngineResponse("four", 3, 1));

		Response response = new OpenAIEndpoints()
				.runModelCompletion(request("{\"model\":\"engine-1\",\"prompt\":\"2+2\",\"stream\":true}"));

		assertEquals(200, response.getStatus());
		List<String> data = sseData(entity(response));
		assertEquals(2, data.size());
		assertEquals("[DONE]", data.get(1));
		JsonObject chunk = JsonParser.parseString(data.get(0)).getAsJsonObject();
		assertEquals("text_completion", chunk.get("object").getAsString());
		JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();
		assertEquals("four", choice.get("text").getAsString());
		assertEquals("stop", choice.get("finish_reason").getAsString());
		assertEquals(4, chunk.getAsJsonObject("usage").get("total_tokens").getAsInt());
	}

	@Test
	void embedsAListOfInputs() throws Exception {
		when(engine.embeddings(anyList(), eq(insight), anyMap()))
				.thenReturn(new EmbeddingsModelEngineResponse(List.of(List.of(0.1), List.of(0.2)), 5, null));

		Response response = new OpenAIEndpoints()
				.runV1ModelEmbeddings(request("{\"model\":\"engine-1\",\"input\":[\"a\",\"b\"]}"));

		assertEquals(200, response.getStatus());
		JsonObject body = json(response);
		assertEquals("list", body.get("object").getAsString());
		assertEquals(2, body.getAsJsonArray("data").size());
		assertEquals(1, body.getAsJsonArray("data").get(1).getAsJsonObject().get("index").getAsInt());
		assertEquals(5, body.getAsJsonObject("usage").get("prompt_tokens").getAsInt());
		verify(engine).embeddings(eq(List.of("a", "b")), eq(insight), anyMap());
	}

	@Test
	void embedsAStringInputAsOneText() throws Exception {
		when(engine.embeddings(anyList(), eq(insight), anyMap()))
				.thenReturn(new EmbeddingsModelEngineResponse(List.of(List.of(0.1)), 2, null));

		Response response = new OpenAIEndpoints()
				.runV1ModelEmbeddings(request("{\"model\":\"engine-1\",\"input\":\"hello\"}"));

		assertEquals(200, response.getStatus());
		assertEquals(1, json(response).getAsJsonArray("data").size());
		verify(engine).embeddings(eq(List.of("hello")), eq(insight), anyMap());
	}

	@Test
	void rejectsAnEmbeddingInputThatIsNotTextWith400() throws Exception {
		Response response = new OpenAIEndpoints()
				.runV1ModelEmbeddings(request("{\"model\":\"engine-1\",\"input\":[1,2]}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: The 'input' field must be a string or a list of strings.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
		verify(engine, never()).embeddings(anyList(), any(), anyMap());
	}

	@Test
	void rejectsAnEmbeddingRequestWithoutInputWith400() throws Exception {
		Response response = new OpenAIEndpoints().runModelEmbeddings(request("{\"model\":\"engine-1\",\"input\":[]}"));

		assertEquals(400, response.getStatus());
		assertEquals("Bad Request: The 'data' parameter is missing the required 'input' field.",
				json(response).get(Constants.ERROR_MESSAGE).getAsString());
	}

	@Test
	void listsAndRetrievesTheModelsTheUserCanSee() throws Exception {
		try (MockedStatic<OpenAIModelsHelper> models = mockStatic(OpenAIModelsHelper.class)) {
			models.when(() -> OpenAIModelsHelper.listModels(user))
					.thenReturn(Map.of("object", "list", "data", List.of(Map.of("id", ENGINE))));
			models.when(() -> OpenAIModelsHelper.retrieveModel(user, ENGINE))
					.thenReturn(Map.of("id", ENGINE, "object", "model"));

			JsonObject list = json(new OpenAIEndpoints().listV1Models(request("")));
			assertEquals("list", list.get("object").getAsString());
			assertEquals(ENGINE, list.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString());

			Response retrieved = new OpenAIEndpoints().retrieveV1Model(request(""), ENGINE);
			assertEquals(200, retrieved.getStatus());
			assertEquals("model", json(retrieved).get("object").getAsString());
		}
	}

	private void answerSynchronouslyWith(Object reply) {
		invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), anyMap())).thenAnswer(invocation -> {
			sentToModel.set(invocation.getArgument(3));
			return reply;
		});
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

	private static String entity(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static JsonObject json(Response response) throws IOException {
		assertNotNull(response.getEntity());
		return JsonParser.parseString(entity(response)).getAsJsonObject();
	}

	private static List<String> sseData(String sse) {
		assertTrue(sse.endsWith("\n\n"), "every event ends with a blank line");
		List<String> data = new ArrayList<>();
		for (String frame : sse.split("\n\n")) {
			for (String line : frame.split("\n")) {
				if (line.startsWith("data: ")) {
					data.add(line.substring("data: ".length()));
				}
			}
		}
		return data;
	}
}
