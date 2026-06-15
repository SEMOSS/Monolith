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
package prerna.web.services.util;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.auth.User;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.om.Insight;
import prerna.om.ThreadStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.util.Constants;
import prerna.util.Utility;

/**
 * Central dispatch point for executing the {@code LLM(...)} pixel from the
 * provider-compatible web endpoints (OpenAI / Anthropic / Ollama).
 *
 * <p>
 * Both streaming and non-streaming requests funnel through here so the
 * server-side {@code LLMReactor} (room / parent-room resolution, image copying,
 * use_history defaulting, inference logging, etc.) always runs. Endpoints
 * should never call {@code room.ask(...)} directly - any behavior change to how
 * the model is invoked belongs here (or in {@code LLMReactor}) so it applies to
 * every code path in one place.
 */
public class ModelPixelExecutor {

	private static final Logger classLogger = LogManager.getLogger(ModelPixelExecutor.class);

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/**
	 * Build the {@code LLM(...)} pixel string. This is the single source of truth
	 * for how the endpoints invoke the model; {@code command} is {@code 'ignore'}
	 * because the full prompt and all parameters are carried in
	 * {@code paramValues}.
	 *
	 * @param engine  the model engine being invoked
	 * @param room    the room the message belongs to
	 * @param dataMap the parameter map (full_prompt, tools, temperature, etc.)
	 * @return the pixel expression to execute
	 */
	public static String buildModelPixel(IModelEngine engine, Room room, Map<String, Object> dataMap) {
		return "LLM(engine='" + engine.getEngineId() + "',roomId='" + room.getId() + "',command='ignore'"
				+ ",paramValues=[" + GSON.toJson(dataMap) + "]);";
	}

	/**
	 * Dispatch the model pixel asynchronously on a virtual thread and return the
	 * job id. Used by streaming endpoints, which then poll {@link PixelJobManager}
	 * for partial stream output.
	 *
	 * @param engine    the model engine being invoked
	 * @param insight   the insight context for the request
	 * @param room      the room the message belongs to
	 * @param dataMap   the parameter map carried in the pixel's paramValues
	 * @param sessionId the session id for the job
	 * @return the job id that can be polled on {@link PixelJobManager}
	 */
	public static String startAsyncModelRequest(IModelEngine engine, Insight insight, Room room,
			Map<String, Object> dataMap, String sessionId) {
		try {
			PixelJobManager manager = PixelJobManager.getManager();
			PixelJobRunner jobRunner = manager.makeJob(insight, sessionId, null);
			String jobId = jobRunner.getJobId();

			String modelPixel = buildModelPixel(engine, room, dataMap);
			classLogger.info("Dispatching async model pixel: {}", modelPixel);
			jobRunner.addPixel(modelPixel);
			Thread.ofVirtual().start(jobRunner);
			return jobId;
		} catch (Exception e) {
			classLogger.error("Failed to start async model request for engine '{}': {}",
					engine == null ? "unknown" : engine.getEngineId(), e.getMessage(), e);
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	/**
	 * Dispatch the model pixel synchronously on the current thread and return the
	 * model response. Used by non-streaming endpoints: this runs the exact same
	 * pixel as the streaming path, but simply waits for the final payload instead
	 * of polling for partial stream chunks.
	 *
	 * @param engine  the model engine being invoked
	 * @param insight the insight context for the request
	 * @param room    the room the message belongs to
	 * @param dataMap the parameter map carried in the pixel's paramValues
	 * @return the reconstructed model response
	 */
	public static AskModelEngineResponse<?> askModelSync(IModelEngine engine, Insight insight, Room room,
			Map<String, Object> dataMap) {
		String modelPixel = buildModelPixel(engine, room, dataMap);
		classLogger.info("Dispatching sync model pixel: {}", modelPixel);

		PixelRunner runner = insight.runPixel(modelPixel);
		if (runner.getResults() == null || runner.getResults().isEmpty()) {
			throw new IllegalStateException(
					"Model request returned no output for engine '" + engine.getEngineId() + "'");
		}

		Object payload = runner.getResults().get(0).getValue();
		AskModelEngineResponse<?> response = AskModelEngineResponse.fromObject(payload);

		// fromObject rebuilds the response from the pixel payload but does not carry
		// over messageId/roomId, which the response processors rely on - restore them.
		if (payload instanceof Map) {
			Map<?, ?> payloadMap = (Map<?, ?>) payload;
			Object messageId = payloadMap.get(AskModelEngineResponse.MESSAGE_ID);
			if (messageId != null) {
				response.setMessageId(messageId.toString());
			}
			Object roomId = payloadMap.get(AskModelEngineResponse.ROOM_ID);
			if (roomId != null) {
				response.setRoomId(roomId.toString());
			}
		}

		return response;
	}

	/**
	 * Seed the {@link ThreadStore} for the current request thread so downstream
	 * pixel execution (and the {@code LLMReactor}) can resolve the insight,
	 * session, job and user without them being threaded through every call.
	 *
	 * @param insight   the insight context for the request
	 * @param sessionId the session id the request belongs to
	 * @param jobId     the job id assigned to this request
	 */
	public static void initializeThreadStore(Insight insight, String sessionId, String jobId) {
		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(sessionId);
		ThreadStore.setJobId(jobId);
		ThreadStore.setUser(insight.getUser());
	}

	/**
	 * Resolve and apply the caller's timezone to the {@link User}. Uses the
	 * {@code tz} request parameter when present and valid, otherwise falls back to
	 * the application default zone. Invalid values are logged and replaced with the
	 * default rather than failing the request.
	 *
	 * @param user    the user whose zone id is being set
	 * @param request the incoming request, read for the optional {@code tz}
	 *                parameter
	 */
	public static void applyUserTimezone(User user, HttpServletRequest request) {
		ZoneId zoneId;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn(
						"Invalid timezone value '{}' for Ollama request; falling back to application default '{}': {}",
						strTz, Utility.getApplicationZoneId(), e.getMessage(), e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		user.setZoneId(zoneId);
	}

	/**
	 * Pull the authenticated {@link User} off the session.
	 *
	 * @param session the current http session, may be {@code null}
	 * @return the session user, or {@code null} if there is no session or no user
	 *         on it
	 */
	public static User getSessionUser(HttpSession session) {
		if (session == null) {
			return null;
		}
		return (User) session.getAttribute(Constants.SESSION_USER);
	}

	/**
	 * Invalidate the current session (when present) and build the standard 401
	 * response used when a request arrives without a valid authenticated session.
	 *
	 * @param request the incoming request
	 * @param session the current session, may be {@code null}
	 * @return a 401 {@link Response} carrying an "invalid session" error message
	 */
	public static Response invalidSessionResponse(HttpServletRequest request, HttpSession session) {
		if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
			session.invalidate();
		}
		return errorResponse(401, "User session is invalid");
	}

	/**
	 * Build a JSON error {@link Response} carrying a single
	 * {@link Constants#ERROR_MESSAGE} entry. Shared by the provider endpoints so
	 * every error payload has the same shape.
	 *
	 * @param statusCode the HTTP status code to return
	 * @param message    the human-readable error message
	 * @return the error {@link Response}
	 */
	public static Response errorResponse(int statusCode, String message) {
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put(Constants.ERROR_MESSAGE, message);
		return WebUtility.getResponse(errorMap, statusCode);
	}

	/**
	 * Private constructor - this class exposes only static helpers and must not be
	 * instantiated.
	 */
	private ModelPixelExecutor() {
	}
}
