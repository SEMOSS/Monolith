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

import com.google.common.base.Strings;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.io.connector.GenericTokenFiller;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
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

}
