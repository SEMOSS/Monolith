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
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.semoss.web.services.local.ResourceUtility;
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

	///////////////////////////////////////////////////////////////

	/*
	 * Groups
	 */

	@GET
	@Path("/getGroups")
	@Produces("application/json")
	public Response getGroups(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the groups", AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> adminGroupUtils.getGroups(term, limit, offset));
	}

	@GET
	@Path("/getGroupDetails")
	@Produces("application/json")
	public Response getGroupDetails(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("type") String type) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(type));
		return ResourceUtility.respondAsAdmin(request, "read the details of group " + group,
				AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> adminGroupUtils.getGroupDetails(group, groupType));
	}

	@GET
	@Path("/getNumGroups")
	@Produces("application/json")
	public Response getNumGroups(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the groups", AdminSecurityGroupUtils::getInstance,
				(user, adminGroupUtils) -> {
					Long numGroups = adminGroupUtils.getNumGroups(term);
					return numGroups == null ? Long.valueOf(0) : numGroups;
				});
	}

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

	@GET
	@Path("/getGroupMembers")
	@Produces("application/json")
	public Response getGroupMembers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the members of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getGroupMembers(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), term, limit, offset));
	}

	@GET
	@Path("/getNumMembersInGroup")
	@Produces("application/json")
	public Response getNumMembersInGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the members of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getNumMembersInGroup(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), term));
	}

	@GET
	@Path("/getNonGroupMembers")
	@Produces("application/json")
	public Response getNonGroupMembers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset,
			@QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
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

	@GET
	@Path("/getGroupManagers")
	@Produces("application/json")
	public Response getGroupManagers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId) {
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

	@POST
	@Produces("application/json")
	@Path("/editGroupProjectPermission")
	public Response editGroupProjectPermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String projectId = WebUtility.inputSQLSanitizer(request.getParameter("projectId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request, "change the access group " + groupId + " has to project " + projectId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String project = ValueUtils.requireNonBlank(projectId,
							"The project id ('projectId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.editGroupProjectPermission(user, id, groupType, project, level, endDate);
					return true;
				});
	}

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

	@GET
	@Path("/getProjectsForGroup")
	@Produces("application/json")
	public Response getProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset,
			@QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility
				.respondAsAdmin(request, "list the projects of group " + group, AdminSecurityGroupUtils::getInstance,
						(user, adminGroupUtils) -> adminGroupUtils.getProjectsForGroup(
								ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset,
								onlyApps));
	}

	@GET
	@Path("/getNumProjectsForGroup")
	@Produces("application/json")
	public Response getNumProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the projects of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils.getNumProjectsForGroup(
						ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, onlyApps));
	}

	@GET
	@Path("/getAvailableProjectsForGroup")
	@Produces("application/json")
	public Response getAvailableProjectsForGroup(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset, @QueryParam("onlyApps") boolean onlyApps) {
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

	@POST
	@Produces("application/json")
	@Path("/addGroupEnginePermission")
	public Response addGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request, "give group " + groupId + " access to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.addGroupEnginePermission(user, id, groupType, engine, level, endDate);
					return true;
				});
	}

	@POST
	@Produces("application/json")
	@Path("/editGroupEnginePermission")
	public Response editGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String permission = WebUtility.inputSQLSanitizer(request.getParameter("permission"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String endDate = WebUtility.inputSQLSanitizer(request.getParameter("endDate"));
		return ResourceUtility.respondAsAdmin(request, "change the access group " + groupId + " has to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					int level = requirePermission(permission);
					adminGroupUtils.editGroupEnginePermission(user, id, groupType, engine, level, endDate);
					return true;
				});
	}

	@POST
	@Produces("application/json")
	@Path("/removeGroupEnginePermission")
	public Response removeGroupEnginePermission(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String engineId = WebUtility.inputSQLSanitizer(request.getParameter("engineId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		return ResourceUtility.respondAsAdmin(request, "take away the access group " + groupId + " has to engine " + engineId,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> {
					String id = ValueUtils.requireNonBlank(groupId, GROUP_ID_PARAM_REQUIRED);
					String engine = ValueUtils.requireNonBlank(engineId,
							"The engine id ('engineId') cannot be null or empty");
					adminGroupUtils.removeGroupEnginePermission(user, id, groupType, engine);
					return true;
				});
	}

	@GET
	@Path("/getEnginesForGroup")
	@Produces("application/json")
	public Response getEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "list the engines of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils.getEnginesForGroup(
						ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term, limit, offset));
	}

	@GET
	@Path("/getNumEnginesForGroup")
	@Produces("application/json")
	public Response getNumEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(groupType));
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "count the engines of group " + group,
				AdminSecurityGroupUtils::getInstance, (user, adminGroupUtils) -> adminGroupUtils
						.getNumEnginesForGroup(ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED), type, term));
	}

	@GET
	@Path("/getAvailableEnginesForGroup")
	@Produces("application/json")
	public Response getAvailableEnginesForGroup(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
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
	 * Reads the required {@code permission} parameter: 1 owner, 2 editor or 3
	 * read only.
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
