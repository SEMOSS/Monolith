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
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.semoss.web.services.local.MCPResourceSchemas.ExtMcpCommsRequest;
import prerna.semoss.web.services.local.MCPResourceSchemas.ExtMcpCommsResponse;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class MCPResource {

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);

	private static final long MAX_IDLE_TIMEOUT_MINUTES = 30;

	public static final String MCP_AUTH_KEY = "MCP_AUTH_KEY";
	private static final Map<String, Insight> INSIGHT_MAP = new ConcurrentHashMap<>();
	private static final Map<String, ReentrantLock> SESSION_LOCKS = new ConcurrentHashMap<>();

	private static final ExecutorService SSE_EXECUTOR;
	static {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(25, 150, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "mcp-sse-worker");
					t.setDaemon(true);
					return t;
				});
		executor.allowCoreThreadTimeOut(true);
		SSE_EXECUTOR = executor;
	}

	/**
	 * Handle streamable HTTP connection
	 * 
	 * @param toolbox_id
	 * @param access
	 * @param request
	 * @param response
	 */
	@POST
	@Path("/comms")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(operationId = "comms", summary = "Send an MCP message to a toolbox", description = "The MCP server endpoint of a toolbox, which MCP clients connect to: takes one MCP JSON-RPC 2.0 message (initialize, tools/list, tools/call, resources/list, prompts/list, or a notification) and answers it, following the MCP streamable HTTP transport. Only the JSON-RPC fields Monolith reads and writes are modeled. A request whose Accept header selects text/event-stream is answered as a server-sent event instead.", extensions = @Extension(properties = @ExtensionProperty(name = "x-semoss-sdk-skip", value = "MCP server endpoint: MCP clients connect to it over the MCP transport")))
	@Parameter(name = "Authorization", in = ParameterIn.HEADER, description = "Bearer <ACCESS_KEY>:<SECRET_KEY>, the user's access key pair; not needed when the session already has a signed-in user", schema = @Schema(types = "string"))
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "One MCP JSON-RPC 2.0 message", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExtMcpCommsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The JSON-RPC response; a text/event-stream request gets it as one server-sent event", content = {
			@Content(mediaType = "application/json", schema = @Schema(implementation = ExtMcpCommsResponse.class)),
			@Content(mediaType = "text/event-stream", schema = @Schema(types = "string", description = "Server-sent events whose data is an MCP JSON-RPC 2.0 response")) })
	@ApiResponse(responseCode = "202", description = "The message was a notification, which gets no body")
	public void commsHttp(
			@Parameter(description = "The toolbox (project) whose tools the MCP server exposes") @PathParam("toolbox_id") String toolbox_id,
			@Parameter(description = "Not read; authenticate with the Authorization header") @QueryParam("access_key") String access,
			@Context HttpServletRequest request, @Context HttpServletResponse response) {
		classLogger.info("Running tool via streamable http... {}", toolbox_id);

		// Get the input stream from the async context
		try {
			final InputStream is = request.getInputStream();

			// Initialize session
			String authorization = request.getHeader("Authorization");
			HttpSession session = request.getSession();
			addAuthKeyToSession(session, authorization);
			String sessionId = session.getId();
			Insight insight = getInsight(session, authorization);

			int sessionTimeoutSeconds = session.getMaxInactiveInterval();
			long idleTimeoutMinutes = (sessionTimeoutSeconds <= 0) ? MAX_IDLE_TIMEOUT_MINUTES
					: Math.min(MAX_IDLE_TIMEOUT_MINUTES, sessionTimeoutSeconds / 60L);

			MCPReaper reaper = new MCPReaper(insight, sessionId, is, response, toolbox_id,
					request.getRequestURL().toString(), ThreadContext.getImmutableContext(), idleTimeoutMinutes);
			reaper.run();
		} catch (IOException e) {
			if (!WebUtility.handleStreamingException(e, classLogger, toolbox_id, null, null)) {
				classLogger.error("Error running tool via streamable http.... {}", toolbox_id, e);
			}
		}
	}

	/**
	 * Handle SSE connection for backward compatibility
	 * 
	 * @param toolbox_id
	 * @param access
	 * @param eventSink
	 * @param sse
	 * @param is
	 * @param request
	 */
	// Hidden: the text/event-stream variant of POST /comms, which shares its path and method; commsHttp documents both
	@Hidden
	@POST
	@Path("/comms")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void commsSse(@PathParam("toolbox_id") String toolbox_id, @QueryParam("access_key") String access,
			@Context SseEventSink eventSink, @Context Sse sse, InputStream is, @Context HttpServletRequest request,
			@Context HttpServletResponse response) {
		classLogger.info("Running tool via SSE... {}", toolbox_id);

		// Initialize session
		String authorization = request.getHeader("Authorization");
		HttpSession session = request.getSession();
		addAuthKeyToSession(session, authorization);
		String sessionId = session.getId();
		Insight insight = getInsight(session, authorization);

		try {
			response.setContentType(MediaType.SERVER_SENT_EVENTS);
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");
			response.setCharacterEncoding("UTF-8");
			response.flushBuffer();

			BufferedReader reader = new BufferedReader(new InputStreamReader(is));
			MCPReaper reaper = new MCPReaper(insight, sessionId, reader, eventSink, sse, toolbox_id,
					request.getRequestURL().toString(), ThreadContext.getImmutableContext());
			SSE_EXECUTOR.submit(reaper);
		} catch (IOException e) {
			if (!WebUtility.handleStreamingException(e, classLogger, toolbox_id, null, null)) {
				classLogger.error("Error running tool via sse.... {}", toolbox_id, e);
			}
		}
	}

	/**
	 * Adds the authorization key to a set in the session in a thread-safe manner.
	 * 
	 * @param session       The user's HttpSession
	 * @param authorization The authorization key to add
	 */
	private static void addAuthKeyToSession(HttpSession session, String authorization) {
		if (authorization != null) {
			String sessionId = session.getId();
			ReentrantLock sessionLock = SESSION_LOCKS.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
			sessionLock.lock();
			try {
				Set<String> mcpKeys = (Set<String>) session.getAttribute(MCP_AUTH_KEY);
				if (mcpKeys == null) {
					mcpKeys = new HashSet<>();
					session.setAttribute(MCP_AUTH_KEY, mcpKeys);
				}
				mcpKeys.add(authorization);
			} finally {
				sessionLock.unlock();
			}
		}
	}

	/**
	 * Remove a key from the mcpThread cache
	 * 
	 * @param key
	 */
	public static void clearInsight(String key) {
		if (key != null) {
			Insight removedInsight = INSIGHT_MAP.remove(key);
			if (removedInsight != null) {
				MCPReaper.clearInsightLock(removedInsight);
				classLogger.info("Removed cached insight from MCP thread");
			}
		}
	}

	/**
	 * Remove the session lock entry created for MCP auth-key writes.
	 *
	 * @param sessionId http session id
	 */
	public static void clearSessionLock(String sessionId) {
		if (sessionId != null) {
			SESSION_LOCKS.remove(sessionId);
		}
	}

	/**
	 * Remove an insight lock entry from MCP reaper lock cache.
	 *
	 * @param insightId insight identifier
	 */
	public static void clearInsightLock(String insightId) {
		MCPReaper.clearInsightLock(insightId);
	}

	/**
	 * Clears MCP session-scoped cache and lock state.
	 *
	 * @param sessionId http session id
	 */
	public static void clearSessionState(String sessionId) {
		clearInsight(sessionId);
		clearSessionLock(sessionId);
	}

	/**
	 *
	 * @param session
	 * @param authorization
	 * @return
	 */
	private Insight getInsight(HttpSession session, String authorization) {
		// no authorization key - default to session id
		String key = authorization != null ? authorization : session.getId();
		// fast path: avoid compute lock on cache hit
		Insight existing = INSIGHT_MAP.get(key);
		if (existing != null && InsightStore.getInstance().get(existing.getInsightId()) != null) {
			return existing;
		}
		return INSIGHT_MAP.compute(key, (k, e) -> {
			if (e != null && InsightStore.getInstance().get(e.getInsightId()) != null) {
				return e;
			}
			return initSession(session);
		});
	}

	/**
	 *
	 * @param session
	 * @return
	 */
	private Insight initSession(HttpSession session) {
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

}
