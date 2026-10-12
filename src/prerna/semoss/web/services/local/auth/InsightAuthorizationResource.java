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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

@Path("/auth/insight")
@PermitAll
public class InsightAuthorizationResource {

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
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String offsetValue = WebUtility.inputSanitizer(offset);
		String limitValue = WebUtility.inputSanitizer(limit);
		return ResourceUtility.respondAllowingAnonymous(request, "search their insights", user -> {
			List<String> projectFilter = null;
			if (project != null && !project.trim().isEmpty()) {
				projectFilter = new ArrayList<>();
				projectFilter.add(project.trim());
			}
			return SecurityInsightUtils.searchUserInsights(user, projectFilter, term, false, null, null, limitValue,
					offsetValue);
		});
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
		String project = WebUtility.inputSanitizer(projectId);
		return ResourceUtility.respondAllowingAnonymous(request,
				"list the insights they can edit in project " + project,
				user -> SecurityInsightUtils.getUserEditableInsights(user, project));
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
		String project = WebUtility.inputSanitizer(projectId);
		String insight = WebUtility.inputSanitizer(insightId);
		return ResourceUtility.respondAllowingAnonymous(request,
				"read their permission on insight " + insight + " of project " + project, user -> {
					String permission = SecurityInsightUtils.getActualUserInsightPermission(user, project, insight);
					if (permission == null) {
						throw new IllegalAccessException("User does not have access to this insight");
					}

					Map<String, String> ret = new HashMap<>();
					ret.put("permission", permission);
					return ret;
				});
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
		String project = WebUtility.inputSanitizer(projectId);
		String memberId = WebUtility.inputSQLSanitizer(userId);
		String insight = WebUtility.inputSanitizer(insightId);
		String memberPermission = WebUtility.inputSanitizer(permission);
		return ResourceUtility.respondAllowingAnonymous(request,
				"list the users of insight " + insight + " of project " + project, user -> {
					List<Map<String, Object>> members = SecurityInsightUtils.getInsightUsers(user, project, insight,
							memberId, memberPermission, limit, offset);
					long totalMembers = SecurityInsightUtils.getInsightUsersCount(user, project, insight, memberId,
							memberPermission);
					Map<String, Object> ret = new HashMap<>();
					ret.put("totalMembers", totalMembers);
					ret.put("members", members);
					return ret;
				});
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
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request,
				"add user " + newUserId + " to insight " + insightId + " of project " + projectId, user -> {
					// add the person with read only access if they do not have access to the app
					if (SecurityProjectUtils.getUserProjectPermission(newUserId, projectId) == null) {
						SecurityProjectUtils.addProjectUser(user, newUserId, projectId,
								AccessPermissionEnum.READ_ONLY.getPermission(), endDate);
					}
					SecurityInsightUtils.addInsightUser(user, newUserId, projectId, insightId, permission, endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request, "edit the permission of user " + existingUserId + " on insight "
				+ insightId + " of project " + projectId, user -> {
					SecurityInsightUtils.editInsightUserPermission(user, existingUserId, projectId, insightId,
							newPermission, endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request,
				"edit the permissions of users on insight " + insightId + " of project " + projectId, user -> {
					if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("This functionality is limited to only admins");
					}

					List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					SecurityInsightUtils.editInsightUserPermissions(user, projectId, insightId, requests, endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		return ResourceUtility.respond(request,
				"remove user " + existingUserId + " from insight " + insightId + " of project " + projectId, user -> {
					SecurityInsightUtils.removeInsightUser(user, existingUserId, projectId, insightId);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("isPublic"));
		String visibility = isPublic ? "public" : "private";
		return ResourceUtility.respond(request,
				"make insight " + insightId + " of project " + projectId + " " + visibility, user -> {
					if (isPublic && AbstractSecurityUtils.adminOnlyInsightSetPublic()
							&& !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("Only an admin can set an insight as public");
					}

					SecurityInsightUtils.setInsightGlobalWithinProject(user, projectId, insightId, isPublic);

					/*
					 * BELOW COMMENTED OUT IS INVALID LOGIC WE DO NOT WANT TO MAKE IT HIDDEN IN
					 * INSIGHTS DB THAT WILL RESULT IN IT NOT BEING LOADED TO SECURITY AT ALL
					 */

					// also update in the app itself
					// so it is properly synchronized with the security db
//					ClusterUtil.reactorPullInsightsDB(appId);
//					IEngine app = Utility.getEngine(appId);
//					InsightAdministrator admin = new InsightAdministrator(app.getInsightDatabase());
//					admin.updateInsightGlobal(insightId, !isPublic);
//					ClusterUtil.reactorPushInsightDB(appId);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String favorite = isFavorite ? "favorite" : "unfavorite";
		return ResourceUtility.respond(request, favorite + " insight " + insightId + " of project " + projectId,
				user -> {
					SecurityInsightUtils.setInsightFavorite(user, projectId, insightId, isFavorite);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Set the insight visibility for the user to be seen
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightVisibility")
	public Response setInsightVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String action = (visible ? "show" : "hide") + " insight " + insightId + " of project " + projectId;
		return ResourceUtility.respond(request, action, user -> {
			SecurityInsightUtils.setInsightVisibility(user, projectId, insightId, visible);
			return true;
		});
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
		String project = WebUtility.inputSanitizer(projectId);
		String insight = WebUtility.inputSanitizer(insightId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAllowingAnonymous(request,
				"list the users without access to insight " + insight + " of project " + project,
				user -> SecurityInsightUtils.getInsightUsersNoCredentials(user, project, insight, term, limit, offset));
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request, "add users to insight " + insightId + " of project " + projectId,
				user -> {
					if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("This functionality is limited to only admins");
					}

					// adding user permissions in bulk
					List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					SecurityInsightUtils.addInsightUserPermissions(user, projectId, insightId, permission, endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		return ResourceUtility.respond(request, "remove users from insight " + insightId + " of project " + projectId,
				user -> {
					List<String> ids = new Gson().fromJson(form.getFirst("ids"), List.class);
					ids = WebUtility.inputSanitizer(ids);

					if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("This functionality is limited to only admins");
					}

					SecurityInsightUtils.removeInsightUsers(user, ids, projectId, insightId);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request,
				"approve requests for access to insight " + insightId + " of project " + projectId, user -> {
					if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("This functionality is limited to only admins");
					}

					// adding user permissions and updating user access requests in bulk
					List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
					SecurityInsightUtils.approveInsightUserAccessRequests(user, projectId, insightId, requests,
							endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
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
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		return ResourceUtility.respond(request,
				"deny requests for access to insight " + insightId + " of project " + projectId, user -> {
					if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
						throw new IllegalAccessException("This functionality is limited to only admins");
					}

					// updating user access requests in bulk
					List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
					requestIds = WebUtility.inputSanitizer(requestIds);
					SecurityInsightUtils.denyInsightUserAccessRequests(user, projectId, insightId, requestIds);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}
}
