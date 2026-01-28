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
import prerna.util.SocialPropertiesUtil;
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
	 * ChatGPT discovers OAuth by requesting this endpoint.
	 * We redirect to Keycloak's metadata endpoint since Keycloak is the authorization server.
	 */
	@GET
	@Path("/.well-known/oauth-authorization-server")
	@Produces("application/json")
	public Response getAuthorizationServerMetadata(@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OAuth Authorization Server Metadata endpoint called <<<<<");
		try {
			// Redirect ChatGPT to Keycloak's OpenID configuration
			String keycloakRealmUrl = getKeycloakRealmUrl();
			String keycloakMetadataUrl = keycloakRealmUrl + "/.well-known/openid-configuration";
			classLogger.info("Redirecting to Keycloak metadata: " + keycloakMetadataUrl);

			return Response.status(307) // 307 Temporary Redirect (preserves method)
				.location(java.net.URI.create(keycloakMetadataUrl))
				.build();
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "internal_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * OpenID Configuration (alias for oauth-authorization-server)
	 * Some clients request this instead - redirect to Keycloak
	 */
	@GET
	@Path("/.well-known/openid-configuration")
	@Produces("application/json")
	public Response getOpenIDConfiguration(@Context HttpServletRequest request) {
		classLogger.info(">>>>> MCP OpenID Configuration endpoint called <<<<<");
		return getAuthorizationServerMetadata(request);
	}

	/**
	 * OAuth Authorization Server Metadata with project ID in path
	 * ChatGPT appends /.well-known/oauth-authorization-server to the MCP server URL
	 * Redirect to Keycloak regardless of project ID
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
	 * OpenID Configuration with project ID in path - redirect to Keycloak
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

			// Point to Keycloak as the authorization server (not SEMOSS)
			String keycloakRealmUrl = getKeycloakRealmUrl();
			classLogger.info("Keycloak Realm URL: " + keycloakRealmUrl);

			Map<String, Object> metadata = new HashMap<>();
			metadata.put("resource", mcpResourceUrl);
			metadata.put("authorization_servers", java.util.Arrays.asList(keycloakRealmUrl));
			metadata.put("scopes_supported", java.util.Arrays.asList("openid", "profile", "email", "offline_access"));
			metadata.put("resource_documentation", baseUrl + "/docs/mcp");
			metadata.put("bearer_methods_supported", java.util.Arrays.asList("header"));

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
	 * Get the Keycloak realm URL from social.properties
	 */
	private String getKeycloakRealmUrl() {
		SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
		String keycloakRealmUrl = socialData.getProperty("keycloak_realm_url");
		if (keycloakRealmUrl == null || keycloakRealmUrl.isEmpty() || keycloakRealmUrl.startsWith("<")) {
			// Fall back to generic OAuth configuration
			String genericAuthUrl = socialData.getProperty("generic_auth_url");
			if (genericAuthUrl != null && genericAuthUrl.contains("/protocol/openid-connect")) {
				keycloakRealmUrl = genericAuthUrl.substring(0, genericAuthUrl.indexOf("/protocol/openid-connect"));
			} else {
				keycloakRealmUrl = "https://sso.semoss.org/realms/dev";
			}
		}
		return keycloakRealmUrl;
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
