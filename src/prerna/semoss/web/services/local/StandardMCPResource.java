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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Standard MCP endpoint at /mcp for ChatGPT compatibility
 * This acts as a proxy to the actual MCPResource implementation
 */
@Singleton
@Path("/mcp")
@PermitAll
public class StandardMCPResource {

	private static final Logger classLogger = LogManager.getLogger(StandardMCPResource.class);
	private static final Map<String, Insight> MCP_MAP = new ConcurrentHashMap<>();

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

			// Get authorization header for token-based caching and cleanup
			String authorization = request.getHeader("Authorization");

			// Track authorization token in session for cleanup
			addAuthKeyToSession(session, authorization);

			// Get or create insight (cache per authorization + project)
			Insight insight = null;
			String cacheKey = authorization + "_" + projectId;
			if (!MCP_MAP.containsKey(cacheKey)) {
				insight = initSession(session);
				if (insight != null) {
					MCP_MAP.put(cacheKey, insight);
				}
			} else {
				insight = MCP_MAP.get(cacheKey);
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
				.entity("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal server error\"}}")
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
	 * Adds the authorization key to a set in the session in a thread-safe manner.
	 * Same approach as MCPResource for consistent cleanup behavior.
	 *
	 * @param session       The user's HttpSession
	 * @param authorization The authorization key to add
	 */
	private static void addAuthKeyToSession(HttpSession session, String authorization) {
		if (authorization != null) {
			synchronized (session) {
				Set<String> mcpKeys = (Set<String>) session.getAttribute(MCPResource.MCP_AUTH_KEY);
				if (mcpKeys == null) {
					mcpKeys = new HashSet<>();
					session.setAttribute(MCPResource.MCP_AUTH_KEY, mcpKeys);
				}
				mcpKeys.add(authorization);
			}
		}
	}

	/**
	 * Remove all insights cached for a given authorization token (across all projects).
	 * Called during session cleanup to prevent memory leaks.
	 *
	 * @param authorization The authorization token
	 */
	public static void clearInsightsByAuthorization(String authorization) {
		if (authorization != null) {
			// Find all cache keys starting with this authorization token
			Set<String> keysToRemove = MCP_MAP.keySet().stream()
				.filter(key -> key.startsWith(authorization + "_"))
				.collect(Collectors.toSet());

			for (String key : keysToRemove) {
				Insight removedInsight = MCP_MAP.remove(key);
				if (removedInsight != null) {
					classLogger.info("Removed cached insight from StandardMCP thread for auth key: {}", key);
				}
			}
		}
	}

	/**
	 * Protected Resource Metadata (RFC 9728)
	 * Required by MCP spec for ChatGPT to discover the resource server configuration
	 */
	@GET
	@Path("/.well-known/oauth-protected-resource")
	@Produces("application/json")
	public Response getProtectedResourceMetadata(@Context HttpServletRequest request) {
		try {
			String baseUrl = getBaseUrlForMCP(request);
			String mcpResourceUrl = baseUrl + "/api/mcp";

			// Point to Keycloak as the authorization server
			String issuerUrl = getOAuthIssuerUrl();

			Map<String, Object> metadata = new HashMap<>();
			metadata.put("resource", mcpResourceUrl);
			metadata.put("authorization_servers", java.util.Arrays.asList(issuerUrl));
			metadata.put("scopes_supported", java.util.Arrays.asList("openid", "profile", "email", "offline_access"));
			metadata.put("resource_documentation", baseUrl + "/docs/mcp");
			metadata.put("bearer_methods_supported", java.util.Arrays.asList("header"));

			classLogger.debug("Returning MCP Protected Resource metadata: " + metadata);
			return WebUtility.getResponse(metadata, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "internal_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * Detect the OAuth provider to use for MCP authentication
	 * Uses same logic as UserAccessKeyFilter
	 * Returns the provider name (e.g., "keycloak", "google")
	 */
	private String detectOAuthProvider() {
		try {
			SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
			List<Map<String, Object>> loginsMap = socialData.getAvailableProviders();
			Set<String> allowedOAuthProviders = new HashSet<>();

			for (Map<String, Object> loginInfo : loginsMap) {
				// Check if this is OAuth
				if ((boolean) loginInfo.get("isOauth")) {
					allowedOAuthProviders.add((String) loginInfo.get("label"));
				}
			}

			// If exactly one OAuth provider configured, use it
			if (allowedOAuthProviders.size() == 1) {
				return allowedOAuthProviders.iterator().next();
			}

			// Multiple providers - prefer keycloak if configured
			if (allowedOAuthProviders.contains("keycloak")) {
				classLogger.debug("Multiple OAuth providers found, using keycloak");
				return "keycloak";
			}

			// Otherwise return first provider
			if (!allowedOAuthProviders.isEmpty()) {
				String provider = allowedOAuthProviders.iterator().next();
				classLogger.debug("Multiple OAuth providers found, using first: " + provider);
				return provider;
			}

			classLogger.warn("No OAuth providers configured for MCP");
			return null;

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			return null;
		}
	}

	/**
	 * Get the OAuth provider's issuer URL.
	 * This is the base URL that contains /.well-known/openid-configuration
	 */
	private String getOAuthIssuerUrl() {
		SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
		String provider = detectOAuthProvider();

		if (provider == null) {
			throw new IllegalStateException("No OAuth provider configured for MCP. "
				+ "At least one OAuth provider must be configured in social properties.");
		}

		String prefix = provider.toLowerCase() + "_";
		classLogger.debug("Getting OAuth issuer URL for provider: " + provider);

		// 1. Try issuer_url (standard OIDC property)
		String issuerUrl = socialData.getProperty(prefix + "issuer_url");
		if (issuerUrl != null && !issuerUrl.isEmpty() && !issuerUrl.startsWith("<")) {
			return issuerUrl;
		}

		// 2. Extract from auth_url (remove path after realm/tenant)
		String authUrl = socialData.getProperty(prefix + "auth_url");
		if (authUrl != null && !authUrl.isEmpty()) {
			// Find common OIDC endpoint patterns and extract base
			int idx = -1;
			if (authUrl.contains("/protocol/openid-connect")) {
				idx = authUrl.indexOf("/protocol/openid-connect");
			} else if (authUrl.contains("/oauth2/v2.0")) {
				idx = authUrl.indexOf("/oauth2/v2.0");
			} else if (authUrl.contains("/o/oauth2")) {
				idx = authUrl.indexOf("/o/oauth2");
			}

			if (idx > 0) {
				String extracted = authUrl.substring(0, idx);
				classLogger.debug("Extracted issuer from " + prefix + "auth_url: " + extracted);
				return extracted;
			}
		}

		throw new IllegalStateException("Could not determine issuer URL for provider '" + provider + "'. "
			+ "Configure " + provider.toLowerCase() + "_issuer_url or " + provider.toLowerCase() + "_auth_url in social properties.");
	}

	/**
	 * Helper method to construct base URL for MCP OAuth
	 * Prefers configured URL over dynamic detection for production/cluster deployments
	 */
	private String getBaseUrlForMCP(HttpServletRequest request) {
		SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

		// 1. Try configured base URL (for production/clusters)
		String configuredUrl = socialData.getProperty("mcp_base_url");
		if (configuredUrl != null && !configuredUrl.isEmpty() && !configuredUrl.startsWith("<")) {
			classLogger.debug("Using configured mcp_base_url: " + configuredUrl);
			return configuredUrl;
		}

		// 2. Fallback to request-based URL construction
		String baseUrl = request.getScheme() + "://" + request.getServerName() + request.getContextPath();
		classLogger.debug("Using request-based base URL for MCP: " + baseUrl);
		return baseUrl;
	}
}
