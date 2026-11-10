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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.engine.impl.InsightAdministrator;
import prerna.project.api.IProject;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightDeleteRequest;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightDenyRequestsRequest;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightGetInsightsRow;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightGrantAllRequest;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightProjectInsightRow;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightProjectUserRequest;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResourceSchemas.AuthAdminInsightUserAccessRow;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightApproveRequestsRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightMembersPage;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightRemoveUserRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightRemoveUsersRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightSetGlobalRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserNoCredentialsRow;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserPermissionRequest;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResourceSchemas.AuthInsightUserPermissionsRequest;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/insight")
@PermitAll
public class AdminInsightAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminInsightAuthorizationResource.class);

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
	@Operation(operationId = "getInsights", summary = "Search all insights", description = "Returns every insight, optionally only those of one project or whose name matches a search term, a page at a time. Admins only.")
	@ApiResponse(responseCode = "200", description = "The insights", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminInsightGetInsightsRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsights(@Context HttpServletRequest request, @Parameter(description = "Only the insights of this project") @QueryParam("projectId") String projectId,
			@Parameter(description = "Text to match against the insight name") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the insights when not an admin");
			classLogger.error("Failed to retrieve insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> projectFilter = null;
		if (projectId != null && !(projectId = projectId.trim()).isEmpty()) {
			projectFilter = new ArrayList<>();
			projectFilter.add(projectId);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUserInsights(user, projectFilter, searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the insights for the project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectInsights")
	@Operation(operationId = "getProjectInsights", summary = "List the insights of a project", description = "Returns every insight of a project. Admins only.")
	@ApiResponse(responseCode = "200", description = "The insights", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminInsightProjectInsightRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectInsights(@Context HttpServletRequest request, @Parameter(description = "The project id") @QueryParam("projectId") String projectId,
			@Parameter(description = "Not used") @QueryParam("searchTerm") String searchTerm) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the insight for project {} when not an admin", projectId);
			classLogger.error("Failed to retrieve project insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getProjectInsights(projectId);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the project users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("getAllProjectInsightUsers")
	@Operation(operationId = "getAllProjectInsightUsers", summary = "List a user's access to a project's insights", description = "Returns every insight of a project with the permission one user has on it. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightProjectUserRequest.class)))
	@ApiResponse(responseCode = "200", description = "The insights", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminInsightUserAccessRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAllProjectInsightUsers(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the user insight access for project {} when not an admin",
					projectId);
			classLogger.error("Failed to retrieve all project insight users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserInsightAccess(projectId, userId), 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("deleteProjectInsights")
	@Operation(operationId = "deleteProjectInsights", summary = "Delete insights of a project", description = "Deletes insights from a project and from the security database. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightDeleteRequest.class)))
	@ApiResponse(responseCode = "200", description = "The insights were deleted", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, the user is not an admin, or the insights could not be deleted", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response deleteProjectInsights(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		Gson gson = new Gson();
		List<String> insightIds = gson.fromJson(form.getFirst("insightId"), List.class);
		insightIds = WebUtility.inputSQLSanitizer(insightIds);

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to delete insight from projectId {} when not an admin", projectId);
			classLogger.error("Failed to delete project insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.deleteProjectInsights(projectId, insightIds);
		} catch (Exception e) {
			classLogger.error("Failed to delete project insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param projectId
	 * @param insightId
	 * @param userId
	 * @param permission
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsers")
	@Operation(operationId = "getInsightUsers", summary = "List the users of an insight", description = "Returns a page of the users who have access to an insight, optionally filtered by user id and permission, and how many match in total. Admins only.")
	@ApiResponse(responseCode = "200", description = "The users", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthInsightMembersPage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsightUsers(@Context HttpServletRequest request, @Parameter(description = "The id of the project the insight belongs to") @QueryParam("projectId") String projectId,
			@Parameter(description = "The insight id") @QueryParam("insightId") String insightId, @Parameter(description = "Text to match against the user id") @QueryParam("userId") String userId,
			@Parameter(description = "Only users with this permission level, such as OWNER, EDIT or READ_ONLY") @QueryParam("permission") String permission, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many users to skip") @QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		userId = WebUtility.inputSQLSanitizer(userId);
		insightId = WebUtility.inputSQLSanitizer(insightId);
		permission = WebUtility.inputSQLSanitizer(permission);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to get all users who have access to insight {} in project {} when not an admin",
					insightId, projectId);
			classLogger.error("Failed to retrieve insight users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		List<Map<String, Object>> members = adminUtils.getInsightUsers(projectId, insightId, userId, permission, limit,
				offset);
		long totalMembers = adminUtils.getInsightUsersCount(projectId, insightId, userId, permission);
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);

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
	@Operation(operationId = "addInsightUserPermission", summary = "Give a user access to an insight", description = "Gives a user a permission level on an insight. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user {} to insight {} in project {} when not an admin", newUserId,
					insightId, projectId);
			classLogger.error("Failed to add insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addInsightUser(newUserId, projectId, insightId, permission, user, endDate);
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
	 * Give permission to user for all insights in a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/grantAllProjectInsights")
	@Operation(operationId = "grantAllProjectInsights", summary = "Give a user access to every insight of a project", description = "Gives a user one permission level on every insight of a project, changing the level on the ones they already have access to. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightGrantAllRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantAllProjectInsights(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull the projects that user {} has access to when not an admin",
					userId);
			classLogger.error("Failed to grant all project insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllProjectInsights(projectId, userId, permission, user);
		} catch (Exception e) {
			classLogger.error("Failed to grant all project insights.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted all projects to {} with permission {}", userId, permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Give permission to user for all insights in an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/grantNewUsersInsightAccess")
	@Operation(operationId = "grantNewUsersInsightAccess", summary = "Give everyone without access an insight", description = "Gives everyone who has no access to an insight one permission level on it. It is a one time grant, not a rule for people who join later. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantNewUsersInsightAccess(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant new users insight access when not an admin");
			classLogger.error("Failed to grant new users insight access.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersInsightAccess(projectId, insightId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to grant new users insight access.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted new users with permission {}", permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add all users to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(operationId = "addAllUsers", summary = "Give everyone access to an insight", description = "Gives everyone one permission level on an insight, changing the level of those who already have access. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add all users to insight {} in project {} when not an admin", insightId,
					projectId);
			classLogger.error("Failed to add all users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addAllInsightUsers(projectId, insightId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to add all users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to project {} with permission {}", projectId, permission);

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
	@Operation(operationId = "editInsightUserPermission", summary = "Change a user's permission on an insight", description = "Changes the permission level a user has on an insight. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to edit user {} permissions for insight {} in project {} when not an admin",
					existingUserId, insightId, projectId);
			classLogger.error("Failed to update insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.editInsightUserPermission(existingUserId, projectId, insightId, newPermission, user, endDate);
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
	 * Edit user permission for insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermissions")
	@Operation(operationId = "editInsightUserPermissions", summary = "Change users' permissions on an insight", description = "Changes the permission levels of several users on an insight at once. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		SecurityAdminUtils adminUtils = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update insight user permissions.", e);
			classLogger.warn("User is trying to edit user access permissions for insight {} when not an admin",
					insightId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			adminUtils.editInsightUserPermissions(projectId, insightId, requests, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to update insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user access permissions to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * update all user's permission level to new permission level for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateInsightUserPermissions")
	@Operation(operationId = "updateInsightUserPermissions", summary = "Change everyone with access to an insight to one level", description = "Changes every user who has access to an insight to one permission level. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response updateAppUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update app user permissions.", e);
			classLogger.warn("User is trying to edit user permissions for project {} when not an admin", projectId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.updateInsightUserPermissions(projectId, insightId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to update app user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to insight {} with level {}", insightId, newPermission);

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
	@Operation(operationId = "removeInsightUserPermission", summary = "Take a user's access to an insight away", description = "Removes a user's access to an insight. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightRemoveUserRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to remove user {} from having access to insight {} in project {} when not an admin",
					existingUserId, insightId, projectId);
			classLogger.error("Failed to remove insight user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.removeInsightUser(existingUserId, projectId, insightId);
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
	 * Remove user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightGlobal")
	@Operation(operationId = "setInsightGlobal", summary = "Make an insight public or private", description = "Sets whether an insight is public within its project, in the security database and the project's insights database. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The insight was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setInsightGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		boolean isGlobal = Boolean.parseBoolean(form.getFirst("isPublic"));
		String logPublic = isGlobal ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight {} in project {}{} when not an admin", insightId,
					projectId, logPublic);
			classLogger.error("Failed to update insight global.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setInsightGlobalWithinProject(projectId, insightId, isGlobal);
			IProject project = Utility.getProject(projectId);
			InsightAdministrator admin = new InsightAdministrator(project.getInsightDatabase());
			admin.updateInsightGlobal(insightId, isGlobal);
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
	 * Get the users with no access to a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsersNoCredentials")
	@Operation(operationId = "getInsightUsersNoCredentials", summary = "List the users without access to an insight", description = "Returns the users who do not have access to an insight. Admins only. The search term, limit and offset are read but not applied.")
	@ApiResponse(responseCode = "200", description = "The users", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthInsightUserNoCredentialsRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getInsightUsersNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The id of the project the insight belongs to") @QueryParam("projectId") String projectId, @Parameter(description = "The insight id") @QueryParam("insightId") String insightId,
			@Parameter(description = "Not used") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "Not used") @QueryParam("limit") long limit,
			@Parameter(description = "Not used") @QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		insightId = WebUtility.inputSQLSanitizer(insightId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to get all users who have access to insight {} in project {} when not an admin",
					insightId, projectId);
			classLogger.error("Failed to retrieve insight users no credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> ret = adminUtils.getInsightUsersNoCredentials(projectId, insightId, searchTerm, limit,
				offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermissions")
	@Operation(operationId = "addInsightUserPermissions", summary = "Give users access to an insight", description = "Gives several users permission levels on an insight at once. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user permission to insight {} when not an admin", insightId);
			classLogger.error("Failed to add insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		// adding user permissions in bulk
		List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			adminUtils.addInsightUserPermissions(projectId, insightId, permission, user, endDate);
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
	 * Remove user permissions for an insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermissions")
	@Operation(operationId = "removeInsightUserPermissions", summary = "Take users' access to an insight away", description = "Removes several users' access to an insight at once. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightRemoveUsersRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove usersfrom having access to insight {} when not an admin",
					insightId);
			classLogger.error("Failed to remove insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Gson gson = new Gson();
		List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);
		try {
			adminUtils.removeInsightUsers(ids, projectId, insightId);
		} catch (Exception e) {
			classLogger.error("Failed to remove insight user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Admin approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveInsightUserAccessRequest")
	@Operation(operationId = "approveInsightUserAccessRequest", summary = "Approve requests for access to an insight", description = "Approves requests for access to an insight, giving each requester the level named. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthInsightApproveRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response approveInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to approve user request for permission to insight {} when not an admin",
					insightId);
			classLogger.error("Failed to approve insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveInsightUserAccessRequests(userId, userType, projectId, insightId, requests, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to approve insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access requests and added user permissions to project {} insigth {}",
				projectId, insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Admin deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyInsightUserAccessRequest")
	@Operation(operationId = "denyInsightUserAccessRequest", summary = "Deny requests for access to an insight", description = "Denies requests for access to an insight. Admins only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminInsightDenyRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The change could not be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response denyInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to deny user request for permission to insight {} when not an admin",
					insightId);
			classLogger.error("Failed to deny insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), List.class);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyInsightUserAccessRequests(userId, userType, projectId, insightId, requestids);
		} catch (Exception e) {
			classLogger.error("Failed to deny insight user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to insight {}", insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

}
