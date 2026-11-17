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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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

import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.reactor.agent.run.AgentRunHandle;
import prerna.reactor.agent.run.AgentRunRequest;
import prerna.reactor.agent.run.AgentRunService;
import prerna.reactor.agent.run.AgentRunStatus;
import prerna.util.Constants;
import prerna.util.Utility;

class A2AResourceTest {

	private static final String WORKSPACE = "ws-1";

	private User user;
	private Insight insight;
	private String insightId;
	private InsightStore store;
	private AgentRunService runs;
	private Map<String, String> properties;

	private MockedStatic<Utility> utility;
	private MockedStatic<InsightStore> insightStore;
	private MockedStatic<ModelInferenceLogsUtils> inferenceLogs;
	private MockedStatic<AgentRunService> agentRunService;
	private MockedStatic<SecurityProjectUtils> projects;

	@BeforeEach
	void mockTheSemossRuntime() {
		user = mock(User.class);
		insightId = "insight-" + UUID.randomUUID();
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn(insightId);
		when(insight.getUser()).thenReturn(user);
		store = mock(InsightStore.class);
		when(store.get(insightId)).thenReturn(insight);
		runs = mock(AgentRunService.class);
		properties = new HashMap<>();
		properties.put("A2A_ENABLED", "true");

		utility = mockStatic(Utility.class);
		utility.when(Utility::getApplicationZoneId).thenReturn("UTC");
		utility.when(() -> Utility.getDIHelperProperty(anyString()))
				.thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
		utility.when(() -> Utility.getModel("engine-1")).thenReturn(mock(IModelEngine.class));
		insightStore = mockStatic(InsightStore.class);
		insightStore.when(InsightStore::getInstance).thenReturn(store);
		inferenceLogs = mockStatic(ModelInferenceLogsUtils.class);
		agentRunService = mockStatic(AgentRunService.class);
		agentRunService.when(AgentRunService::get).thenReturn(runs);
		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(activeWorkspace());
		projects = mockStatic(SecurityProjectUtils.class);
		projects.when(() -> SecurityProjectUtils.userCanViewProject(any(), eq(WORKSPACE))).thenReturn(true);
	}

	@AfterEach
	void closeTheStaticMocks() {
		projects.close();
		agentRunService.close();
		inferenceLogs.close();
		insightStore.close();
		utility.close();
		ThreadStore.remove();
	}

	@Test
	void runsEachSseCallOnANamedVirtualThread() throws Exception {
		Field field = A2AResource.class.getDeclaredField("SSE_EXECUTOR");
		field.setAccessible(true);
		Thread thread = ((ExecutorService) field.get(null)).submit(Thread::currentThread).get(5, TimeUnit.SECONDS);

		assertTrue(thread.isVirtual());
		assertTrue(thread.getName().startsWith("a2a-sse-"), thread.getName());
	}

	@Test
	void runsEverySseCallAtOnce() throws Exception {
		Field field = A2AResource.class.getDeclaredField("SSE_EXECUTOR");
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
	void hidesTheAgentCardWhenA2AIsDisabled() throws Exception {
		properties.remove("A2A_ENABLED");

		Response response = new A2AResource().agentCard(WORKSPACE, mock(HttpServletRequest.class));

		assertEquals(404, response.getStatus());
		assertEquals("A2A is disabled", json(response).get("error").getAsString());
		inferenceLogs.verify(() -> ModelInferenceLogsUtils.getWorkspaceEntry(anyString()), never());
	}

	@Test
	void publishesTheWorkspaceAsAnAgentCard() throws Exception {
		Map<String, Object> row = new HashMap<>();
		row.put("name", "Research Desk");
		row.put("description", "Finds things");
		row.put("config_json", "{\"skills\":[{\"id\":\"lookup\",\"name\":\"Lookup\",\"description\":\"Looks up\","
				+ "\"tags\":[\"search\",\" \"]},\"summarize\",{\"description\":\"nameless\"}]}");
		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(row);
		HttpServletRequest request = cardRequest("https", "example.com", 8443, "/Monolith");

		JsonObject card = json(new A2AResource().agentCard(WORKSPACE, request));

		assertEquals("Research Desk", card.get("name").getAsString());
		assertEquals("Finds things", card.get("description").getAsString());
		String rpcUrl = "https://example.com:8443/Monolith/api/ext/a2a/workspace/ws-1/rpc";
		assertTrue(card.toString().contains(rpcUrl), card.toString());
		assertTrue(card.toString().contains("JSONRPC"), card.toString());
		assertTrue(card.getAsJsonObject("capabilities").get("streaming").getAsBoolean());
		assertFalse(card.getAsJsonObject("capabilities").get("pushNotifications").getAsBoolean());
		JsonArray skills = card.getAsJsonArray("skills");
		assertEquals(2, skills.size(), "a skill without an id or name is left out");
		JsonObject lookup = skills.get(0).getAsJsonObject();
		assertEquals("lookup", lookup.get("id").getAsString());
		assertEquals("Lookup", lookup.get("name").getAsString());
		assertEquals(List.of("search"), strings(lookup.getAsJsonArray("tags")));
		JsonObject summarize = skills.get(1).getAsJsonObject();
		assertEquals("summarize", summarize.get("id").getAsString());
		assertEquals("Workspace skill", summarize.get("description").getAsString());
		assertTrue(card.getAsJsonObject("securitySchemes").has("semossBearer"));
	}

	@Test
	void fallsBackToADefaultCardAndOmitsStandardPorts() throws Exception {
		HttpServletRequest request = cardRequest("http", "localhost", 80, "");

		JsonObject card = json(new A2AResource().agentCard(WORKSPACE, request));

		assertEquals("SEMOSS Workspace Agent", card.get("name").getAsString());
		assertEquals("SEMOSS workspace-backed agent", card.get("description").getAsString());
		assertTrue(card.toString().contains("http://localhost/api/ext/a2a/workspace/ws-1/rpc"), card.toString());
		JsonArray skills = card.getAsJsonArray("skills");
		assertEquals(1, skills.size());
		assertEquals("semoss-agent", skills.get(0).getAsJsonObject().get("id").getAsString());
	}

	@Test
	void usesTheConfiguredBaseUrlForTheCard() throws Exception {
		properties.put("A2A_AGENT_CARD_BASE_URL", "https://agents.example.com//");

		JsonObject card = json(new A2AResource().agentCard(WORKSPACE, cardRequest("http", "internal", 8080, "/x")));

		assertTrue(card.toString().contains("https://agents.example.com/api/ext/a2a/workspace/ws-1/rpc"),
				card.toString());
		assertFalse(card.toString().contains("internal"));
	}

	@Test
	void hidesTheRpcEndpointWhenA2AIsDisabled() throws Exception {
		properties.put("A2A_ENABLED", "false");

		Response response = new A2AResource().rpc(WORKSPACE, body("{}"), mock(HttpServletRequest.class));

		assertEquals(404, response.getStatus());
	}

	@Test
	void answersAnUnknownMethodWithMethodNotFoundAndTheRequestId() throws Exception {
		Response response = new A2AResource().rpc(WORKSPACE,
				body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"message/send\",\"params\":{}}"), rpcRequest(user));

		assertEquals(200, response.getStatus());
		JsonObject rpc = json(response);
		assertEquals("2.0", rpc.get("jsonrpc").getAsString());
		assertEquals(7, rpc.get("id").getAsInt());
		assertEquals(-32601, rpc.getAsJsonObject("error").get("code").getAsInt());
		assertEquals("Unsupported A2A method: message/send", rpc.getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void rejectsACallerWithoutASignedInUserWith401() throws Exception {
		Response response = new A2AResource().rpc(WORKSPACE, body("{\"id\":1,\"method\":\"GetTask\"}"),
				rpcRequest(null));

		assertEquals(401, response.getStatus());
		assertEquals(-32001, json(response).getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void rejectsABodyThatIsNotAJsonRpcObjectWith400() throws Exception {
		Response response = new A2AResource().rpc(WORKSPACE, body("[1,2]"), rpcRequest(user));

		assertEquals(400, response.getStatus());
		assertEquals("JSON-RPC object is required", json(response).getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void returnsTheCallersTaskWithItsFinalTextAsAnArtifact() throws Exception {
		Map<String, Object> run = run("run-1", "COMPLETED");
		run.put("roomId", "room-1");
		run.put("finalText", "The answer");
		run.put("finalOutputMessageId", "msg-9");
		run.put("modelId", "engine-1");
		run.put("completedAt", Timestamp.from(Instant.parse("2026-03-04T05:06:07Z")));
		when(runs.getRun("run-1", insight)).thenReturn(run);

		Response response = new A2AResource().rpc(WORKSPACE,
				body("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"GetTask\",\"params\":{\"id\":\"run-1\"}}"),
				rpcRequest(user));

		assertEquals(200, response.getStatus());
		JsonObject rpc = json(response);
		assertEquals("req-1", rpc.get("id").getAsString());
		JsonObject task = rpc.getAsJsonObject("result");
		assertEquals("run-1", task.get("id").getAsString());
		assertEquals("room-1", task.get("contextId").getAsString());
		JsonObject status = task.getAsJsonObject("status");
		assertEquals("TASK_STATE_COMPLETED", status.get("state").getAsString());
		assertEquals("2026-03-04T05:06:07Z", status.get("timestamp").getAsString());
		JsonObject message = status.getAsJsonObject("message");
		assertEquals("msg-9", message.get("messageId").getAsString());
		assertEquals("ROLE_AGENT", message.get("role").getAsString());
		assertEquals("The answer", message.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
		JsonObject artifact = task.getAsJsonArray("artifacts").get(0).getAsJsonObject();
		assertEquals("run-1-final-output", artifact.get("artifactId").getAsString());
		assertEquals("The answer", artifact.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
		assertEquals(0, task.getAsJsonArray("history").size());
		assertEquals("COMPLETED", task.getAsJsonObject("metadata").get("semossStatus").getAsString());
		assertEquals("engine-1", task.getAsJsonObject("metadata").get("modelId").getAsString());
		verify(insight).setUser(user);
	}

	@Test
	void mapsEveryRunStatusToATaskState() throws Exception {
		Map<String, String> expected = Map.of("SUBMITTED", "TASK_STATE_SUBMITTED", "RUNNING", "TASK_STATE_WORKING",
				"INPUT_REQUIRED", "TASK_STATE_INPUT_REQUIRED", "COMPLETED", "TASK_STATE_COMPLETED", "CANCELLED",
				"TASK_STATE_CANCELED", "FAILED", "TASK_STATE_FAILED");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			when(runs.getRun("run-1", insight)).thenReturn(run("run-1", entry.getKey()));

			JsonObject task = json(new A2AResource().rpc(WORKSPACE,
					body("{\"id\":1,\"method\":\"GetTask\",\"params\":{\"taskId\":\"run-1\"}}"), rpcRequest(user)))
					.getAsJsonObject("result");

			assertEquals(entry.getValue(), task.getAsJsonObject("status").get("state").getAsString(), entry.getKey());
			assertEquals("run-1", task.get("contextId").getAsString(), "a run without a room uses its id");
			assertEquals(0, task.getAsJsonArray("artifacts").size());
		}
	}

	@Test
	void requiresATaskIdToGetOrCancelATask() throws Exception {
		Response get = new A2AResource().rpc(WORKSPACE, body("{\"id\":1,\"method\":\"GetTask\",\"params\":{}}"),
				rpcRequest(user));
		assertEquals(400, get.getStatus());
		JsonObject error = json(get).getAsJsonObject("error");
		assertEquals(-32602, error.get("code").getAsInt());
		assertEquals("task id is required", error.get("message").getAsString());

		Response cancel = new A2AResource().rpc(WORKSPACE, body("{\"id\":1,\"method\":\"CancelTask\"}"),
				rpcRequest(user));
		assertEquals(400, cancel.getStatus());
	}

	@Test
	void cancelsTheCallersTask() throws Exception {
		when(runs.stop("run-2", insight)).thenReturn(run("run-2", "CANCELLED"));

		JsonObject task = json(new A2AResource().rpc(WORKSPACE,
				body("{\"id\":1,\"method\":\"CancelTask\",\"params\":{\"runId\":\"run-2\"}}"), rpcRequest(user)))
				.getAsJsonObject("result");

		assertEquals("TASK_STATE_CANCELED", task.getAsJsonObject("status").get("state").getAsString());
		verify(runs).stop("run-2", insight);
	}

	@Test
	void sendsAMessageAsAnAgentRunAndWaitsForItsResult() throws Exception {
		when(runs.run(any(AgentRunRequest.class)))
				.thenReturn(new AgentRunHandle("run-3", "ctx-1", AgentRunStatus.SUBMITTED));
		Map<String, Object> done = run("run-3", "COMPLETED");
		done.put("finalText", "hi back");
		when(runs.waitForRun("run-3", insight, 0L)).thenReturn(done);

		JsonObject rpc = json(new A2AResource().rpc(WORKSPACE, body("{\"id\":2,\"method\":\"SendMessage\",\"params\":{"
				+ "\"contextId\":\"ctx-1\",\"message\":{\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"},"
				+ "{\"text\":\"there\"}],\"metadata\":{\"engineId\":\"engine-1\"}}}}"), rpcRequest(user)));

		JsonObject task = rpc.getAsJsonObject("result").getAsJsonObject("task");
		assertEquals("run-3", task.get("id").getAsString());
		assertEquals("TASK_STATE_COMPLETED", task.getAsJsonObject("status").get("state").getAsString());
		ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
		verify(runs).run(captor.capture());
		AgentRunRequest request = captor.getValue();
		assertEquals("ctx-1", request.getRoomId());
		assertEquals("hello\nthere", request.getInput());
		assertEquals("engine-1", request.getEngineIdFallback());
		assertEquals(WORKSPACE, request.getWorkspaceId());
		assertEquals("semoss", request.getHarnessType());
		assertEquals(insight, request.getInsight());
	}

	@Test
	void returnsTheSubmittedTaskRightAwayWhenAsked() throws Exception {
		when(runs.run(any(AgentRunRequest.class)))
				.thenReturn(new AgentRunHandle("run-4", "room", AgentRunStatus.SUBMITTED));
		when(runs.getRun("run-4", insight)).thenReturn(run("run-4", "SUBMITTED"));

		JsonObject task = json(new A2AResource().rpc(WORKSPACE, body("{\"id\":3,\"method\":\"SendMessage\",\"params\":{"
				+ "\"engineId\":\"engine-1\",\"configuration\":{\"returnImmediately\":true},"
				+ "\"message\":{\"parts\":[{\"text\":\"go\"}]}}}"), rpcRequest(user))).getAsJsonObject("result")
				.getAsJsonObject("task");

		assertEquals("TASK_STATE_SUBMITTED", task.getAsJsonObject("status").get("state").getAsString());
		verify(runs, never()).waitForRun(anyString(), any(), anyLong());
	}

	@Test
	void answersAStreamingSendOverPlainJsonWithTheSubmittedTask() throws Exception {
		when(runs.run(any(AgentRunRequest.class)))
				.thenReturn(new AgentRunHandle("run-5", "room", AgentRunStatus.SUBMITTED));
		when(runs.getRun("run-5", insight)).thenReturn(run("run-5", "RUNNING"));

		JsonObject task = json(new A2AResource().rpc(WORKSPACE, body("{\"id\":4,\"method\":\"SendStreamingMessage\","
				+ "\"params\":{\"modelId\":\"engine-1\",\"message\":{\"text\":\"go\"}}}"), rpcRequest(user)))
				.getAsJsonObject("result").getAsJsonObject("task");

		assertEquals("TASK_STATE_WORKING", task.getAsJsonObject("status").get("state").getAsString());
		verify(runs, never()).waitForRun(anyString(), any(), anyLong());
	}

	@Test
	void takesTheModelFromTheWorkspaceConfigThenTheServerDefault() throws Exception {
		when(runs.run(any(AgentRunRequest.class))).thenReturn(new AgentRunHandle("run-6", "r", AgentRunStatus.SUBMITTED));
		when(runs.waitForRun("run-6", insight, 0L)).thenReturn(run("run-6", "COMPLETED"));
		Map<String, Object> row = new HashMap<>();
		row.put("config_json", "{\"defaultModelId\":\"engine-1\"}");
		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(row);
		String send = "{\"id\":5,\"method\":\"SendMessage\",\"params\":{\"message\":{\"parts\":[{\"text\":\"q\"}]}}}";

		new A2AResource().rpc(WORKSPACE, body(send), rpcRequest(user));
		ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
		verify(runs).run(captor.capture());
		assertEquals("engine-1", captor.getValue().getEngineIdFallback());

		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(activeWorkspace());
		Response missing = new A2AResource().rpc(WORKSPACE, body(send), rpcRequest(user));
		assertEquals(400, missing.getStatus());
		assertTrue(json(missing).getAsJsonObject("error").get("message").getAsString()
				.startsWith("A2A request requires a model."));

		properties.put("A2A_DEFAULT_MODEL_ID", " engine-1 ");
		assertEquals(200, new A2AResource().rpc(WORKSPACE, body(send), rpcRequest(user)).getStatus());
	}

	@Test
	void rejectsAModelThatDoesNotLoad() throws Exception {
		Response response = new A2AResource().rpc(WORKSPACE, body("{\"id\":6,\"method\":\"SendMessage\",\"params\":{"
				+ "\"engine\":\"missing\",\"message\":{\"parts\":[{\"text\":\"q\"}]}}}"), rpcRequest(user));

		assertEquals(400, response.getStatus());
		assertEquals("Could not load model engine 'missing' for A2A workspace 'ws-1'",
				json(response).getAsJsonObject("error").get("message").getAsString());
		verify(runs, never()).run(any());
	}

	@Test
	void rejectsPartsThatAreNotText() throws Exception {
		Response file = new A2AResource().rpc(WORKSPACE, body("{\"id\":7,\"method\":\"SendMessage\",\"params\":{"
				+ "\"engineId\":\"engine-1\",\"message\":{\"parts\":[{\"kind\":\"file\",\"url\":\"x\"}]}}}"),
				rpcRequest(user));
		assertEquals(400, file.getStatus());
		JsonObject error = json(file).getAsJsonObject("error");
		assertEquals(-32010, error.get("code").getAsInt());
		assertEquals("A2A V1 supports text parts only", error.get("message").getAsString());

		Response raw = new A2AResource().rpc(WORKSPACE, body("{\"id\":8,\"method\":\"SendMessage\",\"params\":{"
				+ "\"engineId\":\"engine-1\",\"message\":{\"parts\":[{\"raw\":\"AAAA\"}]}}}"), rpcRequest(user));
		assertEquals(-32010, json(raw).getAsJsonObject("error").get("code").getAsInt());

		Response empty = new A2AResource().rpc(WORKSPACE, body("{\"id\":9,\"method\":\"SendMessage\",\"params\":{"
				+ "\"engineId\":\"engine-1\",\"message\":{\"parts\":[{\"text\":\"  \"}]}}}"), rpcRequest(user));
		assertEquals(400, empty.getStatus());
		assertEquals("A2A text message parts are required",
				json(empty).getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void reportsAnUnexpectedFailureAsA500ServerError() throws Exception {
		when(runs.getRun("run-7", insight)).thenThrow(new IllegalStateException("database down"));

		Response response = new A2AResource().rpc(WORKSPACE,
				body("{\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"run-7\"}}"), rpcRequest(user));

		assertEquals(500, response.getStatus());
		JsonObject error = json(response).getAsJsonObject("error");
		assertEquals(-32000, error.get("code").getAsInt());
		assertEquals("database down", error.get("message").getAsString());
	}

	@Test
	void refusesAWorkspaceTheCallerCannotUse() throws Exception {
		String get = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"GetTask\",\"params\":{\"id\":\"run-1\"}}";
		projects.when(() -> SecurityProjectUtils.userCanViewProject(user, WORKSPACE)).thenReturn(false);
		assertDenied(new A2AResource().rpc(WORKSPACE, body(get), rpcRequest(user)));

		projects.when(() -> SecurityProjectUtils.userCanViewProject(user, WORKSPACE)).thenReturn(true);
		Map<String, Object> inactive = activeWorkspace();
		inactive.put("is_active", false);
		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(inactive);
		assertDenied(new A2AResource().rpc(WORKSPACE, body(get), rpcRequest(user)));

		inferenceLogs.when(() -> ModelInferenceLogsUtils.getWorkspaceEntry(WORKSPACE)).thenReturn(null);
		assertDenied(new A2AResource().rpc(WORKSPACE, body(get), rpcRequest(user)));
		verify(runs, never()).getRun(anyString(), any());
	}

	private static void assertDenied(Response response) {
		assertEquals(403, response.getStatus());
		JsonObject rpc = json(response);
		assertEquals(3, rpc.get("id").getAsInt());
		assertEquals(-32000, rpc.getAsJsonObject("error").get("code").getAsInt());
		assertEquals("Workspace ws-1 does not exist or user does not have access to it",
				rpc.getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void hidesTheAgentCardOfAWorkspaceTheCallerCannotView() throws Exception {
		projects.when(() -> SecurityProjectUtils.userCanViewProject(user, WORKSPACE)).thenReturn(false);

		Response response = new A2AResource().agentCard(WORKSPACE, cardRequest("https", "example.com", 443, ""));

		assertEquals(404, response.getStatus());
		assertEquals("Workspace not found", json(response).get("error").getAsString());
	}

	@Test
	void endsAStreamOnceTheTaskFinishesOrWaitsOnTheClient() {
		assertFalse(A2AResource.endsTheStream(task(TaskState.TASK_STATE_SUBMITTED)));
		assertFalse(A2AResource.endsTheStream(task(TaskState.TASK_STATE_WORKING)));
		assertTrue(A2AResource.endsTheStream(task(TaskState.TASK_STATE_INPUT_REQUIRED)));
		assertTrue(A2AResource.endsTheStream(task(TaskState.TASK_STATE_AUTH_REQUIRED)));
		assertTrue(A2AResource.endsTheStream(task(TaskState.TASK_STATE_COMPLETED)));
		assertTrue(A2AResource.endsTheStream(task(TaskState.TASK_STATE_CANCELED)));
		assertTrue(A2AResource.endsTheStream(task(TaskState.TASK_STATE_FAILED)));
	}

	private static Task task(TaskState state) {
		return Task.builder().id("run-1").contextId("room-1").status(new TaskStatus(state, null, null)).build();
	}

	@Test
	void opensTheInsightAgainAfterItsCacheEntryIsCleared() throws Exception {
		when(runs.getRun("run-1", insight)).thenReturn(run("run-1", "RUNNING"));
		HttpServletRequest request = rpcRequest(user);
		String get = "{\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"run-1\"}}";

		new A2AResource().rpc(WORKSPACE, body(get), request);
		new A2AResource().rpc(WORKSPACE, body(get), request);
		verify(insight, times(1)).setUser(user);

		A2AResource.clearInsight(request.getSession().getId());
		new A2AResource().rpc(WORKSPACE, body(get), request);
		verify(insight, times(2)).setUser(user);
	}

	@Test
	void clearsTheThreadStoreAfterEachCall() throws Exception {
		when(runs.getRun("run-1", insight)).thenAnswer(invocation -> {
			assertEquals(user, ThreadStore.getUser());
			assertEquals(insightId, ThreadStore.getInsightId());
			return run("run-1", "RUNNING");
		});

		new A2AResource().rpc(WORKSPACE, body("{\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"run-1\"}}"),
				rpcRequest(user));

		assertNull(ThreadStore.getUser());
		assertNull(ThreadStore.getInsightId());
	}

	private Map<String, Object> run(String runId, String status) {
		Map<String, Object> run = new HashMap<>();
		run.put("runId", runId);
		run.put("status", status);
		return run;
	}

	private HttpServletRequest rpcRequest(User sessionUser) {
		String sessionId = "a2a-session-" + UUID.randomUUID();
		Map<String, Object> attributes = new HashMap<>();
		if (sessionUser != null) {
			attributes.put(Constants.SESSION_USER, sessionUser);
		}
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
		doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1))).when(session)
				.setAttribute(anyString(), any());
		when(store.getInsightIDsForSession(sessionId)).thenReturn(new HashSet<>(Set.of(insightId)));
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession()).thenReturn(session);
		return request;
	}

	private static Map<String, Object> activeWorkspace() {
		Map<String, Object> row = new HashMap<>();
		row.put("is_active", true);
		return row;
	}

	private HttpServletRequest cardRequest(String scheme, String host, int port, String contextPath) {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		when(request.getScheme()).thenReturn(scheme);
		when(request.getServerName()).thenReturn(host);
		when(request.getServerPort()).thenReturn(port);
		when(request.getContextPath()).thenReturn(contextPath);
		return request;
	}

	private static InputStream body(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

	private static JsonObject json(Response response) {
		return JsonParser.parseString((String) response.getEntity()).getAsJsonObject();
	}

	private static List<String> strings(JsonArray array) {
		List<String> values = new ArrayList<>();
		array.forEach(element -> values.add(element.getAsString()));
		return values;
	}
}
