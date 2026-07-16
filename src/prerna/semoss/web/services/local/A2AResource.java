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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import prerna.auth.User;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.reactor.agent.AgentRunContext;
import prerna.reactor.agent.run.AgentRuntimeManager;
import prerna.reactor.agent.run.RunAgentRequest;
import prerna.reactor.agent.run.RunAgentResult;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Singleton
@Path("/ext/a2a/workspace/{workspaceId}")
@PermitAll
public class A2AResource {

	private static final Logger logger = LogManager.getLogger(A2AResource.class);
	private static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(OffsetDateTime.class, (JsonSerializer<OffsetDateTime>) (src, typeOfSrc, context) ->
					new JsonPrimitive(src.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
			.create();
	private static final Map<String, Insight> INSIGHT_MAP = new java.util.concurrent.ConcurrentHashMap<>();
	private static final String A2A_PROTOCOL_VERSION = "1.0";
	private static final String A2A_TRANSPORT_JSONRPC = "JSONRPC";

	private static final ExecutorService SSE_EXECUTOR;
	static {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 100, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "a2a-sse-worker");
					t.setDaemon(true);
					return t;
				});
		executor.allowCoreThreadTimeOut(true);
		SSE_EXECUTOR = executor;
	}

	@GET
	@Path("/.well-known/agent-card.json")
	@Produces(MediaType.APPLICATION_JSON)
	public Response agentCard(@PathParam("workspaceId") String workspaceId, @Context HttpServletRequest request) {
		if (!isEnabled()) {
			return jsonResponse(Response.Status.NOT_FOUND, errorBody("A2A is disabled"));
		}
		Map<String, Object> row = ModelInferenceLogsUtils.getWorkspaceEntry(workspaceId);
		String name = stringValue(row, "name", "SEMOSS Workspace Agent");
		String description = stringValue(row, "description", "SEMOSS workspace-backed agent");

		return jsonResponse(buildAgentCard(name, description, endpointUrl(request, workspaceId),
				workspaceSkills(row), securitySchemes(), securityRequirements()));
	}

	@POST
	@Path("/rpc")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response rpc(@PathParam("workspaceId") String workspaceId, InputStream is,
			@Context HttpServletRequest request) {
		if (!isEnabled()) {
			return jsonResponse(Response.Status.NOT_FOUND, errorBody("A2A is disabled"));
		}
		try {
			A2ARequestContext requestContext = initRequestContext(request);
			JsonObject rpc = parseRpc(is);
			Object id = idValue(rpc);
			String method = text(rpc, "method");
			Object result;
			if (isSendMessageMethod(method)) {
				result = sendMessageResponse(handleSend(workspaceId, rpc, requestContext, true));
			} else if (isGetTaskMethod(method)) {
				result = taskPayload(handleGet(rpc, requestContext));
			} else if (isCancelTaskMethod(method)) {
				result = taskPayload(handleCancel(rpc, requestContext));
			} else if (isStreamingMessageMethod(method) || isSubscribeTaskMethod(method)) {
				result = handleStreamAsJson(workspaceId, rpc, requestContext);
			} else {
				return jsonResponse(jsonRpcError(id, -32601, "Unsupported A2A method: " + method));
			}
			return jsonResponse(jsonRpcResult(id, result));
		} catch (UnsupportedOperationException e) {
			return jsonResponse(Response.Status.BAD_REQUEST, jsonRpcError(null, -32010, e.getMessage()));
		} catch (IllegalArgumentException e) {
			return jsonResponse(Response.Status.BAD_REQUEST, jsonRpcError(null, -32602, e.getMessage()));
		} catch (SecurityException e) {
			return jsonResponse(Response.Status.UNAUTHORIZED, jsonRpcError(null, -32001, e.getMessage()));
		} catch (Exception e) {
			logger.warn("A2A rpc failed: {}", e.getMessage(), e);
			return jsonResponse(Response.Status.INTERNAL_SERVER_ERROR, jsonRpcError(null, -32000, e.getMessage()));
		} finally {
			ThreadStore.remove();
		}
	}

	@POST
	@Path("/rpc")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void rpcSse(@PathParam("workspaceId") String workspaceId, InputStream is,
			@Context SseEventSink eventSink, @Context Sse sse, @Context HttpServletRequest request) {
		A2ARequestContext requestContext = initRequestContext(request);
		ThreadStore.remove();
		SSE_EXECUTOR.submit(() -> {
			try {
				if (!isEnabled()) {
					sendSse(eventSink, sse, "error", jsonRpcError(null, -32000, "A2A is disabled"));
					return;
				}
				JsonObject rpc = parseRpc(is);
				Object responseId = idValue(rpc);
				String method = text(rpc, "method");
				String runId;
				if (isStreamingMessageMethod(method)) {
					Task task = handleSend(workspaceId, rpc, requestContext, false);
					runId = taskId(task);
					sendSse(eventSink, sse, "task", jsonRpcResult(responseId, streamTask(task)));
				} else if (isSubscribeTaskMethod(method)) {
					runId = extractTaskId(params(rpc));
					if (runId == null) {
						throw new IllegalArgumentException("task id is required");
					}
					Task task = handleGet(rpc, requestContext);
					sendSse(eventSink, sse, "task", jsonRpcResult(responseId, streamTask(task)));
					if (isTerminalTask(task)) {
						return;
					}
				} else {
					throw new UnsupportedOperationException(
							"SSE supports SendStreamingMessage and SubscribeToTask only");
				}
				streamRun(runId, eventSink, sse, requestContext, responseId);
			} catch (Exception e) {
				logger.warn("A2A SSE failed: {}", e.getMessage(), e);
				sendSse(eventSink, sse, "error", jsonRpcError(null, -32000, e.getMessage()));
			} finally {
				ThreadStore.remove();
				if (eventSink != null && !eventSink.isClosed()) {
					eventSink.close();
				}
			}
		});
	}

	private Task handleSend(String workspaceId, JsonObject rpc, A2ARequestContext requestContext,
			boolean honorReturnImmediately) throws InterruptedException {
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		JsonObject params = params(rpc);
		JsonObject message = object(params, "message");
		if (message == null) {
			message = params;
		}
		String input = extractTextInput(message);
		String roomId = firstNonBlank(text(params, "contextId"), text(params, "sessionId"), text(message, "contextId"),
				text(message, "sessionId"), GUID.v7().toUUID().toString());
		String engineId = firstNonBlank(text(params, "engine"), text(params, "engineId"), text(params, "modelId"),
				metadataValue(params, "engine"), metadataValue(params, "engineId"), metadataValue(params, "modelId"),
				metadataValue(message, "engine"), metadataValue(message, "engineId"), metadataValue(message, "modelId"),
				workspaceModelId(workspaceId),
				StringUtils.trimToNull(Utility.getDIHelperProperty("A2A_DEFAULT_MODEL_ID")));
		if (engineId == null) {
			throw new IllegalArgumentException("A2A request requires a model. Pass metadata.engineId/modelId, "
					+ "set WORKSPACE.CONFIG_JSON.modelId, or configure A2A_DEFAULT_MODEL_ID.");
		}
		if (Utility.getModel(engineId) == null) {
			throw new IllegalArgumentException("Could not load model engine '" + engineId + "' for A2A workspace '"
					+ workspaceId + "'");
		}

		Map<String, Object> paramMap = new HashMap<>();
		RunAgentRequest runRequest = new RunAgentRequest(roomId, input, engineId, "semoss", workspaceId,
					AgentRunContext.DEFAULT_MAX_TURNS, AgentRunContext.DEFAULT_MAX_REFLECTIONS, paramMap,
					new HashMap<>(), insight);
		RunAgentResult result = AgentRuntimeManager.get().run(runRequest);
		if (honorReturnImmediately && !returnImmediately(params)) {
			return taskFromRun(AgentRuntimeManager.get().waitForRun(result.getRunId(), insight, 0L));
		}
		return taskFromRun(AgentRuntimeManager.get().getRun(result.getRunId(), insight));
	}

	private Task handleGet(JsonObject rpc, A2ARequestContext requestContext) {
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		String runId = extractTaskId(params(rpc));
		if (runId == null) {
			throw new IllegalArgumentException("task id is required");
		}
		return taskFromRun(AgentRuntimeManager.get().getRun(runId, insight));
	}

	private Task handleCancel(JsonObject rpc, A2ARequestContext requestContext) {
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		String runId = extractTaskId(params(rpc));
		if (runId == null) {
			throw new IllegalArgumentException("task id is required");
		}
		return taskFromRun(AgentRuntimeManager.get().stop(runId, insight));
	}

	private Map<String, Object> handleStreamAsJson(String workspaceId, JsonObject rpc,
			A2ARequestContext requestContext) throws InterruptedException {
		if (isStreamingMessageMethod(text(rpc, "method"))) {
			return streamTask(handleSend(workspaceId, rpc, requestContext, false));
		}
		return streamTask(handleGet(rpc, requestContext));
	}

	private void streamRun(String runId, SseEventSink eventSink, Sse sse, A2ARequestContext requestContext,
			Object responseId) throws Exception {
		// Poll durable AGENT_RUN state and emit an SSE status update whenever the task
		// state changes, until the run reaches a terminal state or the client disconnects.
		// Replaces the removed in-memory AgentRunEventBus subscribe/replay path.
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		String lastState = null;
		while (eventSink != null && !eventSink.isClosed()) {
			Task task = taskFromRun(AgentRuntimeManager.get().getRun(runId, insight));
			String state = task != null && task.status() != null && task.status().state() != null
					? task.status().state().name() : null;
			if (!Objects.equals(state, lastState)) {
				lastState = state;
				Map<String, Object> payload = statusUpdateFromTask(task, null);
				sendSse(eventSink, sse, "status", jsonRpcResult(responseId, streamStatusUpdate(payload)));
			}
			if (isTerminalTask(task)) {
				return;
			}
			Thread.sleep(1000L);
		}
	}

	private static void sendSse(SseEventSink eventSink, Sse sse, String event, Object data) {
		if (eventSink == null || eventSink.isClosed()) {
			return;
		}
		eventSink.send(sse.newEventBuilder().name(event).mediaType(MediaType.APPLICATION_JSON_TYPE)
				.data(String.class, GSON.toJson(data)).build());
	}

	private static Response jsonResponse(Object body) {
		return Response.ok(GSON.toJson(body), MediaType.APPLICATION_JSON).build();
	}

	private static Response jsonResponse(Response.Status status, Object body) {
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(GSON.toJson(body)).build();
	}

	private A2ARequestContext initRequestContext(HttpServletRequest request) {
		WebUtility.loggingContext(request);
		HttpSession session = request.getSession();
		String authorization = request.getHeader("Authorization");
		addAuthKeyToSession(session, authorization);
		Insight insight = getInsight(session, authorization);
		return new A2ARequestContext(
				insight,
				session.getId(),
				ThreadStore.getRouteId(),
				ThreadStore.getLocalHostname(),
				ThreadStore.getLocalProtocol(),
				ThreadStore.getLocalPort());
	}

	private static void seedThreadStore(A2ARequestContext requestContext) {
		if (requestContext == null) {
			return;
		}
		Insight insight = requestContext.insight;
		if (insight != null) {
			ThreadStore.setUser(insight.getUser());
			ThreadStore.setInsightId(insight.getInsightId());
		}
		ThreadStore.setSessionId(requestContext.sessionId);
		ThreadStore.setRouteId(requestContext.routeId);
		ThreadStore.setLocalHostname(requestContext.localHostname);
		ThreadStore.setLocalProtocol(requestContext.localProtocol);
		ThreadStore.setLocalPort(requestContext.localPort);
	}

	private static void addAuthKeyToSession(HttpSession session, String authorization) {
		if (authorization != null) {
			synchronized (session) {
				@SuppressWarnings("unchecked")
				Set<String> mcpKeys = (Set<String>) session.getAttribute(MCPResource.MCP_AUTH_KEY);
				if (mcpKeys == null) {
					mcpKeys = new HashSet<>();
					session.setAttribute(MCPResource.MCP_AUTH_KEY, mcpKeys);
				}
				mcpKeys.add(authorization);
			}
		}
	}

	private Insight getInsight(HttpSession session, String authorization) {
		String key = authorization != null ? authorization : session.getId();
		Insight existing = INSIGHT_MAP.get(key);
		if (existing != null && InsightStore.getInstance().get(existing.getInsightId()) != null) {
			return existing;
		}
		return INSIGHT_MAP.compute(key, (k, e) -> {
			if (e != null && InsightStore.getInstance().get(e.getInsightId()) != null) {
				return e;
			}
			return initSession(session);
		});
	}

	private Insight initSession(HttpSession session) {
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			user = ThreadStore.getUser();
		}
		if (user == null) {
			throw new SecurityException("A2A requires an authenticated SEMOSS user");
		}
		String insightId = (String) session.getAttribute(Constants.INSIGHT);
		String sessionId = session.getId();
		Insight insight;
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(sessionId, insightId);
			} else {
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
			if (user != null) {
				user.setZoneId(ZoneId.of(Utility.getApplicationZoneId()));
			}
			session.setAttribute(Constants.INSIGHT, insightId);
		} else {
			insight = InsightStore.getInstance().get(insightId);
		}
		insight.setUser(user);
		return insight;
	}

	private static JsonObject parseRpc(InputStream is) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				throw new IllegalArgumentException("JSON-RPC object is required");
			}
			return root.getAsJsonObject();
		}
	}

	private static JsonObject params(JsonObject rpc) {
		JsonObject params = object(rpc, "params");
		return params == null ? new JsonObject() : params;
	}

	private static String extractTextInput(JsonObject message) {
		JsonArray parts = array(message, "parts");
		if (parts == null || parts.size() == 0) {
			String text = firstNonBlank(text(message, "text"), text(message, "content"));
			if (text == null) {
				throw new IllegalArgumentException("A2A text message parts are required");
			}
			return text;
		}
		StringBuilder builder = new StringBuilder();
		for (JsonElement element : parts) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject part = element.getAsJsonObject();
			String kind = firstNonBlank(text(part, "kind"), text(part, "type"));
			if (kind != null && !"text".equalsIgnoreCase(kind) && !"text/plain".equalsIgnoreCase(kind)) {
				throw new UnsupportedOperationException("A2A V1 supports text parts only");
			}
			String text = firstNonBlank(text(part, "text"), text(part, "content"));
			if (text == null && (part.has("raw") || part.has("url") || part.has("data"))) {
				throw new UnsupportedOperationException("A2A V1 supports text parts only");
			}
			if (text != null) {
				if (builder.length() > 0) {
					builder.append("\n");
				}
				builder.append(text);
			}
		}
		String input = builder.toString().trim();
		if (input.isEmpty()) {
			throw new IllegalArgumentException("A2A text message parts are required");
		}
		return input;
	}

	private static String extractTaskId(JsonObject params) {
		return firstNonBlank(text(params, "id"), text(params, "taskId"), text(params, "runId"));
	}

	private static boolean isSendMessageMethod(String method) {
		return "SendMessage".equals(method);
	}

	private static boolean isStreamingMessageMethod(String method) {
		return "SendStreamingMessage".equals(method);
	}

	private static boolean isGetTaskMethod(String method) {
		return "GetTask".equals(method);
	}

	private static boolean isCancelTaskMethod(String method) {
		return "CancelTask".equals(method);
	}

	private static boolean isSubscribeTaskMethod(String method) {
		return "SubscribeToTask".equals(method);
	}

	private static boolean returnImmediately(JsonObject params) {
		JsonObject configuration = object(params, "configuration");
		JsonElement value = configuration == null ? null : configuration.get("returnImmediately");
		return value != null && value.isJsonPrimitive() && value.getAsBoolean();
	}

	private static Map<String, Object> sendMessageResponse(Task task) {
		return oneOf("task", taskPayload(task));
	}

	private static Map<String, Object> streamTask(Task task) {
		return oneOf("task", taskPayload(task));
	}

	private static Map<String, Object> streamStatusUpdate(Map<String, Object> statusUpdate) {
		return oneOf("statusUpdate", statusUpdate);
	}

	private static Task taskFromRun(Map<String, Object> run) {
		String runId = stringValue(run.get("runId"));
		if (runId == null) {
			runId = stringValue(run.get("id"));
		}
		String roomId = stringValue(run.get("roomId"));
		if (roomId == null) {
			roomId = runId;
		}
		String finalText = stringValue(run.get("finalText"));

		Message statusMessage = null;
		if (finalText != null) {
			statusMessage = Message.builder()
					.messageId(firstNonBlank(stringValue(run.get("finalOutputMessageId")), runId + "-final"))
					.contextId(roomId)
					.taskId(runId)
					.role(Message.Role.ROLE_AGENT)
					.parts(new TextPart(finalText, null))
					.build();
		}

		TaskStatus status = new TaskStatus(toTaskState(stringValue(run.get("status"))), statusMessage,
				toOffsetDateTime(firstNonNull(run.get("completedAt"), run.get("startedAt"), run.get("dateCreated"))));

		return Task.builder()
				.id(runId)
				.contextId(roomId)
				.status(status)
				.artifacts(finalText == null ? List.of() : List.of(Artifact.builder()
						.artifactId(runId + "-final-output")
						.name("final-output")
						.description("Final agent response")
						.parts(new TextPart(finalText, null))
						.build()))
				.metadata(metadataFromRun(run))
				.build();
	}

	private static Map<String, Object> statusUpdateFromTask(Task task, Map<String, Object> event) {
		Map<String, Object> update = new HashMap<>();
		update.put("taskId", task.id());
		update.put("contextId", task.contextId());
		update.put("status", statusPayload(task.status()));

		Map<String, Object> metadata = new HashMap<>();
		Map<?, ?> taskMetadata = task.metadata();
		if (taskMetadata != null) {
			copyIfPresent(taskMetadata, metadata, "semossStatus");
			copyIfPresent(taskMetadata, metadata, "workspaceId");
			copyIfPresent(taskMetadata, metadata, "modelId");
			copyIfPresent(taskMetadata, metadata, "jobId");
			copyIfPresent(taskMetadata, metadata, "inputMessageId");
			copyIfPresent(taskMetadata, metadata, "finalOutputMessageId");
			copyIfPresent(taskMetadata, metadata, "errorMessage");
		}
		if (event != null) {
			copyIfPresent(event, metadata, "sequence");
			copyIfPresent(event, metadata, "event");
			copyIfPresent(event, metadata, "timestamp");
		}
		if (!metadata.isEmpty()) {
			update.put("metadata", metadata);
		}
		return update;
	}

	private static Map<String, Object> taskPayload(Task task) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("id", task.id());
		payload.put("contextId", task.contextId());
		payload.put("status", statusPayload(task.status()));
		payload.put("artifacts", artifactsPayload(task.artifacts()));
		payload.put("history", messagesPayload(task.history()));
		if (task.metadata() != null && !task.metadata().isEmpty()) {
			payload.put("metadata", task.metadata());
		}
		return payload;
	}

	private static Map<String, Object> statusPayload(TaskStatus status) {
		Map<String, Object> payload = new HashMap<>();
		if (status == null) {
			return payload;
		}
		payload.put("state", status.state());
		if (status.message() != null) {
			payload.put("message", messagePayload(status.message()));
		}
		if (status.timestamp() != null) {
			payload.put("timestamp", status.timestamp().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		}
		return payload;
	}

	private static List<Object> messagesPayload(List<Message> messages) {
		List<Object> payload = new ArrayList<>();
		if (messages != null) {
			for (Message message : messages) {
				payload.add(messagePayload(message));
			}
		}
		return payload;
	}

	private static Map<String, Object> messagePayload(Message message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("role", message.role());
		payload.put("parts", partsPayload(message.parts()));
		putIfPresent(payload, "messageId", message.messageId());
		putIfPresent(payload, "contextId", message.contextId());
		putIfPresent(payload, "taskId", message.taskId());
		putIfPresent(payload, "referenceTaskIds", message.referenceTaskIds());
		putIfPresent(payload, "metadata", message.metadata());
		putIfPresent(payload, "extensions", message.extensions());
		return payload;
	}

	private static List<Object> artifactsPayload(List<Artifact> artifacts) {
		List<Object> payload = new ArrayList<>();
		if (artifacts != null) {
			for (Artifact artifact : artifacts) {
				Map<String, Object> artifactPayload = new HashMap<>();
				putIfPresent(artifactPayload, "artifactId", artifact.artifactId());
				putIfPresent(artifactPayload, "name", artifact.name());
				putIfPresent(artifactPayload, "description", artifact.description());
				artifactPayload.put("parts", partsPayload(artifact.parts()));
				putIfPresent(artifactPayload, "metadata", artifact.metadata());
				putIfPresent(artifactPayload, "extensions", artifact.extensions());
				payload.add(artifactPayload);
			}
		}
		return payload;
	}

	private static List<Object> partsPayload(List<Part<?>> parts) {
		List<Object> payload = new ArrayList<>();
		if (parts != null) {
			for (Part<?> part : parts) {
				payload.add(partPayload(part));
			}
		}
		return payload;
	}

	private static Map<String, Object> partPayload(Part<?> part) {
		Map<String, Object> payload = new HashMap<>();
		if (part instanceof TextPart) {
			TextPart textPart = (TextPart) part;
			payload.put(TextPart.TEXT, textPart.text());
			putIfPresent(payload, "metadata", textPart.metadata());
		} else {
			String text = invokeStringAccessor(part, TextPart.TEXT);
			if (text != null) {
				payload.put(TextPart.TEXT, text);
			} else {
				JsonElement json = GSON.toJsonTree(part);
				if (json != null && json.isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
						payload.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
					}
				}
			}
		}
		return payload;
	}

	private static String invokeStringAccessor(Object object, String accessor) {
		if (object == null || accessor == null) {
			return null;
		}
		try {
			Object value = object.getClass().getMethod(accessor).invoke(object);
			return stringValue(value);
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, Object> oneOf(String key, Object value) {
		Map<String, Object> response = new HashMap<>();
		response.put(key, value);
		return response;
	}

	private static String taskId(Task task) {
		return task == null ? null : task.id();
	}

	private static boolean isTerminalTask(Task task) {
		return task != null && task.status() != null && task.status().state() != null && task.status().state().isFinal();
	}

	private static Map<String, Object> metadataFromRun(Map<String, Object> run) {
		Map<String, Object> metadata = new HashMap<>();
		putIfPresent(metadata, "semossStatus", run.get("status"));
		putIfPresent(metadata, "workspaceId", run.get("workspaceId"));
		putIfPresent(metadata, "modelId", run.get("modelId"));
		putIfPresent(metadata, "jobId", run.get("jobId"));
		putIfPresent(metadata, "inputMessageId", run.get("inputMessageId"));
		putIfPresent(metadata, "finalOutputMessageId", run.get("finalOutputMessageId"));
		putIfPresent(metadata, "errorMessage", run.get("errorMessage"));
		return metadata;
	}

	private static TaskState toTaskState(String status) {
		if ("SUBMITTED".equals(status)) {
			return TaskState.TASK_STATE_SUBMITTED;
		}
		if ("RUNNING".equals(status)) {
			return TaskState.TASK_STATE_WORKING;
		}
		if ("INPUT_REQUIRED".equals(status)) {
			return TaskState.TASK_STATE_INPUT_REQUIRED;
		}
		if ("COMPLETED".equals(status)) {
			return TaskState.TASK_STATE_COMPLETED;
		}
		if ("CANCELLED".equals(status)) {
			return TaskState.TASK_STATE_CANCELED;
		}
		if ("FAILED".equals(status)) {
			return TaskState.TASK_STATE_FAILED;
		}
		return TaskState.UNRECOGNIZED;
	}

	private static OffsetDateTime toOffsetDateTime(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Timestamp) {
			return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
		}
		String raw = StringUtils.trimToNull(String.valueOf(value));
		if (raw == null || "null".equals(raw)) {
			return null;
		}
		try {
			return Timestamp.valueOf(raw).toInstant().atOffset(ZoneOffset.UTC);
		} catch (Exception ignored) {
			// fall through
		}
		try {
			return Instant.parse(raw).atOffset(ZoneOffset.UTC);
		} catch (Exception ignored) {
			// fall through
		}
		try {
			return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static Map<String, Object> jsonRpcResult(Object id, Object result) {
		Map<String, Object> response = new HashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("result", result);
		return response;
	}

	private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("code", code);
		error.put("message", message);
		Map<String, Object> response = new HashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("error", error);
		return response;
	}

	private static Map<String, Object> errorBody(String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("error", message);
		return error;
	}

	private static Object idValue(JsonObject rpc) {
		JsonElement id = rpc.get("id");
		return id == null || id.isJsonNull() ? null : GSON.fromJson(id, Object.class);
	}

	private static String workspaceModelId(String workspaceId) {
		JsonObject cfg = workspaceConfig(workspaceId);
		if (cfg == null) {
			return null;
		}
		return firstNonBlank(text(cfg, "modelId"), text(cfg, "model_id"), text(cfg, "defaultModelId"),
				text(cfg, "default_model_id"), text(cfg, "engine"), text(cfg, "engineId"), text(cfg, "defaultEngineId"),
				text(cfg, "default_engine_id"));
	}

	private static AgentCard buildAgentCard(String name, String description, String endpointUrl,
			List<AgentSkill> skills, Map<String, SecurityScheme> securitySchemes,
			List<SecurityRequirement> securityRequirements) {
		AgentInterface jsonRpcInterface = new AgentInterface(A2A_TRANSPORT_JSONRPC, endpointUrl);
		return AgentCard.builder()
				.name(name)
				.description(description)
				.version(A2A_PROTOCOL_VERSION)
				.url(endpointUrl)
				.preferredTransport(A2A_TRANSPORT_JSONRPC)
				.supportedInterfaces(List.of(jsonRpcInterface))
				.defaultInputModes(List.of("text/plain"))
				.defaultOutputModes(List.of("text/plain"))
				.capabilities(AgentCapabilities.builder()
						.streaming(true)
						.pushNotifications(false)
						.build())
				.skills(skills)
				.securitySchemes(securitySchemes)
				.securityRequirements(securityRequirements)
				.build();
	}

	private static AgentSkill agentSkill(String id, String name, String description, List<Object> tags) {
		AgentSkill.Builder builder = AgentSkill.builder()
				.id(id)
				.name(name)
				.description(description);
		if (tags != null && !tags.isEmpty()) {
			List<String> stringTags = new ArrayList<>();
			for (Object tag : tags) {
				String value = tag == null ? null : StringUtils.trimToNull(String.valueOf(tag));
				if (value != null) {
					stringTags.add(value);
				}
			}
			if (!stringTags.isEmpty()) {
				builder.tags(stringTags);
			}
		}
		return builder.build();
	}

	private static List<AgentSkill> workspaceSkills(Map<String, Object> row) {
		List<AgentSkill> skills = new ArrayList<>();
		JsonObject cfg = configFromRow(row);
		JsonArray skillRefs = cfg == null ? null : array(cfg, "skills");
		if (skillRefs == null) {
			skills.add(defaultSkill());
			return skills;
		}
		for (JsonElement element : skillRefs) {
			AgentSkill skill = null;
			if (element.isJsonObject()) {
				JsonObject obj = element.getAsJsonObject();
				String id = firstNonBlank(text(obj, "id"), text(obj, "skillId"), text(obj, "name"));
				String name = firstNonBlank(text(obj, "name"), text(obj, "id"), text(obj, "skillId"));
				if (id != null && name != null) {
					skill = agentSkill(id, name, firstNonBlank(text(obj, "description"), "Workspace skill"),
							tagsForSkill(obj));
				}
			} else if (element.isJsonPrimitive()) {
				String value = element.getAsString();
				skill = agentSkill(value, value, "Workspace skill", listOf("semoss", "workspace"));
			}
			if (skill != null) {
				skills.add(skill);
			}
		}
		if (skills.isEmpty()) {
			skills.add(defaultSkill());
		}
		return skills;
	}

	private static Map<String, SecurityScheme> securitySchemes() {
		Map<String, SecurityScheme> schemes = new HashMap<>();
		schemes.put("semossBearer", HTTPAuthSecurityScheme.builder()
				.scheme("Bearer")
				.description("SEMOSS access key or session bearer token.")
				.build());
		return schemes;
	}

	private static List<SecurityRequirement> securityRequirements() {
		return List.of(SecurityRequirement.builder()
				.scheme("semossBearer", List.of())
				.build());
	}

	private static AgentSkill defaultSkill() {
		return agentSkill("semoss-agent", "SEMOSS Agent", "Run the SEMOSS workspace agent with text prompts.",
				listOf("semoss", "workspace", "agent"));
	}

	private static List<Object> tagsForSkill(JsonObject obj) {
		JsonArray tags = array(obj, "tags");
		if (tags == null || tags.size() == 0) {
			return listOf("semoss", "workspace");
		}
		List<Object> values = new ArrayList<>();
		for (JsonElement element : tags) {
			if (element != null && element.isJsonPrimitive()) {
				String tag = StringUtils.trimToNull(element.getAsString());
				if (tag != null) {
					values.add(tag);
				}
			}
		}
		return values.isEmpty() ? listOf("semoss", "workspace") : values;
	}

	private static JsonObject workspaceConfig(String workspaceId) {
		return configFromRow(ModelInferenceLogsUtils.getWorkspaceEntry(workspaceId));
	}

	private static JsonObject configFromRow(Map<String, Object> row) {
		if (row == null || row.get("config_json") == null) {
			return null;
		}
		String raw = String.valueOf(row.get("config_json")).trim();
		if (raw.isEmpty()) {
			return null;
		}
		try {
			return JsonParser.parseString(raw).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	private static String endpointUrl(HttpServletRequest request, String workspaceId) {
		String baseOverride = StringUtils.trimToNull(Utility.getDIHelperProperty("A2A_AGENT_CARD_BASE_URL"));
		if (baseOverride != null) {
			return trimTrailingSlash(baseOverride) + "/api/ext/a2a/workspace/" + workspaceId + "/rpc";
		}
		StringBuilder base = new StringBuilder();
		base.append(request.getScheme()).append("://").append(request.getServerName());
		int port = request.getServerPort();
		if (port > 0 && port != 80 && port != 443) {
			base.append(":").append(port);
		}
		base.append(request.getContextPath());
		base.append("/api/ext/a2a/workspace/").append(workspaceId).append("/rpc");
		return base.toString();
	}

	private static boolean isEnabled() {
		return Boolean.parseBoolean(String.valueOf(Utility.getDIHelperProperty("A2A_ENABLED")));
	}

	private static JsonObject object(JsonObject object, String key) {
		JsonElement element = object == null ? null : object.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject object, String key) {
		JsonElement element = object == null ? null : object.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String metadataValue(JsonObject object, String key) {
		JsonObject metadata = object(object, "metadata");
		return text(metadata, key);
	}

	private static String text(JsonObject object, String key) {
		JsonElement element = object == null ? null : object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			String value = element.getAsString();
			return StringUtils.trimToNull(value);
		}
		return null;
	}

	private static String stringValue(Map<String, Object> map, String key, String defaultValue) {
		if (map == null || map.get(key) == null) {
			return defaultValue;
		}
		return firstNonBlank(String.valueOf(map.get(key)), defaultValue);
	}

	private static String stringValue(Object value) {
		if (value == null) {
			return null;
		}
		return StringUtils.trimToNull(String.valueOf(value));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private static Object firstNonNull(Object... values) {
		if (values == null) {
			return null;
		}
		for (Object value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static List<Object> listOf(Object... values) {
		List<Object> list = new ArrayList<>();
		if (values != null) {
			for (Object value : values) {
				list.add(value);
			}
		}
		return list;
	}

	private static String trimTrailingSlash(String value) {
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
		Object value = source.get(key);
		if (value != null) {
			target.put(key, value);
		}
	}

	private static void putIfPresent(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private static final class A2ARequestContext {
		private final Insight insight;
		private final String sessionId;
		private final String routeId;
		private final String localHostname;
		private final String localProtocol;
		private final Integer localPort;

		private A2ARequestContext(Insight insight, String sessionId, String routeId, String localHostname,
				String localProtocol, Integer localPort) {
			this.insight = insight;
			this.sessionId = sessionId;
			this.routeId = routeId;
			this.localHostname = localHostname;
			this.localProtocol = localProtocol;
			this.localPort = localPort;
		}
	}
}
