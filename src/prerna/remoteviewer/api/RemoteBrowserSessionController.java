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

import java.nio.file.Files;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;

import prerna.auth.User;
import prerna.reactor.playwright.PlaywrightUtility;
import prerna.reactor.playwright.RecordingMeta;
import prerna.reactor.playwright.Selector;
import prerna.reactor.playwright.StepsEnvelope;
import prerna.remoteviewer.model.RemoteBrowserInputEvent;
import prerna.remoteviewer.model.RemoteBrowserSessionCreateRequest;
import prerna.remoteviewer.model.RemoteBrowserSessionCreateResponse;
import prerna.remoteviewer.model.RemoteBrowserRecordedStep;
import prerna.remoteviewer.security.RemoteBrowserInputEventValidator;
import prerna.remoteviewer.security.RemoteBrowserUrlSafetyValidator;
import prerna.remoteviewer.service.RemoteBrowserSession;
import prerna.remoteviewer.service.RemoteBrowserSessionManager;
import prerna.semoss.web.services.local.ResourceUtility;

/**
 * REST resource for creating and managing remote browser sessions.
 *
 * <p>
 * Mounted at {@code /api/browser-sessions} via {@code MonolithApplication}.
 */
@Path("/browser-sessions")
public class RemoteBrowserSessionController {

	private static final Logger classLogger = LogManager.getLogger(RemoteBrowserSessionController.class);
	private static final Gson GSON = new Gson();
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	/**
	 * Creates a new isolated browser session and navigates to the requested URL.
	 *
	 * <p>
	 * POST /api/browser-sessions
	 * 
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

		RemoteBrowserSessionCreateRequest req;
		try {
			req = GSON.fromJson(body, RemoteBrowserSessionCreateRequest.class);
		} catch (Exception e) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid request body");
		}

		if (req == null || req.getUrl() == null || req.getUrl().isBlank()) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'url' is required");
		}

		try {
			RemoteBrowserUrlSafetyValidator.validate(req.getUrl());
		} catch (IllegalArgumentException e) {
			return buildError(Response.Status.BAD_REQUEST, e.getMessage());
		}

		String userId = user.getPrimaryLoginToken().getId();
		int vpWidth = req.getViewportWidth() != null ? req.getViewportWidth() : 0;
		int vpHeight = req.getViewportHeight() != null ? req.getViewportHeight() : 0;

		RemoteBrowserSession session;
		try {
			session = RemoteBrowserSessionManager.getInstance().createSession(user, req.getUrl(), vpWidth, vpHeight);
		} catch (IllegalStateException e) {
			return buildError(Response.Status.TOO_MANY_REQUESTS, e.getMessage());
		} catch (Exception e) {
			classLogger.error("Failed to create browser session for user {}: {}", userId, e.getMessage(), e);
			return buildError(Response.Status.INTERNAL_SERVER_ERROR, "Could not create browser session");
		}

		String wsUrl = "/browserSocket/" + session.getSessionId();
		RemoteBrowserSessionCreateResponse resp = new RemoteBrowserSessionCreateResponse(session.getSessionId(), wsUrl,
				session.getViewportWidth(), session.getViewportHeight());

		classLogger.info("Browser session {} created for user {}", session.getSessionId(), userId);
		return Response.ok(GSON.toJson(resp)).build();
	}

	/**
	 * Returns metadata for an existing session.
	 *
	 * <p>
	 * GET /api/browser-sessions/{sessionId}
	 */
	@GET
	@Path("/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSession(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "Session not found");
		}

		RemoteBrowserSession session = opt.get();
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
	 * <p>
	 * GET /api/browser-sessions/{sessionId}/steps
	 */
	@GET
	@Path("/{sessionId}/steps")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRemoteBrowserRecordedSteps(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "Session not found");
		}

		RemoteBrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		List<RemoteBrowserRecordedStep> steps = session.getRemoteBrowserRecordedSteps();
		return Response.ok(GSON.toJson(steps)).build();
	}

	/**
	 * Saves the replayable Playwright recording for a remote browser session into
	 * the selected project's recordings folder.
	 *
	 * <p>
	 * POST /api/browser-sessions/{sessionId}/recording/save
	 *
	 * <pre>
	 * {
	 *   "project": "PROJECT_ID",
	 *   "name": "github-login-2026-07-07",
	 *   "title": "Github login",
	 *   "description": "",
	 *   "intent": ""
	 * }
	 * </pre>
	 */
	@POST
	@Path("/{sessionId}/recording/save")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response saveRecording(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId,
			String body) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "No active recording buffer found");
		}

		RemoteBrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		SaveRecordingRequest req;
		try {
			req = GSON.fromJson(body, SaveRecordingRequest.class);
		} catch (Exception e) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid request body");
		}

		if (req == null || req.project == null || req.project.isBlank()) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'project' is required");
		}
		if (req.name == null || req.name.isBlank()) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'name' is required");
		}

		try {
			long now = System.currentTimeMillis();
			String base = PlaywrightUtility.sanitizeFilename(req.name);
			java.nio.file.Path file = PlaywrightUtility.initRecordingsDir(req.project)
					.resolve(base.endsWith(".json") ? base : base + ".json");

			RecordingMeta existingMeta = null;
			if (Files.exists(file)) {
				try {
					existingMeta = JSON.readValue(file.toFile(), StepsEnvelope.class).meta();
				} catch (Exception ignored) {
					// Keep saving even if the old file is malformed; only metadata preservation is lost.
				}
			}

			RecordingMeta meta = new RecordingMeta(
					(existingMeta != null && existingMeta.id() != null) ? existingMeta.id() : sessionId,
					req.title, req.description,
					(existingMeta != null && existingMeta.createdAt() != null) ? existingMeta.createdAt() : now, now,
					req.intent);
			StepsEnvelope env = new StepsEnvelope("1.0", meta, session.getRecordingHistory().steps());
			JSON.writeValue(file.toFile(), env);
			session.clearRecordingBuffer();
			if (session.isRecordingEnabled()) {
				prerna.remoteviewer.service.RemoteBrowserRecordingService.recordCurrentNavigation(session);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("filePath", file.toAbsolutePath().toString());
			response.put("fileName", file.getFileName().toString());
			response.put("project", req.project);
			response.put("saved", true);
			return Response.ok(GSON.toJson(response)).build();
		} catch (Exception e) {
			classLogger.error("Failed to save remote browser recording session={} project={} name={}: {}", sessionId,
					req.project, req.name, e.getMessage(), e);
			return buildError(Response.Status.INTERNAL_SERVER_ERROR, "Could not save recording");
		}
	}

	/**
	 * Closes a browser session.
	 *
	 * <p>
	 * DELETE /api/browser-sessions/{sessionId}
	 */
	@DELETE
	@Path("/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response closeSession(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return Response.ok(GSON.toJson(Map.of("message", "Session not found or already closed"))).build();
		}

		RemoteBrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		RemoteBrowserSessionManager.getInstance().closeSession(session);
		classLogger.info("Browser session {} closed by user {}", sessionId, user.getPrimaryLoginToken().getId());
		return Response.ok(GSON.toJson(Map.of("message", "Session closed"))).build();
	}

	// ---- helpers ----

	/**
	 * Injects a single input event from an external source (e.g. Chrome extension)
	 * into the session's event queue.
	 * POST /api/browser-sessions/{sessionId}/inject
	 */
	@POST
	@Path("/{sessionId}/inject")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response injectEvent(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId, String body) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return buildError(Response.Status.UNAUTHORIZED, "User session is invalid");
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return buildError(Response.Status.NOT_FOUND, "Session not found");
		}

		RemoteBrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			return buildError(Response.Status.FORBIDDEN, "Access denied");
		}

		RemoteBrowserInputEvent event;
		try {
			event = GSON.fromJson(body, RemoteBrowserInputEvent.class);
		} catch (Exception e) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid event body");
		}

		classLogger.info("Remote viewer inject received session={} user={} event={}",
				sessionId, user.getPrimaryLoginToken().getId(), describeEvent(event));

		scaleRecordedCoordinates(event, session);

		try {
			RemoteBrowserInputEventValidator.validate(event, session.getViewportWidth(), session.getViewportHeight());
		} catch (IllegalArgumentException e) {
			classLogger.warn("Remote viewer inject rejected session={} event={} reason={}",
					sessionId, describeEvent(event), e.getMessage());
			return buildError(Response.Status.BAD_REQUEST, e.getMessage());
		}

		boolean offered = session.eventQueue.offer(event);
		if (!offered) {
			classLogger.warn("Remote viewer inject queue full session={} event={}", sessionId, describeEvent(event));
			return buildError(Response.Status.TOO_MANY_REQUESTS, "Session event queue is full");
		}

		session.touchActivity();
		classLogger.info("Remote viewer inject queued session={} queueSize={} event={}",
				sessionId, session.eventQueue.size(), describeEvent(event));
		return Response.ok(GSON.toJson(Map.of("queued", true))).build();
	}

	// ---- helpers ----

	private static Response buildError(Response.Status status, String message) {
		Map<String, String> body = new HashMap<>();
		body.put("error", message);
		return Response.status(status).entity(GSON.toJson(body)).build();
	}

	private static void scaleRecordedCoordinates(RemoteBrowserInputEvent event, RemoteBrowserSession session) {
		if (event == null || event.getX() == null || event.getY() == null) {
			return;
		}

		Integer recordedWidth = event.getRecordedViewportWidth();
		Integer recordedHeight = event.getRecordedViewportHeight();
		if (recordedWidth == null || recordedHeight == null || recordedWidth <= 0 || recordedHeight <= 0) {
			return;
		}

		double originalX = event.getX();
		double originalY = event.getY();
		double scaleX = (double) session.getViewportWidth() / recordedWidth;
		double scaleY = (double) session.getViewportHeight() / recordedHeight;
		event.setX(event.getX() * scaleX);
		event.setY(event.getY() * scaleY);
		classLogger.info("Remote viewer inject scaled coordinates session={} recordedViewport={}x{} sessionViewport={}x{} scale=({}, {}) from=({}, {}) to=({}, {})",
				session.getSessionId(), recordedWidth, recordedHeight,
				session.getViewportWidth(), session.getViewportHeight(),
				round(scaleX), round(scaleY), round(originalX), round(originalY),
				round(event.getX()), round(event.getY()));
	}

	private static String describeEvent(RemoteBrowserInputEvent event) {
		if (event == null) {
			return "null";
		}

        Selector selector = event.getSelector();
		String selectorDesc = selector == null
				? "none"
				: selector.strategy() + ":" + truncate(selector.value(), 120);
		StringBuilder sb = new StringBuilder();
		sb.append("type=").append(event.getType());
		sb.append(", x=").append(round(event.getX()));
		sb.append(", y=").append(round(event.getY()));
		sb.append(", selector=").append(selectorDesc);
		sb.append(", waitAfterMs=").append(event.getWaitAfterMs());
		sb.append(", recordedViewport=")
				.append(event.getRecordedViewportWidth()).append("x")
				.append(event.getRecordedViewportHeight());
		if (event.getUrl() != null) {
			sb.append(", url=").append(truncate(event.getUrl(), 180));
		}
		if (event.getText() != null) {
			sb.append(", textLength=").append(event.getText().length());
		}
		if (event.getKey() != null) {
			sb.append(", key=").append(event.getKey());
		}
		return sb.toString();
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "...";
	}

	private static Object round(Double value) {
		if (value == null) {
			return null;
		}
		return Math.round(value * 100.0) / 100.0;
	}

	private static String safeUrl(RemoteBrowserSession session) {
		try {
			return session.getPage().url();
		} catch (Exception e) {
			return "";
		}
	}

	private static class SaveRecordingRequest {
		private String project;
		private String name;
		private String title;
		private String description;
		private String intent;
	}
}
