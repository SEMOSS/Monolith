package prerna.semoss.web.services.local;

import java.io.InputStream;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Standard MCP endpoint at /mcp for ChatGPT compatibility
 * This acts as a proxy to the actual MCPResource implementation
 */
@Singleton
@Path("/mcp")
@PermitAll
public class StandardMCPResource {

	private static final Logger classLogger = LogManager.getLogger(StandardMCPResource.class);
	private Map<String, Insight> mcpThread = new HashMap<>();

	// Default toolbox - use _all_projects to aggregate tools from all accessible projects
	private static final String DEFAULT_TOOLBOX = "_all_projects";

	/**
	 * Standard MCP endpoint for ChatGPT
	 * Handles JSON-RPC 2.0 requests at /mcp
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleMCPRequest(String jsonBody, @Context HttpServletRequest request) {
		classLogger.info("Standard MCP endpoint called at /mcp");
		classLogger.info("MCP request body: " + jsonBody);

		try {
			HttpSession session = request.getSession(false);
			if (session == null) {
				classLogger.error("No session found for MCP request");
				return Response.status(401)
					.entity("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\"Unauthorized - No session\"}}")
					.build();
			}

			String sessionId = session.getId();
			String authorization = request.getHeader("Authorization");

			// Get or create insight
			Insight insight = null;
			if (!mcpThread.containsKey(authorization)) {
				insight = initSession(session);
				if (insight != null) {
					mcpThread.put(authorization, insight);
				}
			} else {
				insight = mcpThread.get(authorization);
			}

			if (insight == null) {
				classLogger.error("Could not create insight for MCP request");
				return Response.status(500)
					.entity("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Could not create insight\"}}")
					.build();
			}

			User user = insight.getUser();
			classLogger.info("Processing MCP request for user: " + user.getPrimaryLogin());

			// Process the JSON-RPC request using MCPReaper's logic
			// Use DEFAULT_TOOLBOX to aggregate tools from all accessible projects
			MCPReaper reaper = new MCPReaper(user, insight, sessionId, DEFAULT_TOOLBOX,
					ThreadContext.getImmutableContext());
			String responseJson = reaper.processJsonRpcRequest(jsonBody);

			classLogger.info("MCP response: " + responseJson);
			return Response.ok(responseJson).build();

		} catch (Exception e) {
			classLogger.error("Error processing MCP request", e);
			return Response.status(500)
				.entity("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error: " +
					e.getMessage().replace("\"", "\\\"") + "\"}}")
				.build();
		}
	}

	/**
	 * Initialize session and create insight
	 */
	private Insight initSession(HttpSession session) {
		if (session != null) {
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			String insightId = (String) session.getAttribute(Constants.INSIGHT);
			String sessionId = session.getId();
			Insight insight = null;

			// insight id could be null
			if (insightId == null) {
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
				ZoneId zoneId = ZoneId.of(Utility.getApplicationZoneId());
				user.setZoneId(zoneId);
				session.setAttribute(Constants.INSIGHT, insightId);
			} else {
				insight = InsightStore.getInstance().get(insightId);
			}

			// set the user
			insight.setUser(user);
			return insight;
		}
		return null;
	}
}
