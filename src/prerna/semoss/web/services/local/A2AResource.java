package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.auth.User;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.reactor.agent.AgentRunContext;
import prerna.reactor.agent.run.AgentRunEventBus;
import prerna.reactor.agent.run.AgentRunEventBus.AgentRunEvent;
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
	private static final Gson GSON = new Gson();
	private static final Map<String, Insight> INSIGHT_MAP = new java.util.concurrent.ConcurrentHashMap<>();

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

		Map<String, Object> card = new HashMap<>();
		card.put("name", name);
		card.put("description", description);
		card.put("version", "1.0");
		card.put("supportedInterfaces", listOf(agentInterface(request, workspaceId)));
		card.put("defaultInputModes", listOf("text/plain"));
		card.put("defaultOutputModes", listOf("text/plain"));

		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("streaming", true);
		capabilities.put("pushNotifications", false);
		capabilities.put("extendedAgentCard", false);
		card.put("capabilities", capabilities);

		card.put("securitySchemes", securitySchemes());
		card.put("securityRequirements", securityRequirements());
		card.put("skills", workspaceSkills(row));
		return jsonResponse(card);
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
			if ("SendMessage".equals(method)) {
				result = sendMessageResponse(handleSend(workspaceId, rpc, requestContext, true));
			} else if ("GetTask".equals(method)) {
				result = handleGet(rpc, requestContext);
			} else if ("CancelTask".equals(method)) {
				result = handleCancel(rpc, requestContext);
			} else if ("SendStreamingMessage".equals(method) || "SubscribeToTask".equals(method)) {
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
					if ("SendStreamingMessage".equals(method)) {
						Map<String, Object> task = handleSend(workspaceId, rpc, requestContext, false);
						runId = String.valueOf(task.get("id"));
						sendSse(eventSink, sse, "task", jsonRpcResult(responseId, streamTask(task)));
					} else if ("SubscribeToTask".equals(method)) {
						runId = extractTaskId(params(rpc));
						if (runId == null) {
							throw new IllegalArgumentException("task id is required");
						}
						Map<String, Object> task = handleGet(rpc, requestContext);
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

	private Map<String, Object> handleSend(String workspaceId, JsonObject rpc, A2ARequestContext requestContext,
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

	private Map<String, Object> handleGet(JsonObject rpc, A2ARequestContext requestContext) {
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		String runId = extractTaskId(params(rpc));
		if (runId == null) {
			throw new IllegalArgumentException("task id is required");
		}
		return taskFromRun(AgentRuntimeManager.get().getRun(runId, insight));
	}

	private Map<String, Object> handleCancel(JsonObject rpc, A2ARequestContext requestContext) {
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
		if ("SendStreamingMessage".equals(text(rpc, "method"))) {
			return streamTask(handleSend(workspaceId, rpc, requestContext, false));
		}
		return streamTask(handleGet(rpc, requestContext));
	}

	private void streamRun(String runId, SseEventSink eventSink, Sse sse, A2ARequestContext requestContext,
			Object responseId) throws Exception {
		LinkedBlockingQueue<AgentRunEvent> queue = new LinkedBlockingQueue<>();
		AutoCloseable subscription = AgentRunEventBus.get().subscribe(runId, queue::offer);
		try {
			for (AgentRunEvent event : AgentRunEventBus.get().replay(runId)) {
				sendRunEvent(eventSink, sse, requestContext, event, responseId);
				if (event.isTerminal()) {
					return;
				}
			}
			while (eventSink != null && !eventSink.isClosed()) {
				AgentRunEvent event = queue.poll(30, TimeUnit.SECONDS);
				if (event == null) {
					continue;
				}
				sendRunEvent(eventSink, sse, requestContext, event, responseId);
				if (event.isTerminal()) {
					return;
				}
			}
		} finally {
			subscription.close();
		}
	}

	private void sendRunEvent(SseEventSink eventSink, Sse sse, A2ARequestContext requestContext, AgentRunEvent event,
			Object responseId) {
		seedThreadStore(requestContext);
		Insight insight = requestContext.insight;
		Map<String, Object> task = taskFromRun(AgentRuntimeManager.get().getRun(event.getRunId(), insight));
		Map<String, Object> payload = statusUpdateFromTask(task, event.isTerminal(), event.toMap());
		sendSse(eventSink, sse, event.getEvent(), jsonRpcResult(responseId, streamStatusUpdate(payload)));
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

	private static boolean returnImmediately(JsonObject params) {
		JsonObject configuration = object(params, "configuration");
		JsonElement value = configuration == null ? null : configuration.get("returnImmediately");
		return value != null && value.isJsonPrimitive() && value.getAsBoolean();
	}

	private static Map<String, Object> sendMessageResponse(Map<String, Object> task) {
		Map<String, Object> response = new HashMap<>();
		response.put("task", task);
		return response;
	}

	private static Map<String, Object> streamTask(Map<String, Object> task) {
		Map<String, Object> response = new HashMap<>();
		response.put("task", task);
		return response;
	}

	private static Map<String, Object> streamStatusUpdate(Map<String, Object> statusUpdate) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusUpdate", statusUpdate);
		return response;
	}

	private static boolean isTerminalTask(Map<String, Object> task) {
		Object statusObject = task == null ? null : task.get("status");
		if (!(statusObject instanceof Map)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> status = (Map<String, Object>) statusObject;
		String state = stringValue(status.get("state"));
		return "TASK_STATE_COMPLETED".equals(state) || "TASK_STATE_FAILED".equals(state)
				|| "TASK_STATE_CANCELED".equals(state) || "TASK_STATE_REJECTED".equals(state);
	}

	private static Map<String, Object> taskFromRun(Map<String, Object> run) {
		String status = stringValue(run.get("status"));
		Map<String, Object> task = new HashMap<>();
		task.put("id", run.get("runId"));
		task.put("contextId", run.get("roomId"));

		Map<String, Object> taskStatus = new HashMap<>();
		taskStatus.put("state", toWireState(status));
		String timestamp = firstNonBlank(String.valueOf(run.get("completedAt")), String.valueOf(run.get("startedAt")),
				String.valueOf(run.get("dateCreated")));
		if (timestamp != null && !"null".equals(timestamp)) {
			taskStatus.put("timestamp", toIsoTimestamp(timestamp));
		}
		String finalText = stringValue(run.get("finalText"));
		if (finalText != null) {
			Map<String, Object> message = new HashMap<>();
			message.put("messageId", firstNonBlank(stringValue(run.get("finalOutputMessageId")),
					stringValue(run.get("runId")) + "-final"));
			message.put("contextId", run.get("roomId"));
			message.put("taskId", run.get("runId"));
			message.put("role", "ROLE_AGENT");
			message.put("parts", listOf(textPart(finalText)));
			taskStatus.put("message", message);
		}
		task.put("status", taskStatus);
		task.put("artifacts", run.get("artifacts") instanceof List ? run.get("artifacts") : new ArrayList<>());

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("semossStatus", status);
		metadata.put("workspaceId", run.get("workspaceId"));
		metadata.put("modelId", run.get("modelId"));
		metadata.put("jobId", run.get("jobId"));
		metadata.put("inputMessageId", run.get("inputMessageId"));
		metadata.put("finalOutputMessageId", run.get("finalOutputMessageId"));
		metadata.put("errorMessage", run.get("errorMessage"));
		task.put("metadata", metadata);
		return task;
	}

	private static Map<String, Object> statusUpdateFromTask(Map<String, Object> task, boolean terminal,
			Map<String, Object> event) {
		Map<String, Object> update = new HashMap<>();
		update.put("taskId", task.get("id"));
		update.put("contextId", task.get("contextId"));
		update.put("status", task.get("status"));

		Map<String, Object> metadata = new HashMap<>();
		Object taskMetadata = task.get("metadata");
		if (taskMetadata instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> taskMetadataMap = (Map<String, Object>) taskMetadata;
			copyIfPresent(taskMetadataMap, metadata, "semossStatus");
			copyIfPresent(taskMetadataMap, metadata, "workspaceId");
			copyIfPresent(taskMetadataMap, metadata, "modelId");
			copyIfPresent(taskMetadataMap, metadata, "jobId");
			copyIfPresent(taskMetadataMap, metadata, "inputMessageId");
			copyIfPresent(taskMetadataMap, metadata, "finalOutputMessageId");
			copyIfPresent(taskMetadataMap, metadata, "errorMessage");
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

	private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
		Object value = source.get(key);
		if (value != null) {
			target.put(key, value);
		}
	}

	private static Map<String, Object> textPart(String text) {
		Map<String, Object> part = new HashMap<>();
		part.put("text", text);
		part.put("mediaType", "text/plain");
		return part;
	}

	private static String toWireState(String status) {
		if ("SUBMITTED".equals(status)) {
			return "TASK_STATE_SUBMITTED";
		}
		if ("RUNNING".equals(status)) {
			return "TASK_STATE_WORKING";
		}
		if ("INPUT_REQUIRED".equals(status)) {
			return "TASK_STATE_INPUT_REQUIRED";
		}
		if ("COMPLETED".equals(status)) {
			return "TASK_STATE_COMPLETED";
		}
		if ("CANCELLED".equals(status)) {
			return "TASK_STATE_CANCELED";
		}
		if ("FAILED".equals(status)) {
			return "TASK_STATE_FAILED";
		}
		return status == null ? null : "TASK_STATE_" + status.toUpperCase();
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

	private static String toIsoTimestamp(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Timestamp) {
			return DateTimeFormatter.ISO_INSTANT.format(((Timestamp) value).toInstant());
		}
		String raw = String.valueOf(value).trim();
		if (raw.isEmpty() || "null".equals(raw)) {
			return null;
		}
		try {
			return DateTimeFormatter.ISO_INSTANT.format(Timestamp.valueOf(raw).toInstant());
		} catch (Exception ignored) {
			// fall through
		}
		try {
			return DateTimeFormatter.ISO_INSTANT.format(Instant.parse(raw));
		} catch (Exception ignored) {
			return raw;
		}
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

	private static List<Map<String, Object>> workspaceSkills(Map<String, Object> row) {
		List<Map<String, Object>> skills = new ArrayList<>();
		JsonObject cfg = configFromRow(row);
		JsonArray skillRefs = cfg == null ? null : array(cfg, "skills");
		if (skillRefs == null) {
			skills.add(defaultSkill());
			return skills;
		}
		for (JsonElement element : skillRefs) {
			Map<String, Object> skill = new HashMap<>();
			if (element.isJsonObject()) {
				JsonObject obj = element.getAsJsonObject();
				skill.put("id", firstNonBlank(text(obj, "id"), text(obj, "skillId"), text(obj, "name")));
				skill.put("name", firstNonBlank(text(obj, "name"), text(obj, "id"), text(obj, "skillId")));
				skill.put("description", firstNonBlank(text(obj, "description"), "Workspace skill"));
				skill.put("tags", tagsForSkill(obj));
			} else if (element.isJsonPrimitive()) {
				String value = element.getAsString();
				skill.put("id", value);
				skill.put("name", value);
				skill.put("description", "Workspace skill");
				skill.put("tags", listOf("semoss", "workspace"));
			}
			if (stringValue(skill.get("id")) != null && stringValue(skill.get("name")) != null) {
				skills.add(skill);
			}
		}
		if (skills.isEmpty()) {
			skills.add(defaultSkill());
		}
		return skills;
	}

	private static Map<String, Object> agentInterface(HttpServletRequest request, String workspaceId) {
		Map<String, Object> iface = new HashMap<>();
		iface.put("url", endpointUrl(request, workspaceId));
		iface.put("protocolBinding", "JSONRPC");
		iface.put("protocolVersion", "1.0");
		return iface;
	}

	private static Map<String, Object> securitySchemes() {
		Map<String, Object> http = new HashMap<>();
		http.put("scheme", "Bearer");
		http.put("description", "SEMOSS access key or session bearer token.");

		Map<String, Object> scheme = new HashMap<>();
		scheme.put("httpAuthSecurityScheme", http);

		Map<String, Object> schemes = new HashMap<>();
		schemes.put("semossBearer", scheme);
		return schemes;
	}

	private static List<Object> securityRequirements() {
		Map<String, Object> scopes = new HashMap<>();
		scopes.put("list", new ArrayList<>());

		Map<String, Object> schemes = new HashMap<>();
		schemes.put("semossBearer", scopes);

		Map<String, Object> requirement = new HashMap<>();
		requirement.put("schemes", schemes);
		return listOf(requirement);
	}

	private static Map<String, Object> defaultSkill() {
		Map<String, Object> skill = new HashMap<>();
		skill.put("id", "semoss-agent");
		skill.put("name", "SEMOSS Agent");
		skill.put("description", "Run the SEMOSS workspace agent with text prompts.");
		skill.put("tags", listOf("semoss", "workspace", "agent"));
		return skill;
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
