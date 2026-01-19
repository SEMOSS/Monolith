package prerna.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class MCPReaper implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(MCPReaper.class);

	private User user = null;
	private Insight insight = null;
	private String sessionId = null;
	private BufferedReader reader = null;
	private SseEventSink eventSink = null;
	private Sse sse = null;
	private String toolbox = null;
	private boolean done = false;

	private Map<String, String> log4jContextMap;

	/**
	 * Constructor for SSE mode
	 * @param user
	 * @param insight
	 * @param sessionId
	 * @param reader
	 * @param eventSink
	 * @param sse
	 * @param toolbox
	 * @param map
	 */
	public MCPReaper(User user, Insight insight, String sessionId, BufferedReader reader, SseEventSink eventSink,
			Sse sse, String toolbox, Map<String, String> log4jContextMap) {
		this.user = user;
		this.insight = insight;
		this.sessionId = sessionId;
		this.reader = reader;
		this.eventSink = eventSink;
		this.toolbox = toolbox;
		this.sse = sse;

		if (log4jContextMap == null) {
			this.log4jContextMap = new HashMap<>();
		} else {
			this.log4jContextMap = log4jContextMap;
		}
	}

	/**
	 * Constructor for synchronous JSON mode (ChatGPT)
	 * @param user
	 * @param insight
	 * @param sessionId
	 * @param toolbox
	 * @param log4jContextMap
	 */
	public MCPReaper(User user, Insight insight, String sessionId, String toolbox, Map<String, String> log4jContextMap) {
		this.user = user;
		this.insight = insight;
		this.sessionId = sessionId;
		this.toolbox = toolbox;

		if (log4jContextMap == null) {
			this.log4jContextMap = new HashMap<>();
		} else {
			this.log4jContextMap = log4jContextMap;
		}
	}

	/**
	 * Process a JSON-RPC request synchronously (for ChatGPT)
	 * @param jsonBody
	 * @return JSON-RPC response
	 */
	public String processJsonRpcRequest(String jsonBody) {
		try (var ctx = org.apache.logging.log4j.CloseableThreadContext.putAll(this.log4jContextMap)) {
			classLogger.info("Processing JSON-RPC request: " + jsonBody);
			String response = generateResponse(jsonBody, sessionId, toolbox, insight);
			classLogger.info("Generated JSON-RPC response: " + response);
			return response;
		}
	}

	@Override
	public void run() {
		try (var ctx = org.apache.logging.log4j.CloseableThreadContext.putAll(this.log4jContextMap)) {
			String actualContent = null;
			while ((actualContent = reader.readLine()) != null && !done) // that will block every time.. we are good
			{
				classLogger.info("REQUEST :::: " + actualContent);
				// Stream the file in chunks
				String output = generateResponse(actualContent, sessionId, toolbox, insight);
				classLogger.info("RESPONSE :::: " + output);

				if (output != null) {
					OutboundSseEvent event = sse.newEventBuilder().data(String.class, output).build();
					eventSink.send(event);
				}
				done = true;
			}
		} catch (IOException e) {
			classLogger.error(Constants.STACKTRACE, e);
			/*
			 * { "jsonrpc": "2.0", "id": 4, "result": { "content": [ { "type": "text",
			 * "text": "Failed to fetch weather data: API rate limit exceeded" } ],
			 * "isError": true } }
			 */
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
		}

		classLogger.debug("Done with thread !!");
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
		// get the session
		// see if the user is available
		// get the insight from it
		// if not make a new insight
		// set up pixel runner and get output from it
		// pass it back.
		boolean dropLogging = true;

		String jobId = "";
		String insightId = WebUtility.inputSanitizer(insight.getInsightId());

		// set if we are scheduler mode
		Boolean schedulerMode = ThreadStore.isSchedulerMode();
		if (schedulerMode != null) {
			insight.setSchedulerMode(schedulerMode);
		}

		return runPixelJob(user, insight, expression, jobId, insightId, sessionId, null, dropLogging);
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

			classLogger.info("Making call to " + callName);
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
		return method.startsWith("notifications/") || method.equals("notifications/initialized")
				|| method.equals("notifications/cancelled") || method.equals("notifications/progress")
				|| method.equals("notifications/message") || method.equals("notifications/resources/updated")
				|| method.equals("notifications/tools/list_changed")
				|| method.equals("notifications/prompts/list_changed");
	}

	/**
	 * 
	 * @param method
	 * @param root
	 */
	private void handleNotification(String method, JSONObject root) {
		if (method.equalsIgnoreCase("notifications/initialized")) {
			classLogger.info("Client initialization complete");
			// Perform any post-initialization setup here
			// Don't think we have any at this time

		} else if (method.equalsIgnoreCase("notifications/cancelled")) {
			if (root.has("params") && root.getJSONObject("params").has("requestId")) {
				Object requestId = root.getJSONObject("params").get("requestId");
				classLogger.info("Request cancelled: " + requestId);
				// Don't have a way to cancel at this time ...
			}
		} else if (method.equalsIgnoreCase("notifications/progress")) {
			if (root.has("params")) {
				JSONObject params = root.getJSONObject("params");
				classLogger.info("Progress update: " + params.toString());
			}
		} else if (method.equalsIgnoreCase("notifications/message")) {
			if (root.has("params")) {
				JSONObject params = root.getJSONObject("params");
				classLogger.info("Message notification: " + params.toString());
			}
		} else if (method.equalsIgnoreCase("notifications/resources/updated")) {
			classLogger.info("Resources updated notification received");
			// Handle resource updates
			// Don't think we have any at this time

		} else if (method.equalsIgnoreCase("notifications/tools/list_changed")) {
			classLogger.info("Tools list changed notification received");
			// Handle tools list changes
			// Don't think we have any at this time

		} else if (method.equalsIgnoreCase("notifications/prompts/list_changed")) {
			classLogger.info("Prompts list changed notification received");
			// Handle prompts list changes
			// Don't think we have any at this time

		} else {
			classLogger.warn("Unknown notification method: " + method);
		}
	}

}
