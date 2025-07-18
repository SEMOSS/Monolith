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

public class MCPSSEReaper implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(MCPSSEReaper.class);

	User user = null;
	Insight insight = null;
	String sessionId = null;
	BufferedReader reader = null;
	SseEventSink eventSink = null;
	Sse sse = null;
	String toolbox = null;
	
	public MCPSSEReaper(User user, Insight insight, String sessionId, BufferedReader reader, SseEventSink eventSink, Sse sse, String toolbox)
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
			// This loop will now block and wait for new lines from the client indefinitely
			while ((actualContent = reader.readLine()) != null)
			{
				classLogger.info("INTERACTIVE REQUEST ::::    " + actualContent);
				// Stream the file in chunks
				String output = generateResponse(actualContent, sessionId, toolbox, insight);
				classLogger.info("INTERACTIVE RESPONSE ::::    " + output);

				if (output != null && !eventSink.isClosed()) {
					final OutboundSseEvent event = sse.newEventBuilder()
							.id(String.valueOf(System.currentTimeMillis()))
							.name("message-to-client")
							.data(String.class, output)
							.build();
					eventSink.send(event);
				}
			}
		} catch (IOException e) {
			// Client has likely disconnected
			classLogger.warn("Client disconnected from interactive session: " + e.getMessage());
		} finally {
			if(eventSink != null) {
				eventSink.close();
			}
			classLogger.debug("Done with interactive thread !!");
		}
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

		// set in thread
		ThreadStore.setInsightId(WebUtility.inputSanitizer(insightId));
		ThreadStore.setSessionId(sessionId);
		ThreadStore.setRouteId(routeId);
		ThreadStore.setJobId(jobId);
		ThreadStore.setUser(user);

		String job = null;
		if(routeId == null || routeId.isEmpty()) {
			job = "META | Job(\"" + jobId + "\", \"" + insightId + "\", \"" + sessionId + "\", \"" + routeId + "\");";
		} else {
			job = "META | Job(\"" + jobId + "\", \"" + insightId + "\", \"" + sessionId + "\");";
		}
		// add the job first
		// so we can do things like logging
		jt.addPixel(job);
		// then add the expression
		jt.addPixel(expression);
		//jt.setInsight(insight);
		jt.run();
		PixelRunner pixelRunner = jt.getRunner();
		List <NounMetadata> output = pixelRunner.getResults();
		
		if(dropLogging) {
			jt.setStatus(PixelJobStatus.COMPLETE);
			manager.clearJob(jobId);
			manager.removeJob(jobId);			
		}
		if(output != null && output.size() > 0)
			return output.get(1).getValue();
		
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
				String expression = "InitMCP(project='" + projectId + "');";
				JSONObject resultMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				response.put("result", resultMap);
			}
			else if(method.equalsIgnoreCase("tools/list"))
			{
				String expression = "GetMCPTools(project='" + projectId + "');";
				JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				response.put("result", toolMap);
			}
			else if(method.equalsIgnoreCase("resources/list"))
			{
				String expression = "GetMCPResources(project='" + projectId + "');";
				JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				response.put("result", toolMap);
			}
			else if(method.equalsIgnoreCase("prompts/list"))
			{
				String expression = "GetMCPTools(project='" + projectId + "');";
				JSONObject toolMap = (JSONObject)runPixel(insight.getUser()	,insight, expression, sessionId);
				response.put("result", toolMap);
			}
			else if(method.equalsIgnoreCase("tools/call"))
			{
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

				Map<String, Object> resultMap = new HashMap<>();
				List<Map<String, Object>> contentList = new ArrayList<>();
				Map<String, Object> contentMap = new HashMap<>();
				contentMap.put("type", "text");
				contentMap.put("text", retObject);
				contentList.add(contentMap);
	
				resultMap.put("content", contentList);
				resultMap.put("isError", error);
				
				response.put("result", resultMap);
			}
			
			return response.toString();
		}			
		else
			return null;
	}
}