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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.usage.ModelUsageGuard;
import prerna.engine.impl.model.usage.ModelUsageLimitException;
import prerna.om.Insight;
import prerna.util.Constants;
import prerna.util.Utility;

/**
 * Servlet-side concerns shared by the provider-compatible web endpoints (OpenAI
 * / Anthropic / Ollama): resolving the session user, applying the caller's
 * timezone and building the common error responses, including the one for a
 * call a usage limit refused.
 *
 * <p>
 * Invoking the model itself lives in {@link ModelPixelInvoker} so that callers
 * which have no servlet request - the {@code OpenAIPassthroughReactor}, which
 * serves the same protocol to python processes over the insight socket - run
 * the identical {@code LLM(...)} pixel.
 */
public class ModelPixelExecutor {

	private static final Logger classLogger = LogManager.getLogger(ModelPixelExecutor.class);

	/**
	 * The error code for a call refused by a minute or hour rule, which can be
	 * retried after {@code Retry-After}. A day or longer rule has
	 * {@link ModelUsageLimitException#ERROR_CODE}, which is not worth retrying
	 * until the limit resets.
	 */
	public static final String RATE_LIMIT_CODE = "rate_limit_exceeded";
	/** The error code for a call refused because usage limits cannot be checked */
	public static final String UNAVAILABLE_CODE = "usage_limits_unavailable";
	private static final int TOO_MANY_REQUESTS = 429;
	private static final int SERVICE_UNAVAILABLE = 503;
	private static final String RETRY_AFTER = "Retry-After";

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
	 * Run a model call through the {@code LLM(...)} pixel, as
	 * {@link ModelPixelInvoker#askModelSync} does. The pixel reports a usage limit
	 * refusal as an error, which the refusal records on this thread, so a failed
	 * call throws the refusal itself.
	 *
	 * @param engine  the model engine being invoked
	 * @param insight the insight context for the request
	 * @param room    the room the message belongs to
	 * @param dataMap the parameter map carried in the pixel's paramValues
	 * @return the model response
	 * @throws ModelUsageLimitException when a usage limit refused the call
	 */
	public static AskModelEngineResponse<?> askModelSync(IModelEngine engine, Insight insight, Room room,
			Map<String, Object> dataMap) {
		ModelUsageLimitException.clearLastOnThread();
		try {
			return ModelPixelInvoker.askModelSync(engine, insight, room, dataMap);
		} catch (RuntimeException e) {
			ModelUsageLimitException refusal = findUsageLimitRefusal(e);
			if (refusal == null) {
				refusal = ModelUsageLimitException.takeLastOnThread();
			}
			throw refusal != null ? refusal : e;
		}
	}

	/**
	 * Find the usage limit refusal behind a failed model call. The call can wrap
	 * it, so the causes are searched too.
	 *
	 * @param error what the model call threw
	 * @return the refusal, or {@code null} when no usage limit refused the call
	 */
	public static ModelUsageLimitException findUsageLimitRefusal(Throwable error) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
			if (cause instanceof ModelUsageLimitException refusal) {
				return refusal;
			}
		}
		return null;
	}

	/**
	 * Check a streaming call against the caller's usage limits before its stream
	 * starts, without reserving anything. A stream that has started has already
	 * answered 200, so this is the only point where a refusal can still have its
	 * own status. The call is checked again when it runs, and a refusal then is
	 * reported inside the stream.
	 *
	 * @param user   the caller
	 * @param engine the model
	 * @return the refusal, or {@code null} when the stream may start
	 */
	public static ModelUsageLimitException checkUsageLimitsBeforeStream(User user, IModelEngine engine) {
		try {
			ModelUsageGuard.check(user, engine);
			return null;
		} catch (ModelUsageLimitException refusal) {
			return refusal;
		} catch (Exception e) {
			// the call itself reports any other problem, as it would without this check
			classLogger.debug("Usage limit check before streaming model {} could not run: {}",
					engine == null ? "unknown" : engine.getEngineId(), e.getMessage(), e);
			return null;
		}
	}

	/**
	 * The error code a provider-style error body gives a call a usage limit
	 * refused, which tells a client whether it is worth retrying soon.
	 *
	 * @param refusal a usage limit refusal
	 * @return {@link #UNAVAILABLE_CODE} when limits cannot be checked,
	 *         {@link #RATE_LIMIT_CODE} for a minute or hour rule, otherwise
	 *         {@link ModelUsageLimitException#ERROR_CODE}
	 */
	public static String usageLimitCode(ModelUsageLimitException refusal) {
		if (refusal.isUnavailable()) {
			return UNAVAILABLE_CODE;
		}
		return refusal.isRateLimit() ? RATE_LIMIT_CODE : ModelUsageLimitException.ERROR_CODE;
	}

	/**
	 * Build the response for a call a usage limit refused: 429, or 503 when the
	 * limits cannot be checked right now. A minute or hour rule adds
	 * {@code Retry-After}, the seconds until it resets; a day or longer rule has
	 * none, so clients do not keep retrying for hours.
	 *
	 * @param refusal the refusal
	 * @param body    the provider-style error body, whose code is
	 *                {@link #usageLimitCode}
	 * @return the response
	 */
	public static Response usageLimitResponse(ModelUsageLimitException refusal, Map<String, Object> body) {
		classLogger.info("A usage limit refused a model call: {}", refusal.getMessage());
		Long retryAfter = retryAfterSeconds(refusal, Instant.now());
		List<String[]> headers = retryAfter == null ? null
				: Collections.singletonList(new String[] { RETRY_AFTER, String.valueOf(retryAfter) });
		return WebUtility.getResponse(body, refusal.isUnavailable() ? SERVICE_UNAVAILABLE : TOO_MANY_REQUESTS, headers);
	}

	/**
	 * Works out the {@code Retry-After} seconds of a call a usage limit refused,
	 * from when the refusing rule resets.
	 *
	 * @param refusal a usage limit refusal
	 * @param now     the current time
	 * @return the whole seconds until a minute or hour rule resets, at least 1;
	 *         {@code null} for a longer rule or an unknown reset time
	 */
	static Long retryAfterSeconds(ModelUsageLimitException refusal, Instant now) {
		String resetsAt = refusal.getResetsAt();
		if (!refusal.isRateLimit() || resetsAt == null) {
			return null;
		}
		try {
			long millis = Duration.between(now, Instant.parse(resetsAt)).toMillis();
			return Math.max(1L, (millis + 999L) / 1000L);
		} catch (DateTimeParseException e) {
			classLogger.warn("Usage limit refusal has a reset time that is not ISO 8601: {}", resetsAt);
			return null;
		}
	}

	/**
	 * Private constructor - this class exposes only static helpers and must not be
	 * instantiated.
	 */
	private ModelPixelExecutor() {
	}
}
