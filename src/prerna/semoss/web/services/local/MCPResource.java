package prerna.semoss.web.services.local;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
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

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
@SecurityRequirement(name = "basicAuth")
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
	public void comms(@PathParam("toolbox_id") String toolbox_id, 
			@QueryParam("access_key") String access,
			@Context SseEventSink eventSink, 
			@Context Sse sse, 
			InputStream is,
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
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			String insightId = (String) session.getAttribute(Constants.INSIGHT);
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

}
