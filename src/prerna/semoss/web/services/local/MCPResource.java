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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
import org.apache.logging.log4j.ThreadContext;

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class MCPResource {

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);

	public static final String MCP_AUTH_KEY = "MCP_AUTH_KEY";
	private static final Map<String, Insight> INSIGHT_MAP = new ConcurrentHashMap<>();

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
		classLogger.info("Running tool via streamable http... {}", toolbox_id);

		// Get the input and output streams from the async context
		try {
			final InputStream is = request.getInputStream();
			final OutputStream os = response.getOutputStream();

			// set response headers
			response.setContentType(MediaType.APPLICATION_JSON);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");
			response.setCharacterEncoding("UTF-8");
			response.flushBuffer();

			// Initialize session
			String authorization = request.getHeader("Authorization");
			HttpSession session = request.getSession();
			addAuthKeyToSession(session, authorization);
			String sessionId = session.getId();
			Insight insight = getInsight(session, authorization);

			MCPReaper reaper = new MCPReaper(insight, sessionId, is, os, toolbox_id, request.getRequestURL().toString(),
					ThreadContext.getImmutableContext());
			reaper.run();
		} catch (IOException e) {
			classLogger.error("Error running tool via streamable http.... {}", toolbox_id, e);
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
		classLogger.info("Running tool via SSE... {}", toolbox_id);

		// Initialize session
		String authorization = request.getHeader("Authorization");
		HttpSession session = request.getSession();
		addAuthKeyToSession(session, authorization);
		String sessionId = session.getId();
		Insight insight = getInsight(session, authorization);

		try {
			response.setContentType(MediaType.SERVER_SENT_EVENTS);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");
			response.setCharacterEncoding("UTF-8");
			response.flushBuffer();

			BufferedReader reader = new BufferedReader(new InputStreamReader(is));
			MCPReaper reaper = new MCPReaper(insight, sessionId, reader, eventSink, sse, toolbox_id,
					request.getRequestURL().toString(), ThreadContext.getImmutableContext());
			Thread t = new Thread(reaper);
			t.start();
		} catch (IOException e) {
			classLogger.error("Error running tool via sse.... {}", toolbox_id, e);
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
			synchronized (session) {
				Set<String> mcpKeys = (Set<String>) session.getAttribute(MCP_AUTH_KEY);
				if (mcpKeys == null) {
					mcpKeys = new HashSet<>();
					session.setAttribute(MCP_AUTH_KEY, mcpKeys);
				}
				mcpKeys.add(authorization);
			}
		}
	}

	/**
	 * Remove a key from the mcpThread cache
	 * 
	 * @param authorization
	 */
	public static void clearInsight(String authorization) {
		if (authorization != null) {
			Insight removedInsight = INSIGHT_MAP.remove(authorization);
			if (removedInsight != null) {
				classLogger.info("Removed cached insight from MCP thread for auth key");
			}
		}
	}

	/**
	 * 
	 * @param session
	 * @param authorization
	 * @return
	 */
	private Insight getInsight(HttpSession session, String authorization) {
		return INSIGHT_MAP.computeIfAbsent(authorization, key -> initSession(session));
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
