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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.auth.AccessPermissionEnum;
import prerna.auth.AuthProvider;
import prerna.auth.utils.SecurityGroupInsightsUtils;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.GroupInsightAuthorizationResourceSchemas.AuthGroupInsightGrantRequest;
import prerna.semoss.web.services.local.auth.GroupInsightAuthorizationResourceSchemas.AuthGroupInsightRemoveRequest;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAccessPage;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAvailableRow;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetPermission;
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
	@Operation(operationId = "getGroupInsightPermission", summary = "Read a group's permission on an insight", description = "Returns the permission level a group has on an insight, with no permission when it has none.")
	@ApiResponse(responseCode = "200", description = "The group's permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetPermission.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getGroupInsightPermission(@Context HttpServletRequest request,
			@Parameter(description = "The group id", required = true) @QueryParam("groupId") String groupId, @Parameter(description = "The group type, the login provider the group comes from", required = true) @QueryParam("type") String type,
			@Parameter(description = "The id of the project the insight belongs to", required = true) @QueryParam("projectId") String projectId, @Parameter(description = "The insight id", required = true) @QueryParam("insightId") String insightId) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(type));
		String insight = WebUtility.inputSQLSanitizer(insightId);
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respond(request,
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
	@Operation(operationId = "addGroupInsightPermission", summary = "Give a group access to an insight", description = "Gives a group a permission level on an insight. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupInsightGrantRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "editGroupInsightPermission", summary = "Change a group's permission on an insight", description = "Changes the permission level a group has on an insight. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupInsightGrantRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "removeGroupInsightPermission", summary = "Take a group's access to an insight away", description = "Removes a group's access to an insight. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupInsightRemoveRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "getGroupsWithAccessToInsight", summary = "List the groups with access to an insight", description = "Returns a page of the groups that have access to an insight, and how many there are in total. The user must be able to view it.")
	@ApiResponse(responseCode = "200", description = "The groups with access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetAccessPage.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getGroupsWithAccessToInsight(@Context HttpServletRequest request,
			@Parameter(description = "The id of the project the insight belongs to", required = true) @QueryParam("projectId") String projectId, @Parameter(description = "The insight id", required = true) @QueryParam("insightId") String insightId,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
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
	@Operation(operationId = "getAvailableGroupsForInsight", summary = "List the groups that can be given access to an insight", description = "Returns the groups that do not have access to an insight yet, for its owners to choose from.")
	@ApiResponse(responseCode = "200", description = "The groups without access", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupTargetAvailableRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAvailableGroupsForInsight(@Context HttpServletRequest request,
			@Parameter(description = "The id of the project the insight belongs to", required = true) @QueryParam("projectId") String projectId, @Parameter(description = "The insight id", required = true) @QueryParam("insightId") String insightId,
			@Parameter(description = "Text to match against the group id") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String insight = WebUtility.inputSQLSanitizer(insightId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups that can be given access to insight " + insight,
				user -> SecurityGroupInsightsUtils.getAvailableGroupsForInsight(user, project, insight, term, limit,
						offset));
	}

}
