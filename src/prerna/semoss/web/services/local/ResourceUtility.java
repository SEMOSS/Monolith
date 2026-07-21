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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;
import prerna.web.services.util.WebUtility;

public class ResourceUtility {

	private static final Logger classLogger = LogManager.getLogger(ResourceUtility.class);

	protected static List<String> allowAccessWithoutUsers = new ArrayList<>();
	static {
		allowAccessWithoutUsers.add("config");
		allowAccessWithoutUsers.add("config/fetchCsrf");
	}

	public static List<String> allowAccessWithoutLogin = new ArrayList<>();
	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		allowAccessWithoutLogin.add("session/active");
		allowAccessWithoutLogin.add("session/cleanSession");
		allowAccessWithoutLogin.add("session/cancelCleanSession");
		allowAccessWithoutLogin.add("session/invalidateSession");

		allowAccessWithoutLogin.add("config");
		allowAccessWithoutLogin.add("config/fetchCsrf");
		allowAccessWithoutLogin.add("config/endpoints");
		allowAccessWithoutLogin.add("auth/logins");
		allowAccessWithoutLogin.add("auth/loginsAllowed");
		allowAccessWithoutLogin.add("auth/login");
		allowAccessWithoutLogin.add("auth/loginLDAP");
		allowAccessWithoutLogin.add("auth/changeADPassword");
		allowAccessWithoutLogin.add("auth/loginLinOTP");
		allowAccessWithoutLogin.add("auth/createUser");
		allowAccessWithoutLogin.add("auth/whoami");
		allowAccessWithoutLogin.add("auth/user/setupResetPassword");
		allowAccessWithoutLogin.add("auth/user/resetPassword");
		for (AuthProvider v : AuthProvider.values()) {
			allowAccessWithoutLogin.add("auth/userinfo/" + v.toString().toLowerCase());
			allowAccessWithoutLogin.add("auth/login/" + v.toString().toLowerCase());
		}
		// legacy ms login
		allowAccessWithoutLogin.add("auth/userinfo/ms");
		allowAccessWithoutLogin.add("auth/login/ms");
	}

	/**
	 * Get the user
	 * 
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static User getUser(@Context HttpServletRequest request) throws IllegalAccessException {
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		return user;
	}

	/**
	 * Parse the body of an incoming request that is either application/json or
	 * application/x-www-form-urlencoded into a single map of parameters.
	 *
	 * For JSON, nested types (objects, arrays, numbers, booleans) are preserved.
	 * For form-urlencoded, all values are returned as String (or List<String> if
	 * multiple values for the same key).
	 */
	public static Map<String, Object> parseRequestBody(HttpServletRequest request) throws IOException {
		Map<String, Object> params = new HashMap<>();
		String contentType = request.getContentType();
		if (contentType != null && contentType.toLowerCase().contains("application/json")) {
			StringBuilder jsonBuffer = new StringBuilder();
			try (BufferedReader reader = request.getReader()) {
				String line;
				while ((line = reader.readLine()) != null) {
					jsonBuffer.append(line);
				}
			}
			if (jsonBuffer.length() == 0) {
				return params;
			}
			Gson gson = new GsonBuilder().disableHtmlEscaping().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
					.create();
			Type mapType = new TypeToken<Map<String, Object>>() {
			}.getType();
			try {
				Map<String, Object> parsed = gson.fromJson(jsonBuffer.toString(), mapType);
				if (parsed != null) {
					params.putAll(parsed);
				}
			} catch (JsonSyntaxException e) {
				throw new IOException("Invalid JSON syntax in request body", e);
			}
		} else {
			Map<String, String[]> parameterMap = request.getParameterMap();
			for (Map.Entry<String, String[]> e : parameterMap.entrySet()) {
				String[] values = e.getValue();
				if (values == null || values.length == 0) {
					continue;
				}
				if (values.length == 1) {
					params.put(e.getKey(), values[0]);
				} else {
					params.put(e.getKey(), Arrays.asList(values));
				}
			}
		}
		return params;
	}

	/**
	 * Build a fresh insight, register it in the InsightStore, and execute the given
	 * pixel expression for the user attached to this request.
	 *
	 * Used by engine resource endpoints that wrap pixel reactor calls. Unlike
	 * {@link NameServer#runPixelJob}, this returns HTTP 400 when the resulting
	 * pixel run carries an {@link PixelOperationType#ERROR} or
	 * {@link PixelOperationType#INVALID_SYNTAX} op type, instead of the standard
	 * always-200 SEMOSS pixel response.
	 */
	public static Response runPixel(HttpServletRequest request, String expression) {
		User user;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		HttpSession session = request.getSession(false);
		String sessionId = session != null ? session.getId() : null;

		Insight insight = new Insight();
		InsightStore.getInstance().put(insight);
		String insightId = insight.getInsightId();
		if (sessionId != null) {
			InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		}
		insight.setUser(user);
		user.setZoneId(Utility.getApplicationZoneIdObj());

		if (!expression.endsWith(";")) {
			expression = expression + ";";
		}

		final PixelJobManager manager = PixelJobManager.getManager();
		final PixelJobRunner jobRunner = manager.makeJob(WebUtility.inputSanitizer(insightId), insight, sessionId,
				null);
		final String jobId = jobRunner.getJobId();
		jobRunner.addPixel(expression);
		jobRunner.run();
		PixelRunner pixelRunner = jobRunner.getRunner();

		int status = pixelRunHasError(pixelRunner) ? 400 : 200;

		// Cleanup runs as the afterWrite hook in PixelStreamUtility, so the job
		// and Insight stay alive until the response body has been fully written.
		final Insight finalInsight = insight;
		StreamingOutput stream = PixelStreamUtility.collectPixelData(pixelRunner, jobRunner,
				() -> cleanupAfterPixel(finalInsight, manager, jobId, jobRunner));

		if (stream == null) {
			classLogger.error("PixelStreamUtility returned a null streaming output for insight '{}' running '{}'",
					insightId, expression);
			cleanupAfterPixel(insight, manager, jobId, jobRunner);
			Map<String, Object> errorMap = new HashMap<>();
			errorMap.put("errorType", "unknown");
			errorMap.put(Constants.ERROR_MESSAGE, "Failed to build pixel response stream");
			return Response.status(500).entity(errorMap)
					.header("Cache-Control",
							"no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
					.header("Pragma", "no-cache").build();
		}

		return Response.status(status).entity(stream)
				.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
				.header("Pragma", "no-cache").build();
	}

	/**
	 * Release per-request pixel resources: mark the job complete, remove it from
	 * the {@link PixelJobManager}, and drop the {@link Insight} (closes any
	 * Python/R environments and removes it from the {@link InsightStore}). Both
	 * cleanup branches are guarded so a failure in one does not skip the other.
	 * <p>
	 * Package-private so streaming endpoints in this package (e.g.
	 * {@link ModelEngineResource#llmStreaming}) can share the same post-stream
	 * cleanup contract.
	 */
	static void cleanupAfterPixel(Insight insight, PixelJobManager manager, String jobId, PixelJobRunner jobRunner) {
		try {
			if (jobRunner != null) {
				jobRunner.setStatus(PixelJobStatus.COMPLETE);
			}
			if (manager != null && jobId != null) {
				manager.clearJob(jobId);
				manager.removeJob(jobId);
			}
		} catch (Exception e) {
			classLogger.error("Failed to clear pixel job '{}' during post-stream cleanup", jobId, e);
		}
		try {
			if (insight != null) {
				InsightUtility.dropInsight(insight);
			}
		} catch (Exception e) {
			classLogger.error("Failed to drop insight '{}' during post-stream cleanup",
					insight == null ? null : insight.getInsightId(), e);
		}
	}

	/**
	 * Returns true if any of the runner's pixel results carry an op type that
	 * indicates the pixel itself failed (a reactor threw, or the expression was not
	 * parseable). Used to map a SEMOSS pixel error onto an HTTP 400.
	 */
	private static boolean pixelRunHasError(PixelRunner pixelRunner) {
		if (pixelRunner == null) {
			return false;
		}
		List<NounMetadata> results = pixelRunner.getResults();
		if (results == null || results.isEmpty()) {
			return false;
		}
		for (NounMetadata result : results) {
			if (result == null || result.getOpType() == null) {
				continue;
			}
			if (result.getOpType().contains(PixelOperationType.ERROR)
					|| result.getOpType().contains(PixelOperationType.INVALID_SYNTAX)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean canAccessWithoutUsers(String fullUrl) {
		for (String ignore : allowAccessWithoutUsers) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean canAccessWithoutLogin(String fullUrl) {
		for (String ignore : allowAccessWithoutLogin) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean endsWithMatch(Collection<String> ignoreForFE, String fullUrl) {
		for (String ignore : ignoreForFE) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}
}
