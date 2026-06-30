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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(AuthorizationResource.class);
	@Context
	protected ServletContext context;

	@GET
	@Produces("application/json")
	@Path("searchForUser")
	public Response searchForUser(@Context HttpServletRequest request, @QueryParam("engineId") String engineId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  invalid user session trying to access authorization resources");
			classLogger.error("Failed to search for user.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean graphApi = Boolean
				.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// if not graph api
		// then we will look at our security db
		if (!graphApi) {
			try {
				List<Map<String, Object>> ret = SecurityEngineUtils.getEngineUsersNoCredentials(user, engineId,
						searchTerm, limit, offset);
				return WebUtility.getResponse(ret, 200);
			} catch (IllegalAccessException e) {
				classLogger.warn(
						"User is trying to pull users for {} that do not have credentials without having proper access",
						engineId);
				classLogger.error("Failed to search for user.", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		HttpSession session = request.getSession(false);
		String sessionKey = "u_" + User.getSingleLogginName(user) + "_" + searchTerm;

		// Initialize or retrieve session data
		Map<String, Object> sessionData = (Map<String, Object>) session.getAttribute(sessionKey);
		// New search if:
		// 1. No session data exists (first time searching this term), OR
		// 2. Offset is 0 (user is restarting the search)
		if (sessionData == null || offset == 0) {
			// Clear any existing data and start fresh
			sessionData = new HashMap<>();
			session.setAttribute(sessionKey, sessionData);
		}

		String graphApiGroupId = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_groupId");
		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.fetchMsGraphUsers(user, searchTerm,
					graphApiGroupId, sessionData);
			return WebUtility.getResponse(filteredUsers, 200);
		} catch (Exception e) {
			classLogger.error("Failed to search for user.", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

}