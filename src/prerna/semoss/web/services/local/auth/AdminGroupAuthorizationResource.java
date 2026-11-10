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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.AuthProvider;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityGroupManagerUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupAddEnginePermissionRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupAddGroupRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupAvailableEngineRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupAvailableProjectRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupDeleteGroupRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupEditGroupDetailsRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupEnginePermissionRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupGetGroupsRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupProjectPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupRemoveEnginePermissionRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AdminGroupRemoveProjectPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupAddMemberRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupDetails;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupEngineRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupNonMemberRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupPersonRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupPersonRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupProjectRow;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

/**
 * Groups for admins: creating, editing and deleting them, their members and
 * managers, and the projects and engines they can use. Each endpoint runs
 * through {@link ResourceUtility#respondAsAdmin} with
 * {@link AdminSecurityGroupUtils#getInstance}, which is the one admin check:
 * anyone who is not an admin is answered with 401 before the endpoint does
 * anything, and the endpoint gets the admin group utilities.
 */
@Path("/auth/admin/group")
@PermitAll
public class AdminGroupAuthorizationResource {

	private static final String GROUP_ID_REQUIRED = "Must define the group id";
	private static final String GROUP_ID_PARAM_REQUIRED = "The group id ('groupId') cannot be null or empty";
	private static final String USER_ID_PARAM_REQUIRED = "The user id ('userId') cannot be null or empty";
	private static final String USER_TYPE_PARAM_REQUIRED = "The user login type ('type') cannot be null or empty";
	/** The form field for a team limit on a model, as JSON */
	private static final String USAGE_LIMIT = "usageLimit";

	///////////////////////////////////////////////////////////////

	/*
	 * Groups
	 */

	@Operation(operationId = "getGroups", summary = "List the groups", description = "Admins only. Lists every group, custom and login provider groups, sorted by type and id, one page at a time. Custom groups carry their member count.")
	@ApiResponse(responseCode = "200", description = "The groups", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminGroupGetGroupsRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroups")
	@Produces("application/json")
	public Response getGroups(@Context HttpServletRequest request, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the groups", AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> adminGroupUtils.getGroups(term, limit, offset));
	}

	@Operation(operationId = "getGroupDetails", summary = "Read a group", description = "Admins only. Returns the details of one group.")
	@ApiResponse(responseCode = "200", description = "The group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupDetails.class)))
	@ApiResponse(responseCode = "400", description = "The group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupDetails")
	@Produces("application/json")
	public Response getGroupDetails(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("type") String type) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(type));
		return ResourceUtility.respondAsAdmin(request, "read the details of group " + group,
				AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> adminGroupUtils.getGroupDetails(group, groupType));
	}

	@Operation(operationId = "getNumGroups", summary = "Count the groups", description = "Admins only. Counts the groups matching the search, for paging getGroups.")
	@ApiResponse(responseCode = "200", description = "The number of groups", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of groups")))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumGroups")
	@Produces("application/json")
	public Response getNumGroups(@Context HttpServletRequest request, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the groups", AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> {
					Long numGroups = adminGroupUtils.getNumGroups(term);
					return numGroups == null ? Long.valueOf(0) : numGroups;
				});
	}

	@Operation(operationId = "addGroup", summary = "Add a group", description = "Admins only. Adds a custom group, or a group from a login provider.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupAddGroupRequest.class)))
	@ApiResponse(responseCode = "200", description = "The group was added", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The group id or type is missing, or the group already exists", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/addGroup")
	public Response addGroup(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String description = WebUtility.inputSanitizer(request.getParameter("description"));
		return ResourceUtility.respondAsAdmin(request, "add group " + groupId, AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, "The group id cannot be null or empty");
					String type = ValueUtils.requireNonBlank(groupType, "The group type cannot be null");
					adminGroupUtils.addGroup(user, id, type, description == null ? "" : description.trim());
					return true;
				});
	}

	@Operation(operationId = "deleteGroup", summary = "Delete a group", description = "Admins only. Deletes a group and everything it was given.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupDeleteGroupRequest.class)))
	@ApiResponse(responseCode = "200", description = "The group was deleted", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/deleteGroup")
	public Response deleteGroup(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		return ResourceUtility.respondAsAdmin(request, "delete group " + groupId, AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> {
					adminGroupUtils.deleteGroupAndPropagate(
							ValueUtils.requireNonBlank(groupId, "The group id cannot be null or empty"), groupType);
					return true;
				});
	}

	@Operation(operationId = "editGroupDetails", summary = "Edit a group", description = "Admins only. Renames a group or changes its description, carrying its access over to the new id.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupEditGroupDetailsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The group was changed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, or the group cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/editGroupDetails")
	public Response editGroupDetails(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String newGroupId = WebUtility.inputSQLSanitizer(request.getParameter("newGroupId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String newDescription = WebUtility.inputSanitizer(request.getParameter("newDescription"));
		return ResourceUtility.respondAsAdmin(request, "edit group " + groupId, AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, "The group id cannot be null or empty");
					String newId = ValueUtils.requireNonBlank(newGroupId, "The new group id cannot be null or empty");
					String type = ValueUtils.requireNonBlank(groupType, "The group type cannot be null");
					adminGroupUtils.editGroupDetailsAndPropagate(user, id, type, newId, newDescription);
					return true;
				});
	}

	/*
	 * Group Members For Custom Groups
	 */

	@Operation(operationId = "getGroupMembers", summary = "List the members of a custom group", description = "Admins only. Lists a custom group's members, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The members", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupPersonRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist or is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupMembers")
	@Produces("application/json")
	public Response getGroupMembers(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the members of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getGroupMembers(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), term, limit, offset));
	}

	@Operation(operationId = "getNumMembersInGroup", summary = "Count the members of a custom group", description = "Admins only. Counts the members matching the search, for paging getGroupMembers.")
	@ApiResponse(responseCode = "200", description = "The number of members", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of members")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist or is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumMembersInGroup")
	@Produces("application/json")
	public Response getNumMembersInGroup(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the members of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getNumMembersInGroup(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), term));
	}

	@Operation(operationId = "getNonGroupMembers", summary = "Search for people to add to a custom group", description = "Admins only. Lists users who are not members of the group yet, from the security database or, when it is available and msGraphLookup is not false, from the Microsoft directory.")
	@ApiResponse(responseCode = "200", description = "The people", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupNonMemberRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist or is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNonGroupMembers")
	@Produces("application/json")
	public Response getNonGroupMembers(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset,
			@Parameter(description = "true to search the Microsoft directory, false for the security database; leave out to use the directory whenever it is available") @QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "search for people to add to group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED);
					if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
						return adminGroupUtils.getNonGroupMembers(id, term, limit, offset);
					}
					// search the directory, leaving out everyone already in the group
					return MsGraphUtility.getGroupUsers(request, user, id,
							adminGroupUtils.getGroupMembers(id, null, -1, -1), term, limit, offset);
				});
	}

	/**
	 * Adds a member to a custom group. A person picked from the Microsoft directory
	 * who is not in the security database yet is added to it, with the details the
	 * directory holds for them.
	 */
	@Operation(operationId = "addGroupMember", summary = "Add a member to a custom group", description = "Admins only. Adds a person to a custom group. A person picked from the Microsoft directory who is not in the security database yet is added to it, with the details the directory holds for them.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupAddMemberRequest.class)))
	@ApiResponse(responseCode = "200", description = "The member was added", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the end date cannot be read, the group is not a custom group, or the person is already a member", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/addGroupMember")
	public Response addGroupMember(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
		String userLoginType = WebUtility.inputSQLSanitizer(request.getParameter("type"));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request, "add user " + userId + " to group " + groupId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					SecurityGroupManagerUtils.addUserToGroup(adminGroupUtils, user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userId, USER_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userLoginType, USER_TYPE_PARAM_REQUIRED), endDate);
					return true;
				});
	}

	@Operation(operationId = "deleteGroupMember", summary = "Remove a member from a custom group", description = "Admins only. Takes a person out of a custom group.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The member was removed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person is not a member", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/deleteGroupMember")
	public Response deleteGroupMember(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
		String userLoginType = WebUtility.inputSQLSanitizer(request.getParameter("type"));
		return ResourceUtility.respondAsAdmin(request, "remove user " + userId + " from group " + groupId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					SecurityGroupManagerUtils.removeUserFromGroup(adminGroupUtils,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userId, USER_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userLoginType, USER_TYPE_PARAM_REQUIRED));
					return true;
				});
	}

	/*
	 * Group Managers For Custom Groups
	 */

	@Operation(operationId = "getGroupManagers", summary = "List the managers of a custom group", description = "Admins only. Lists a custom group's managers, sorted by name.")
	@ApiResponse(responseCode = "200", description = "The managers", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupPersonRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist or is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupManagers")
	@Produces("application/json")
	public Response getGroupManagers(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respondAsAdmin(request, "list the managers of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> SecurityGroupManagerUtils
						.getGroupManagers(adminGroupUtils, ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED)));
	}

	/**
	 * Makes someone a manager of a custom group. A person picked from the Microsoft
	 * directory who is not in the security database yet is added to it, with the
	 * details the directory holds for them.
	 */
	@Operation(operationId = "addGroupManager", summary = "Make someone a manager of a custom group", description = "Admins only. Makes a person a manager of a custom group. A person picked from the Microsoft directory who is not in the security database yet is added to it, with the details the directory holds for them.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The manager was added", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person already manages it", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/addGroupManager")
	public Response addGroupManager(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
		String userLoginType = WebUtility.inputSQLSanitizer(request.getParameter("type"));
		return ResourceUtility.respondAsAdmin(request, "make user " + userId + " a manager of group " + groupId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					SecurityGroupManagerUtils.addGroupManager(adminGroupUtils, user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userId, USER_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userLoginType, USER_TYPE_PARAM_REQUIRED));
					return true;
				});
	}

	@Operation(operationId = "removeGroupManager", summary = "Remove a manager of a custom group", description = "Admins only. Stops a person managing a custom group.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The manager was removed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person does not manage it", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/removeGroupManager")
	public Response removeGroupManager(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
		String userLoginType = WebUtility.inputSQLSanitizer(request.getParameter("type"));
		return ResourceUtility.respondAsAdmin(request, "remove user " + userId + " as a manager of group " + groupId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					SecurityGroupManagerUtils.removeGroupManager(adminGroupUtils,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userId, USER_ID_PARAM_REQUIRED),
							ValueUtils.requireNonBlank(userLoginType, USER_TYPE_PARAM_REQUIRED));
					return true;
				});
	}

	///////////////////////////////////////////////////////////////

	/*
	 * Group Project Permissions
	 */

	@Operation(operationId = "addGroupProjectPermission", summary = "Give a group access to a project", description = "Admins only. Gives a group access to a project at a permission level.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing or the permission is not a whole number, or the group already has access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/addGroupProjectPermission")
	public Response addGroupProjectPermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String projectId = WebUtility.inputSQLSanitizer(request.getParameter("projectId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request, "give group " + groupId + " access to project " + projectId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String project = ValueUtils.requireNonBlank(projectId,
							"The project id ('projectId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.addGroupProjectPermission(user, id, groupType, project, level, endDate);
					return true;
				});
	}

	@Operation(operationId = "editGroupProjectPermission", summary = "Change a group's access to a project", description = "Admins only. Changes the permission level or end date of a group's access to a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was changed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing or the permission is not a whole number, or the group has no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/editGroupProjectPermission")
	public Response editGroupProjectPermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String projectId = WebUtility.inputSQLSanitizer(request.getParameter("projectId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request,
				"change the access group " + groupId + " has to project " + projectId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String project = ValueUtils.requireNonBlank(projectId,
							"The project id ('projectId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.editGroupProjectPermission(user, id, groupType, project, level, endDate);
					return true;
				});
	}

	@Operation(operationId = "removeGroupProjectPermission", summary = "Take away a group's access to a project", description = "Admins only. Takes away a group's access to a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupRemoveProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was taken away", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, or the group has no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/removeGroupProjectPermission")
	public Response removeGroupProjectPermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String projectId = WebUtility.inputSQLSanitizer(request.getParameter("projectId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		return ResourceUtility.respondAsAdmin(request,
				"take away the access group " + groupId + " has to project " + projectId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String project = ValueUtils.requireNonBlank(projectId,
							"The project id ('projectId') cannot be null or empty");
					adminGroupUtils.removeGroupProjectPermission(user, id, groupType, project);
					return true;
				});
	}

	@Operation(operationId = "getProjectsForGroup", summary = "List the projects a group has access to", description = "Admins only. Lists the projects the group holds a grant on, with the grant, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupProjectRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getProjectsForGroup")
	@Produces("application/json")
	public Response getProjectsForGroup(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many rows to skip") @QueryParam("offset") long offset,
			@Parameter(description = "True to keep only the projects with a portal") @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility
				.respondAsAdmin(request, "list the projects of group " + group, AdminSecurityGroupUtils::getInstance,
						(user, adminGroupUtils) -> adminGroupUtils.getProjectsForGroup(
								ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset,
								onlyApps));
	}

	@Operation(operationId = "getNumProjectsForGroup", summary = "Count the projects a group has access to", description = "Admins only. Counts the projects matching the search, for paging getProjectsForGroup.")
	@ApiResponse(responseCode = "200", description = "The number of projects", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of projects")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumProjectsForGroup")
	@Produces("application/json")
	public Response getNumProjectsForGroup(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "True to keep only the projects with a portal") @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the projects of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils.getNumProjectsForGroup(
						ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, onlyApps));
	}

	@Operation(operationId = "getAvailableProjectsForGroup", summary = "List the projects a group can be given", description = "Admins only. Lists the projects the group has no access to yet, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminGroupAvailableProjectRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getAvailableProjectsForGroup")
	@Produces("application/json")
	public Response getAvailableProjectsForGroup(@Context HttpServletRequest request,
			@Parameter(description = "The group id") @QueryParam("groupId") String groupId, @Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset, @Parameter(description = "True to keep only the projects with a portal") @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility
				.respondAsAdmin(request, "list the projects group " + group + " can be given",
						AdminSecurityGroupUtils::getInstance,
						(user, adminGroupUtils) -> adminGroupUtils.getAvailableProjectsForGroup(
								ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset,
								onlyApps));
	}

	///////////////////////////////////////////////////////////////

	/*
	 * Group Engine Permissions
	 */

	/**
	 * Gives a group access to an engine. On a model, {@code usageLimit} (JSON,
	 * empty for no limit defined) is the team limit, with shared and each-member
	 * rules.
	 */
	@Operation(operationId = "addGroupEnginePermission", summary = "Give a group access to an engine", description = "Admins only. Gives a group access to an engine at a permission level. On a model, usageLimit sets the team limit, with shared and each-member rules.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupAddEnginePermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the permission is not a whole number, the usage limit cannot be read, or the group already has access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/addGroupEnginePermission")
	public Response addGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		boolean usageLimitSent = request.getParameterMap().containsKey(USAGE_LIMIT);
		String usageLimit = request.getParameter(USAGE_LIMIT);
		return ResourceUtility.respondAsAdmin(request, "give group " + groupId + " access to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					int level = requirePermission(permission);
					if (usageLimitSent) {
						adminGroupUtils.addGroupEnginePermission(user, id, groupType, engine, level, endDate,
								SecurityModelUsageLimitUtils.readJson(usageLimit));
					} else {
						adminGroupUtils.addGroupEnginePermission(user, id, groupType, engine, level, endDate);
					}
					return true;
				});
	}

	@Operation(operationId = "editGroupEnginePermission", summary = "Change a group's access to an engine", description = "Admins only. Changes the permission level or end date of a group's access to an engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupEnginePermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was changed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing or the permission is not a whole number, or the group has no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/editGroupEnginePermission")
	public Response editGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request,
				"change the access group " + groupId + " has to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.editGroupEnginePermission(user, id, groupType, engine, level, endDate);
					return true;
				});
	}

	@Operation(operationId = "removeGroupEnginePermission", summary = "Take away a group's access to an engine", description = "Admins only. Takes away a group's access to an engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AdminGroupRemoveEnginePermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was taken away", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, or the group has no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@POST
	@Produces("application/json")
	@Path("/removeGroupEnginePermission")
	public Response removeGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		return ResourceUtility.respondAsAdmin(request,
				"take away the access group " + groupId + " has to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					adminGroupUtils.removeGroupEnginePermission(user, id, groupType, engine);
					return true;
				});
	}

	@Operation(operationId = "getEnginesForGroup", summary = "List the engines a group has access to", description = "Admins only. Lists the engines the group holds a grant on, with the grant, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupEngineRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getEnginesForGroup")
	@Produces("application/json")
	public Response getEnginesForGroup(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the engines of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils.getEnginesForGroup(
						ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset));
	}

	@Operation(operationId = "getNumEnginesForGroup", summary = "Count the engines a group has access to", description = "Admins only. Counts the engines matching the search, for paging getEnginesForGroup.")
	@ApiResponse(responseCode = "200", description = "The number of engines", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of engines")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumEnginesForGroup")
	@Produces("application/json")
	public Response getNumEnginesForGroup(@Context HttpServletRequest request, @Parameter(description = "The group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the engines of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getNumEnginesForGroup(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term));
	}

	@Operation(operationId = "getAvailableEnginesForGroup", summary = "List the engines a group can be given", description = "Admins only. Lists the engines the group has no access to yet, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminGroupAvailableEngineRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group does not exist", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getAvailableEnginesForGroup")
	@Produces("application/json")
	public Response getAvailableEnginesForGroup(@Context HttpServletRequest request,
			@Parameter(description = "The group id") @QueryParam("groupId") String groupId, @Parameter(description = "The group type, CUSTOM or a login provider") @QueryParam("groupType") String groupType,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the engines group " + group + " can be given",
				AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> adminGroupUtils.getAvailableEnginesForGroup(
						ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset));
	}

	///////////////////////////////////////////////////////////////

	/**
	 * Reads the required {@code permission} parameter: 1 owner, 2 editor or 3 read
	 * only.
	 *
	 * @param permission the sanitized parameter
	 * @throws IllegalArgumentException when it is missing or not a whole number
	 */
	private static int requirePermission(String permission) {
		String value = ValueUtils.requireNonBlank(permission,
				"The permission integer value ('permission') cannot be null or empty");
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Must pass a valid integer value. Received value = " + value);
		}
	}
}
