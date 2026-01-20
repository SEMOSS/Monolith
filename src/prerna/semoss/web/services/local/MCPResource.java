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
package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.common.base.Strings;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IEngine;
import prerna.engine.api.IMCP;
import prerna.engine.impl.MCPFactory;
import prerna.io.connector.GenericTokenFiller;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.security.HttpHelperUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * MCP (Model Context Protocol) Resource for SEMOSS.
 *
 * Supports two transport mechanisms:
 * - SSE (Server-Sent Events) via /comms endpoint - for Claude Code and streaming clients
 * - HTTP POST with JSON-RPC via base endpoint - for ChatGPT and request/response clients
 *
 * Both transports use the same underlying IMCP interface for tool execution.
 */
@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class MCPResource {

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);
	private static final String MCP_OAUTH_STATE_KEY = "mcp_oauth_state";

	private final Map<String, Insight> mcpThread = new HashMap<>();
	private static final SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

	// ============================================================================
	// MCP Protocol Endpoints (JSON-RPC over HTTP - for ChatGPT)
	// ============================================================================

	/**
	 * Base MCP endpoint (GET) - returns server info and capabilities.
	 */
	@GET
	@Path("/")
	@Produces({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
	public Response getMCPServerInfo(
			@PathParam("toolbox_id") String toolboxId,
			@Context HttpServletRequest request) {
		return buildServerInfoResponse(toolboxId, request);
	}

	/**
	 * Base MCP endpoint (POST) - handles MCP JSON-RPC protocol messages.
	 * This is the main endpoint for ChatGPT MCP integration.
	 */
	@POST
	@Path("/")
	@Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
	@Produces({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
	public Response handleMCPRequest(
			@PathParam("toolbox_id") String toolboxId,
			InputStream is,
			@Context HttpServletRequest request) {

		try {
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

			if (requestBody == null || requestBody.trim().isEmpty()) {
				return buildServerInfoResponse(toolboxId, request);
			}

			JSONObject jsonRequest = new JSONObject(requestBody);

			if (!jsonRequest.has("method")) {
				return buildServerInfoResponse(toolboxId, request);
			}

			String method = jsonRequest.getString("method");
			classLogger.debug("MCP method: {}", method);

			switch (method) {
				case "initialize":
				case "mcp/initialize":
					return handleInitialize(toolboxId, jsonRequest);
				case "notifications/initialized":
				case "initialized":
					return Response.ok().build();
				case "tools/list":
					return handleToolsList(toolboxId, jsonRequest);
				case "tools/call":
					return handleToolsCall(toolboxId, jsonRequest, request);
				case "resources/list":
					return jsonRpcResponse(jsonRequest, new JSONObject().put("resources", new JSONArray()));
				case "prompts/list":
					return jsonRpcResponse(jsonRequest, new JSONObject().put("prompts", new JSONArray()));
				case "ping":
					return jsonRpcResponse(jsonRequest, new JSONObject());
				default:
					classLogger.warn("Unknown MCP method: {}", method);
					return jsonRpcError(jsonRequest, -32601, "Method not found: " + method);
			}

		} catch (Exception e) {
			classLogger.error("Error handling MCP request", e);
			return jsonRpcError(null, -32603, "Internal error: " + e.getMessage());
		}
	}

	// ============================================================================
	// MCP SSE Endpoint (for Claude Code and streaming clients)
	// ============================================================================

	/**
	 * SSE endpoint for streaming MCP communication.
	 * Used by Claude Code and other clients that support Server-Sent Events.
	 */
	@POST
	@Path("/comms")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void comms(
			@PathParam("toolbox_id") String toolboxId,
			@QueryParam("access_key") String access,
			@Context SseEventSink eventSink,
			@Context Sse sse,
			InputStream is,
			@Context HttpServletRequest request) {

		classLogger.debug("SSE connection for toolbox: {}", toolboxId);

		String authorization = request.getHeader("Authorization");
		HttpSession session = request.getSession(false);
		String sessionId = session.getId();
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));

		Insight insight;
		User user;

		if (!mcpThread.containsKey(authorization)) {
			insight = initSession(session);
			user = insight.getUser();
			mcpThread.put(authorization, insight);
		} else {
			insight = mcpThread.get(authorization);
			user = insight.getUser();
		}

		MCPReaper reaper = new MCPReaper(user, insight, sessionId, reader, eventSink, sse, toolboxId,
				ThreadContext.getImmutableContext());
		new Thread(reaper).start();
	}

	// ============================================================================
	// REST API Endpoints (for direct tool access)
	// ============================================================================

	/**
	 * List available tools (REST API).
	 */
	@GET
	@Path("/tools")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listTools(
			@PathParam("toolbox_id") String toolboxId,
			@Context HttpServletRequest request) {

		Response authError = checkAuth(request);
		if (authError != null) return authError;

		try {
			JSONObject tools = getMCPTools(toolboxId);
			if (tools == null) {
				return errorResponse("Toolbox not found: " + toolboxId, 404);
			}
			return Response.ok(tools.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			classLogger.error("Error listing tools", e);
			return errorResponse("Failed to list tools: " + e.getMessage(), 500);
		}
	}

	/**
	 * Execute a tool (REST API).
	 */
	@POST
	@Path("/tools/call")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response callToolRest(
			@PathParam("toolbox_id") String toolboxId,
			InputStream is,
			@Context HttpServletRequest request) {

		Response authError = checkAuth(request);
		if (authError != null) return authError;

		try {
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			JSONObject requestJson = new JSONObject(requestBody);

			String toolName = requestJson.getString("name");
			JSONObject arguments = requestJson.optJSONObject("arguments");
			if (arguments == null) {
				arguments = new JSONObject();
			}

			Object result = executeTool(toolboxId, toolName, arguments, request);
			return buildToolResultResponse(result, false);

		} catch (Exception e) {
			classLogger.error("Error executing tool", e);
			return buildToolResultResponse(e.getMessage(), true);
		}
	}

	/**
	 * Execute a specific tool by name (REST API).
	 */
	@POST
	@Path("/tools/{tool_name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response callToolByName(
			@PathParam("toolbox_id") String toolboxId,
			@PathParam("tool_name") String toolName,
			InputStream is,
			@Context HttpServletRequest request) {

		Response authError = checkAuth(request);
		if (authError != null) return authError;

		try {
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			JSONObject arguments = requestBody.trim().isEmpty() ? new JSONObject() : new JSONObject(requestBody);

			Object result = executeTool(toolboxId, toolName, arguments, request);
			return buildToolResultResponse(result, false);

		} catch (Exception e) {
			classLogger.error("Error executing tool: {}", toolName, e);
			return buildToolResultResponse(e.getMessage(), true);
		}
	}

	// ============================================================================
	// Discovery & Health Endpoints
	// ============================================================================

	/**
	 * Health check endpoint.
	 */
	@GET
	@Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	public Response healthCheck(@PathParam("toolbox_id") String toolboxId) {
		JSONObject health = new JSONObject();
		health.put("status", "healthy");
		health.put("toolbox_id", toolboxId);
		health.put("timestamp", System.currentTimeMillis());
		return Response.ok(health.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * OAuth Protected Resource Metadata (RFC 9728) - required for ChatGPT MCP OAuth.
	 * NOTE: This endpoint has been moved to WellKnownResource to ensure it works at the root path
	 * /.well-known/oauth-protected-resource as required by RFC 9728.
	 * The leading slash in @Path does not reliably override the parent class path in all JAX-RS implementations.
	 */
	/*
	@GET
	@Path("/.well-known/oauth-protected-resource")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOAuthProtectedResourceMetadata(
			@PathParam("toolbox_id") String toolboxId,
			@Context HttpServletRequest request) {

		String resourceUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolboxId;
		String authServerUrl = getAuthServerUrl();

		JSONObject metadata = new JSONObject();
		metadata.put("resource", resourceUrl);
		metadata.put("authorization_servers", new JSONArray().put(authServerUrl));
		metadata.put("scopes_supported", new JSONArray()
				.put("openid").put("profile").put("email").put("offline_access"));

		return Response.ok(metadata.toString()).type(MediaType.APPLICATION_JSON).build();
	}
	*/

	/**
	 * OpenAPI specification for ChatGPT Actions.
	 */
	@GET
	@Path("/openapi.json")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOpenApiSpec(
			@PathParam("toolbox_id") String toolboxId,
			@Context HttpServletRequest request) {

		Response authError = checkAuth(request);
		if (authError != null) return authError;

		try {
			JSONObject tools = getMCPTools(toolboxId);
			if (tools == null) {
				return errorResponse("Toolbox not found: " + toolboxId, 404);
			}
			JSONObject spec = buildOpenApiSpec(toolboxId, tools, request);
			return Response.ok(spec.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			classLogger.error("Error generating OpenAPI spec", e);
			return errorResponse("Failed to generate OpenAPI spec: " + e.getMessage(), 500);
		}
	}

	// ============================================================================
	// OAuth Endpoints
	// ============================================================================

	@GET
	@Path("/oauth/authorize")
	public Response initiateOAuth(
			@PathParam("toolbox_id") String toolboxId,
			@QueryParam("provider") String provider,
			@QueryParam("state") String state,
			@Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {

		provider = WebUtility.inputSanitizer(provider);

		if (socialData.getLoginsAllowed().get(provider) == null || !socialData.getLoginsAllowed().get(provider)) {
			return errorResponse(provider + " login is not allowed", 400);
		}

		HttpSession session = request.getSession(true);
		if (state != null && !state.isEmpty()) {
			session.setAttribute(MCP_OAUTH_STATE_KEY, state);
		}
		session.setAttribute("mcp_toolbox_id", toolboxId);

		String redirectUrl = buildOAuthRedirectUrl(provider);
		response.setStatus(302);
		response.sendRedirect(redirectUrl);
		return null;
	}

	@GET
	@Path("/oauth/callback/{provider}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleOAuthCallback(
			@PathParam("toolbox_id") String toolboxId,
			@PathParam("provider") String provider,
			@QueryParam("code") String code,
			@QueryParam("state") String state,
			@QueryParam("error") String error,
			@Context HttpServletRequest request) {

		provider = WebUtility.inputSanitizer(provider);

		if (error != null && !error.isEmpty()) {
			return errorResponse("OAuth error: " + error, 400);
		}

		HttpSession session = request.getSession(false);
		if (session != null) {
			String storedState = (String) session.getAttribute(MCP_OAUTH_STATE_KEY);
			if (storedState != null && !storedState.equals(state)) {
				return errorResponse("Invalid state parameter", 400);
			}
			session.removeAttribute(MCP_OAUTH_STATE_KEY);
		}

		if (code == null || code.isEmpty() || !code.matches("[ -~]+")) {
			return errorResponse("Invalid authorization code", 400);
		}

		try {
			AccessToken accessToken = exchangeCodeForToken(provider, code);
			if (accessToken == null) {
				return errorResponse("Failed to exchange code for access token", 401);
			}

			addAccessTokenToSession(accessToken, request);

			JSONObject result = new JSONObject();
			result.put("success", true);
			result.put("provider", provider);
			result.put("user_id", accessToken.getId());
			result.put("user_name", accessToken.getName());
			result.put("user_email", accessToken.getEmail());

			return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			classLogger.error("OAuth callback error", e);
			return errorResponse("OAuth failed: " + e.getMessage(), 500);
		}
	}

	@GET
	@Path("/oauth/userinfo")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getUserInfo(
			@PathParam("toolbox_id") String toolboxId,
			@Context HttpServletRequest request) {

		HttpSession session = request.getSession(false);
		if (session == null) {
			return errorResponse("No active session", 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return errorResponse("User not authenticated", 401);
		}

		JSONObject userInfo = new JSONObject();
		userInfo.put("user_id", user.getPrimaryLogin());

		AccessToken primaryToken = user.getPrimaryLoginToken();
		if (primaryToken != null) {
			userInfo.put("user_name", primaryToken.getName());
			userInfo.put("user_email", primaryToken.getEmail());
		}

		return Response.ok(userInfo.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	// ============================================================================
	// MCP JSON-RPC Handlers (Private)
	// ============================================================================

	private Response handleInitialize(String toolboxId, JSONObject jsonRequest) {
		IEngine engine = getEngine(toolboxId);
		if (engine == null) {
			return jsonRpcError(jsonRequest, -32602, "Toolbox not found: " + toolboxId);
		}

		String clientVersion = "2024-11-05";
		JSONObject params = jsonRequest.optJSONObject("params");
		if (params != null && params.has("protocolVersion")) {
			clientVersion = params.getString("protocolVersion");
		}

		JSONObject result = new JSONObject();
		result.put("protocolVersion", clientVersion);
		result.put("serverInfo", new JSONObject()
				.put("name", "SEMOSS MCP Server")
				.put("version", "1.0.0"));
		result.put("capabilities", new JSONObject()
				.put("tools", new JSONObject().put("listChanged", false)));

		return jsonRpcResponse(jsonRequest, result);
	}

	private Response handleToolsList(String toolboxId, JSONObject jsonRequest) {
		try {
			JSONObject tools = getMCPTools(toolboxId);
			if (tools == null) {
				return jsonRpcError(jsonRequest, -32602, "Toolbox not found: " + toolboxId);
			}

			JSONObject result = new JSONObject();
			result.put("tools", tools.optJSONArray("tools") != null ? tools.getJSONArray("tools") : new JSONArray());
			return jsonRpcResponse(jsonRequest, result);

		} catch (Exception e) {
			classLogger.error("Error in tools/list", e);
			return jsonRpcError(jsonRequest, -32603, e.getMessage());
		}
	}

	private Response handleToolsCall(String toolboxId, JSONObject jsonRequest, HttpServletRequest request) {
		try {
			JSONObject params = jsonRequest.optJSONObject("params");
			if (params == null) {
				return jsonRpcError(jsonRequest, -32602, "Missing params");
			}

			String toolName = params.optString("name");
			if (toolName == null || toolName.isEmpty()) {
				return jsonRpcError(jsonRequest, -32602, "Missing tool name");
			}

			JSONObject arguments = params.optJSONObject("arguments");
			if (arguments == null) {
				arguments = new JSONObject();
			}

			Object result = executeTool(toolboxId, toolName, arguments, request);

			JSONObject resultObj = new JSONObject();
			JSONArray content = new JSONArray();
			content.put(new JSONObject().put("type", "text").put("text", result != null ? result.toString() : "null"));
			resultObj.put("content", content);

			return jsonRpcResponse(jsonRequest, resultObj);

		} catch (Exception e) {
			classLogger.error("Error in tools/call", e);
			return jsonRpcError(jsonRequest, -32603, e.getMessage());
		}
	}

	// ============================================================================
	// Shared Helper Methods
	// ============================================================================

	/**
	 * Execute a tool - shared by both JSON-RPC and REST endpoints.
	 */
	private Object executeTool(String toolboxId, String toolName, JSONObject arguments, HttpServletRequest request) throws Exception {
		IEngine engine = getEngine(toolboxId);
		if (engine == null) {
			throw new IllegalArgumentException("Toolbox not found: " + toolboxId);
		}

		HttpSession session = request.getSession(false);
		Insight insight = initSession(session);
		if (insight == null) {
			throw new IllegalStateException("Failed to initialize session");
		}

		Map<String, Object> argsMap = new HashMap<>();
		for (String key : arguments.keySet()) {
			argsMap.put(key, arguments.get(key));
		}

		IMCP mcp = MCPFactory.build(engine);
		return mcp.callTool(toolName, argsMap, insight);
	}

	/**
	 * Get MCP tools for a toolbox.
	 */
	private JSONObject getMCPTools(String toolboxId) {
		IEngine engine = getEngine(toolboxId);
		if (engine == null) {
			return null;
		}
		IMCP mcp = MCPFactory.build(engine);
		return mcp.getMCPTools();
	}

	/**
	 * Get engine by ID (tries both engine and project).
	 */
	private IEngine getEngine(String engineId) {
		IEngine engine = null;
		try {
			engine = Utility.getEngine(engineId);
		} catch (Exception ex) {
			// ignore
		}
		if (engine == null) {
			try {
				engine = Utility.getProject(engineId);
			} catch (Exception ex) {
				// ignore
			}
		}
		return engine;
	}

	/**
	 * Initialize session and insight.
	 */
	private Insight initSession(HttpSession session) {
		if (session == null) {
			return null;
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
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
			ZoneId zoneId = ZoneId.of(Utility.getApplicationZoneId());
			user.setZoneId(zoneId);
			session.setAttribute(Constants.INSIGHT, insightId);
		} else {
			insight = InsightStore.getInstance().get(insightId);
		}

		insight.setUser(user);
		return insight;
	}

	/**
	 * Check authentication - returns error response if not authenticated, null if OK.
	 */
	private Response checkAuth(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return errorResponse("Authentication required", 401);
		}
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return errorResponse("User not authenticated", 401);
		}
		return null;
	}

	// ============================================================================
	// Response Builders
	// ============================================================================

	private Response jsonRpcResponse(JSONObject request, JSONObject result) {
		JSONObject response = new JSONObject();
		response.put("jsonrpc", "2.0");
		if (request != null && request.has("id")) {
			response.put("id", request.get("id"));
		}
		response.put("result", result);
		return Response.ok(response.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private Response jsonRpcError(JSONObject request, int code, String message) {
		JSONObject response = new JSONObject();
		response.put("jsonrpc", "2.0");
		if (request != null && request.has("id")) {
			response.put("id", request.get("id"));
		}
		response.put("error", new JSONObject().put("code", code).put("message", message));
		return Response.status(400).entity(response.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private Response errorResponse(String message, int status) {
		Map<String, Object> ret = new HashMap<>();
		ret.put(Constants.ERROR_MESSAGE, message);
		return WebUtility.getResponse(ret, status);
	}

	private Response buildToolResultResponse(Object result, boolean isError) {
		JSONObject response = new JSONObject();
		JSONArray content = new JSONArray();
		content.put(new JSONObject()
				.put("type", "text")
				.put("text", result != null ? result.toString() : "null"));
		response.put("content", content);
		response.put("isError", isError);

		int status = isError ? 400 : 200;
		return Response.status(status).entity(response.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private Response buildServerInfoResponse(String toolboxId, HttpServletRequest request) {
		try {
			JSONObject tools = getMCPTools(toolboxId);
			if (tools == null) {
				return errorResponse("Toolbox not found: " + toolboxId, 404);
			}

			String baseUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolboxId;

			JSONObject serverInfo = new JSONObject();
			serverInfo.put("name", "SEMOSS MCP Server");
			serverInfo.put("version", "1.0.0");
			serverInfo.put("toolbox_id", toolboxId);

			if (tools.has("tools")) {
				serverInfo.put("tools", tools.getJSONArray("tools"));
			}

			serverInfo.put("capabilities", new JSONObject()
					.put("tools", true)
					.put("resources", false)
					.put("prompts", false));

			serverInfo.put("endpoints", new JSONObject()
					.put("tools_list", baseUrl + "/tools")
					.put("tools_call", baseUrl + "/tools/call")
					.put("openapi", baseUrl + "/openapi.json")
					.put("health", baseUrl + "/health"));

			return Response.ok(serverInfo.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			classLogger.error("Error building server info", e);
			return errorResponse("Failed to get server info: " + e.getMessage(), 500);
		}
	}

	// ============================================================================
	// URL & OAuth Helpers
	// ============================================================================

	private String getExternalBaseUrl(HttpServletRequest request) {
		// First check configured URL
		String configuredUrl = socialData.getProperty("mcp_external_base_url");
		if (configuredUrl != null && !configuredUrl.isEmpty() && !configuredUrl.startsWith("<")) {
			return configuredUrl.endsWith("/") ? configuredUrl.substring(0, configuredUrl.length() - 1) : configuredUrl;
		}

		// Try to detect from headers
		String scheme = request.getHeader("X-Forwarded-Proto");
		if (scheme == null || scheme.isEmpty()) {
			String cfVisitor = request.getHeader("Cf-Visitor");
			scheme = (cfVisitor != null && cfVisitor.contains("https")) ? "https" : request.getScheme();
		}

		String host = request.getHeader("X-Forwarded-Host");
		if (host == null || host.isEmpty()) {
			host = request.getHeader("X-Original-Host");
		}
		if (host == null || host.isEmpty()) {
			host = request.getHeader("Host");
		}
		if (host == null || host.isEmpty()) {
			host = request.getServerName();
			int port = request.getServerPort();
			if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
				host += ":" + port;
			}
		}

		return scheme + "://" + host + request.getContextPath();
	}

	private String getAuthServerUrl() {
		String authServerUrl = socialData.getProperty("keycloak_realm_url");
		if (authServerUrl == null || authServerUrl.isEmpty() || authServerUrl.startsWith("<")) {
			String genericAuthUrl = socialData.getProperty("generic_auth_url");
			if (genericAuthUrl != null && genericAuthUrl.contains("/protocol/openid-connect")) {
				authServerUrl = genericAuthUrl.substring(0, genericAuthUrl.indexOf("/protocol/openid-connect"));
			} else {
				authServerUrl = "https://sso.semoss.org/realms/dev";
			}
		}
		return authServerUrl;
	}

	private String buildOAuthRedirectUrl(String provider) throws UnsupportedEncodingException {
		String prefix = provider + "_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");
		String authUrl = socialData.getProperty(prefix + "auth_url");

		if (Strings.isNullOrEmpty(authUrl)) {
			throw new IllegalArgumentException("Authorize URL cannot be null or empty");
		}

		String state = UUID.randomUUID().toString();
		String separator = authUrl.contains("?") ? "&" : "?";

		return authUrl + separator
				+ "client_id=" + clientId
				+ "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
				+ "&scope=" + java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8.name())
				+ "&state=" + state
				+ "&response_type=code";
	}

	private AccessToken exchangeCodeForToken(String provider, String code) {
		String prefix = provider + "_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String clientSecret = socialData.getProperty(prefix + "secret_key");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String tokenUrl = socialData.getProperty(prefix + "token_url");

		if (Strings.isNullOrEmpty(tokenUrl)) {
			throw new IllegalArgumentException("Token URL cannot be null or empty");
		}

		Map<String, String> params = new HashMap<>();
		params.put("client_id", clientId);
		params.put("redirect_uri", redirectUri);
		params.put("code", code);
		params.put("grant_type", "authorization_code");
		params.put("client_secret", clientSecret);

		AccessToken accessToken = HttpHelperUtility.getAccessToken(tokenUrl, params, true, true);
		if (accessToken == null) {
			return null;
		}

		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());
		accessToken.setProvider(providerEnum);

		String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String[] beanPropsArr = beanProps.split(",", -1);
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
		boolean sanitizeResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));

		GenericTokenFiller profiler = new GenericTokenFiller();
		profiler.fillAccessToken(accessToken, userInfoURL, jsonPattern, beanPropsArr, null, sanitizeResponse);

		return accessToken;
	}

	private void addAccessTokenToSession(AccessToken accessToken, HttpServletRequest request) {
		HttpSession session = request.getSession(true);
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			user = new User();
		}
		user.setAccessToken(accessToken);
		session.setAttribute(Constants.SESSION_USER, user);
	}

	// ============================================================================
	// OpenAPI Spec Builder
	// ============================================================================

	private JSONObject buildOpenApiSpec(String toolboxId, JSONObject mcpTools, HttpServletRequest request) {
		String baseUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolboxId;

		JSONObject spec = new JSONObject();
		spec.put("openapi", "3.0.0");
		spec.put("info", new JSONObject()
				.put("title", "SEMOSS MCP Tool Server")
				.put("description", "MCP Tool Server for toolbox: " + toolboxId)
				.put("version", "1.0.0"));
		spec.put("servers", new JSONArray().put(new JSONObject()
				.put("url", baseUrl)
				.put("description", "SEMOSS MCP Server")));

		// Security
		spec.put("components", new JSONObject()
				.put("securitySchemes", new JSONObject()
						.put("bearerAuth", new JSONObject()
								.put("type", "http")
								.put("scheme", "bearer")
								.put("bearerFormat", "JWT"))));
		spec.put("security", new JSONArray().put(new JSONObject().put("bearerAuth", new JSONArray())));

		// Paths
		JSONObject paths = new JSONObject();

		// Health endpoint
		paths.put("/health", new JSONObject().put("get", new JSONObject()
				.put("summary", "Health check")
				.put("operationId", "healthCheck")
				.put("responses", new JSONObject().put("200", new JSONObject().put("description", "Server is healthy")))));

		// Tools list endpoint
		paths.put("/tools", new JSONObject().put("get", new JSONObject()
				.put("summary", "List available tools")
				.put("operationId", "listTools")
				.put("responses", new JSONObject().put("200", new JSONObject().put("description", "List of tools")))));

		// Tools call endpoint
		JSONObject callRequestSchema = new JSONObject()
				.put("type", "object")
				.put("properties", new JSONObject()
						.put("name", new JSONObject().put("type", "string").put("description", "Tool name"))
						.put("arguments", new JSONObject().put("type", "object").put("description", "Tool arguments")))
				.put("required", new JSONArray().put("name"));

		paths.put("/tools/call", new JSONObject().put("post", new JSONObject()
				.put("summary", "Execute a tool")
				.put("operationId", "callTool")
				.put("requestBody", new JSONObject()
						.put("required", true)
						.put("content", new JSONObject()
								.put("application/json", new JSONObject().put("schema", callRequestSchema))))
				.put("responses", new JSONObject()
						.put("200", new JSONObject().put("description", "Tool execution result"))
						.put("400", new JSONObject().put("description", "Bad request"))
						.put("401", new JSONObject().put("description", "Unauthorized")))));

		// Add individual tool endpoints
		if (mcpTools.has("tools")) {
			JSONArray tools = mcpTools.getJSONArray("tools");
			for (int i = 0; i < tools.length(); i++) {
				JSONObject tool = tools.getJSONObject(i);
				String toolName = tool.getString("name");
				String description = tool.optString("description", "Execute " + toolName);
				JSONObject inputSchema = tool.optJSONObject("inputSchema");

				JSONObject toolEndpoint = new JSONObject()
						.put("summary", toolName)
						.put("description", description)
						.put("operationId", "call_" + toolName.replace("-", "_").replace(" ", "_"))
						.put("responses", new JSONObject().put("200", new JSONObject().put("description", "Tool result")));

				if (inputSchema != null) {
					toolEndpoint.put("requestBody", new JSONObject()
							.put("required", true)
							.put("content", new JSONObject()
									.put("application/json", new JSONObject().put("schema", inputSchema))));
				}

				paths.put("/tools/" + toolName, new JSONObject().put("post", toolEndpoint));
			}
		}

		spec.put("paths", paths);
		return spec;
	}
}
