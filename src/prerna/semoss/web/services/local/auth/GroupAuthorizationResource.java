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

import java.util.List;
import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.utils.SecurityGroupManagerUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

/**
 * Custom groups for their managers. A manager lists the groups they manage,
 * sees the projects and engines each can use, and adds or removes their members
 * and managers; admins can do the same for every custom group here, and
 * everything else under {@code /auth/admin/group}. The owner of a project or
 * engine can see who manages a custom group before they give it access.
 * {@link SecurityGroupManagerUtils} checks who may do what; anonymous sessions
 * can do nothing here.
 */
@Path("/auth/group")
@PermitAll
public class GroupAuthorizationResource {

	@GET
	@Path("/getGroups")
	@Produces("application/json")
	public Response getGroups(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups they manage",
				user -> SecurityGroupManagerUtils.getManagedGroups(user, term, limit, offset));
	}

	@GET
	@Path("/getNumGroups")
	@Produces("application/json")
	public Response getNumGroups(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the groups they manage",
				user -> SecurityGroupManagerUtils.getNumManagedGroups(user, term));
	}

	@GET
	@Path("/getGroupDetails")
	@Produces("application/json")
	public Response getGroupDetails(@Context HttpServletRequest request, @QueryParam("groupId") String groupId) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respond(request, "read the details of group " + group,
				user -> SecurityGroupManagerUtils.getGroupDetails(user, group));
	}

	/**
	 * The managers of a custom group. Its managers and admins can always see them;
	 * the owner of a project or engine passes its id to see them before giving the
	 * group access to it.
	 */
	@GET
	@Path("/getGroupManagers")
	@Produces("application/json")
	public Response getGroupManagers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("projectId") String projectId, @QueryParam("engineId") String engineId) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String project = WebUtility.inputSQLSanitizer(projectId);
		String engine = WebUtility.inputSQLSanitizer(engineId);
		return ResourceUtility.respond(request, "list the managers of group " + group,
				user -> SecurityGroupManagerUtils.getGroupManagers(user, group, project, engine));
	}

	@GET
	@Path("/getGroupMembers")
	@Produces("application/json")
	public Response getGroupMembers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the members of group " + group,
				user -> SecurityGroupManagerUtils.getGroupMembers(user, group, term, limit, offset));
	}

	@GET
	@Path("/getNumMembersInGroup")
	@Produces("application/json")
	public Response getNumMembersInGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the members of group " + group,
				user -> SecurityGroupManagerUtils.getNumMembersInGroup(user, group, term));
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
		return ResourceUtility.respond(request, "search for people to add to group " + group, user -> {
			if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
				return SecurityGroupManagerUtils.getNonGroupMembers(user, group, term, limit, offset);
			}
			// search the directory, leaving out everyone already in the group
			List<Map<String, Object>> members = SecurityGroupManagerUtils.getAllGroupMembers(user, group);
			return MsGraphUtility.getGroupUsers(request, user, group, members, term,
					SecurityGroupManagerUtils.clampPageSize(limit), offset);
		});
	}

	/**
	 * The projects a custom group has access to, so its managers know what the
	 * people they add can use. Only the projects' owners and admins change it.
	 */
	@GET
	@Path("/getProjectsForGroup")
	@Produces("application/json")
	public Response getProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset, @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the projects of group " + group,
				user -> SecurityGroupManagerUtils.getProjectsForGroup(user, group, term, limit, offset, onlyApps));
	}

	@GET
	@Path("/getNumProjectsForGroup")
	@Produces("application/json")
	public Response getNumProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the projects of group " + group,
				user -> SecurityGroupManagerUtils.getNumProjectsForGroup(user, group, term, onlyApps));
	}

	/**
	 * The engines a custom group has access to, so its managers know what the
	 * people they add can use. Only the engines' owners and admins change it.
	 */
	@GET
	@Path("/getEnginesForGroup")
	@Produces("application/json")
	public Response getEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the engines of group " + group,
				user -> SecurityGroupManagerUtils.getEnginesForGroup(user, group, term, limit, offset));
	}

	@GET
	@Path("/getNumEnginesForGroup")
	@Produces("application/json")
	public Response getNumEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the engines of group " + group,
				user -> SecurityGroupManagerUtils.getNumEnginesForGroup(user, group, term));
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
		return ResourceUtility.respond(request, "add user " + userId + " to group " + groupId, user -> {
			SecurityGroupManagerUtils.addUserToGroup(user, groupId, userId, userLoginType, endDate);
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
		return ResourceUtility.respond(request, "remove user " + userId + " from group " + groupId, user -> {
			SecurityGroupManagerUtils.removeUserFromGroup(user, groupId, userId, userLoginType);
			return true;
		});
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
		return ResourceUtility.respond(request, "make user " + userId + " a manager of group " + groupId, user -> {
			SecurityGroupManagerUtils.addGroupManager(user, groupId, userId, userLoginType);
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
		return ResourceUtility.respond(request, "remove user " + userId + " as a manager of group " + groupId, user -> {
			SecurityGroupManagerUtils.removeGroupManager(user, groupId, userId, userLoginType);
			return true;
		});
	}
}
