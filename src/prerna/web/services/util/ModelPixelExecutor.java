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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.util.Constants;
import prerna.util.Utility;

/**
 * Servlet-side concerns shared by the provider-compatible web endpoints (OpenAI
 * / Anthropic / Ollama): resolving the session user, applying the caller's
 * timezone and building the common error responses.
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
