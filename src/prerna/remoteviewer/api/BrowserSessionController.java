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
package prerna.remoteviewer.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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

import com.google.gson.Gson;

import prerna.auth.User;
import prerna.remoteviewer.model.BrowserSessionCreateRequest;
import prerna.remoteviewer.model.BrowserSessionCreateResponse;
import prerna.remoteviewer.model.RecordedStep;
import prerna.remoteviewer.security.UrlSafetyValidator;
import prerna.remoteviewer.service.BrowserSession;
import prerna.remoteviewer.service.BrowserSessionManager;
import prerna.semoss.web.services.local.ResourceUtility;

/**
 * REST resource for creating and managing remote browser sessions.
 *
 * <p>Mounted at {@code /api/browser-sessions} via {@code MonolithApplication}.
 */
@Path("/browser-sessions")
public class BrowserSessionController {

	private static final Logger classLogger = LogManager.getLogger(BrowserSessionController.class);
	private static final Gson GSON = new Gson();

	/**
	 * Creates a new isolated browser session and navigates to the requested URL.
	 *
	 * <p>POST /api/browser-sessions
	 * <pre>
	 * {
	 *   "url": "https://github.com",
	 *   "viewportWidth": 1365,
	 *   "viewportHeight": 768
	 * }
	 * </pre>
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createSession(@Context HttpServletRequest request, String body) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		BrowserSessionCreateRequest req;
		try {
			req = GSON.fromJson(body, BrowserSessionCreateRequest.class);
		} catch (Exception e) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid request body");
		}

		if (req == null || req.getUrl() == null || req.getUrl().isBlank()) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'url' is required");
		}

		try {
			UrlSafetyValidator.validate(req.getUrl());
		} catch (IllegalArgumentException e) {
			return buildError(Response.Status.BAD_REQUEST, e.getMessage());
		}

		String userId = user.getPrimaryLoginToken().getId();
		int vpWidth = req.getViewportWidth() != null ? req.getViewportWidth() : 0;
		int vpHeight = req.getViewportHeight() != null ? req.getViewportHeight() : 0;

		BrowserSession session;
		try {
			session = BrowserSessionManager.getInstance().createSession(userId, req.getUrl(), vpWidth, vpHeight);
		} catch (IllegalStateException e) {
			return buildError(Response.Status.TOO_MANY_REQUESTS, e.getMessage());
		} catch (Exception e) {
			classLogger.error("Failed to create browser session for user {}: {}", userId, e.getMessage(), e);
			return buildError(Response.Status.INTERNAL_SERVER_ERROR, "Could not create browser session");
		}

		String wsUrl = "/browserSocket/" + session.getSessionId();
		BrowserSessionCreateResponse resp = new BrowserSessionCreateResponse(
				session.getSessionId(), wsUrl, session.getViewportWidth(), session.getViewportHeight());

		classLogger.info("Browser session {} created for user {}", session.getSessionId(), userId);
		return Response.ok(GSON.toJson(resp)).build();
	}

	/**
	 * Returns metadata for an existing session.
	 *
	 * <p>GET /api/browser-sessions/{sessionId}
	 */
	@GET
	@Path("/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSession(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<BrowserSession> opt = BrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "Session not found");
		}

		BrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		Map<String, Object> info = new HashMap<>();
		info.put("sessionId", session.getSessionId());
		info.put("viewport", Map.of("width", session.getViewportWidth(), "height", session.getViewportHeight()));
		info.put("currentUrl", safeUrl(session));
		info.put("createdAt", session.getCreatedAt().toString());
		info.put("lastActivityAt", session.getLastActivityAt().toString());

		return Response.ok(GSON.toJson(info)).build();
	}

	/**
	 * Returns the recorded steps for a session.
	 *
	 * <p>GET /api/browser-sessions/{sessionId}/steps
	 */
	@GET
	@Path("/{sessionId}/steps")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRecordedSteps(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<BrowserSession> opt = BrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "Session not found");
		}

		BrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		List<RecordedStep> steps = session.getRecordedSteps();
		return Response.ok(GSON.toJson(steps)).build();
	}

	/**
	 * Closes a browser session.
	 *
	 * <p>DELETE /api/browser-sessions/{sessionId}
	 */
	@DELETE
	@Path("/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response closeSession(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<BrowserSession> opt = BrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return Response.ok(GSON.toJson(Map.of("message", "Session not found or already closed"))).build();
		}

		BrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		BrowserSessionManager.getInstance().closeSession(sessionId);
		classLogger.info("Browser session {} closed by user {}", sessionId, user.getPrimaryLoginToken().getId());
		return Response.ok(GSON.toJson(Map.of("message", "Session closed"))).build();
	}

	// ---- helpers ----

	private static Response buildError(Response.Status status, String message) {
		Map<String, String> body = new HashMap<>();
		body.put("error", message);
		return Response.status(status).entity(GSON.toJson(body)).build();
	}

	private static String safeUrl(BrowserSession session) {
		try {
			return session.getPage().url();
		} catch (Exception e) {
			return "";
		}
	}
}
