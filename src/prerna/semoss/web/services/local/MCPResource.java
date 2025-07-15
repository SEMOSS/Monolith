package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.comm.PixelJobThread;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class MCPResource {

	// MCP remote communication - https://www.npmjs.com/package/mcp-remote

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);
	private Map<String, Insight> mcpThread = new HashMap<>();

	@POST
	@Path("/it")
	@Consumes(MediaType.APPLICATION_JSON) // Assume JSON input
	@Produces(MediaType.TEXT_PLAIN)
	public Response getInsightData(InputStream is)
	{
		classLogger.debug("Came into the MCP");
		StreamingOutput stream = output -> {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
				// Simulate processing input and generating streamed response
				for (int i = 0; i < 10; i++) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));                	
					String outputLine = "Processed: " + reader.readLine() + " - Item: " + i;
					writer.write(outputLine + "\n");
					writer.flush();  // Flush after each write to ensure streaming
					Thread.sleep(500); // Simulate some processing time
				}
			}
			catch (IOException | InterruptedException e) {
				throw new WebApplicationException(e); // Handle exception appropriately
			}
		};
		return Response.ok(stream).build();
	}

	@POST
	@Path("/comms")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void comms(@PathParam("toolbox_id") String toolbox_id , 
			@QueryParam("access_key") String access,
			@Context SseEventSink eventSink, 
			@Context Sse sse, InputStream is,
			@Context HttpServletRequest request) 
	{
		classLogger.debug("Runing tool.. " + toolbox_id);
		// initialize session
		String authorization = request.getHeader("Authorization");
		HttpSession session = request.getSession(false);
		String sessionId = session.getId();
		Insight insight = null;
		User user = null;
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));

		if(!mcpThread.containsKey(authorization))
		{
			insight = initSession(session);
			user = insight.getUser();
			mcpThread.put(authorization, insight);			
		}
		else
		{
			insight = mcpThread.get(authorization);
			user = insight.getUser();

		}
		MCPReaper reaper = new MCPReaper(user, insight, sessionId, reader, eventSink, sse, toolbox_id);			
		Thread t = new Thread(reaper);
		t.start();
	}

	/**
	 * 
	 * @param session
	 * @return
	 */
	private Insight initSession(HttpSession session) {
		if(session != null)
		{
			User user = (User)session.getAttribute(Constants.SESSION_USER);
			String insightId = (String)session.getAttribute(Constants.INSIGHT);
			String sessionId = session.getId();		
			Insight insight = null;
			// insight id could be null
			if (insightId == null)
			{
				Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
				if (sessionInsights == null || sessionInsights.isEmpty()) {
					// need to make a new insight here
					insight = new Insight();
					InsightStore.getInstance().put(insight);
					insightId = insight.getInsightId();
					InsightStore.getInstance().addToSessionHash(sessionId, insightId);
				} else {
					// pull the insight id from the session set
					insightId = sessionInsights.iterator().next();
					insight = InsightStore.getInstance().get(insightId);
				}			
				// get the zone id
				ZoneId zoneId = ZoneId.of(Utility.getApplicationZoneId());;
				user.setZoneId(zoneId);
				session.setAttribute(Constants.INSIGHT, insightId);
			}
			else
			{
				insight = InsightStore.getInstance().get(insightId);				
			}

			// set the user
			insight.setUser(user);		
			return insight;
		}
		return null;
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
		if(schedulerMode != null) {
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
	private static Object runPixelJob(User user, Insight insight, String expression, String jobId, 
			String insightId, String sessionId, String routeId, boolean dropLogging) 
	{
		PixelJobManager manager = PixelJobManager.getManager();
		PixelJobThread jt = manager.makeJob(WebUtility.inputSanitizer(insightId), insight, sessionId, routeId);
		jobId = jt.getJobId();
		jt.addPixel(expression);
		jt.run();
		PixelRunner pixelRunner = jt.getRunner();
		List <NounMetadata> output = pixelRunner.getResults();
		// there are times when we spin up
		// other runPixel requests on the same 
		// insight but don't want to drop the master insight
		// console logging
		// example is ExportToExcel grids 
		if(dropLogging) {
			jt.setStatus(PixelJobStatus.COMPLETE);
			manager.clearJob(jobId);
			manager.removeJob(jobId);			
		}
		if(output != null && output.size() > 0) {
			return output.get(1).getValue();
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
		JSONObject root = new JSONObject(json);

		if(!root.has("id")) {
			return null;
		}

		int id = root.getInt("id");
		response.put("id", id);
		response.put("jsonrpc","2.0");
		String method = root.getString("method");

		if(method.equals("initialize"))
		{
			//{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05",
			//"capabilities":{"experimental":{},"prompts":{"listChanged":false},
			//"resources":{"subscribe":false,"listChanged":false},
			//"tools":{"listChanged":false}},
			//"serverInfo":{"name":"Stock Price Server","version":"1.8.0"}}}
			String expression = "InitMCP(project='" + projectId + "');";
			JSONObject resultMap = (JSONObject) runPixel(insight.getUser()	,insight, expression, sessionId);

			response.put("result", resultMap);

		}
		//{"method":"tools/list","params":{},"jsonrpc":"2.0","id":1}
		if(method.equalsIgnoreCase("tools/list"))
		{
			// schema - I can only give example.. MCP doesnt put the spec properly
			// {"jsonrpc":"2.0","id":1,
			//"result":{
			//"tools":[{"name":"get_stock_price","description":"\n    Retrieve the current stock price for the given ticker symbol.\n    Returns the latest closing price as a float.\n    ",
			//	"inputSchema":{"properties":{"symbol":{"title":"Symbol","type":"string"}},"required":["symbol"], "title":"get_stock_priceArguments","type":"object"}
			//}
			//	,{"name":"get_stock_history","description":"\n    Retrieve historical data for a stock given a ticker symbol and a period.\n    Returns the historical data as a CSV formatted string.\n    \n    Parameters:\n        symbol: The stock ticker symbol.\n        period: The period over which to retrieve historical data (e.g., '1mo', '3mo', '1y').\n    ",
			// "inputSchema":{"properties":{"symbol":{"title":"Symbol","type":"string"},"period":{"default":"1mo","title":"Period","type":"string"}},"required":["symbol"],"title":"get_stock_historyArguments","type":"object"}},{"name":"compare_stocks","description":"\n    Compare the current stock prices of two ticker symbols.\n    Returns a formatted message comparing the two stock prices.\n    \n    Parameters:\n        symbol1: The first stock ticker symbol.\n        symbol2: The second stock ticker symbol.\n    ","inputSchema":{"properties":{"symbol1":{"title":"Symbol1","type":"string"},"symbol2":{"title":"Symbol2","type":"string"}},"required":["symbol1","symbol2"],"title":"compare_stocksArguments","type":"object"}}]}}

			//{"jsonrpc":"2.0",
			//"result":{"tools":[{"name":"get_stock_price",
			//"inputSchema":{"title":"get_stock_priceArguments","type":"object",
			//"properties":{"symbol":{"title":"symbol","type":"string"}},"required":["symbol"]},"description":"\n    Retrieve the current stock price for the given ticker symbol.\n    Returns the latest closing price as a float.\n    "}]},
			//"id":1}

			String expression = "GetMCPTools(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
			response.put("result", toolMap);

		}
		//{"method":"tools/list","params":{},"jsonrpc":"2.0","id":2}
		if(method.equalsIgnoreCase("resources/list"))
		{
			//{"jsonrpc":"2.0","id":3,"result":{"resources":[]}}
			String expression = "GetMCPResources(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
			response.put("result", toolMap);

		}
		//{"method":"resources/list","params":{},"jsonrpc":"2.0","id":3}
		if(method.equalsIgnoreCase("prompts/list"))
		{
			// {"jsonrpc":"2.0","id":4,"result":{"prompts":[]}}

			String expression = "GetMCPTools(project='" + projectId + "');";
			JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
			response.put("result", toolMap);


		}
		//{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOG"}}}
		if(method.equalsIgnoreCase("tools/call"))
		{
			//{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"334.07000732421875"}],"isError":false}}
			//{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"334.07000732421875"}],"isError":false}}
			String callName = root.getJSONObject("params").getString("name");
			String arguments = "[" + root.getJSONObject("params").get("arguments") + "]";
			String pixel = "RunMCPTool(project='" + projectId + "'"
					+ ", function='" + callName + "'"
					+ ", paramValues=" + arguments + ");";

			System.err.println("Making call to " + callName);
			Object retObject = pixel;
			boolean error = false;

			try {
				retObject = runPixel(insight.getUser()	,insight, pixel, sessionId);					
			} catch (Exception ex) {
				retObject = ex.getMessage();
				error = true;
			}

			Map resultMap = new HashMap();

			List contentList = new ArrayList();
			Map contentMap = new HashMap();
			contentMap.put("type", "text");
			// need to determine the output type
			contentMap.put("text", retObject);

			contentList.add(contentMap);

			resultMap.put("content", contentList);
			resultMap.put("isError", error);

			response.put("result", resultMap);
		}

		//{"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOGL"}},"jsonrpc":"2.0","id":5}
		classLogger.debug(response.toString());		
		return response.toString();
	}
}
