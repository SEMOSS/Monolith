package prerna.semoss.web.services.local;

import java.io.InputStream;

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
import prerna.util.MCP.MCPUrlUtility;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

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

	/**
	 * Standard MCP endpoint for ChatGPT with project ID
	 * Handles JSON-RPC 2.0 requests at /mcp/{project_id}
	 */
	@POST
	@Path("/{project_id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleMCPRequest(
			@PathParam("project_id") String projectId,
			String jsonBody,
			@Context HttpServletRequest request) {
		classLogger.info("Standard MCP endpoint called at /mcp/" + projectId);
		classLogger.info("MCP request body: " + jsonBody);

		try {
			// Get user from session (authenticated by MCPProjectAuthFilter)
			HttpSession session = request.getSession(false);
			if (session == null) {
				classLogger.error("No session found - MCPProjectAuthFilter should have created one");
				return Response.status(401).entity("{\"error\":\"No session\"}").build();
			}

			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user == null) {
				classLogger.error("No authenticated user in session");
				return Response.status(401).entity("{\"error\":\"Not authenticated\"}").build();
			}

			classLogger.info("User from session: " + user.getPrimaryLogin());
			String sessionId = session.getId();

			// Get or create insight (cache per session + project)
			Insight insight = null;
			String cacheKey = sessionId + "_" + projectId;
			if (!mcpThread.containsKey(cacheKey)) {
				insight = initSession(session);
				if (insight != null) {
					mcpThread.put(cacheKey, insight);
				}
			} else {
				insight = mcpThread.get(cacheKey);
			}

			if (insight == null) {
				classLogger.error("Could not create insight for MCP request");
				return Response.status(500)
					.entity("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Could not create insight\"}}")
					.build();
			}

			// Ensure the insight has the authenticated user
			insight.setUser(user);
			classLogger.info("Processing MCP request for user: " + user.getPrimaryLogin() + " and project: " + projectId);

			// Process the JSON-RPC request using MCPReaper's logic
			// Pass the specific project ID from the URL
			MCPReaper reaper = new MCPReaper(user, insight, sessionId, projectId,
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

	/**
	 * OAuth Authorization Server Metadata
	 * ChatGPT discovers OAuth by requesting this endpoint
	 */
	@GET
	@Path("/.well-known/oauth-authorization-server")
	@Produces("application/json")
	public Response getAuthorizationServerMetadata(@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OAuth Authorization Server Metadata endpoint called <<<<<");
		try {
			String baseUrl = getBaseUrlForMCP(request);
			classLogger.info("MCP Base URL: " + baseUrl);

			Map<String, Object> metadata = new HashMap<>();
			metadata.put("issuer", baseUrl + "/api/mcp");
			metadata.put("authorization_endpoint", baseUrl + "/api/auth/mcp/authorize");
			metadata.put("token_endpoint", baseUrl + "/api/auth/mcp/token");
			metadata.put("registration_endpoint", baseUrl + "/api/auth/oauth/register");
			metadata.put("response_types_supported", java.util.Arrays.asList("code"));
			metadata.put("grant_types_supported",
				java.util.Arrays.asList("authorization_code", "refresh_token"));
			metadata.put("code_challenge_methods_supported", java.util.Arrays.asList("S256"));
			metadata.put("token_endpoint_auth_methods_supported", java.util.Arrays.asList("none"));
			metadata.put("subject_types_supported", java.util.Arrays.asList("public"));
			metadata.put("token_endpoint_auth_signing_alg_values_supported",
				java.util.Arrays.asList("RS256"));
			metadata.put("scopes_supported", java.util.Arrays.asList("mcp.read", "mcp.write", "projects.read"));

			classLogger.info("Returning MCP OAuth metadata: " + metadata);
			return WebUtility.getResponse(metadata, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "internal_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * OpenID Configuration (alias for oauth-authorization-server)
	 * Some clients request this instead
	 */
	@GET
	@Path("/.well-known/openid-configuration")
	@Produces("application/json")
	public Response getOpenIDConfiguration(@Context HttpServletRequest request) {
		return getAuthorizationServerMetadata(request);
	}

	/**
	 * OAuth Authorization Server Metadata with project ID in path
	 * ChatGPT appends /.well-known/oauth-authorization-server to the MCP server URL
	 */
	@GET
	@Path("/{project_id}/.well-known/oauth-authorization-server")
	@Produces("application/json")
	public Response getAuthorizationServerMetadataWithProject(@PathParam("project_id") String projectId,
			@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OAuth Authorization Server Metadata endpoint called for project: " + projectId + " <<<<<");
		return getAuthorizationServerMetadata(request);
	}

	/**
	 * OpenID Configuration with project ID in path
	 */
	@GET
	@Path("/{project_id}/.well-known/openid-configuration")
	@Produces("application/json")
	public Response getOpenIDConfigurationWithProject(@PathParam("project_id") String projectId,
			@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OpenID Configuration endpoint called for project: " + projectId + " <<<<<");
		return getAuthorizationServerMetadata(request);
	}

	/**
	 * Protected Resource Metadata (RFC 9728)
	 * Required by MCP spec for ChatGPT to discover the resource server configuration
	 */
	@GET
	@Path("/.well-known/oauth-protected-resource")
	@Produces("application/json")
	public Response getProtectedResourceMetadata(@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OAuth Protected Resource Metadata endpoint called <<<<<");
		try {
			String baseUrl = getBaseUrlForMCP(request);
			String mcpResourceUrl = baseUrl + "/api/mcp";
			classLogger.info("MCP Resource URL: " + mcpResourceUrl);

			Map<String, Object> metadata = new HashMap<>();
			metadata.put("resource", mcpResourceUrl);
			metadata.put("authorization_servers", java.util.Arrays.asList(mcpResourceUrl));
			metadata.put("scopes_supported", java.util.Arrays.asList("mcp.read", "mcp.write", "projects.read"));
			metadata.put("resource_documentation", baseUrl + "/docs/mcp");
			metadata.put("token_endpoint_auth_methods_supported", java.util.Arrays.asList("none"));

			classLogger.info("Returning MCP Protected Resource metadata: " + metadata);
			return WebUtility.getResponse(metadata, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "internal_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * Protected Resource Metadata with project ID in path
	 */
	@GET
	@Path("/{project_id}/.well-known/oauth-protected-resource")
	@Produces("application/json")
	public Response getProtectedResourceMetadataWithProject(@PathParam("project_id") String projectId,
			@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OAuth Protected Resource Metadata endpoint called for project: " + projectId + " <<<<<");
		return getProtectedResourceMetadata(request);
	}

	/**
	 * Helper method to construct base URL for MCP OAuth
	 * Used by OAuth metadata endpoints
	 */
	private String getBaseUrlForMCP(HttpServletRequest request) {
		String baseUrl = MCPUrlUtility.getExternalBaseUrl(request);
		classLogger.info("Base URL for MCP: " + baseUrl);
		return baseUrl;
	}
}
