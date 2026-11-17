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
package prerna.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import prerna.auth.User;
import prerna.engine.impl.model.message.MessageInputMedia;
import prerna.om.Insight;
import prerna.om.ThreadStore;
import prerna.reactor.ReactorDefinition;
import prerna.reactor.agent.mcp.MCPErrorCode;
import prerna.reactor.agent.mcp.MCPUtility;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.execptions.SemossMCPException;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.web.services.util.WebUtility;

public class MCPReaper implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(MCPReaper.class);
	private static final Map<String, ReentrantLock> INSIGHT_LOCKS = new ConcurrentHashMap<>();
	// values from the request are placed inside pixels, so each is held to the
	// characters it legitimately uses: MCP versions are release dates, and tool
	// names use the characters the MCP spec recommends
	private static final Pattern PROTOCOL_VERSION_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

	/**
	 * Removes a lock entry for an insight id from the MCP lock cache.
	 *
	 * @param insightId insight identifier
	 */
	public static void clearInsightLock(String insightId) {
		String sanitizedInsightId = WebUtility.inputSanitizer(insightId);
		if (sanitizedInsightId != null) {
			INSIGHT_LOCKS.remove(sanitizedInsightId);
		}
	}

	/**
	 * Removes a lock entry for an insight instance from the MCP lock cache.
	 *
	 * @param insight insight instance
	 */
	public static void clearInsightLock(Insight insight) {
		if (insight == null) {
			return;
		}
		String sanitizedInsightId = WebUtility.inputSanitizer(insight.getInsightId());
		String insightLockKey = sanitizedInsightId != null ? sanitizedInsightId
				: Integer.toString(System.identityHashCode(insight));
		INSIGHT_LOCKS.remove(insightLockKey);
	}

	private static final ScheduledExecutorService CONNECTION_REAPER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "mcp-connection-reaper");
		t.setDaemon(true);
		return t;
	});

	private final long idleTimeoutMinutes;

	private enum Mode {
		SSE, HTTP_STREAM
	}

	private final Mode mode;
	private final Insight insight;
	private final String sessionId;
	private final String toolbox;
	private final String requestUrl;
	private final Map<String, String> log4jContextMap;

	// HTTP Stream specific fields
	private InputStream is = null;
	private OutputStream os = null;
	private HttpServletResponse response = null;

	// SSE specific fields
	private BufferedReader reader = null;
	private SseEventSink eventSink = null;
	private Sse sse = null;

	/**
	 * Constructor for HTTP Stream mode. Takes the response rather than its output
	 * stream because the status and headers depend on the message that comes in -
	 * see {@link #sendHttpAccepted()}.
	 *
	 * @param insight
	 * @param sessionId
	 * @param is
	 * @param response
	 * @param toolbox
	 * @param requestUrl
	 * @param log4jContextMap
	 */
	public MCPReaper(Insight insight, String sessionId, InputStream is, HttpServletResponse response, String toolbox,
			String requestUrl, Map<String, String> log4jContextMap, long idleTimeoutMinutes) {
		this.mode = Mode.HTTP_STREAM;
		this.insight = insight;
		this.sessionId = sessionId;
		this.is = is;
		this.response = response;
		this.toolbox = toolbox;
		this.requestUrl = requestUrl;
		this.idleTimeoutMinutes = idleTimeoutMinutes;

		if (log4jContextMap == null) {
			this.log4jContextMap = new HashMap<>();
		} else {
			this.log4jContextMap = log4jContextMap;
		}
	}

	/**
	 * Constructor for SSE mode
	 * 
	 * @param insight
	 * @param sessionId
	 * @param reader
	 * @param eventSink
	 * @param sse
	 * @param toolbox
	 * @param requestUrl
	 * @param log4jContextMap
	 */
	public MCPReaper(Insight insight, String sessionId, BufferedReader reader, SseEventSink eventSink, Sse sse,
			String toolbox, String requestUrl, Map<String, String> log4jContextMap) {
		this.mode = Mode.SSE;
		this.insight = insight;
		this.sessionId = sessionId;
		this.reader = reader;
		this.eventSink = eventSink;
		this.sse = sse;
		this.toolbox = toolbox;
		this.requestUrl = requestUrl;
		this.idleTimeoutMinutes = 0; // unused for one-shot SSE

		if (log4jContextMap == null) {
			this.log4jContextMap = new HashMap<>();
		} else {
			this.log4jContextMap = log4jContextMap;
		}
	}

	@Override
	public void run() {
		// every tool call leaves its user, session and job on this thread, which is a
		// request thread or a pooled SSE worker that serves other connections next
		try {
			if (this.mode == Mode.HTTP_STREAM) {
				runHttp();
			} else {
				runSse();
			}
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Runs the bidirectional HTTP stream communication
	 */
	private void runHttp() {
		AtomicBoolean timedOut = new AtomicBoolean(false);
		AtomicReference<ScheduledFuture<?>> idleTimer = new AtomicReference<>();
		AtomicLong timerGeneration = new AtomicLong(0);

		Runnable resetIdleTimer = () -> {
			long gen = timerGeneration.incrementAndGet();
			ScheduledFuture<?> existing = idleTimer.get();
			if (existing != null) {
				existing.cancel(false);
			}
			idleTimer.set(CONNECTION_REAPER.schedule(() -> {
				// only act if this task is still the current generation
				if (timerGeneration.get() == gen) {
					timedOut.set(true);
					try {
						this.is.close();
					} catch (IOException ignored) {
					}
				}
			}, idleTimeoutMinutes, TimeUnit.MINUTES));
		};

		// start the idle timer before blocking on readLine
		resetIdleTimer.run();

		try (var ctx = org.apache.logging.log4j.CloseableThreadContext.putAll(this.log4jContextMap);
				BufferedReader streamReader = new BufferedReader(
						new InputStreamReader(this.is, StandardCharsets.UTF_8))) {

			String actualContent;
			while ((actualContent = streamReader.readLine()) != null) {
				resetIdleTimer.run(); // message received - reset idle clock
				classLogger.debug("HTTP REQUEST :::: {}", actualContent);
				String output = generateResponse(actualContent, sessionId, toolbox, insight);
				classLogger.debug("HTTP RESPONSE generated ({} chars)", output == null ? 0 : output.length());

				if (output != null) {
					sendHttpEvent(output);
				} else {
					sendHttpAccepted();
				}
			}
		} catch (IOException e) {
			if (timedOut.get()) {
				classLogger.info("MCP HTTP connection closed after {}min idle timeout", idleTimeoutMinutes);
			} else {
				classLogger.error("MCPReaper (HTTP) encountered an I/O error", e);
			}
		} finally {
			ScheduledFuture<?> timer = idleTimer.get();
			if (timer != null) {
				timer.cancel(false);
			}
		}

		classLogger.debug("Done with MCPReaper HTTP thread, client has closed the connection.");
	}

	/**
	 *
	 * @param data
	 * @throws IOException
	 */
	private void sendHttpEvent(String data) throws IOException {
		classLogger.debug("Sending MCP HTTP response ({} chars)", data == null ? 0 : data.length());
		prepareHttpJsonResponse();
		byte[] bytes = (data + "\n").getBytes(StandardCharsets.UTF_8);
		this.os.write(bytes);
		this.os.flush();
	}

	/**
	 * Commits the response as a json message body. Deferred until we actually have
	 * something to write, so that a notification can still go back as a 202.
	 *
	 * @throws IOException
	 */
	private void prepareHttpJsonResponse() throws IOException {
		if (this.os == null) {
			this.os = this.response.getOutputStream();
		}
		if (!this.response.isCommitted()) {
			this.response.setStatus(HttpServletResponse.SC_OK);
			this.response.setContentType(MediaType.APPLICATION_JSON);
			this.response.setHeader("X-Content-Type-Options", "nosniff");
			this.response.setCharacterEncoding("UTF-8");
			this.response.setHeader("Cache-Control", "no-cache");
			this.response.setHeader("Connection", "keep-alive");
		}
	}

	/**
	 * A json-rpc notification carries no id and so gets no response body. The mcp
	 * streamable http spec wants 202 Accepted for that, and clients rely on it: a
	 * 200 that advertises a json content type with an empty body makes them try to
	 * parse the message that never arrives.
	 */
	private void sendHttpAccepted() {
		if (this.response.isCommitted()) {
			// something already wrote on this connection, nothing left to say
			return;
		}
		classLogger.debug("Acknowledging notification with 202 for session {}", this.sessionId);
		this.response.setStatus(HttpServletResponse.SC_ACCEPTED);
		this.response.setContentLength(0);
	}

	/**
	 * Runs the one-shot SSE communication
	 */
	private void runSse() {
		try (var ctx = org.apache.logging.log4j.CloseableThreadContext.putAll(this.log4jContextMap)) {
			String actualContent = null;
			// This will block, read one line, and then the loop will terminate
			if ((actualContent = this.reader.readLine()) != null) {
				classLogger.debug("SSE REQUEST :::: {}", actualContent);
				String output = generateResponse(actualContent, this.sessionId, this.toolbox, this.insight);
				classLogger.debug("SSE RESPONSE generated ({} chars)", output == null ? 0 : output.length());

				if (output != null) {
					OutboundSseEvent event = this.sse.newEventBuilder().data(String.class, output).build();
					this.eventSink.send(event);
				}
			}
		} catch (IOException e) {
			classLogger.error("MCPReaper (SSE) encountered an I/O error", e);
			JSONObject error = new JSONObject();
			error.put("jsonrpc", "2.0");
			error.put("id", 3);
			JSONObject result = new JSONObject();
			JSONObject content = new JSONObject();
			content.put("type", "text");
			content.put("text", e.getMessage());
			result.put("content", content);
			result.put("isError", true);
			error.put("result", result);

			// send the error
			OutboundSseEvent event = sse.newEventBuilder().data(String.class, error.toString()).build();
			eventSink.send(event);
		} finally {
			if (this.eventSink != null) {
				try {
					this.eventSink.close();
				} catch (IOException e) {
					classLogger.error("Unable to close SSE event sink", e);
				}
			}
		}

		classLogger.debug("Done with MCPReaper SSE thread !!");
	}

	/**
	 * 
	 * @param user
	 * @param insight
	 * @param expression
	 * @param sessionId
	 * @return
	 */
	private Object runPixel(User user, Insight insight, String expression, String sessionId) {
		boolean dropLogging = true;

		String jobId = "";
		String insightId = WebUtility.inputSanitizer(insight.getInsightId());
		String insightLockKey = insightId != null ? insightId : Integer.toString(System.identityHashCode(insight));
		ReentrantLock insightLock = INSIGHT_LOCKS.computeIfAbsent(insightLockKey, ignored -> new ReentrantLock());

		// serialize concurrent calls on the same insight - multiple parallel
		// HTTP streaming connections from the same client share an insight instance
		insightLock.lock();
		try {
			Boolean schedulerMode = ThreadStore.isSchedulerMode();
			if (schedulerMode != null) {
				insight.setSchedulerMode(schedulerMode);
			}
			return runPixelJob(user, insight, expression, jobId, insightId, sessionId, null, dropLogging);
		} finally {
			insightLock.unlock();
		}
	}

	/**
	 * 
	 * @param user
	 * @param insight
	 * @param expression
	 * @param jobId
	 * @param insightId
	 * @param sessionId
	 * @param routeId
	 * @param dropLogging
	 * @return
	 */
	private static Object runPixelJob(User user, Insight insight, String expression, String jobId, String insightId,
			String sessionId, String routeId, boolean dropLogging) {
		PixelJobManager manager = PixelJobManager.getManager();
		PixelJobRunner jobRunner = manager.makeJob(WebUtility.inputSanitizer(insightId), insight, sessionId, routeId);
		jobId = jobRunner.getJobId();
		jobRunner.addPixel(expression);
		// this just runs as a method call
		jobRunner.run();
		PixelRunner pixelRunner = jobRunner.getRunner();
		List<NounMetadata> output = pixelRunner.getResults();
		// there are times when we spin up
		// other runPixel requests on the same
		// insight but don't want to drop the master insight
		// console logging
		// example is ExportToExcel grids
		if (dropLogging) {
			jobRunner.setStatus(PixelJobStatus.COMPLETE);
			manager.clearJob(jobId);
			manager.removeJob(jobId);
		}
		if (output != null && !output.isEmpty()) {
			return output.get(0).getValue();
		}

		return null;
	}

	/**
	 * Answers one JSON-RPC request from the MCP client. Every request gets one
	 * reply with its id and either a result or an error; a notification gets none.
	 *
	 * @param json      the request as the client sent it
	 * @param sessionId the session the toolbox runs in
	 * @param projectId the toolbox project
	 * @param insight   the insight the toolbox's pixels run in
	 * @return the reply, or null for a notification
	 */
	private String generateResponse(String json, String sessionId, String projectId, Insight insight) {
		JSONObject root;
		try {
			root = new JSONObject(json);
		} catch (JSONException e) {
			return errorResponse(JSONObject.NULL, MCPErrorCode.PARSE_ERROR.getCode(),
					MCPErrorCode.PARSE_ERROR.getDescription());
		}

		Object id = root.has("id") ? root.get("id") : JSONObject.NULL;
		if (!(root.opt("method") instanceof String method)) {
			return errorResponse(id, MCPErrorCode.INVALID_REQUEST.getCode(),
					"Invalid Request - Missing or invalid 'method' field");
		}

		if (isNotification(method)) {
			handleNotification(method, root);
			return null;
		}

		if (!root.has("id")) {
			return errorResponse(JSONObject.NULL, MCPErrorCode.INVALID_REQUEST.getCode(),
					"Invalid Request - Missing required 'id' field");
		}

		try {
			JSONObject response = new JSONObject();
			response.put("id", id);
			response.put("jsonrpc", "2.0");
			response.put("result", dispatch(method, root, sessionId, projectId, insight));
			return response.toString();
		} catch (JsonRpcError e) {
			return errorResponse(id, e.code, e.getMessage());
		} catch (RuntimeException e) {
			classLogger.error("Failed to answer the MCP {} request for toolbox {}", method, projectId, e);
			return errorResponse(id, MCPErrorCode.INTERNAL_ERROR.getCode(),
					e.getMessage() != null ? e.getMessage() : MCPErrorCode.INTERNAL_ERROR.getDescription());
		}
	}

	/**
	 * Runs one request method and returns its result.
	 *
	 * @param method    the request method
	 * @param root      the whole request
	 * @param sessionId the session the toolbox runs in
	 * @param projectId the toolbox project
	 * @param insight   the insight the toolbox's pixels run in
	 * @return the result to send
	 * @throws JsonRpcError when the request is answered with an error
	 */
	private Object dispatch(String method, JSONObject root, String sessionId, String projectId, Insight insight)
			throws JsonRpcError {
		// {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
		// {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18",
		// "serverInfo":{"name":"Stock Prices","version":"1.8.0"},
		// "capabilities":{"experimental":{},"prompts":{"listChanged":false},
		// "resources":{"listChanged":false,"subscribe":false},"tools":{"listChanged":false}}}}
		if (method.equals("initialize")) {
			if (!root.has("params")) {
				throw new JsonRpcError(MCPErrorCode.INVALID_REQUEST.getCode(),
						"Invalid Request - Missing required 'params.protocolVersion' field");
			}
			JSONObject params = requireParams(root);
			if (!params.has("protocolVersion")) {
				throw new JsonRpcError(MCPErrorCode.INVALID_REQUEST.getCode(),
						"Invalid Request - Missing required 'protocolVersion' field");
			}
			if (!(params.get("protocolVersion") instanceof String protocolVersion)
					|| !PROTOCOL_VERSION_PATTERN.matcher(protocolVersion).matches()) {
				throw new JsonRpcError(MCPErrorCode.INVALID_PARAMS.getCode(),
						"Invalid params - 'protocolVersion' must be a version such as 2025-06-18");
			}
			return runJsonPixel(insight,
					"InitMCP(project='" + projectId + "', protocolVersion='" + protocolVersion + "');", sessionId);
		}
		// {"jsonrpc":"2.0","id":1,"method":"ping"}
		// {"jsonrpc":"2.0","id":1,"result":{}}
		if (method.equals("ping")) {
			return new JSONObject();
		}
		// {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
		// {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_stock_price",
		// "description":"Retrieve the latest closing price for a ticker symbol",
		// "inputSchema":{"type":"object","properties":{"symbol":{"type":"string"}},"required":["symbol"]}}]}}
		if (method.equalsIgnoreCase("tools/list")) {
			return runJsonPixel(insight, "GetMCPTools(project='" + projectId + "');", sessionId);
		}
		// {"jsonrpc":"2.0","id":3,"method":"resources/list"}
		// {"jsonrpc":"2.0","id":3,"result":{"resources":[]}}
		if (method.equalsIgnoreCase("resources/list")) {
			return runJsonPixel(insight, "GetMCPResources(project='" + projectId + "');", sessionId);
		}
		// {"jsonrpc":"2.0","id":4,"method":"resources/templates/list"}
		// {"jsonrpc":"2.0","id":4,"result":{"resourceTemplates":[]}}
		if (method.equalsIgnoreCase("resources/templates/list")) {
			return runJsonPixel(insight, "GetMCPResourcesTemplates(project='" + projectId + "');", sessionId);
		}
		// {"jsonrpc":"2.0","id":5,"method":"prompts/list"}
		// {"jsonrpc":"2.0","id":5,"result":{"prompts":[]}}
		if (method.equalsIgnoreCase("prompts/list")) {
			return runJsonPixel(insight, "GetMCPPrompts(project='" + projectId + "');", sessionId);
		}
		// {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOG"}}}
		// replies are shown at callTool
		if (method.equalsIgnoreCase("tools/call")) {
			return callTool(requireParams(root), sessionId, projectId, insight);
		}
		// {"jsonrpc":"2.0","id":7,"method":"completion/complete","params":{}}
		// {"jsonrpc":"2.0","id":7,"error":{"code":-32601,"message":"Method not found - The method does not exist / is not available"}}
		throw new JsonRpcError(MCPErrorCode.METHOD_NOT_FOUND.getCode(),
				MCPErrorCode.METHOD_NOT_FOUND.getDescription());
	}

	/**
	 * Runs a tools/call request and returns its MCP tool result.
	 *
	 * <pre>
	 * a text result:
	 * {"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"334.07"}],"isError":false}}
	 *
	 * a result the tool's outputSchema describes:
	 * {"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"{\"price\":334.07}"}],
	 *   "isError":false,"structuredContent":{"price":334.07}}}
	 *
	 * a multimodal result:
	 * {"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"The closing prices"},
	 *   {"type":"image","data":"iVBORw0KGgo...","mimeType":"image/png"}],"isError":false}}
	 *
	 * a tool that fails:
	 * {"jsonrpc":"2.0","id":6,"error":{"code":-32004,"message":"Tool execution failed - Tool ran but failed during execution"}}
	 * </pre>
	 *
	 * @param params    the request's params
	 * @param sessionId the session the toolbox runs in
	 * @param projectId the toolbox project
	 * @param insight   the insight the toolbox's pixels run in
	 * @return the tool result
	 * @throws JsonRpcError when the params are invalid or the tool fails
	 */
	private Map<String, Object> callTool(JSONObject params, String sessionId, String projectId, Insight insight)
			throws JsonRpcError {
		if (!(params.opt("name") instanceof String callName) || !TOOL_NAME_PATTERN.matcher(callName).matches()) {
			throw new JsonRpcError(MCPErrorCode.INVALID_PARAMS.getCode(),
					"Invalid params - 'name' must be the name of a tool");
		}
		Object arguments = params.opt("arguments");
		if (arguments == null) {
			arguments = new JSONObject();
		} else if (!(arguments instanceof JSONObject)) {
			throw new JsonRpcError(MCPErrorCode.INVALID_PARAMS.getCode(),
					"Invalid params - 'arguments' must be an object");
		}
		String pixel = "RunMCPTool(project='" + projectId + "'" + ", function='" + callName + "'" + ", paramValues=["
				+ arguments + "]);";

		classLogger.info("Making call to {}", callName);
		try {
			Object retObject = runPixel(insight.getUser(), insight, pixel, sessionId);
			Map<String, Object> result = buildToolCallResult(retObject, insight);
			if (isPlainTextResult(result)) {
				addStructuredContent(result, retObject, outputSchema(insight, projectId, callName, sessionId));
			}
			return result;
		} catch (SemossMCPException e) {
			throw new JsonRpcError(e.getError().getCode(),
					e.getMessage() != null ? e.getMessage() : e.getError().getDescription());
		} catch (Exception e) {
			throw new JsonRpcError(MCPErrorCode.TOOL_EXECUTION_FAILED.getCode(),
					e.getMessage() != null ? e.getMessage() : MCPErrorCode.TOOL_EXECUTION_FAILED.getDescription());
		}
	}

	/**
	 * Returns the request's params object.
	 *
	 * @param root the whole request
	 * @return the params
	 * @throws JsonRpcError when params is missing or is not an object
	 */
	private static JSONObject requireParams(JSONObject root) throws JsonRpcError {
		if (!(root.opt("params") instanceof JSONObject params)) {
			throw new JsonRpcError(MCPErrorCode.INVALID_PARAMS.getCode(), "Invalid params - 'params' must be an object");
		}
		return params;
	}

	/**
	 * Runs a pixel whose result is a JSON object, as initialize and the list
	 * methods do. A failed reactor leaves its error message as the result, so a
	 * string that is not JSON is answered as that error.
	 *
	 * @param insight    the insight the pixel runs in
	 * @param expression the pixel
	 * @param sessionId  the session the toolbox runs in
	 * @return the result object
	 * @throws JsonRpcError when the pixel does not return a JSON object
	 */
	private JSONObject runJsonPixel(Insight insight, String expression, String sessionId) throws JsonRpcError {
		Object value = runPixel(insight.getUser(), insight, expression, sessionId);
		if (value instanceof JSONObject json) {
			return json;
		}
		if (value instanceof Map<?, ?> map) {
			return new JSONObject(map);
		}
		if (value instanceof String text) {
			try {
				return new JSONObject(text);
			} catch (JSONException e) {
				throw new JsonRpcError(MCPErrorCode.INTERNAL_ERROR.getCode(), text);
			}
		}
		throw new JsonRpcError(MCPErrorCode.INTERNAL_ERROR.getCode(), MCPErrorCode.INTERNAL_ERROR.getDescription());
	}

	/**
	 * Builds a JSON-RPC error reply.
	 *
	 * <pre>
	 * -32700, a request that is not JSON, so its id cannot be read:
	 * {"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error - Invalid JSON was received by the server"}}
	 *
	 * -32600, a request that is not a valid request:
	 * {"jsonrpc":"2.0","id":8,"error":{"code":-32600,"message":"Invalid Request - Missing or invalid 'method' field"}}
	 *
	 * -32601, a method the toolbox does not have:
	 * {"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"Method not found - The method does not exist / is not available"}}
	 *
	 * -32602, params that are missing, of the wrong type, or have characters a pixel cannot safely hold:
	 * {"jsonrpc":"2.0","id":10,"error":{"code":-32602,"message":"Invalid params - 'name' must be the name of a tool"}}
	 * {"jsonrpc":"2.0","id":11,"error":{"code":-32602,"message":"Invalid params - 'protocolVersion' must be a version such as 2025-06-18"}}
	 *
	 * -32603, a pixel that failed or returned something other than a JSON object:
	 * {"jsonrpc":"2.0","id":12,"error":{"code":-32603,"message":"Reactor GetMCPPrompts failed"}}
	 * </pre>
	 *
	 * @param id      the request's id, or JSON null when it could not be read
	 * @param code    the error code
	 * @param message the error message
	 * @return the reply
	 */
	private static String errorResponse(Object id, int code, String message) {
		JSONObject error = new JSONObject();
		error.put("code", code);
		error.put("message", message);
		JSONObject response = new JSONObject();
		response.put("id", id);
		response.put("jsonrpc", "2.0");
		response.put("error", error);
		return response.toString();
	}

	/** A JSON-RPC error to answer a request with. */
	private static final class JsonRpcError extends Exception {

		private static final long serialVersionUID = 1L;

		private final int code;

		private JsonRpcError(int code, String message) {
			super(message);
			this.code = code;
		}
	}

	/**
	 * Converts the internal room-file multimodal envelope into standard MCP content
	 * blocks. Ordinary outputs remain a single text block.
	 */
	private Map<String, Object> buildToolCallResult(Object retObject, Insight insight) {
		String output = retObject == null ? "null" : retObject.toString();
		JSONObject envelope;
		try {
			envelope = new JSONObject(output);
		} catch (JSONException e) {
			return buildTextToolResult(output, false);
		}

		if (!envelope.has(MCPUtility.SEMOSS_MULTIMODAL_TOOL_RESPONSE_KEY)) {
			return buildTextToolResult(output, false);
		}

		Object rawBlocks = envelope.opt(MCPUtility.SEMOSS_MULTIMODAL_TOOL_RESPONSE_KEY);
		if (!(rawBlocks instanceof JSONArray)) {
			return buildTextToolResult("Tool returned an invalid multimodal response.", true);
		}

		JSONArray blocks = (JSONArray) rawBlocks;
		if (blocks.length() == 0) {
			return buildTextToolResult("Tool returned an empty multimodal response.", true);
		}
		List<Map<String, Object>> content = new ArrayList<>();
		try {
			for (int blockIndex = 0; blockIndex < blocks.length(); blockIndex++) {
				Object rawBlock = blocks.get(blockIndex);
				if (!(rawBlock instanceof JSONObject)) {
					return buildTextToolResult("Tool returned an invalid multimodal response.", true);
				}

				JSONObject block = (JSONObject) rawBlock;
				String type = block.optString("type", null);
				if ("text".equals(type)) {
					Object text = block.opt("text");
					if (!(text instanceof String)) {
						return buildTextToolResult("Tool returned an invalid multimodal text block.", true);
					}
					content.add(textContent((String) text));
				} else if ("image".equals(type) && !block.has("data")) {
					List<String> imagePaths = imagePaths(block.opt("image"));
					if (imagePaths.isEmpty()) {
						return buildTextToolResult("Tool returned an invalid multimodal image block.", true);
					}

					for (String imagePath : imagePaths) {
						Path resolvedPath = MCPUtility.resolveContainedMcpFile(insight.getInsightFolder(), imagePath);
						String format = MessageInputMedia.extractFormat(resolvedPath.getFileName().toString());
						String mimeType = MessageInputMedia.guessMimeType(resolvedPath.toString(), format);
						if (mimeType == null || !mimeType.startsWith("image/")) {
							return buildTextToolResult(
									"External MCP currently supports image attachments only; the referenced file is not an image.",
									true);
						}

						Map<String, Object> imageContent = new HashMap<>();
						imageContent.put("type", "image");
						imageContent.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(resolvedPath)));
						imageContent.put("mimeType", mimeType);
						content.add(imageContent);
					}
				} else {
					return buildTextToolResult("External MCP currently supports text and image result blocks only.",
							true);
				}
			}
		} catch (Exception e) {
			return buildTextToolResult(
					"The tool returned an image that is unavailable or outside its execution folder.", true);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("content", content);
		result.put("isError", false);
		return result;
	}

	private List<String> imagePaths(Object rawImagePaths) {
		List<String> paths = new ArrayList<>();
		if (rawImagePaths instanceof JSONArray) {
			JSONArray pathArray = (JSONArray) rawImagePaths;
			for (int pathIndex = 0; pathIndex < pathArray.length(); pathIndex++) {
				Object path = pathArray.opt(pathIndex);
				if (!(path instanceof String) || ((String) path).isBlank()) {
					return new ArrayList<>();
				}
				paths.add((String) path);
			}
		} else if (rawImagePaths instanceof String && !((String) rawImagePaths).isBlank()) {
			paths.add((String) rawImagePaths);
		}
		return paths;
	}

	private Map<String, Object> buildTextToolResult(String text, boolean isError) {
		Map<String, Object> result = new HashMap<>();
		List<Map<String, Object>> content = new ArrayList<>();
		content.add(textContent(text));
		result.put("content", content);
		result.put("isError", isError);
		return result;
	}

	private Map<String, Object> textContent(String text) {
		Map<String, Object> content = new HashMap<>();
		content.put("type", "text");
		content.put("text", text);
		return content;
	}

	/**
	 * Whether a tool result is a successful single text block, the only kind given
	 * structured content.
	 */
	private static boolean isPlainTextResult(Map<String, Object> result) {
		return Boolean.FALSE.equals(result.get("isError")) && result.get("content") instanceof List<?> content
				&& content.size() == 1 && content.get(0) instanceof Map<?, ?> block && "text".equals(block.get("type"));
	}

	/**
	 * The outputSchema of a tool in the project's tools/list, or null when it has
	 * none.
	 */
	private JSONObject outputSchema(Insight insight, String projectId, String toolName, String sessionId) {
		try {
			Object toolMap = runPixel(insight.getUser(), insight, "GetMCPTools(project='" + projectId + "');",
					sessionId);
			JSONArray tools = toolMap instanceof JSONObject json ? json.optJSONArray("tools") : null;
			for (int i = 0; tools != null && i < tools.length(); i++) {
				JSONObject tool = tools.optJSONObject(i);
				if (tool != null && toolName.equals(tool.optString("name"))) {
					return tool.optJSONObject("outputSchema");
				}
			}
		} catch (Exception e) {
			classLogger.warn("Could not read the output schema of MCP tool {}: {}", toolName, e.getMessage());
		}
		return null;
	}

	/**
	 * Adds a tool's output as {@code structuredContent}, which MCP requires to
	 * match the tool's {@code outputSchema}. An output the schema wraps
	 * ({@link ReactorDefinition#WRAP_RESULT_MARKER}) goes under
	 * {@link ReactorDefinition#WRAPPED_RESULT}. An output that does not fit the
	 * schema's shape stays text only.
	 *
	 * @param result       the tool result, given {@code structuredContent} in place
	 * @param retObject    what the tool returned
	 * @param outputSchema the tool's output schema, or null
	 */
	static void addStructuredContent(Map<String, Object> result, Object retObject, JSONObject outputSchema) {
		if (outputSchema == null) {
			return;
		}
		boolean wrapped = outputSchema.optBoolean(ReactorDefinition.WRAP_RESULT_MARKER, false);
		JSONObject properties = outputSchema.optJSONObject("properties");
		JSONObject valueSchema = wrapped && properties != null
				? properties.optJSONObject(ReactorDefinition.WRAPPED_RESULT)
				: outputSchema;
		String output = retObject == null ? "" : retObject.toString().trim();
		Object value = structuredValue(output, valueSchema == null ? null : valueSchema.optString("type", null));
		if (value == null || (!wrapped && !(value instanceof JSONObject))) {
			classLogger.warn("A tool output does not fit its output schema, so it is returned as text only");
			return;
		}
		result.put("structuredContent",
				wrapped ? new JSONObject().put(ReactorDefinition.WRAPPED_RESULT, value) : value);
	}

	/**
	 * Parses a tool's text output as the JSON value its schema type expects, or
	 * null.
	 */
	private static Object structuredValue(String output, String type) {
		try {
			if (output.startsWith("\"") && output.endsWith("\"") && output.length() > 1) {
				return new JSONArray("[" + output + "]").get(0);
			}
			if ("string".equals(type)) {
				return output;
			}
			if (output.startsWith("{")) {
				return new JSONObject(output);
			}
			if (output.startsWith("[")) {
				return new JSONArray(output);
			}
			Object scalar = JSONObject.stringToValue(output);
			return scalar == JSONObject.NULL ? null : scalar;
		} catch (JSONException e) {
			return null;
		}
	}

	/**
	 * Returns whether a method is a notification. A notification gets no reply:
	 * over HTTP it is answered with 202 and no body, and over SSE no event is sent.
	 *
	 * <pre>
	 * {"jsonrpc":"2.0","method":"notifications/initialized"}
	 * {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":6}}
	 * </pre>
	 *
	 * @param method the request method
	 * @return true for a notification
	 */
	private boolean isNotification(String method) {
		return method.startsWith("notifications/");
	}

	/**
	 * 
	 * @param method
	 * @param root
	 */
	private void handleNotification(String method, JSONObject root) {
		if (method.equals("notifications/initialized")) {
			classLogger.info("Client initialization complete");
		} else if (method.equals("notifications/cancelled")) {
			JSONObject params = root.optJSONObject("params");
			if (params != null && params.has("requestId")) {
				classLogger.info("Request cancelled: {}", params.get("requestId"));
			}
		} else if (method.equals("notifications/progress")) {
			if (root.has("params")) {
				classLogger.info("Progress update: {}", root.opt("params"));
			}
		} else if (method.equals("notifications/message")) {
			if (root.has("params")) {
				classLogger.info("Message notification: {}", root.opt("params"));
			}
		} else if (method.equals("notifications/resources/updated")) {
			classLogger.info("Resources updated notification received");
		} else if (method.equals("notifications/tools/list_changed")) {
			classLogger.info("Tools list changed notification received");
		} else if (method.equals("notifications/prompts/list_changed")) {
			classLogger.info("Prompts list changed notification received");
		} else {
			classLogger.warn("Unknown notification method: {}", method);
		}
	}

}
