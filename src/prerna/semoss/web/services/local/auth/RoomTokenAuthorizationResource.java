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
package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityRoomTokenUtils;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/roomtoken")
@PermitAll
public class RoomTokenAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(RoomTokenAuthorizationResource.class);

	@GET
	@Produces("application/json")
	@Path("getRoomTokenLimits")
	public Response getRoomTokenLimits(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			List<Map<String, Object>> limits = SecurityRoomTokenUtils.getAllRoomTokenLimits();
			return WebUtility.getResponse(limits, 200);
		} catch (Exception e) {
			classLogger.error("Failed to get room token limits", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	@Path("setRoomTokenLimit")
	public Response setRoomTokenLimit(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			String userId = WebUtility.inputSanitizer(form.getFirst("userId"));
			if (userId != null && userId.trim().isEmpty()) {
				userId = null;
			}

			long maxTokens = parseLong(form.getFirst("maxTokens"), -1);
			long maxInputTokens = parseLong(form.getFirst("maxInputTokens"), -1);
			long maxOutputTokens = parseLong(form.getFirst("maxOutputTokens"), -1);
			boolean isActive = parseBoolean(form.getFirst("isActive"), true);

			String createdBy = user.getAccessToken(user.getLogins().get(0)).getId();

			if (userId == null) {
				SecurityRoomTokenUtils.setDefaultRoomTokenLimit(maxTokens, maxInputTokens, maxOutputTokens, isActive, createdBy);
			} else {
				SecurityRoomTokenUtils.setUserRoomTokenLimit(userId, maxTokens, maxInputTokens, maxOutputTokens, isActive, createdBy);
			}

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			ret.put("userId", userId);
			ret.put("maxTokens", maxTokens);
			ret.put("maxInputTokens", maxInputTokens);
			ret.put("maxOutputTokens", maxOutputTokens);
			ret.put("isActive", isActive);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to set room token limit", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	@Path("removeRoomTokenLimit")
	public Response removeRoomTokenLimit(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			String userId = WebUtility.inputSanitizer(form.getFirst("userId"));
			if (userId == null || userId.trim().isEmpty()) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must provide a userId to remove");
				return WebUtility.getResponse(errorMap, 400);
			}

			SecurityRoomTokenUtils.removeUserRoomTokenLimit(userId);

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			ret.put("userId", userId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to remove room token limit", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@GET
	@Produces("application/json")
	@Path("getRoomTokenUsage")
	public Response getRoomTokenUsage(@Context HttpServletRequest request,
			@QueryParam("roomId") String roomId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		roomId = WebUtility.inputSanitizer(roomId);
		if (roomId == null || roomId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a roomId");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			Map<String, Object> userOverride = SecurityRoomTokenUtils.getRoomTokenLimitForUser(userId);
			Map<String, Object> defaultLimit = SecurityRoomTokenUtils.getDefaultRoomTokenLimit();
			Map<String, Object> effectiveLimit = SecurityRoomTokenUtils.getEffectiveRoomTokenLimit(userId);

			Map<String, Object> ret = new HashMap<>();

			Number combinedUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, null);
			Number inputUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, "INPUT");
			Number outputUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, "RESPONSE");

			ret.put("roomId", roomId);
			ret.put("appliesToAllRooms", true);
			ret.put("tokensUsed", combinedUsage != null ? combinedUsage.longValue() : 0);
			ret.put("inputTokensUsed", inputUsage != null ? inputUsage.longValue() : 0);
			ret.put("outputTokensUsed", outputUsage != null ? outputUsage.longValue() : 0);

			if (effectiveLimit != null) {
				ret.put("configured", true);
				ret.put("tokenLimit", effectiveLimit.get("maxTokens"));
				ret.put("inputTokenLimit", effectiveLimit.get("maxInputTokens"));
				ret.put("outputTokenLimit", effectiveLimit.get("maxOutputTokens"));
				ret.put("isActive", effectiveLimit.get("isActive"));
				if (userOverride != null && (userOverride.get("isActive") == null
						|| Boolean.TRUE.equals(userOverride.get("isActive")))) {
					ret.put("limitScope", "USER_OVERRIDE");
				} else if (defaultLimit != null) {
					ret.put("limitScope", "PLATFORM_DEFAULT");
				} else {
					ret.put("limitScope", "UNCONFIGURED");
				}
			} else {
				ret.put("configured", false);
				ret.put("tokenLimit", null);
				ret.put("inputTokenLimit", null);
				ret.put("outputTokenLimit", null);
				ret.put("limitScope", "UNCONFIGURED");
			}

			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to get room token usage", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	private long parseLong(String val, long defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		try {
			return Long.parseLong(val.trim());
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private boolean parseBoolean(String val, boolean defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		return Boolean.parseBoolean(val.trim());
	}
}
