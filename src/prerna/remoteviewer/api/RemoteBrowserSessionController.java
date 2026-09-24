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

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.playwright.PlaywrightUtility;
import prerna.reactor.playwright.PlaywrightDownloadRegistry;
import prerna.reactor.playwright.RecordingMeta;
import prerna.reactor.playwright.StepsEnvelope;
import prerna.remoteviewer.model.RemoteBrowserRecordedStep;
import prerna.remoteviewer.model.RemoteBrowserSessionCreateRequest;
import prerna.remoteviewer.model.RemoteBrowserSessionCreateResponse;
import prerna.remoteviewer.security.RemoteBrowserUrlSafetyValidator;
import prerna.remoteviewer.service.RemoteBrowserSelectedTextService;
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
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

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

		if (req == null) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid request body");
		}

		String requestedUrl = req.getUrl() == null ? "" : req.getUrl().trim();
		boolean preserveExisting = Boolean.TRUE.equals(req.getPreserveExisting());
		if (requestedUrl.isBlank() && !preserveExisting) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'url' is required");
		}

		if (!requestedUrl.isBlank()) {
			try {
				RemoteBrowserUrlSafetyValidator.validate(requestedUrl);
			} catch (IllegalArgumentException e) {
				return buildError(Response.Status.BAD_REQUEST, e.getMessage());
			}
		}

		String userId = user.getPrimaryLoginToken().getId();
		int vpWidth = req.getViewportWidth() != null ? req.getViewportWidth() : 0;
		int vpHeight = req.getViewportHeight() != null ? req.getViewportHeight() : 0;

		RemoteBrowserSession session;
		try {
			session = RemoteBrowserSessionManager.getInstance().createSession(user, requestedUrl, vpWidth, vpHeight);
		} catch (IllegalStateException e) {
			return buildError(Response.Status.TOO_MANY_REQUESTS, e.getMessage());
		} catch (Exception e) {
			classLogger.error("Failed to create browser session for user {}: {}", userId, e.getMessage(), e);
			return buildError(Response.Status.INTERNAL_SERVER_ERROR, "Could not create browser session");
		}

		String wsUrl = "/browserSocket/" + session.getSessionId();
		RemoteBrowserSessionCreateResponse resp = new RemoteBrowserSessionCreateResponse(session.getSessionId(), wsUrl,
				session.getViewportWidth(), session.getViewportHeight(), safeUrl(session),
				RemoteBrowserSelectedTextService.contextLimits());

		classLogger.info("Browser session {} created for user {}", session.getSessionId(), userId);
		return Response.ok(GSON.toJson(resp)).build();
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
	public Response getRemoteBrowserRecordedSteps(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId) {
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
	 * Returns the full replayable recording envelope for a session.
	 *
	 * <p>
	 * GET /api/browser-sessions/{sessionId}/recording
	 */
	@GET
	@Path("/{sessionId}/recording")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRecordingEnvelope(@Context HttpServletRequest request,
			@PathParam("sessionId") String sessionId) {
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

		try {
			return Response.ok(PlaywrightUtility.GSON.toJson(session.getRecordingHistory())).build();
		} catch (Exception e) {
			classLogger.error("Failed to serialize remote browser recording session={}: {}", sessionId, e.getMessage(),
					e);
			return buildError(Response.Status.INTERNAL_SERVER_ERROR, "Could not load recording");
		}
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
					existingMeta = PlaywrightUtility.readStepsEnvelope(file.toFile()).meta();
				} catch (Exception ignored) {
					// Keep saving even if the old file is malformed; only metadata preservation is
					// lost.
				}
			}

			RecordingMeta meta = new RecordingMeta(
					(existingMeta != null && existingMeta.id() != null) ? existingMeta.id() : sessionId, req.title,
					req.description,
					(existingMeta != null && existingMeta.createdAt() != null) ? existingMeta.createdAt() : now, now,
					req.intent);
			StepsEnvelope env = new StepsEnvelope("1.0", meta, session.getRecordingHistory().steps());
			PlaywrightUtility.writeStepsEnvelope(file.toFile(), env);
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
	 * Returns metadata for native browser downloads captured during the current or
	 * requested run. Binary contents are intentionally never returned by this API.
	 *
	 * <p>
	 * GET /api/browser-sessions/{sessionId}/downloads?runId=...
	 */
	@GET
	@Path("/{sessionId}/downloads")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getDownloads(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId,
			@QueryParam("runId") String runId) {
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

		PlaywrightDownloadRegistry registry = session.getPlaywrightSession().getDownloadRegistry();
		registry.awaitIdle(2_000);
		String resolvedRunId = runId == null || runId.isBlank() ? registry.getActiveRunId() : runId;
		List<Map<String, Object>> downloads = new ArrayList<>();
		List<Map<String, Object>> errors = new ArrayList<>();
		for (PlaywrightDownloadRegistry.DownloadRecord record : registry.getRecords(resolvedRunId)) {
			Map<String, Object> metadata = record.toMap();
			downloads.add(metadata);
			if ("failed".equals(record.getStatus()) || "save-failed".equals(record.getStatus())) {
				errors.add(downloadError(record));
			}
		}
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("runId", resolvedRunId);
		response.put("downloadCount", downloads.size());
		response.put("downloads", downloads);
		response.put("downloadErrors", errors);
		return Response.ok(GSON.toJson(response)).build();
	}

	/**
	 * Persists staged native browser downloads into an editable Insight. The
	 * operation is per-file best effort and idempotent for an already-saved record.
	 *
	 * <p>
	 * POST /api/browser-sessions/{sessionId}/downloads/save
	 *
	 * <pre>
	 * {"insightId":"current-insight-id","downloadIds":["download-id-1"]}
	 * </pre>
	 */
	@POST
	@Path("/{sessionId}/downloads/save")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response saveDownloads(@Context HttpServletRequest request, @PathParam("sessionId") String sessionId,
			String body) {
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

		SaveDownloadsRequest req;
		try {
			req = body == null || body.isBlank() ? null : GSON.fromJson(body, SaveDownloadsRequest.class);
		} catch (Exception e) {
			return buildError(Response.Status.BAD_REQUEST, "Invalid request body");
		}
		if (req == null || req.insightId == null || req.insightId.isBlank()) {
			return buildError(Response.Status.BAD_REQUEST, "Field 'insightId' is required");
		}

		Insight insight = InsightStore.getInstance().get(req.insightId);
		if (insight == null || insight.getUser() == null) {
			return buildError(Response.Status.NOT_FOUND, "Insight not found");
		}
		if (insight.isSavedInsight()
				&& !SecurityInsightUtils.userCanEditInsight(user, insight.getProjectId(), insight.getRdbmsId())) {
			return buildError(Response.Status.FORBIDDEN, "User does not have edit access to this insight");
		}
		if (!insight.isSavedInsight() && (insight.getUser().getPrimaryLoginToken() == null
				|| !user.getPrimaryLoginToken().getId().equals(insight.getUser().getPrimaryLoginToken().getId()))) {
			return buildError(Response.Status.FORBIDDEN, "Insight is not owned by the authenticated browser session");
		}

		PlaywrightDownloadRegistry registry = session.getPlaywrightSession().getDownloadRegistry();
		registry.awaitIdle(5_000);
		List<PlaywrightDownloadRegistry.DownloadRecord> candidates = selectDownloadRecords(registry, req.downloadIds);
		if (req.downloadIds != null && !req.downloadIds.isEmpty() && candidates.size() != req.downloadIds.size()) {
			return buildError(Response.Status.BAD_REQUEST,
					"One or more downloadIds are not part of this browser session");
		}

		List<Map<String, Object>> saved = new ArrayList<>();
		List<Map<String, Object>> errors = new ArrayList<>();
		for (PlaywrightDownloadRegistry.DownloadRecord record : registry.getActiveRunRecords()) {
			if ("failed".equals(record.getStatus())) {
				errors.add(downloadError(record));
			}
		}
		for (PlaywrightDownloadRegistry.DownloadRecord record : candidates) {
			try {
				Map<String, Object> result = saveOneDownload(record, registry, insight, user);
				if ("saved".equals(record.getStatus())) {
					saved.add(result);
				} else {
					if (errors.stream().noneMatch(error -> record.getDownloadId().equals(error.get("downloadId")))) {
						errors.add(downloadError(record));
					}
				}
			} catch (Exception e) {
				String reason = e.getMessage() == null || e.getMessage().isBlank() ? "Could not save download" : e.getMessage();
				registry.markSaveFailed(record.getDownloadId(), reason);
				errors.add(downloadError(record));
			}
		}
		if (!saved.isEmpty() && insight.getRoomId() != null) {
			ClusterUtil.pushRoomAsync(insight.getRoomId());
		}

		Map<String, Object> response = new LinkedHashMap<>();
		String responseRunId = saved.isEmpty() ? registry.getActiveRunId() : String.valueOf(saved.get(0).get("runId"));
		response.put("runId", responseRunId);
		response.put("downloadSummary", downloadSummary(saved, responseRunId));
		response.put("downloadCount", saved.size());
		response.put("downloads", saved);
		response.put("downloadErrors", errors);
		return Response.ok(GSON.toJson(response)).build();
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

		RemoteBrowserSessionManager.getInstance().finishSession(session);
		classLogger.info("Browser session {} closed by user {}", sessionId, user.getPrimaryLoginToken().getId());
		return Response.ok(GSON.toJson(Map.of("message", "Session closed"))).build();
	}

	// ---- helpers ----

	private static List<PlaywrightDownloadRegistry.DownloadRecord> selectDownloadRecords(
			PlaywrightDownloadRegistry registry, List<String> requestedIds) {
		List<PlaywrightDownloadRegistry.DownloadRecord> available = registry.getActiveRunRecords();
		if (requestedIds == null || requestedIds.isEmpty()) {
			return available.stream()
					.filter(record -> "ready".equals(record.getStatus()) || "save-failed".equals(record.getStatus()))
					.toList();
		}
		List<PlaywrightDownloadRegistry.DownloadRecord> selected = new ArrayList<>();
		for (String id : requestedIds) {
			PlaywrightDownloadRegistry.DownloadRecord record = registry.getRecord(id);
			if (record != null && !selected.contains(record)) {
				selected.add(record);
			}
		}
		return selected;
	}

	private static Map<String, Object> saveOneDownload(PlaywrightDownloadRegistry.DownloadRecord record,
			PlaywrightDownloadRegistry registry, Insight insight, User user) throws IOException {
		return registry.persistRecord(record, insight, user);
	}

	private static Map<String, Object> downloadError(PlaywrightDownloadRegistry.DownloadRecord record) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("downloadId", record.getDownloadId());
		error.put("runId", record.getRunId());
		error.put("stepId", record.getTriggerStepId());
		error.put("fileName", record.getFileName());
		error.put("status", record.getStatus());
		error.put("error", record.getError() == null ? "Download could not be saved" : record.getError());
		return error;
	}

	private static String downloadSummary(List<Map<String, Object>> saved, String runId) {
		if (saved.isEmpty()) {
			return "No browser downloads were saved to the current insight";
		}
		return "Downloaded " + saved.size() + " file" + (saved.size() == 1 ? "" : "s")
				+ " and saved them to the current insight under /browser-downloads/" + runId + "/";
	}

	private static Response buildError(Response.Status status, String message) {
		Map<String, String> body = new HashMap<>();
		body.put("error", message);
		return Response.status(status).entity(GSON.toJson(body)).build();
	}

	private static String safeUrl(RemoteBrowserSession session) {
		try {
			return session.getActivePage().url();
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

	private static class SaveDownloadsRequest {
		private String insightId;
		private List<String> downloadIds;
	}
}
