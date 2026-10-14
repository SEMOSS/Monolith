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
import prerna.auth.utils.SecurityGroupInsightsUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/insight")
@PermitAll
public class GroupInsightAuthorizationResource {

	private static final String GROUP_ID_REQUIRED = "The group id cannot be null or empty";
	private static final String GROUP_TYPE_REQUIRED = "The group type cannot be null or empty";
	private static final String PROJECT_ID_REQUIRED = "The projectId cannot be null or empty";
	private static final String INSIGHT_ID_REQUIRED = "The insightId cannot be null or empty";
	private static final String PERMISSION_REQUIRED = "The permission cannot be null or empty";

	@Context
	protected ServletContext context;

	/**
	 * Get the group insight permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupInsightPermission")
	public Response getGroupInsightPermission(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("type") String type,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(type));
		String insight = WebUtility.inputSQLSanitizer(insightId);
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respondAllowingAnonymous(request,
				"read the permission of group " + group + " on insight " + insight + " of project " + project, user -> {
					Integer permissionCode = SecurityGroupInsightsUtils.getGroupInsightPermission(
							ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(project, PROJECT_ID_REQUIRED),
							ValueUtils.requireNonBlank(insight, INSIGHT_ID_REQUIRED));
					String permission = permissionCode == null ? null
							: AccessPermissionEnum.getPermissionValueById(permissionCode);

					Map<String, String> ret = new HashMap<>();
					ret.put("permission", permission);
					return ret;
				});
	}

	/**
	 * Add a group to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupInsightPermission")
	public Response addGroupInsightPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSQLSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request,
				"add group " + groupId + " to insight " + insightId + " of project " + projectId, user -> {
					SecurityGroupInsightsUtils.addInsightGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED),
							ValueUtils.requireNonBlank(insightId, INSIGHT_ID_REQUIRED),
							ValueUtils.requireNonBlank(permission, PERMISSION_REQUIRED), endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Edit group permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupInsightPermission")
	public Response editGroupInsightPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSQLSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request,
				"edit the permission of group " + groupId + " on insight " + insightId + " of project " + projectId,
				user -> {
					SecurityGroupInsightsUtils.editInsightGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED),
							ValueUtils.requireNonBlank(insightId, INSIGHT_ID_REQUIRED),
							ValueUtils.requireNonBlank(newPermission, PERMISSION_REQUIRED), endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Remove group permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupInsightPermission")
	public Response removeGroupInsightPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(form.getFirst("type")));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		return ResourceUtility.respond(request,
				"remove group " + groupId + " from insight " + insightId + " of project " + projectId, user -> {
					SecurityGroupInsightsUtils.removeInsightGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(projectId, PROJECT_ID_REQUIRED),
							ValueUtils.requireNonBlank(insightId, INSIGHT_ID_REQUIRED));

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * The groups that have access to an insight, and how many there are, for anyone
	 * who can view the insight
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupsWithAccessToInsight")
	public Response getGroupsWithAccessToInsight(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String insight = WebUtility.inputSQLSanitizer(insightId);
		return ResourceUtility.respond(request, "list the groups with access to insight " + insight, user -> {
			Map<String, Object> ret = new HashMap<>();
			ret.put("groups",
					SecurityGroupInsightsUtils.getGroupsWithAccessToInsight(user, project, insight, limit, offset));
			ret.put("totalGroups", SecurityGroupInsightsUtils.getNumGroupsWithAccessToInsight(user, project, insight));
			return ret;
		});
	}

	/**
	 * Groups that do not have access to an insight yet, for its owners to choose
	 * from
	 */
	@GET
	@Produces("application/json")
	@Path("getAvailableGroupsForInsight")
	public Response getAvailableGroupsForInsight(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String insight = WebUtility.inputSQLSanitizer(insightId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups that can be given access to insight " + insight,
				user -> SecurityGroupInsightsUtils.getAvailableGroupsForInsight(user, project, insight, term, limit,
						offset));
	}

}
