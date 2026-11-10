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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.auth.AccessPermissionEnum;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightApproveRequestsRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightDenyRequestsRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightEditableInsightRow;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightGetInsightsRow;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightMembersPage;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightRemoveUserRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightRemoveUsersRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightSetFavoriteRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightSetGlobalRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightSetVisibilityRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserNoCredentialsRow;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserPermission;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserPermissionRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserPermissionsRequest;
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
	@Operation(operationId = "getInsights", summary = "Search the insights the user can see", description = "Returns the insights the user can see, optionally only those of one project or whose name matches a search term, a page at a time.")
	@ApiResponse(responseCode = "200", description = "The insights", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthInsightGetInsightsRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsights(@Context HttpServletRequest request, @Parameter(description = "Only the insights of this project") @QueryParam("projectId") String projectId,
			@Parameter(description = "Text to match against the insight name") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; empty or 0 for all") @QueryParam("limit") String limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") String offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String offsetValue = WebUtility.inputSanitizer(offset);
		String limitValue = WebUtility.inputSanitizer(limit);
		return ResourceUtility.respond(request, "search their insights", user -> {
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
	@Operation(operationId = "getProjectInsights", summary = "List the insights the user can edit in a project", description = "Returns the insights of a project the user can edit: all of them for a project owner, the ones they own or edit for a project editor, and none otherwise.")
	@ApiResponse(responseCode = "200", description = "The insights", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthInsightEditableInsightRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectInsights(@Context HttpServletRequest request, @Parameter(description = "The project id") @QueryParam("projectId") String projectId,
			@Parameter(description = "Not used") @QueryParam("searchTerm") String searchTerm) {
		String project = WebUtility.inputSanitizer(projectId);
		return ResourceUtility.respond(request, "list the insights they can edit in project " + project,
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
	@Operation(operationId = "getUserInsightPermission", summary = "Read the user's permission on an insight", description = "Returns the permission level the signed in user has on an insight; 403 when they have none.")
	@ApiResponse(responseCode = "200", description = "The user's permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthInsightUserPermission.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUserInsightPermission(@Context HttpServletRequest request,
			@Parameter(description = "The id of the project the insight belongs to") @QueryParam("projectId") String projectId, @Parameter(description = "The insight id") @QueryParam("insightId") String insightId) {
		String project = WebUtility.inputSanitizer(projectId);
		String insight = WebUtility.inputSanitizer(insightId);
		return ResourceUtility.respond(request,
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
	@Operation(operationId = "getInsightUsers", summary = "List the users of an insight", description = "Returns a page of the users who have access to an insight, optionally filtered by user id and permission, and how many match in total.")
	@ApiResponse(responseCode = "200", description = "The users", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthInsightMembersPage.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsightUsers(@Context HttpServletRequest request, @Parameter(description = "The id of the project the insight belongs to") @QueryParam("projectId") String projectId,
			@Parameter(description = "The insight id") @QueryParam("insightId") String insightId, @Parameter(description = "Text to match against the user id") @QueryParam("userId") String userId,
			@Parameter(description = "Only users with this permission level, such as OWNER, EDIT or READ_ONLY") @QueryParam("permission") String permission, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many users to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String memberId = WebUtility.inputSQLSanitizer(userId);
		String insight = WebUtility.inputSanitizer(insightId);
		String memberPermission = WebUtility.inputSanitizer(permission);
		return ResourceUtility.respond(request, "list the users of insight " + insight + " of project " + project,
				user -> {
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
	@Operation(operationId = "addInsightUserPermission", summary = "Give a user access to an insight", description = "Gives a user a permission level on an insight, and read only access to its project when they have none.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "editInsightUserPermission", summary = "Change a user's permission on an insight", description = "Changes the permission level a user has on an insight.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "editInsightUserPermissions", summary = "Change users' permissions on an insight", description = "Changes the permission levels of several users on an insight at once. Limited to admins when only admins may add insight access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "removeInsightUserPermission", summary = "Take a user's access to an insight away", description = "Removes a user's access to an insight.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightRemoveUserRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "setInsightGlobal", summary = "Make an insight public or private", description = "Sets whether an insight is public within its project. Only admins may make an insight public when the server limits that to admins.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The insight was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "setInsightFavorite", summary = "Mark an insight a favorite", description = "Marks or unmarks an insight as one of the user's favorites.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightSetFavoriteRequest.class)))
	@ApiResponse(responseCode = "200", description = "The favorite was saved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "setInsightVisibility", summary = "Show or hide an insight", description = "Shows or hides an insight in the user's own lists.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightSetVisibilityRequest.class)))
	@ApiResponse(responseCode = "200", description = "Always true", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "getInsightUsersNoCredentials", summary = "List the users without access to an insight", description = "Returns the users who do not have access to an insight yet, optionally matching a search term. The user must be able to view the insight.")
	@ApiResponse(responseCode = "200", description = "The users", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthInsightUserNoCredentialsRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsightUsersNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The id of the project the insight belongs to") @QueryParam("projectId") String projectId, @Parameter(description = "The insight id") @QueryParam("insightId") String insightId,
			@Parameter(description = "Text to match against the user's id, name, username or email") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many users to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String insight = WebUtility.inputSanitizer(insightId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request,
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
	@Operation(operationId = "addInsightUserPermissions", summary = "Give users access to an insight", description = "Gives several users permission levels on an insight at once. Limited to admins when only admins may add insight access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "removeInsightUserPermissions", summary = "Take users' access to an insight away", description = "Removes several users' access to an insight at once. Limited to admins when only admins may add insight access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightRemoveUsersRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "approveInsightUserAccessRequest", summary = "Approve requests for access to an insight", description = "Approves requests for access to an insight, giving each requester the level named. Limited to admins when only admins may add insight access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightApproveRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "denyInsightUserAccessRequest", summary = "Deny requests for access to an insight", description = "Denies requests for access to an insight. Limited to admins when only admins may add insight access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightDenyRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this, such as when only admins may change insight access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
