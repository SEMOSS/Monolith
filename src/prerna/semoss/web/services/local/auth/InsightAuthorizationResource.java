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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import prerna.auth.AccessPermissionEnum;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/insight")
@PermitAll
public class InsightAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(InsightAuthorizationResource.class);

	/**
	 * Get the insights of user
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsights")
	public Response getInsights(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") String limit,
			@QueryParam("offset") String offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		offset = WebUtility.inputSanitizer(offset);
		limit = WebUtility.inputSanitizer(limit);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> projectFilter = null;
		if (projectId != null && !(projectId = projectId.trim()).isEmpty()) {
			projectFilter = new ArrayList<>();
			projectFilter.add(projectId);
		}

		List<Map<String, Object>> ret = SecurityInsightUtils.searchUserInsights(user, projectFilter, searchTerm, false,
				null, null, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the insights the user can edit in the project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectInsights")
	public Response getProjectInsights(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("searchTerm") String searchTerm) {

		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = SecurityInsightUtils.getUserEditableInsights(user, projectId);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserInsightPermission")
	public Response getUserInsightPermission(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId) {

		projectId = WebUtility.inputSanitizer(projectId);
		insightId = WebUtility.inputSanitizer(insightId);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String permission = SecurityInsightUtils.getActualUserInsightPermission(user, projectId, insightId);
		if (permission == null) {
			classLogger.warn(
					"User is trying to pull permission details for insight {} in project {} without having proper access",
					insightId, projectId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have access to this insight");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, String> ret = new HashMap<String, String>();
		ret.put("permission", permission);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsers")
	public Response getInsightUsers(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("insightId") String insightId, @QueryParam("userId") String userId,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		userId = WebUtility.inputSQLSanitizer(userId);
		insightId = WebUtility.inputSanitizer(insightId);
		permission = WebUtility.inputSanitizer(permission);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			List<Map<String, Object>> members = SecurityInsightUtils.getInsightUsers(user, projectId, insightId, userId,
					permission, limit, offset);
			long totalMembers = SecurityInsightUtils.getInsightUsersCount(user, projectId, insightId, userId,
					permission);
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to pull permission details for insight {} in project {} without having proper access",
					insightId, projectId);
			classLogger.error("Failed to retrieve insight users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add a user to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermission")
	public Response addInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		// add the person with read only access if they do not have access to the app
		if (SecurityProjectUtils.getUserProjectPermission(newUserId, projectId) == null) {
			try {
				SecurityProjectUtils.addProjectUser(user, newUserId, projectId,
						AccessPermissionEnum.READ_ONLY.getPermission(), endDate);
			} catch (IllegalAccessException e) {
				classLogger.warn(
						"User is trying to add user {} to insight {} in project {} without having proper access",
						newUserId, insightId, projectId);
				classLogger.error("Failed to add insight user permission.", e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			} catch (Exception e) {
				classLogger.error("Failed to add insight user permission.", e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		try {
			SecurityInsightUtils.addInsightUser(user, newUserId, projectId, insightId, permission, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to add insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user {} to insight {} in project {} with permission {}", newUserId, insightId,
				projectId, permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermission")
	public Response editInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			SecurityInsightUtils.editInsightUserPermission(user, existingUserId, projectId, insightId, newPermission,
					endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to edit user {} permissions for insight {} in project {} without having proper access",
					existingUserId, insightId, projectId);
			classLogger.error("Failed to update insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user {} permission to insight {} in project {} with level {}", existingUserId,
				insightId, projectId, newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermissions")
	public Response editInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to edit user permissions for insight {} but is not an admin", insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityInsightUtils.editInsightUserPermissions(user, projectId, insightId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user permissions for insight {} without having proper access",
					insightId);
			classLogger.error("Failed to update insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permission to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermission")
	public Response removeInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		try {
			SecurityInsightUtils.removeInsightUser(user, existingUserId, projectId, insightId);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to remove user {} from having access to insight {} in project {} without having proper access",
					existingUserId, insightId, projectId);
			classLogger.error("Failed to remove insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed user {} from having access to insight {} in project {}", existingUserId,
				insightId, projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the insight global
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightGlobal")
	public Response setInsightGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("isPublic"));
		if (isPublic && AbstractSecurityUtils.adminOnlyInsightSetPublic()) {
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				classLogger.warn("User is trying to set the insight {} in project {}  public is not an admin",
						insightId, projectId);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only an admin can set an insight as public");
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		String logPublic = isPublic ? " public " : " private";

		try {
			SecurityInsightUtils.setInsightGlobalWithinProject(user, projectId, insightId, isPublic);

			/*
			 * BELOW COMMENTED OUT IS INVALID LOGIC WE DO NOT WANT TO MAKE IT HIDDEN IN
			 * INSIGHTS DB THAT WILL RESULT IN IT NOT BEING LOADED TO SECURITY AT ALL
			 */

			// also update in the app itself
			// so it is properly synchronized with the security db
//			ClusterUtil.reactorPullInsightsDB(appId);
//			IEngine app = Utility.getEngine(appId);
//			InsightAdministrator admin = new InsightAdministrator(app.getInsightDatabase());
//			admin.updateInsightGlobal(insightId, !isPublic);
//			ClusterUtil.reactorPushInsightDB(appId);

		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight {} in project {}{} without having proper access",
					insightId, projectId, logPublic);
			classLogger.error("Failed to update insight global.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		} catch (Exception e) {
			classLogger.error("Failed to update insight global.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has set the insight {} in project {} {}", insightId, projectId, logPublic.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the insight as favorited by the user
	 * 
	 * @param request
	 * @param form
	 * @param appId
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightFavorite")
	public Response setInsightFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form,
			String appId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityInsightUtils.setInsightFavorite(user, projectId, insightId, isFavorite);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight {} in project {}{} without having proper access",
					insightId, projectId, logFavorited);
			classLogger.error("Failed to update insight favorite.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		} catch (Exception e) {
			classLogger.error("Failed to update insight favorite.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has set the insight {} in project {} {}", insightId, appId, logFavorited.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the users with no access to a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsersNoCredentials")
	public Response getInsightUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		insightId = WebUtility.inputSanitizer(insightId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  user does not have access to provided insight");
			classLogger.error("Failed to retrieve insight users no credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = null;
		try {
			ret = SecurityInsightUtils.getInsightUsersNoCredentials(user, projectId, insightId, searchTerm, limit,
					offset);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User  is trying to pull users without access to insight {} in project {} without having proper access",
					insightId, projectId);
			classLogger.error("Failed to retrieve insight users no credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermissions")
	public Response addInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add user permissions to insight {} but is not an admin", insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions in bulk
		List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityInsightUtils.addInsightUserPermissions(user, projectId, insightId, permission, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to add insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user permissions to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permissions for insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermissions")
	public Response removeInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> ids = new Gson().fromJson(form.getFirst("ids"), List.class);
		ids = WebUtility.inputSanitizer(ids);
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to remove users from having access to insight {} but is not an admin",
					insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityInsightUtils.removeInsightUsers(user, ids, projectId, insightId);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to remove users from having access to insight {} without having proper access",
					insightId);
			classLogger.error("Failed to remove insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveInsightUserAccessRequest")
	public Response approveInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to approve user access to insight {} but is not an admin", insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			SecurityInsightUtils.approveInsightUserAccessRequests(user, projectId, insightId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant user access to insight {} without having proper access",
					insightId);
			classLogger.error("Failed to approve insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to approve insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access and added user permissions to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyInsightUserAccessRequest")
	public Response denyInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to deny user access to insight {} but is not an admin", insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
		requestIds = WebUtility.inputSanitizer(requestIds);
		try {
			SecurityInsightUtils.denyInsightUserAccessRequests(user, projectId, insightId, requestIds);
		} catch (Exception e) {
			classLogger.error("Failed to deny insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
}
