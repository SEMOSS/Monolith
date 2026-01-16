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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
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
import prerna.reactor.agent.mcp.MCPErrorCode;
import prerna.sablecc2.om.execptions.SemossMCPException;
import prerna.security.HttpHelperUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class MCPResource {

	// MCP remote communication - https://www.npmjs.com/package/mcp-remote

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);
	private Map<String, Insight> mcpThread = new HashMap<>();

	private static final String MCP_OAUTH_STATE_KEY = "mcp_oauth_state";
	private static SocialPropertiesUtil socialData = null;
	static {
		socialData = SocialPropertiesUtil.getInstance();
	}

	/**
	 * Base MCP server endpoint (GET) - returns server info and capabilities.
	 * This is called by ChatGPT after OAuth authentication to verify the MCP server.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param request HTTP request
	 * @return MCP server information
	 */
	@GET
	@Path("/")
	@Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.WILDCARD})
	public Response getMCPServerInfo(
			@PathParam("toolbox_id") String toolbox_id,
			@Context HttpServletRequest request) {
		return buildMCPServerInfoResponse(toolbox_id, request);
	}

	/**
	 * Base MCP server endpoint (POST) - handles MCP protocol messages.
	 * ChatGPT may POST to the base endpoint for MCP JSON-RPC style communication.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param is Request body
	 * @param request HTTP request
	 * @return MCP server information or response to the posted message
	 */
	@POST
	@Path("/")
	@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.WILDCARD})
	@Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.WILDCARD})
	public Response postMCPServerInfo(
			@PathParam("toolbox_id") String toolbox_id,
			InputStream is,
			@Context HttpServletRequest request) {

		classLogger.info("MCP POST request received for toolbox: " + toolbox_id);

		try {
			// Read the request body
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			classLogger.debug("MCP POST body: " + requestBody);

			// If empty body, just return server info
			if (requestBody == null || requestBody.trim().isEmpty()) {
				return buildMCPServerInfoResponse(toolbox_id, request);
			}

			// Try to parse as JSON-RPC message
			JSONObject jsonRequest = new JSONObject(requestBody);

			// Check if this is a JSON-RPC request
			if (jsonRequest.has("method")) {
				String method = jsonRequest.getString("method");
				classLogger.info("MCP JSON-RPC method: " + method);

				// Handle different MCP methods
				switch (method) {
					case "initialize":
					case "mcp/initialize":
						return handleMCPInitialize(toolbox_id, jsonRequest, request);
					case "notifications/initialized":
					case "initialized":
						// Notification - no response needed, return empty 200
						return Response.ok().build();
					case "tools/list":
						return handleToolsList(toolbox_id, jsonRequest, request);
					case "tools/call":
						return handleToolsCall(toolbox_id, jsonRequest, request);
					case "resources/list":
						return handleResourcesList(jsonRequest);
					case "prompts/list":
						return handlePromptsList(jsonRequest);
					case "ping":
						return createJsonRpcResponse(jsonRequest, new JSONObject());
					default:
						// Return method not found error for unknown methods
						classLogger.warn("Unknown MCP method: " + method);
						return createJsonRpcError(jsonRequest, -32601, "Method not found: " + method);
				}
			}

			// Default: return server info
			return buildMCPServerInfoResponse(toolbox_id, request);

		} catch (Exception e) {
			classLogger.error("Error handling MCP POST request", e);
			JSONObject error = new JSONObject();
			error.put("jsonrpc", "2.0");
			error.put("error", new JSONObject()
					.put("code", -32603)
					.put("message", "Internal error: " + e.getMessage()));
			return Response.status(500).entity(error.toString()).type(MediaType.APPLICATION_JSON).build();
		}
	}

	/**
	 * Handle MCP initialize method
	 */
	private Response handleMCPInitialize(String toolboxId, JSONObject jsonRequest, HttpServletRequest request) {
		try {
			IEngine engine = getEngine(toolboxId);
			if (engine == null) {
				return createJsonRpcError(jsonRequest, -32602, "Toolbox not found: " + toolboxId);
			}

			// Get client's protocol version and use it (or latest supported)
			String clientProtocolVersion = "2024-11-05";
			JSONObject params = jsonRequest.optJSONObject("params");
			if (params != null && params.has("protocolVersion")) {
				clientProtocolVersion = params.getString("protocolVersion");
			}

			JSONObject result = new JSONObject();
			result.put("protocolVersion", clientProtocolVersion);

			JSONObject serverInfo = new JSONObject();
			serverInfo.put("name", "SEMOSS MCP Server");
			serverInfo.put("version", "1.0.0");
			result.put("serverInfo", serverInfo);

			// Capabilities - indicate we support tools
			JSONObject capabilities = new JSONObject();
			JSONObject toolsCapability = new JSONObject();
			toolsCapability.put("listChanged", false);
			capabilities.put("tools", toolsCapability);
			result.put("capabilities", capabilities);

			return createJsonRpcResponse(jsonRequest, result);

		} catch (Exception e) {
			classLogger.error("Error in MCP initialize", e);
			return createJsonRpcError(jsonRequest, -32603, e.getMessage());
		}
	}

	/**
	 * Handle resources/list method - return empty resources (we don't support resources)
	 */
	private Response handleResourcesList(JSONObject jsonRequest) {
		JSONObject result = new JSONObject();
		result.put("resources", new JSONArray());
		return createJsonRpcResponse(jsonRequest, result);
	}

	/**
	 * Handle prompts/list method - return empty prompts (we don't support prompts)
	 */
	private Response handlePromptsList(JSONObject jsonRequest) {
		JSONObject result = new JSONObject();
		result.put("prompts", new JSONArray());
		return createJsonRpcResponse(jsonRequest, result);
	}

	/**
	 * Handle tools/list method
	 */
	private Response handleToolsList(String toolboxId, JSONObject jsonRequest, HttpServletRequest request) {
		try {
			IEngine engine = getEngine(toolboxId);
			if (engine == null) {
				return createJsonRpcError(jsonRequest, -32602, "Toolbox not found: " + toolboxId);
			}

			IMCP mcp = MCPFactory.build(engine);
			JSONObject mcpTools = mcp.getMCPTools();

			JSONObject result = new JSONObject();
			if (mcpTools.has("tools")) {
				result.put("tools", mcpTools.getJSONArray("tools"));
			} else {
				result.put("tools", new JSONArray());
			}

			return createJsonRpcResponse(jsonRequest, result);

		} catch (Exception e) {
			classLogger.error("Error in tools/list", e);
			return createJsonRpcError(jsonRequest, -32603, e.getMessage());
		}
	}

	/**
	 * Handle tools/call method
	 */
	private Response handleToolsCall(String toolboxId, JSONObject jsonRequest, HttpServletRequest request) {
		try {
			IEngine engine = getEngine(toolboxId);
			if (engine == null) {
				return createJsonRpcError(jsonRequest, -32602, "Toolbox not found: " + toolboxId);
			}

			JSONObject params = jsonRequest.optJSONObject("params");
			if (params == null) {
				return createJsonRpcError(jsonRequest, -32602, "Missing params");
			}

			String toolName = params.optString("name");
			if (toolName == null || toolName.isEmpty()) {
				return createJsonRpcError(jsonRequest, -32602, "Missing tool name");
			}

			JSONObject arguments = params.optJSONObject("arguments");
			if (arguments == null) {
				arguments = new JSONObject();
			}

			// Create insight for tool execution
			HttpSession session = request.getSession(false);
			Insight insight = initSession(session);
			if (insight == null) {
				return createJsonRpcError(jsonRequest, -32603, "Failed to initialize session");
			}

			// Convert arguments to Map
			Map<String, Object> argsMap = new HashMap<>();
			for (String key : arguments.keySet()) {
				argsMap.put(key, arguments.get(key));
			}

			// Execute the tool
			IMCP mcp = MCPFactory.build(engine);
			Object result = mcp.callTool(toolName, argsMap, insight);

			// Build response
			JSONObject resultObj = new JSONObject();
			JSONArray content = new JSONArray();
			JSONObject textContent = new JSONObject();
			textContent.put("type", "text");
			textContent.put("text", result != null ? result.toString() : "null");
			content.put(textContent);
			resultObj.put("content", content);

			return createJsonRpcResponse(jsonRequest, resultObj);

		} catch (Exception e) {
			classLogger.error("Error in tools/call", e);
			return createJsonRpcError(jsonRequest, -32603, e.getMessage());
		}
	}

	/**
	 * Create JSON-RPC 2.0 success response
	 */
	private Response createJsonRpcResponse(JSONObject request, JSONObject result) {
		JSONObject response = new JSONObject();
		response.put("jsonrpc", "2.0");
		if (request.has("id")) {
			response.put("id", request.get("id"));
		}
		response.put("result", result);
		return Response.ok(response.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * Create JSON-RPC 2.0 error response
	 */
	private Response createJsonRpcError(JSONObject request, int code, String message) {
		JSONObject response = new JSONObject();
		response.put("jsonrpc", "2.0");
		if (request != null && request.has("id")) {
			response.put("id", request.get("id"));
		}
		JSONObject error = new JSONObject();
		error.put("code", code);
		error.put("message", message);
		response.put("error", error);
		return Response.status(400).entity(response.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * Build MCP server info response
	 */
	private Response buildMCPServerInfoResponse(String toolbox_id, HttpServletRequest request) {
		classLogger.info("MCP server info requested for toolbox: " + toolbox_id);

		try {
			IEngine engine = getEngine(toolbox_id);
			if (engine == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Toolbox not found: " + toolbox_id);
				return WebUtility.getResponse(ret, 404);
			}

			// Build MCP server info response
			JSONObject serverInfo = new JSONObject();
			serverInfo.put("name", "SEMOSS MCP Server");
			serverInfo.put("version", "1.0.0");
			serverInfo.put("toolbox_id", toolbox_id);

			// Get available tools
			IMCP mcp = MCPFactory.build(engine);
			JSONObject mcpTools = mcp.getMCPTools();
			if (mcpTools.has("tools")) {
				serverInfo.put("tools", mcpTools.getJSONArray("tools"));
			}

			// Add capabilities
			JSONObject capabilities = new JSONObject();
			capabilities.put("tools", true);
			capabilities.put("resources", false);
			capabilities.put("prompts", false);
			serverInfo.put("capabilities", capabilities);

			// Add endpoints info
			String baseUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolbox_id;
			JSONObject endpoints = new JSONObject();
			endpoints.put("tools_list", baseUrl + "/tools");
			endpoints.put("tools_call", baseUrl + "/tools/call");
			endpoints.put("openapi", baseUrl + "/openapi.json");
			endpoints.put("health", baseUrl + "/health");
			serverInfo.put("endpoints", endpoints);

			return Response.ok(serverInfo.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			classLogger.error("Error getting MCP server info", e);
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Failed to get server info: " + e.getMessage());
			return WebUtility.getResponse(ret, 500);
		}
	}

	@POST
	@Path("/it")
	@Consumes(MediaType.APPLICATION_JSON) // Assume JSON input
	@Produces(MediaType.TEXT_PLAIN)
	public Response getInsightData(InputStream is) {
		classLogger.debug("Came into the MCP");
		StreamingOutput stream = output -> {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
				// Simulate processing input and generating streamed response
				for (int i = 0; i < 10; i++) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));
					String outputLine = "Processed: " + reader.readLine() + " - Item: " + i;
					writer.write(outputLine + "\n");
					writer.flush(); // Flush after each write to ensure streaming
					Thread.sleep(500); // Simulate some processing time
				}
			} catch (IOException | InterruptedException e) {
				throw new WebApplicationException(e); // Handle exception appropriately
			}
		};
		return Response.ok(stream).build();
	}

	@POST
	@Path("/comms")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void comms(@PathParam("toolbox_id") String toolbox_id, @QueryParam("access_key") String access,
			@Context SseEventSink eventSink, @Context Sse sse, InputStream is, @Context HttpServletRequest request) {
		classLogger.debug("Runing tool.. " + toolbox_id);
		// initialize session
		String authorization = request.getHeader("Authorization");
		HttpSession session = request.getSession(false);
		String sessionId = session.getId();
		Insight insight = null;
		User user = null;
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));

		if (!mcpThread.containsKey(authorization)) {
			insight = initSession(session);
			user = insight.getUser();
			mcpThread.put(authorization, insight);
		} else {
			insight = mcpThread.get(authorization);
			user = insight.getUser();

		}
		MCPReaper reaper = new MCPReaper(user, insight, sessionId, reader, eventSink, sse, toolbox_id,
				ThreadContext.getImmutableContext());
		Thread t = new Thread(reaper);
		t.start();
	}

	/**
	 * 
	 * @param session
	 * @return
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
	 * OAuth initiation endpoint - redirects to OAuth provider
	 * This is called by OpenAI to start the OAuth flow
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param provider The OAuth provider (google, github, microsoft, etc.)
	 * @param state State parameter from OpenAI for CSRF protection
	 * @param request HTTP request
	 * @param response HTTP response
	 * @throws IOException If redirect fails
	 */
	@GET
	@Path("/oauth/authorize")
	public Response initiateOAuth(
			@PathParam("toolbox_id") String toolbox_id,
			@QueryParam("provider") String provider,
			@QueryParam("state") String state,
			@Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {

		classLogger.info("Initiating OAuth flow for provider: " + provider + ", toolbox: " + toolbox_id);

		provider = WebUtility.inputSanitizer(provider);

		// Validate provider is allowed
		if (socialData.getLoginsAllowed().get(provider) == null || !socialData.getLoginsAllowed().get(provider)) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, provider + " login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		// Store state in session for validation on callback
		HttpSession session = request.getSession(true);
		if (state != null && !state.isEmpty()) {
			session.setAttribute(MCP_OAUTH_STATE_KEY, state);
		}
		session.setAttribute("mcp_toolbox_id", toolbox_id);

		// Build redirect URL to OAuth provider
		String redirectUrl = getOAuthRedirectUrl(provider, request);

		// Redirect to OAuth provider
		response.setStatus(302);
		response.sendRedirect(redirectUrl);
		return null;
	}

	/**
	 * OAuth callback endpoint - handles redirect from OAuth provider
	 * This is where the OAuth provider sends the user after authentication
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param provider The OAuth provider
	 * @param code Authorization code from OAuth provider
	 * @param state State parameter for CSRF protection
	 * @param error Error from OAuth provider (if any)
	 * @param request HTTP request
	 * @param response HTTP response
	 * @return Response with authentication result
	 */
	@GET
	@Path("/oauth/callback/{provider}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleOAuthCallback(
			@PathParam("toolbox_id") String toolbox_id,
			@PathParam("provider") String provider,
			@QueryParam("code") String code,
			@QueryParam("state") String state,
			@QueryParam("error") String error,
			@Context HttpServletRequest request,
			@Context HttpServletResponse response) {

		classLogger.info("OAuth callback received for provider: " + provider + ", toolbox: " + toolbox_id);

		provider = WebUtility.inputSanitizer(provider);

		// Check for errors from OAuth provider
		if (error != null && !error.isEmpty()) {
			classLogger.error("OAuth error from provider: " + error);
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "OAuth error: " + error);
			return WebUtility.getResponse(ret, 400);
		}

		// Validate state parameter
		HttpSession session = request.getSession(false);
		if (session != null) {
			String storedState = (String) session.getAttribute(MCP_OAUTH_STATE_KEY);
			if (storedState != null && !storedState.equals(state)) {
				classLogger.error("State mismatch - possible CSRF attack");
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Invalid state parameter");
				return WebUtility.getResponse(ret, 400);
			}
			session.removeAttribute(MCP_OAUTH_STATE_KEY);
		}

		// Validate code parameter
		if (code == null || code.isEmpty() || !code.matches("[ -~]+")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Invalid authorization code");
			return WebUtility.getResponse(ret, 400);
		}

		// Exchange authorization code for access token
		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());
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
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Failed to exchange code for access token");
			return WebUtility.getResponse(ret, 401);
		}

		// Get user info from OAuth provider
		String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String[] beanPropsArr = beanProps.split(",", -1);
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		accessToken.setProvider(providerEnum);

		boolean sanitizeResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));
		GenericTokenFiller profiler = new GenericTokenFiller();
		profiler.fillAccessToken(accessToken, userInfoURL, jsonPattern, beanPropsArr, null, sanitizeResponse);

		// Store access token in session
		boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
		addAccessTokenToSession(accessToken, request, autoAdd);

		// Return success response with user info
		Map<String, Object> ret = new HashMap<>();
		ret.put("success", true);
		ret.put("provider", provider);
		ret.put("user_id", accessToken.getId());
		ret.put("user_name", accessToken.getName());
		ret.put("user_email", accessToken.getEmail());

		classLogger.info("OAuth authentication successful for user: " + accessToken.getId());
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get user info endpoint - returns authenticated user information
	 * OpenAI calls this to get the current user's info
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param request HTTP request
	 * @return User information
	 */
	@GET
	@Path("/oauth/userinfo")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getUserInfo(
			@PathParam("toolbox_id") String toolbox_id,
			@Context HttpServletRequest request) {

		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "No active session");
			return WebUtility.getResponse(ret, 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User not authenticated");
			return WebUtility.getResponse(ret, 401);
		}

		// Return user info in OpenAI expected format
		Map<String, Object> ret = new HashMap<>();
		ret.put("user_id", user.getPrimaryLogin());

		// Get user info from primary access token
		AccessToken primaryToken = user.getPrimaryLoginToken();
		if (primaryToken != null) {
			ret.put("user_name", primaryToken.getName());
			ret.put("user_email", primaryToken.getEmail());
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Helper method to build OAuth redirect URL
	 */
	private String getOAuthRedirectUrl(String provider, HttpServletRequest request) throws UnsupportedEncodingException {
		provider = WebUtility.inputSanitizer(provider);
		String prefix = provider + "_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");
		String authUrl = socialData.getProperty(prefix + "auth_url");

		if (Strings.isNullOrEmpty(authUrl)) {
			throw new IllegalArgumentException("Authorize URL cannot be null or empty");
		}

		String state = UUID.randomUUID().toString();

		String redirectUrl;
		if (authUrl.contains("?")) {
			redirectUrl = authUrl + "&client_id=" + clientId
					+ "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
					+ "&scope=" + java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8.name())
					+ "&state=" + state
					+ "&response_type=code";
		} else {
			redirectUrl = authUrl + "?client_id=" + clientId
					+ "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
					+ "&scope=" + java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8.name())
					+ "&state=" + state
					+ "&response_type=code";
		}

		return redirectUrl;
	}

	/**
	 * Helper method to add access token to session and create/update user
	 */
	private void addAccessTokenToSession(AccessToken accessToken, HttpServletRequest request, boolean autoAdd) {
		HttpSession session = request.getSession(true);

		// Get or create user
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			user = new User();
		}

		// Add access token to user using setAccessToken
		user.setAccessToken(accessToken);

		// Store user in session
		session.setAttribute(Constants.SESSION_USER, user);

		classLogger.debug("Access token added to session for user: " + accessToken.getId());
	}

	// ============================================================================
	// ChatGPT-Compatible REST Endpoints
	// ============================================================================

	/**
	 * OpenAPI specification endpoint for ChatGPT Actions discovery.
	 * Returns an OpenAPI 3.0 spec that describes the available tools.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param request HTTP request
	 * @return OpenAPI specification JSON
	 */
	@GET
	@Path("/openapi.json")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOpenApiSpec(
			@PathParam("toolbox_id") String toolbox_id,
			@Context HttpServletRequest request) {

		classLogger.info("OpenAPI spec requested for toolbox: " + toolbox_id);

		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Authentication required");
			return WebUtility.getResponse(ret, 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User not authenticated");
			return WebUtility.getResponse(ret, 401);
		}

		try {
			// Get the engine/project
			IEngine engine = getEngine(toolbox_id);
			if (engine == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Toolbox not found: " + toolbox_id);
				return WebUtility.getResponse(ret, 404);
			}

			// Build OpenAPI spec from MCP tools
			IMCP mcp = MCPFactory.build(engine);
			JSONObject mcpTools = mcp.getMCPTools();
			JSONObject openApiSpec = buildOpenApiSpec(toolbox_id, mcpTools, request);

			return Response.ok(openApiSpec.toString()).build();

		} catch (Exception e) {
			classLogger.error("Error generating OpenAPI spec", e);
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Failed to generate OpenAPI spec: " + e.getMessage());
			return WebUtility.getResponse(ret, 500);
		}
	}

	/**
	 * REST endpoint for listing available tools - ChatGPT compatible.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param request HTTP request
	 * @return List of available tools
	 */
	@GET
	@Path("/tools")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listTools(
			@PathParam("toolbox_id") String toolbox_id,
			@Context HttpServletRequest request) {

		classLogger.info("Tools list requested for toolbox: " + toolbox_id);

		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Authentication required");
			return WebUtility.getResponse(ret, 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User not authenticated");
			return WebUtility.getResponse(ret, 401);
		}

		try {
			IEngine engine = getEngine(toolbox_id);
			if (engine == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Toolbox not found: " + toolbox_id);
				return WebUtility.getResponse(ret, 404);
			}

			IMCP mcp = MCPFactory.build(engine);
			JSONObject mcpTools = mcp.getMCPTools();

			return Response.ok(mcpTools.toString()).build();

		} catch (Exception e) {
			classLogger.error("Error listing tools", e);
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Failed to list tools: " + e.getMessage());
			return WebUtility.getResponse(ret, 500);
		}
	}

	/**
	 * REST endpoint for executing a tool - ChatGPT compatible (synchronous).
	 * This is the main endpoint ChatGPT will call to execute tools.
	 *
	 * Expected request body:
	 * {
	 *   "name": "tool_name",
	 *   "arguments": { "param1": "value1", ... }
	 * }
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param is Request body input stream
	 * @param request HTTP request
	 * @return Tool execution result
	 */
	@POST
	@Path("/tools/call")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response callTool(
			@PathParam("toolbox_id") String toolbox_id,
			InputStream is,
			@Context HttpServletRequest request) {

		classLogger.info("Tool call requested for toolbox: " + toolbox_id);

		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Authentication required");
			return WebUtility.getResponse(ret, 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User not authenticated");
			return WebUtility.getResponse(ret, 401);
		}

		try {
			// Parse request body
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			JSONObject requestJson = new JSONObject(requestBody);

			String toolName = requestJson.getString("name");
			JSONObject arguments = requestJson.optJSONObject("arguments");
			if (arguments == null) {
				arguments = new JSONObject();
			}

			classLogger.info("Executing tool: " + toolName + " with arguments: " + arguments.toString());

			// Get engine and create insight
			IEngine engine = getEngine(toolbox_id);
			if (engine == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Toolbox not found: " + toolbox_id);
				return WebUtility.getResponse(ret, 404);
			}

			// Create or get insight for this session
			Insight insight = initSession(session);
			if (insight == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Failed to initialize session");
				return WebUtility.getResponse(ret, 500);
			}

			// Convert JSON arguments to Map
			Map<String, Object> params = new HashMap<>();
			for (String key : arguments.keySet()) {
				params.put(key, arguments.get(key));
			}

			// Execute the tool
			IMCP mcp = MCPFactory.build(engine);
			Object result = mcp.callTool(toolName, params, insight);

			// Build response
			JSONObject response = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", result != null ? result.toString() : "null");
			contentList.add(contentMap);
			response.put("content", contentList);
			response.put("isError", false);

			return Response.ok(response.toString()).build();

		} catch (SemossMCPException e) {
			classLogger.error("MCP error executing tool", e);
			JSONObject error = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", e.getMessage() != null ? e.getMessage() : e.getError().getDescription());
			contentList.add(contentMap);
			error.put("content", contentList);
			error.put("isError", true);
			error.put("errorCode", e.getError().getCode());
			return Response.status(400).entity(error.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			classLogger.error("Error executing tool", e);
			JSONObject error = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", e.getMessage() != null ? e.getMessage() : "Tool execution failed");
			contentList.add(contentMap);
			error.put("content", contentList);
			error.put("isError", true);
			error.put("errorCode", MCPErrorCode.TOOL_EXECUTION_FAILED.getCode());
			return Response.status(500).entity(error.toString()).type(MediaType.APPLICATION_JSON).build();
		}
	}

	/**
	 * Health check endpoint for ChatGPT to verify the server is accessible.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @return Health status
	 */
	@GET
	@Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	public Response healthCheck(@PathParam("toolbox_id") String toolbox_id) {
		Map<String, Object> ret = new HashMap<>();
		ret.put("status", "healthy");
		ret.put("toolbox_id", toolbox_id);
		ret.put("timestamp", System.currentTimeMillis());
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * OAuth Protected Resource Metadata endpoint for ChatGPT MCP integration.
	 * This endpoint is required by OpenAI's MCP authorization spec (RFC 9728).
	 *
	 * ChatGPT calls this endpoint to discover the authorization server
	 * and supported scopes for the MCP server.
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param request HTTP request
	 * @return OAuth protected resource metadata JSON
	 */
	@GET
	@Path("/.well-known/oauth-protected-resource")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOAuthProtectedResourceMetadata(
			@PathParam("toolbox_id") String toolbox_id,
			@Context HttpServletRequest request) {

		classLogger.info("OAuth protected resource metadata requested for toolbox: " + toolbox_id);

		// Build the resource URL (the MCP server URL)
		String resourceUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolbox_id;

		// Get the authorization server URL from social properties
		// First try keycloak_realm_url, then derive from generic_auth_url
		String authServerUrl = socialData.getProperty("keycloak_realm_url");
		if (authServerUrl == null || authServerUrl.isEmpty() || authServerUrl.startsWith("<")) {
			// Try to derive from generic_auth_url (remove /protocol/openid-connect/auth)
			String genericAuthUrl = socialData.getProperty("generic_auth_url");
			if (genericAuthUrl != null && !genericAuthUrl.isEmpty() && genericAuthUrl.contains("/protocol/openid-connect")) {
				authServerUrl = genericAuthUrl.substring(0, genericAuthUrl.indexOf("/protocol/openid-connect"));
			} else {
				// Final fallback to a known default
				authServerUrl = "https://sso.semoss.org/realms/dev";
			}
		}

		JSONObject metadata = new JSONObject();
		metadata.put("resource", resourceUrl);

		JSONArray authServers = new JSONArray();
		authServers.put(authServerUrl);
		metadata.put("authorization_servers", authServers);

		// Supported scopes - standard OpenID Connect scopes
		JSONArray scopes = new JSONArray();
		scopes.put("openid");
		scopes.put("profile");
		scopes.put("email");
		scopes.put("offline_access");
		metadata.put("scopes_supported", scopes);

		return Response.ok(metadata.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * Helper method to get the external base URL, handling reverse proxies and tunnels.
	 * Checks X-Forwarded-* headers to determine the actual external URL.
	 * Supports Cloudflare Tunnel, nginx, Apache, and other reverse proxies.
	 *
	 * Priority:
	 * 1. Configured mcp_external_base_url property (most reliable)
	 * 2. X-Forwarded-Host/X-Forwarded-Proto headers
	 * 3. Origin/Referer headers
	 * 4. Host header
	 * 5. Server name (fallback)
	 */
	private String getExternalBaseUrl(HttpServletRequest request) {
		// First check if external URL is explicitly configured (most reliable for tunnels)
		String configuredUrl = socialData.getProperty("mcp_external_base_url");
		if (configuredUrl != null && !configuredUrl.isEmpty() && !configuredUrl.startsWith("<")) {
			// Remove trailing slash if present
			if (configuredUrl.endsWith("/")) {
				configuredUrl = configuredUrl.substring(0, configuredUrl.length() - 1);
			}
			return configuredUrl;
		}

		// Check for forwarded protocol (from reverse proxy/tunnel)
		String scheme = request.getHeader("X-Forwarded-Proto");
		if (scheme == null || scheme.isEmpty()) {
			// Cloudflare also sends Cf-Visitor with scheme info
			String cfVisitor = request.getHeader("Cf-Visitor");
			if (cfVisitor != null && cfVisitor.contains("https")) {
				scheme = "https";
			} else {
				scheme = request.getScheme();
			}
		}

		// Check for forwarded host - try multiple headers used by different proxies
		String host = request.getHeader("X-Forwarded-Host");

		if (host == null || host.isEmpty()) {
			host = request.getHeader("X-Original-Host");
		}

		if (host == null || host.isEmpty()) {
			// For Cloudflare Quick Tunnels, check Origin header
			String origin = request.getHeader("Origin");
			if (origin != null && !origin.isEmpty() && !origin.contains("localhost")) {
				try {
					java.net.URL originUrl = new java.net.URL(origin);
					host = originUrl.getHost();
					if (originUrl.getPort() != -1 && originUrl.getPort() != 80 && originUrl.getPort() != 443) {
						host += ":" + originUrl.getPort();
					}
				} catch (Exception e) {
					// ignore parsing errors
				}
			}
		}

		if (host == null || host.isEmpty()) {
			// Try Referer header as last resort for external host
			String referer = request.getHeader("Referer");
			if (referer != null && !referer.isEmpty() && !referer.contains("localhost")) {
				try {
					java.net.URL refererUrl = new java.net.URL(referer);
					// Only use if it seems like a tunnel URL (trycloudflare.com, ngrok, etc)
					String refHost = refererUrl.getHost();
					if (refHost.contains("trycloudflare.com") || refHost.contains("ngrok") ||
						refHost.contains("tunnel") || refHost.contains("cloudflare")) {
						host = refHost;
						if (refererUrl.getPort() != -1 && refererUrl.getPort() != 80 && refererUrl.getPort() != 443) {
							host += ":" + refererUrl.getPort();
						}
					}
				} catch (Exception e) {
					// ignore parsing errors
				}
			}
		}

		if (host == null || host.isEmpty()) {
			// Check Host header - this is often rewritten by Cloudflare Tunnel
			host = request.getHeader("Host");
		}

		if (host == null || host.isEmpty()) {
			// Final fallback to server name
			host = request.getServerName();
			int port = request.getServerPort();
			if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
				host += ":" + port;
			}
		}

		// If we detected Cloudflare headers but host is still localhost, log a warning
		String cfRay = request.getHeader("Cf-Ray");
		if (cfRay != null && (host.contains("localhost") || host.contains("127.0.0.1"))) {
			classLogger.warn("Request appears to be from Cloudflare (Cf-Ray: {}) but host is still local: {}. " +
					"Configure 'mcp_external_base_url' in social.properties to fix this.", cfRay, host);
		}

		return scheme + "://" + host + request.getContextPath();
	}

	/**
	 * Helper method to get engine by ID (tries both engine and project).
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
	 * Builds an OpenAPI 3.0 specification from MCP tools.
	 */
	private JSONObject buildOpenApiSpec(String toolboxId, JSONObject mcpTools, HttpServletRequest request) {
		JSONObject spec = new JSONObject();

		// OpenAPI version
		spec.put("openapi", "3.0.0");

		// Info section
		JSONObject info = new JSONObject();
		info.put("title", "SEMOSS MCP Tool Server");
		info.put("description", "MCP Tool Server for toolbox: " + toolboxId +
				". Provides access to SEMOSS tools via REST API.");
		info.put("version", "1.0.0");
		spec.put("info", info);

		// Servers section
		JSONArray servers = new JSONArray();
		JSONObject server = new JSONObject();
		// Use helper method that handles X-Forwarded-* headers for reverse proxies/tunnels
		String baseUrl = getExternalBaseUrl(request) + "/api/ext/mcp/" + toolboxId;
		server.put("url", baseUrl);
		server.put("description", "SEMOSS MCP Server");
		servers.put(server);
		spec.put("servers", servers);

		// Security schemes
		JSONObject components = new JSONObject();
		JSONObject securitySchemes = new JSONObject();
		JSONObject bearerAuth = new JSONObject();
		bearerAuth.put("type", "http");
		bearerAuth.put("scheme", "bearer");
		bearerAuth.put("bearerFormat", "JWT");
		bearerAuth.put("description", "Keycloak JWT bearer token");
		securitySchemes.put("bearerAuth", bearerAuth);
		components.put("securitySchemes", securitySchemes);
		spec.put("components", components);

		// Global security requirement
		JSONArray security = new JSONArray();
		JSONObject securityItem = new JSONObject();
		securityItem.put("bearerAuth", new JSONArray());
		security.put(securityItem);
		spec.put("security", security);

		// Paths
		JSONObject paths = new JSONObject();

		// Health endpoint
		JSONObject healthPath = new JSONObject();
		JSONObject healthGet = new JSONObject();
		healthGet.put("summary", "Health check");
		healthGet.put("description", "Check if the MCP server is healthy and accessible");
		healthGet.put("operationId", "healthCheck");
		JSONObject healthResponses = new JSONObject();
		JSONObject health200 = new JSONObject();
		health200.put("description", "Server is healthy");
		healthResponses.put("200", health200);
		healthGet.put("responses", healthResponses);
		healthPath.put("get", healthGet);
		paths.put("/health", healthPath);

		// Tools list endpoint
		JSONObject toolsPath = new JSONObject();
		JSONObject toolsGet = new JSONObject();
		toolsGet.put("summary", "List available tools");
		toolsGet.put("description", "Returns a list of all available MCP tools");
		toolsGet.put("operationId", "listTools");
		JSONObject toolsResponses = new JSONObject();
		JSONObject tools200 = new JSONObject();
		tools200.put("description", "List of tools");
		toolsResponses.put("200", tools200);
		toolsGet.put("responses", toolsResponses);
		toolsPath.put("get", toolsGet);
		paths.put("/tools", toolsPath);

		// Tools call endpoint - generic
		JSONObject toolsCallPath = new JSONObject();
		JSONObject toolsCallPost = new JSONObject();
		toolsCallPost.put("summary", "Execute a tool");
		toolsCallPost.put("description", "Execute an MCP tool with the provided arguments");
		toolsCallPost.put("operationId", "callTool");

		// Request body
		JSONObject requestBody = new JSONObject();
		requestBody.put("required", true);
		JSONObject requestContent = new JSONObject();
		JSONObject requestJson = new JSONObject();
		JSONObject requestSchema = new JSONObject();
		requestSchema.put("type", "object");
		JSONObject requestProps = new JSONObject();
		JSONObject nameProp = new JSONObject();
		nameProp.put("type", "string");
		nameProp.put("description", "The name of the tool to execute");
		requestProps.put("name", nameProp);
		JSONObject argsProp = new JSONObject();
		argsProp.put("type", "object");
		argsProp.put("description", "Arguments to pass to the tool");
		requestProps.put("arguments", argsProp);
		requestSchema.put("properties", requestProps);
		JSONArray required = new JSONArray();
		required.put("name");
		requestSchema.put("required", required);
		requestJson.put("schema", requestSchema);
		requestContent.put("application/json", requestJson);
		requestBody.put("content", requestContent);
		toolsCallPost.put("requestBody", requestBody);

		// Responses
		JSONObject callResponses = new JSONObject();
		JSONObject call200 = new JSONObject();
		call200.put("description", "Tool execution result");
		callResponses.put("200", call200);
		JSONObject call400 = new JSONObject();
		call400.put("description", "Bad request or tool error");
		callResponses.put("400", call400);
		JSONObject call401 = new JSONObject();
		call401.put("description", "Unauthorized - invalid or missing token");
		callResponses.put("401", call401);
		toolsCallPost.put("responses", callResponses);
		toolsCallPath.put("post", toolsCallPost);
		paths.put("/tools/call", toolsCallPath);

		// Add individual tool endpoints from MCP tools
		if (mcpTools.has("tools")) {
			JSONArray tools = mcpTools.getJSONArray("tools");
			for (int i = 0; i < tools.length(); i++) {
				JSONObject tool = tools.getJSONObject(i);
				String toolName = tool.getString("name");
				String description = tool.optString("description", "Execute " + toolName);
				JSONObject inputSchema = tool.optJSONObject("inputSchema");

				// Create a dedicated endpoint for each tool
				JSONObject toolPath = new JSONObject();
				JSONObject toolPost = new JSONObject();
				toolPost.put("summary", toolName);
				toolPost.put("description", description);
				toolPost.put("operationId", "call_" + toolName.replace("-", "_").replace(" ", "_"));

				if (inputSchema != null) {
					JSONObject toolRequestBody = new JSONObject();
					toolRequestBody.put("required", true);
					JSONObject toolRequestContent = new JSONObject();
					JSONObject toolRequestJson = new JSONObject();
					toolRequestJson.put("schema", inputSchema);
					toolRequestContent.put("application/json", toolRequestJson);
					toolRequestBody.put("content", toolRequestContent);
					toolPost.put("requestBody", toolRequestBody);
				}

				JSONObject toolResponses = new JSONObject();
				JSONObject tool200 = new JSONObject();
				tool200.put("description", "Tool execution result");
				toolResponses.put("200", tool200);
				toolPost.put("responses", toolResponses);

				toolPath.put("post", toolPost);
				paths.put("/tools/" + toolName, toolPath);
			}
		}

		spec.put("paths", paths);

		return spec;
	}

	/**
	 * Dedicated endpoint for calling a specific tool by name.
	 * This allows ChatGPT to call tools directly: POST /tools/{tool_name}
	 *
	 * @param toolbox_id The toolbox/engine ID
	 * @param tool_name The name of the tool to execute
	 * @param is Request body with arguments
	 * @param request HTTP request
	 * @return Tool execution result
	 */
	@POST
	@Path("/tools/{tool_name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response callToolByName(
			@PathParam("toolbox_id") String toolbox_id,
			@PathParam("tool_name") String tool_name,
			InputStream is,
			@Context HttpServletRequest request) {

		classLogger.info("Direct tool call: " + tool_name + " for toolbox: " + toolbox_id);

		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Authentication required");
			return WebUtility.getResponse(ret, 401);
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User not authenticated");
			return WebUtility.getResponse(ret, 401);
		}

		try {
			// Parse arguments from request body
			String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			JSONObject arguments = new JSONObject();
			if (requestBody != null && !requestBody.trim().isEmpty()) {
				arguments = new JSONObject(requestBody);
			}

			classLogger.info("Executing tool: " + tool_name + " with arguments: " + arguments.toString());

			// Get engine
			IEngine engine = getEngine(toolbox_id);
			if (engine == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Toolbox not found: " + toolbox_id);
				return WebUtility.getResponse(ret, 404);
			}

			// Create or get insight
			Insight insight = initSession(session);
			if (insight == null) {
				Map<String, Object> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Failed to initialize session");
				return WebUtility.getResponse(ret, 500);
			}

			// Convert JSON arguments to Map
			Map<String, Object> params = new HashMap<>();
			for (String key : arguments.keySet()) {
				params.put(key, arguments.get(key));
			}

			// Execute the tool
			IMCP mcp = MCPFactory.build(engine);
			Object result = mcp.callTool(tool_name, params, insight);

			// Build response
			JSONObject response = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", result != null ? result.toString() : "null");
			contentList.add(contentMap);
			response.put("content", contentList);
			response.put("isError", false);

			return Response.ok(response.toString()).build();

		} catch (SemossMCPException e) {
			classLogger.error("MCP error executing tool: " + tool_name, e);
			JSONObject error = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", e.getMessage() != null ? e.getMessage() : e.getError().getDescription());
			contentList.add(contentMap);
			error.put("content", contentList);
			error.put("isError", true);
			error.put("errorCode", e.getError().getCode());
			return Response.status(400).entity(error.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			classLogger.error("Error executing tool: " + tool_name, e);
			JSONObject error = new JSONObject();
			List<Map<String, Object>> contentList = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("type", "text");
			contentMap.put("text", e.getMessage() != null ? e.getMessage() : "Tool execution failed");
			contentList.add(contentMap);
			error.put("content", contentList);
			error.put("isError", true);
			error.put("errorCode", MCPErrorCode.TOOL_EXECUTION_FAILED.getCode());
			return Response.status(500).entity(error.toString()).type(MediaType.APPLICATION_JSON).build();
		}
	}

}
