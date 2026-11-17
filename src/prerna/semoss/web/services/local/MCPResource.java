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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

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
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
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
	// the toolbox id is placed inside every pixel the toolbox runs; engine and
	// project ids use only these characters, while the path segment check alone
	// would still allow quotes
	private static final Pattern TOOLBOX_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

	private static final ExecutorService SSE_EXECUTOR = Executors
			.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp-sse-", 0).factory());

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
	public void commsHttp(@PathParam("toolbox_id") String toolbox_id, @QueryParam("access_key") String access,
			@Context HttpServletRequest request, @Context HttpServletResponse response) {
		if (!isValidToolboxId(toolbox_id)) {
			rejectToolboxId(response);
			return;
		}
		classLogger.info("Running tool via streamable http... {}", toolbox_id);

		// Get the input stream from the async context
		try {
			final InputStream is = request.getInputStream();

			// Initialize session
			HttpSession session = request.getSession();
			String cacheKey = insightCacheKey(session, request.getHeader("Authorization"));
			addAuthKeyToSession(session, cacheKey);
			String sessionId = session.getId();
			Insight insight = getInsight(session, cacheKey);

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
	@POST
	@Path("/comms")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.SERVER_SENT_EVENTS)
	public void commsSse(@PathParam("toolbox_id") String toolbox_id, @QueryParam("access_key") String access,
			@Context SseEventSink eventSink, @Context Sse sse, InputStream is, @Context HttpServletRequest request,
			@Context HttpServletResponse response) {
		if (!isValidToolboxId(toolbox_id)) {
			rejectToolboxId(response);
			try {
				eventSink.close();
			} catch (IOException e) {
				classLogger.warn("Unable to close the SSE stream of a refused MCP request", e);
			}
			return;
		}
		classLogger.info("Running tool via SSE... {}", toolbox_id);

		// Initialize session
		HttpSession session = request.getSession();
		String cacheKey = insightCacheKey(session, request.getHeader("Authorization"));
		addAuthKeyToSession(session, cacheKey);
		String sessionId = session.getId();
		Insight insight = getInsight(session, cacheKey);

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
	 * Returns the insight cache key for a caller: the signed in user's login plus a
	 * digest of the Authorization header, or the session id when there is no
	 * header. The same header sent by two people never shares an insight, and the
	 * header itself, a bearer secret, is never kept.
	 *
	 * @param session       the caller's session
	 * @param authorization the Authorization header, or null
	 * @return the cache key
	 */
	public static String insightCacheKey(HttpSession session, String authorization) {
		if (authorization == null) {
			return session.getId();
		}
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		AccessToken login = user == null ? null : user.getPrimaryLoginToken();
		String owner = login == null || login.getId() == null || login.getProvider() == null
				? "session " + session.getId()
				: login.getProvider().getLabel() + " " + login.getId();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(authorization.getBytes(StandardCharsets.UTF_8));
			return owner + " " + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/**
	 * Returns whether a toolbox id is safe to place in the toolbox's pixels.
	 *
	 * @param toolboxId the toolbox id from the request path
	 * @return true when the id has only the characters engine and project ids use
	 */
	private static boolean isValidToolboxId(String toolboxId) {
		return toolboxId != null && TOOLBOX_ID_PATTERN.matcher(toolboxId).matches();
	}

	/**
	 * Answers 400 for a toolbox id that is not safe to use, before any stream
	 * starts.
	 *
	 * @param response the response to answer on
	 */
	private static void rejectToolboxId(HttpServletResponse response) {
		classLogger.warn("Refused an MCP request for an invalid toolbox id");
		try {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid toolbox id");
		} catch (IOException e) {
			classLogger.warn("Unable to answer an MCP request for an invalid toolbox id", e);
		}
	}

	/**
	 * Adds an insight cache key to a set in the session in a thread-safe manner, so
	 * the cached insight is cleared when the session ends.
	 * 
	 * @param session  The user's HttpSession
	 * @param cacheKey The cache key from {@link #insightCacheKey}
	 */
	private static void addAuthKeyToSession(HttpSession session, String cacheKey) {
		if (cacheKey != null && !cacheKey.equals(session.getId())) {
			String sessionId = session.getId();
			ReentrantLock sessionLock = SESSION_LOCKS.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
			sessionLock.lock();
			try {
				Set<String> mcpKeys = (Set<String>) session.getAttribute(MCP_AUTH_KEY);
				if (mcpKeys == null) {
					mcpKeys = new HashSet<>();
					session.setAttribute(MCP_AUTH_KEY, mcpKeys);
				}
				mcpKeys.add(cacheKey);
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
	 * Returns the cached insight for a cache key, opening one for the session when
	 * there is none or the cached one has closed.
	 *
	 * @param session  the caller's session
	 * @param cacheKey the key from {@link #insightCacheKey}
	 * @return the insight to run in
	 */
	private Insight getInsight(HttpSession session, String cacheKey) {
		String key = cacheKey;
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
