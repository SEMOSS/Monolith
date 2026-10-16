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
import prerna.auth.AccessPermissionEnum;
import prerna.auth.AuthProvider;
import prerna.auth.utils.SecurityGroupProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
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
	public Response getGroupProjectPermission(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("type") String type,
			@QueryParam("projectId") String projectId) {
		String project = WebUtility.inputSanitizer(projectId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(type));
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respondAllowingAnonymous(request,
				"read the permission of group " + group + " on project " + project, user -> {
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
	public Response addGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "add group " + groupId + " to project " + projectId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_GRANTED", "PROJECT", projectId), user -> {
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
	public Response editGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit the permission of group " + groupId + " on project " + projectId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_MODIFIED", "PROJECT", projectId),
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
	public Response removeGroupProjectPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respond(request, "remove group " + groupId + " from project " + projectId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_REVOKED", "PROJECT", projectId), user -> {
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
	public Response getAllGroupsWithAccessToProject(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		return ResourceUtility.respondAllowingAnonymous(request, "list the groups with access to project " + project,
				user -> {
					if (project == null || project.isEmpty()) {
						throw new IllegalArgumentException("Must define the projectId");
					}
					List<Map<String, Object>> groups = SecurityGroupProjectUtils.getGroupsWithAccessToProject(user,
							project, limit, offset);
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
	public Response getAvailableGroupsForProject(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAllowingAnonymous(request,
				"list the groups that can be given access to project " + project, user -> {
					if (project == null || project.isEmpty()) {
						throw new IllegalArgumentException("Must define the projectId");
					}
					return SecurityGroupProjectUtils.getAvailableGroupsForProject(user, project, term, limit, offset);
				});
	}

}
