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

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.ServletContext;
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
import prerna.auth.AuthProvider;
import prerna.auth.utils.SecurityGroupProjectUtils;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupProjectGrantRequest;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupProjectRemoveRequest;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAccessPage;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAvailableRow;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetPermission;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/project")
@PermitAll
public class GroupProjectAuthorizationResource {

	private static final String GROUP_ID_REQUIRED = "The group id cannot be null or empty";
	private static final String GROUP_TYPE_REQUIRED = "The group type cannot be null or empty";
	private static final String PROJECT_ID_REQUIRED = "The projectId cannot be null or empty";
	private static final String PERMISSION_REQUIRED = "The permission cannot be null or empty";

	@Context
	protected ServletContext context;

	/**
	 * Get the group project permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupProjectPermission")
	@Operation(operationId = "getGroupProjectPermission", summary = "Read a group's permission on a project", description = "Returns the permission level a group has on a project, with no permission when it has none.")
	@ApiResponse(responseCode = "200", description = "The group's permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetPermission.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getGroupProjectPermission(@Context HttpServletRequest request,
			@Parameter(description = "The group id", required = true) @QueryParam("groupId") String groupId, @Parameter(description = "The group type, the login provider the group comes from", required = true) @QueryParam("type") String type,
			@Parameter(description = "The project id", required = true) @QueryParam("projectId") String projectId) {
		String project = WebUtility.inputSanitizer(projectId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(type));
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respond(request, "read the permission of group " + group + " on project " + project,
				user -> {
					Integer permissionCode = SecurityGroupProjectUtils.getGroupProjectPermission(
							ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(project, PROJECT_ID_REQUIRED));
					String permission = permissionCode == null ? null
							: AccessPermissionEnum.getPermissionValueById(permissionCode);

					Map<String, String> ret = new HashMap<>();
					ret.put("permission", permission);
					return ret;
				});
	}

	/**
	 * Add a group to a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupProjectPermission")
	@Operation(operationId = "addGroupProjectPermission", summary = "Give a group access to a project", description = "Gives a group a permission level on a project. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupProjectGrantRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "add group " + groupId + " to project " + projectId, user -> {
			SecurityGroupProjectUtils.addProjectGroupPermission(user,
					ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
					ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
					ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED),
					ValueUtils.requireNonBlank(permission, PERMISSION_REQUIRED), endDate);

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit group permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupProjectPermission")
	@Operation(operationId = "editGroupProjectPermission", summary = "Change a group's permission on a project", description = "Changes the permission level a group has on a project. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupProjectGrantRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit the permission of group " + groupId + " on project " + projectId,
				user -> {
					SecurityGroupProjectUtils.editProjectGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED),
							ValueUtils.requireNonBlank(newPermission, PERMISSION_REQUIRED), endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Remove group permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupProjectPermission")
	@Operation(operationId = "removeGroupProjectPermission", summary = "Take a group's access to a project away", description = "Removes a group's access to a project. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupProjectRemoveRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respond(request, "remove group " + groupId + " from project " + projectId, user -> {
			SecurityGroupProjectUtils.removeProjectGroupPermission(user,
					ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
					ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
					ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED));

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	@GET
	@Produces("application/json")
	@Path("getGroupsWithAccessToProject")
	@Operation(operationId = "getGroupsWithAccessToProject", summary = "List the groups with access to a project", description = "Returns a page of the groups that have access to a project, and how many there are in total. The user must be able to view it.")
	@ApiResponse(responseCode = "200", description = "The groups with access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetAccessPage.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAllGroupsWithAccessToProject(@Context HttpServletRequest request,
			@Parameter(description = "The project id", required = true) @QueryParam("projectId") String projectId, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		return ResourceUtility.respond(request, "list the groups with access to project " + project, user -> {
			if (project == null || project.isEmpty()) {
				throw new IllegalArgumentException("Must define the projectId");
			}
			List<Map<String, Object>> groups = SecurityGroupProjectUtils.getGroupsWithAccessToProject(user, project,
					limit, offset);
			long totalGroups = SecurityGroupProjectUtils.getNumGroupsWithAccessToProject(user, project);
			Map<String, Object> ret = new HashMap<>();
			ret.put("totalGroups", totalGroups);
			ret.put("groups", groups);
			return ret;
		});
	}

	/**
	 * Groups that do not have access to a project yet, for its owners to choose
	 * from
	 *
	 * @param request
	 * @param projectId
	 * @param searchTerm
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAvailableGroupsForProject")
	@Operation(operationId = "getAvailableGroupsForProject", summary = "List the groups that can be given access to a project", description = "Returns the groups that do not have access to a project yet, for its owners to choose from.")
	@ApiResponse(responseCode = "200", description = "The groups without access", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupTargetAvailableRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAvailableGroupsForProject(@Context HttpServletRequest request,
			@Parameter(description = "The project id", required = true) @QueryParam("projectId") String projectId, @Parameter(description = "Text to match against the group id") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups that can be given access to project " + project,
				user -> {
					if (project == null || project.isEmpty()) {
						throw new IllegalArgumentException("Must define the projectId");
					}
					return SecurityGroupProjectUtils.getAvailableGroupsForProject(user, project, term, limit, offset);
				});
	}

}
