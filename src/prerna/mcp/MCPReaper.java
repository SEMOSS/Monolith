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
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.comm.PixelJobThread;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.web.services.util.WebUtility;

public class MCPReaper implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(MCPReaper.class);

	User user = null;
	Insight insight = null;
	String sessionId = null;
	BufferedReader reader = null;
	SseEventSink eventSink = null;
	Sse sse = null;
	String toolbox = null;
	public boolean done = false;
	
	public MCPReaper(User user, Insight insight, String sessionId, BufferedReader reader, SseEventSink eventSink, Sse sse, String toolbox)
	{
		this.user = user;
		this.insight = insight;
		this.sessionId = sessionId;
		this.reader = reader;
		this.eventSink = eventSink;
		this.toolbox = toolbox;
		this.sse = sse;
	}
	

	@Override
	public void run() {
		try {
			String actualContent = null;
			while ((actualContent = reader.readLine()) != null && !done) // that will block everytime.. we are good
			{
				classLogger.info("REQUEST ::::    " + actualContent);
				// Stream the file in chunks
				String output = generateResponse(actualContent, sessionId, toolbox, insight);
				classLogger.info("RESPONSE ::::    " + actualContent);

				if (output != null) {
					final OutboundSseEvent event = sse.newEventBuilder()
							// .name("message-to-client")
							.data(String.class, output).build();
					eventSink.send(event);
				}
				done = true;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			// close it now
		}
		classLogger.debug("Done with thread !!");
	}
	
	private Object runPixel(User user, Insight insight, String expression, String sessionId)
	{
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
		if(output != null && output.size() > 0)
			return output.get(0).getValue();
		
		return null;
	}
	
	private String generateResponse(String json, String sessionId, String projectId, Insight insight)
	{
		JSONObject response = new JSONObject();
		JSONObject root = new JSONObject(json);
		if(root.has("id"))
		{
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
				/*
				Map resultMap = new HashMap();
				resultMap.put("protocolVersion", "2024-11-05");
				
				Map serverMap = new HashMap();
				serverMap.put("name","Stock Price Server");
				serverMap.put("version","1.8.0");
				resultMap.put("serverInfo", serverMap);
				
				
				Map capabilitiesMap = new HashMap();
				capabilitiesMap.put("experimental", new JSONObject());
				
				Map promptMap = new HashMap();
				promptMap.put("listChanged", false);
				capabilitiesMap.put("prompts", promptMap);
				
				Map resourcesMap = new HashMap();
				resourcesMap.put("listChanged", false);
				resourcesMap.put("subscribe", false);
				capabilitiesMap.put("resources", resourcesMap);
				
				Map toolsMap = new HashMap();
				toolsMap.put("listChanged", false);
				capabilitiesMap.put("tools", toolsMap);
	
				resultMap.put("capabilities", capabilitiesMap);
				*/
				String expression = "InitMCP(project='" + projectId + "');";
				JSONObject resultMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				
				response.put("result", resultMap);
				
			}
			//{"method":"tools/list","params":{},"jsonrpc":"2.0","id":1}
			if(method.equalsIgnoreCase("tools/list"))
			{
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
					
				/*
				
				Map resultMap = new HashMap();
				
				List toolList = new ArrayList();
				Map toolMap = new HashMap();
				toolMap.put("name", "get_stock_price");
				toolMap.put("description", "\n    Retrieve the current stock price for the given ticker symbol.\n    Returns the latest closing price as a float.\n    ");
				
				Map inputSchema = new HashMap();
				
				Map inputPropertiesMap = new HashMap();
				
				Map symbolMap = new HashMap();
				symbolMap.put("title", "symbol");
				symbolMap.put("type", "string");
				
				inputPropertiesMap.put("symbol", symbolMap);
				
				inputSchema.put("properties", inputPropertiesMap);
				
				List requiredList = new ArrayList();
				requiredList.add("symbol");
				
				inputSchema.put("required", requiredList);
				//"title":"get_stock_priceArguments","type":"object"
				inputSchema.put("title", "get_stock_priceArguments");
				inputSchema.put("type", "object");
				
				toolMap.put("inputSchema", inputSchema);
				toolList.add(toolMap);
				
				resultMap.put("tools", toolList);
				response.put("result", resultMap);

				Map periodMap = new HashMap();
				periodMap.put("default", "1mo");
				periodMap.put("default", "1mo");
				periodMap.put("default", "1mo");
				*/
				
				
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

				
				/*Map resultMap = new HashMap();
				List resourceList = new ArrayList();
				resultMap.put("resources", resourceList);
				response.put("result", resultMap);
				*/
			}
			//{"method":"resources/list","params":{},"jsonrpc":"2.0","id":3}
			if(method.equalsIgnoreCase("prompts/list"))
			{
				// {"jsonrpc":"2.0","id":4,"result":{"prompts":[]}}
				
				String expression = "GetMCPTools(project='" + projectId + "');";
				JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				response.put("result", toolMap);

				/*
				Map resultMap = new HashMap();	
				List promptList = new ArrayList();				
				resultMap.put("prompts", promptList);				
				response.put("result", resultMap);
				*/
	
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
				}catch (Exception ex)
				{
					retObject = ex.getMessage();
					error = true;
				}

				Map resultMap = new HashMap();
	
				List contentList = new ArrayList();
				Map contentMap = new HashMap();
				contentMap.put("type", "text");
				//contentMap.put("text", "159.08");
				// need to determine the output type
				contentMap.put("text", retObject);
				
				contentList.add(contentMap);
	
				resultMap.put("content", contentList);
				resultMap.put("isError", error);
				
				response.put("result", resultMap);
			}
			
			//{"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"GOOGL"}},"jsonrpc":"2.0","id":5}
			System.err.println(response.toString());		
			return response.toString();
		}			
		else
			return null;
	}


}
