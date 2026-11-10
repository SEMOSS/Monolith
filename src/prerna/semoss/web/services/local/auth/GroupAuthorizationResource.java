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
import prerna.auth.utils.SecurityGroupManagerUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupAddMemberRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupDetails;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupEngineRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupNonMemberRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupPersonRequest;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupPersonRow;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResourceSchemas.AuthGroupProjectRow;
import prerna.semoss.web.services.local.auth.GroupAuthorizationResourceSchemas.AuthGroupGetGroupsRow;
import prerna.web.services.util.WebUtility;

/**
 * Custom groups for their managers. A manager lists the groups they manage,
 * sees the projects and engines each can use, and adds or removes their members
 * and managers. Being an admin counts for nothing here, since an admin can also
 * act as an ordinary user; admins manage every custom group under
 * {@code /auth/admin/group}. The owner of a project or engine can see who
 * manages a custom group before they give it access.
 * {@link SecurityGroupManagerUtils} checks who may do what.
 */
@Path("/auth/group")
@PermitAll
public class GroupAuthorizationResource {

	@Operation(operationId = "getGroups", summary = "List the custom groups the user manages", description = "Lists the custom groups the signed in user manages, sorted by id, one page at a time, with their member counts and when the user became their manager.")
	@ApiResponse(responseCode = "200", description = "The groups", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupGetGroupsRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroups")
	@Produces("application/json")
	public Response getGroups(@Context HttpServletRequest request, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size, at most the server maximum; 0 or less for that many") @QueryParam("limit") long limit, @Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups they manage",
				user -> SecurityGroupManagerUtils.getManagedGroups(user, term, limit, offset));
	}

	@Operation(operationId = "getNumGroups", summary = "Count the custom groups the user manages", description = "Counts the custom groups the signed in user manages that match the search, for paging getGroups.")
	@ApiResponse(responseCode = "200", description = "The number of groups", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of groups")))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumGroups")
	@Produces("application/json")
	public Response getNumGroups(@Context HttpServletRequest request, @Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the groups they manage",
				user -> SecurityGroupManagerUtils.getNumManagedGroups(user, term));
	}

	@Operation(operationId = "getGroupDetails", summary = "Read a custom group the user manages", description = "Returns the details of a custom group the signed in user manages.")
	@ApiResponse(responseCode = "200", description = "The group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupDetails.class)))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupDetails")
	@Produces("application/json")
	public Response getGroupDetails(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respond(request, "read the details of group " + group,
				user -> SecurityGroupManagerUtils.getGroupDetails(user, group));
	}

	/**
	 * The managers of a custom group. Its managers can always see them; the owner
	 * of a project or engine passes its id to see them before giving the group
	 * access to it.
	 */
	@Operation(operationId = "getGroupManagers", summary = "List the managers of a custom group", description = "Lists a custom group's managers, sorted by name. Its managers can always see them; the owner of a project or engine passes its id to see them before giving the group access to it.")
	@ApiResponse(responseCode = "200", description = "The managers", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupPersonRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupManagers")
	@Produces("application/json")
	public Response getGroupManagers(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "A project the user owns or edits, to see the group's people as its owner or editor") @QueryParam("projectId") String projectId, @Parameter(description = "An engine the user owns or edits, to see the group's people as its owner or editor") @QueryParam("engineId") String engineId) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String project = WebUtility.inputSQLSanitizer(projectId);
		String engine = WebUtility.inputSQLSanitizer(engineId);
		return ResourceUtility.respond(request, "list the managers of group " + group,
				user -> SecurityGroupManagerUtils.getGroupManagers(user, group, project, engine));
	}

	/**
	 * The members of a custom group. Its managers can always see them. The owners
	 * of a project or engine pass its id to see them, also before giving the group
	 * access; its editors do once the group can use it.
	 */
	@Operation(operationId = "getGroupMembers", summary = "List the members of a custom group", description = "Lists a custom group's members, sorted by name, one page at a time. Its managers can always see them; the owners of a project or engine pass its id to see them, also before giving the group access, and its editors once the group can use it.")
	@ApiResponse(responseCode = "200", description = "The members", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupPersonRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getGroupMembers")
	@Produces("application/json")
	public Response getGroupMembers(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "A project the user owns or edits, to see the group's people as its owner or editor") @QueryParam("projectId") String projectId, @Parameter(description = "An engine the user owns or edits, to see the group's people as its owner or editor") @QueryParam("engineId") String engineId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size, at most the server maximum; 0 or less for that many") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String project = WebUtility.inputSQLSanitizer(projectId);
		String engine = WebUtility.inputSQLSanitizer(engineId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the members of group " + group,
				user -> SecurityGroupManagerUtils.getGroupMembers(user, group, project, engine, term, limit, offset));
	}

	/**
	 * How many members a custom group has, for the same people who can list them.
	 */
	@Operation(operationId = "getNumMembersInGroup", summary = "Count the members of a custom group", description = "Counts the members matching the search, for the same people who can list them, for paging getGroupMembers.")
	@ApiResponse(responseCode = "200", description = "The number of members", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of members")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumMembersInGroup")
	@Produces("application/json")
	public Response getNumMembersInGroup(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "A project the user owns or edits, to see the group's people as its owner or editor") @QueryParam("projectId") String projectId, @Parameter(description = "An engine the user owns or edits, to see the group's people as its owner or editor") @QueryParam("engineId") String engineId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String project = WebUtility.inputSQLSanitizer(projectId);
		String engine = WebUtility.inputSQLSanitizer(engineId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the members of group " + group,
				user -> SecurityGroupManagerUtils.getNumMembersInGroup(user, group, project, engine, term));
	}

	@Operation(operationId = "getNonGroupMembers", summary = "Search for people to add to a custom group", description = "For the group's managers. Lists users who are not members of the group yet, from the security database or, when it is available and msGraphLookup is not false, from the Microsoft directory.")
	@ApiResponse(responseCode = "200", description = "The people", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupNonMemberRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNonGroupMembers")
	@Produces("application/json")
	public Response getNonGroupMembers(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size, at most the server maximum; 0 or less for that many") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset,
			@Parameter(description = "true to search the Microsoft directory, false for the security database; leave out to use the directory whenever it is available") @QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
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
	@Operation(operationId = "getProjectsForGroup", summary = "List the projects a custom group has access to", description = "For the group's managers, so they know what the people they add can use. Lists the projects the group holds a grant on, with the grant, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupProjectRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getProjectsForGroup")
	@Produces("application/json")
	public Response getProjectsForGroup(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size, at most the server maximum; 0 or less for that many") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset, @Parameter(description = "True to keep only the projects with a portal") @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the projects of group " + group,
				user -> SecurityGroupManagerUtils.getProjectsForGroup(user, group, term, limit, offset, onlyApps));
	}

	@Operation(operationId = "getNumProjectsForGroup", summary = "Count the projects a custom group has access to", description = "For the group's managers. Counts the projects matching the search, for paging getProjectsForGroup.")
	@ApiResponse(responseCode = "200", description = "The number of projects", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of projects")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumProjectsForGroup")
	@Produces("application/json")
	public Response getNumProjectsForGroup(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "True to keep only the projects with a portal") @QueryParam("onlyApps") boolean onlyApps) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "count the projects of group " + group,
				user -> SecurityGroupManagerUtils.getNumProjectsForGroup(user, group, term, onlyApps));
	}

	/**
	 * The engines a custom group has access to, so its managers know what the
	 * people they add can use. Only the engines' owners and admins change it.
	 */
	@Operation(operationId = "getEnginesForGroup", summary = "List the engines a custom group has access to", description = "For the group's managers, so they know what the people they add can use. Lists the engines the group holds a grant on, with the grant, sorted by name, one page at a time.")
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupEngineRow.class))))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getEnginesForGroup")
	@Produces("application/json")
	public Response getEnginesForGroup(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size, at most the server maximum; 0 or less for that many") @QueryParam("limit") long limit,
			@Parameter(description = "How many rows to skip") @QueryParam("offset") long offset) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the engines of group " + group,
				user -> SecurityGroupManagerUtils.getEnginesForGroup(user, group, term, limit, offset));
	}

	@Operation(operationId = "getNumEnginesForGroup", summary = "Count the engines a custom group has access to", description = "For the group's managers. Counts the engines matching the search, for paging getEnginesForGroup.")
	@ApiResponse(responseCode = "200", description = "The number of engines", content = @Content(mediaType = "application/json", schema = @Schema(types = "integer", format = "int64", description = "The number of engines")))
	@ApiResponse(responseCode = "400", description = "The group id is missing, or the group is not a custom group", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@GET
	@Path("/getNumEnginesForGroup")
	@Produces("application/json")
	public Response getNumEnginesForGroup(@Context HttpServletRequest request, @Parameter(description = "The custom group id") @QueryParam("groupId") String groupId,
			@Parameter(description = "Text to search for; leave out for everything") @QueryParam("searchTerm") String searchTerm) {
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
	@Operation(operationId = "addGroupMember", summary = "Add a member to a custom group", description = "For the group's managers. Adds a person to the group. A person picked from the Microsoft directory who is not in the security database yet is added to it, with the details the directory holds for them.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupAddMemberRequest.class)))
	@ApiResponse(responseCode = "200", description = "The member was added", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the end date cannot be read, the group is not a custom group, or the person is already a member", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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

	@Operation(operationId = "deleteGroupMember", summary = "Remove a member from a custom group", description = "For the group's managers. Takes a person out of the group.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The member was removed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person is not a member", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "addGroupManager", summary = "Make someone a manager of a custom group", description = "For the group's managers. Makes a person a manager of the group. A person picked from the Microsoft directory who is not in the security database yet is added to it, with the details the directory holds for them.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The manager was added", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person already manages it", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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

	@Operation(operationId = "removeGroupManager", summary = "Remove a manager of a custom group", description = "For the group's managers. Stops a person managing the group.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupPersonRequest.class)))
	@ApiResponse(responseCode = "200", description = "The manager was removed", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "A required input is missing, the group is not a custom group, or the person does not manage it", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user does not manage the group, or may not see what was asked", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; techErrorMessage carries its cause", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
