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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.ThreadStore;
import prerna.reactor.agent.mcp.MCPErrorCode;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.comm.PixelJobThread;
import prerna.sablecc2.om.execptions.SemossMCPException;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.web.services.util.WebUtility;

public class MCPReaper implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(MCPReaper.class);

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

	// SSE specific fields
	private BufferedReader reader = null;
	private SseEventSink eventSink = null;
	private Sse sse = null;

	/**
	 * Constructor for HTTP Stream mode
	 * 
	 * @param insight
	 * @param sessionId
	 * @param is
	 * @param os
	 * @param toolbox
	 * @param requestUrl
	 * @param log4jContextMap
	 */
	public MCPReaper(Insight insight, String sessionId, InputStream is, OutputStream os, String toolbox,
			String requestUrl, Map<String, String> log4jContextMap, long idleTimeoutMinutes) {
		this.mode = Mode.HTTP_STREAM;
		this.insight = insight;
		this.sessionId = sessionId;
		this.is = is;
		this.os = os;
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
		if (this.mode == Mode.HTTP_STREAM) {
			runHttp();
		} else {
			runSse();
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
				classLogger.debug("HTTP RESPONSE :::: {}", output);

				if (output != null) {
					sendHttpEvent(output);
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
		classLogger.debug("Sending data {}", data);
		byte[] bytes = (data + "\n").getBytes(StandardCharsets.UTF_8);
		this.os.write(bytes);
		this.os.flush();
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
				classLogger.debug("SSE RESPONSE :::: {}", output);

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
				this.eventSink.close();
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

		// serialize concurrent calls on the same insight — multiple parallel
		// HTTP streaming connections from the same client share an insight instance
		synchronized (insight) {
			Boolean schedulerMode = ThreadStore.isSchedulerMode();
			if (schedulerMode != null) {
				insight.setSchedulerMode(schedulerMode);
			}
			return runPixelJob(user, insight, expression, jobId, insightId, sessionId, null, dropLogging);
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
		PixelJobThread jt = manager.makeJob(WebUtility.inputSanitizer(insightId), insight, sessionId, routeId);
		jobId = jt.getJobId();
		jt.addPixel(expression);
		jt.run();
		PixelRunner pixelRunner = jt.getRunner();
		List<NounMetadata> output = pixelRunner.getResults();
		// there are times when we spin up
		// other runPixel requests on the same
		// insight but don't want to drop the master insight
		// console logging
		// example is ExportToExcel grids
		if (dropLogging) {
			jt.setStatus(PixelJobStatus.COMPLETE);
			manager.clearJob(jobId);
			manager.removeJob(jobId);
		}
		if (output != null && !output.isEmpty()) {
			return output.get(0).getValue();
		}

		return null;
	}

	/**
	 * 
	 * @param json
	 * @param sessionId
	 * @param projectId
	 * @param insight
	 * @return
	 */
	private String generateResponse(String json, String sessionId, String projectId, Insight insight) {
		JSONObject response = new JSONObject();
		JSONObject root = null;
		try {
			root = new JSONObject(json);
		} catch (org.json.JSONException e) {
			/*
			 * { "jsonrpc": "2.0", "id": null, "error": { "code": -32700, "message":
			 * "Parse error - Invalid JSON was received by the server" } }
			 */
			response.put("id", "null");
			response.put("jsonrpc", "2.0");
			JSONObject error = new JSONObject();
			error.put("code", MCPErrorCode.PARSE_ERROR.getCode());
			error.put("message", MCPErrorCode.PARSE_ERROR.getDescription());
			response.put("error", error);
			return response.toString();
		}

		String method = root.getString("method");

		// Handle notifications (no response required)
		if (isNotification(method)) {
			handleNotification(method, root);
			return null; // No response for notifications
		}

		if (!root.has("id")) {
			/*
			 * { "jsonrpc": "2.0", "id": null, "error": { "code": -32600, "message":
			 * "Invalid Request - Missing required 'id' field" } }
			 */
			response.put("id", "null");
			response.put("jsonrpc", "2.0");
			JSONObject error = new JSONObject();
			error.put("code", MCPErrorCode.INVALID_REQUEST.getCode());
			error.put("message", "Invalid Request - Missing required 'id' field");
			response.put("error", error);
			return response.toString();
		}

		int id = root.getInt("id");
		response.put("id", id);
		response.put("jsonrpc", "2.0");

		if (method.equals("initialize")) {
			// {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05",
			// "capabilities":{"experimental":{},"prompts":{"listChanged":false},
			// "resources":{"subscribe":false,"listChanged":false},
			// "tools":{"listChanged":false}},
			// "serverInfo":{"name":"Stock Price Server","version":"1.8.0"}}}

			if (!root.has("params")) {
				JSONObject error = new JSONObject();
				error.put("code", MCPErrorCode.INVALID_REQUEST.getCode());
				error.put("message", "Invalid Request - Missing required 'params.protocolVersion' field");
				response.put("error", error);
				return response.toString();
			}
			JSONObject params = root.getJSONObject("params");
			if (!params.has("protocolVersion")) {
				JSONObject error = new JSONObject();
				error.put("code", MCPErrorCode.INVALID_REQUEST.getCode());
				error.put("message", "Invalid Request - Missing required 'protocolVersion' field");
				response.put("error", error);
				return response.toString();
			}
			String protocolVersion = params.getString("protocolVersion");
			String expression = "InitMCP(project='" + projectId + "', protocolVersion='" + protocolVersion + "');";
			JSONObject resultMap = (JSONObject) runPixel(insight.getUser(), insight, expression, sessionId);
			response.put("result", resultMap);
		}
		// {"method":"tools/list","params":{},"jsonrpc":"2.0","id":1}
		else if (method.equalsIgnoreCase("tools/list")) {
			// {"jsonrpc":"2.0","id":1,
			// "result":{
			// "tools":[{"name":"get_stock_price","description":"\n Retrieve the current
			// stock price for the given ticker symbol.\n Returns the latest closing price
			// as a float.\n ",
			// "inputSchema":{"properties":{"symbol":{"title":"Symbol","type":"string"}},"required":["symbol"],
			// "title":"get_stock_priceArguments","type":"object"}
			// }
			// , {"name":"get_stock_history","description":"\n Retrieve historical data for
			// a stock given a ticker symbol and a period.\n Returns the historical data as
			// a CSV formatted string.\n \n Parameters:\n symbol: The stock ticker symbol.\n
			// period: The period over which to retrieve historical data (e.g., '1mo',
			// '3mo', '1y').\n ",
			// "inputSchema":{"properties":{"symbol":{"title":"Symbol","type":"string"},"period":{"default":"1mo","title":"Period","type":"string"}},"required":["symbol"],"title":"get_stock_historyArguments","type":"object"}},{"name":"compare_stocks","description":"\n
			// Compare the current stock prices of two ticker symbols.\n Returns a formatted
			// message comparing the two stock prices.\n \n Parameters:\n symbol1: The first
			// stock ticker symbol.\n symbol2: The second stock ticker symbol.\n
			// ","inputSchema":{"properties":{"symbol1":{"title":"Symbol1","type":"string"},"symbol2":{"title":"Symbol2","type":"string"}},"required":["symbol1","symbol2"],"title":"compare_stocksArguments","type":"object"}}]}}

			// {"jsonrpc":"2.0",
			// "result":{"tools":[{"name":"get_stock_price",
			// "inputSchema":{"title":"get_stock_priceArguments","type":"object",
			// "properties":{"symbol":{"title":"symbol","type":"string"}},"required":["symbol"]},"description":"\n
			// Retrieve the current stock price for the given ticker symbol.\n Returns the
			// latest closing price as a float.\n "}]},
			// "id":1}

			String expression = "GetMCPTools(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject) runPixel(insight.getUser(), insight, expression, sessionId);
			response.put("result", toolMap);
		}
		// {"method":"tools/list","params":{},"jsonrpc":"2.0","id":2}
		else if (method.equalsIgnoreCase("resources/list")) {
			// {"jsonrpc":"2.0","id":3,"result":{"resources":[]}}
			String expression = "GetMCPResources(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject) runPixel(insight.getUser(), insight, expression, sessionId);
			response.put("result", toolMap);
		} else if (method.equalsIgnoreCase("resources/templates/list")) {
			// {"jsonrpc":"2.0","id":3,"result":{"resources":[]}}
			String expression = "GetMCPResourcesTemplates(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject) runPixel(insight.getUser(), insight, expression, sessionId);
			response.put("result", toolMap);
		}
		// {"method":"resources/list","params":{},"jsonrpc":"2.0","id":3}
		else if (method.equalsIgnoreCase("prompts/list")) {
			// {"jsonrpc":"2.0","id":4,"result":{"prompts":[]}}
			String expression = "GetMCPPrompts(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject) runPixel(insight.getUser(), insight, expression, sessionId);
			response.put("result", toolMap);
		}
		// {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOG"}}}
		else if (method.equalsIgnoreCase("tools/call")) {
			// {"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"334.07000732421875"}],"isError":false}}
			// {"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"334.07000732421875"}],"isError":false}}
			String callName = root.getJSONObject("params").getString("name");
			String arguments = "[" + root.getJSONObject("params").get("arguments") + "]";
			String pixel = "RunMCPTool(project='" + projectId + "'" + ", function='" + callName + "'" + ", paramValues="
					+ arguments + ");";

			classLogger.info("Making call to {}", callName);
			Object retObject = null;
			try {
				retObject = runPixel(insight.getUser(), insight, pixel, sessionId);
				Map<String, Object> resultMap = new HashMap<>();
				List<Map<String, Object>> contentList = new ArrayList<>();
				Map<String, Object> contentMap = new HashMap<>();
				contentMap.put("type", "text");
				contentMap.put("text", retObject);

				contentList.add(contentMap);
				resultMap.put("content", contentList);
				resultMap.put("isError", false);
				response.put("result", resultMap);
			} catch (SemossMCPException e) {
				/*
				 * { "jsonrpc": "2.0", "id": 3, "error": { "code": <example code>, "message":
				 * <example message> } }
				 */
				JSONObject error = new JSONObject();
				error.put("code", e.getError().getCode());
				if (e.getMessage() != null) {
					error.put("message", e.getMessage());
				} else {
					error.put("message", e.getError().getDescription());
				}
				response.put("error", error);
			} catch (Exception e) {
				JSONObject error = new JSONObject();
				error.put("code", MCPErrorCode.TOOL_EXECUTION_FAILED.getCode());
				if (e.getMessage() != null) {
					error.put("message", e.getMessage());
				} else {
					error.put("message", MCPErrorCode.TOOL_EXECUTION_FAILED.getDescription());
				}
				response.put("error", error);
			}
		}

		// {"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOGL"}},"jsonrpc":"2.0","id":5}
		return response.toString();
	}

	/**
	 * 
	 * @param method
	 * @return
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
			if (root.has("params") && root.getJSONObject("params").has("requestId")) {
				Object requestId = root.getJSONObject("params").get("requestId");
				classLogger.info("Request cancelled: {}", requestId);
			}
		} else if (method.equals("notifications/progress")) {
			if (root.has("params")) {
				classLogger.info("Progress update: {}", root.getJSONObject("params"));
			}
		} else if (method.equals("notifications/message")) {
			if (root.has("params")) {
				classLogger.info("Message notification: {}", root.getJSONObject("params"));
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
