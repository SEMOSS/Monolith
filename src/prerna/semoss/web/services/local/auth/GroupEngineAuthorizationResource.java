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
import prerna.auth.utils.SecurityGroupEngineUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.GroupEngineAuthorizationResourceSchemas.AuthGroupEngineAddRequest;
import prerna.semoss.web.services.local.auth.GroupEngineAuthorizationResourceSchemas.AuthGroupEngineEditRequest;
import prerna.semoss.web.services.local.auth.GroupEngineAuthorizationResourceSchemas.AuthGroupEngineRemoveRequest;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAccessPage;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetAvailableRow;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResourceSchemas.AuthGroupTargetPermission;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/engine")
@PermitAll
public class GroupEngineAuthorizationResource {

	private static final String GROUP_ID_REQUIRED = "The group id cannot be null or empty";
	private static final String GROUP_TYPE_REQUIRED = "The group type cannot be null or empty";
	private static final String APP_ID_REQUIRED = "The appId cannot be null or empty";
	private static final String PERMISSION_REQUIRED = "The permission cannot be null or empty";
	/** The form field for a team limit on a model, as JSON */
	private static final String USAGE_LIMIT = "usageLimit";

	@Context
	protected ServletContext context;

	/**
	 * Get the group app permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupAppPermission")
	@Operation(operationId = "getGroupAppPermission", summary = "Read a group's permission on an engine", description = "Returns the permission level a group has on an engine, with no permission when it has none.")
	@ApiResponse(responseCode = "200", description = "The group's permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetPermission.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getGroupAppPermission(@Context HttpServletRequest request, @Parameter(description = "The group id", required = true) @QueryParam("groupId") String groupId,
			@Parameter(description = "The group type, the login provider the group comes from", required = true) @QueryParam("type") String type, @Parameter(description = "The engine id", required = true) @QueryParam("appId") String appId) {
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(type));
		String engine = WebUtility.inputSanitizer(appId);
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respond(request, "read the permission of group " + group + " on engine " + engine,
				user -> {
					Integer permissionCode = SecurityGroupEngineUtils.getGroupDatabasePermission(
							ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(engine, APP_ID_REQUIRED));
					String permission = permissionCode == null ? null
							: AccessPermissionEnum.getPermissionValueById(permissionCode);

					Map<String, String> ret = new HashMap<>();
					ret.put("permission", permission);
					return ret;
				});
	}

	/**
	 * Add a group to an app. On a model, {@code usageLimit} (JSON, empty for no
	 * limit defined) is the team limit, with shared and each-member rules.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupEnginePermission")
	@Operation(operationId = "addGroupEnginePermission", summary = "Give a group access to an engine", description = "Gives a group a permission level on an engine. On a model, usageLimit sets the team usage limit. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupEngineAddRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addGroupEnginePermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		boolean usageLimitSent = form.containsKey(USAGE_LIMIT);
		String usageLimit = form.getFirst(USAGE_LIMIT);
		return ResourceUtility.respond(request, "add group " + groupId + " to engine " + engineId, user -> {
			String group = ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED);
			String groupType = ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED);
			String engine = ValueUtils.requireNonBlank(engineId, "The engineId cannot be null or empty");
			String level = ValueUtils.requireNonBlank(permission, PERMISSION_REQUIRED);
			if (usageLimitSent) {
				SecurityGroupEngineUtils.addEngineGroupPermission(user, group, groupType, engine, level, endDate,
						SecurityModelUsageLimitUtils.readJson(usageLimit));
			} else {
				SecurityGroupEngineUtils.addEngineGroupPermission(user, group, groupType, engine, level, endDate);
			}

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit group permission for an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupAppPermission")
	@Operation(operationId = "editGroupAppPermission", summary = "Change a group's permission on an engine", description = "Changes the permission level a group has on an engine. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupEngineEditRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit the permission of group " + groupId + " on engine " + appId,
				user -> {
					SecurityGroupEngineUtils.editDatabaseGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(appId, APP_ID_REQUIRED),
							ValueUtils.requireNonBlank(newPermission, PERMISSION_REQUIRED), endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Remove group permission for an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupAppPermission")
	@Operation(operationId = "removeGroupAppPermission", summary = "Take a group's access to an engine away", description = "Removes a group's access to an engine. The user must be able to manage its access.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthGroupEngineRemoveRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		return ResourceUtility.respond(request, "remove group " + groupId + " from engine " + appId, user -> {
			SecurityGroupEngineUtils.removeDatabaseGroupPermission(user,
					ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
					ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
					ValueUtils.requireNonBlank(appId, APP_ID_REQUIRED));

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	@GET
	@Produces("application/json")
	@Path("getGroupsWithAccessToEngine")
	@Operation(operationId = "getGroupsWithAccessToEngine", summary = "List the groups with access to an engine", description = "Returns a page of the groups that have access to an engine, and how many there are in total. The user must be able to view it.")
	@ApiResponse(responseCode = "200", description = "The groups with access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthGroupTargetAccessPage.class)))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAllGroupsWithAccessToEngine(@Context HttpServletRequest request,
			@Parameter(description = "The engine id", required = true) @QueryParam("engineId") String engineId, @Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
		String engine = WebUtility.inputSanitizer(engineId);
		return ResourceUtility.respond(request, "list the groups with access to engine " + engine, user -> {
			if (engine == null || engine.isEmpty()) {
				throw new IllegalArgumentException("Must define the engineId");
			}
			List<Map<String, Object>> groups = SecurityGroupEngineUtils.getGroupsWithAccessToEngine(user, engine, limit,
					offset);
			long totalGroups = SecurityGroupEngineUtils.getNumGroupsWithAccessToEngine(user, engine);
			Map<String, Object> ret = new HashMap<>();
			ret.put("totalGroups", totalGroups);
			ret.put("groups", groups);
			return ret;
		});
	}

	/**
	 * Groups that do not have access to an engine yet, for its owners to choose
	 * from
	 *
	 * @param request
	 * @param engineId
	 * @param searchTerm
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAvailableGroupsForEngine")
	@Operation(operationId = "getAvailableGroupsForEngine", summary = "List the groups that can be given access to an engine", description = "Returns the groups that do not have access to an engine yet, for its owners to choose from.")
	@ApiResponse(responseCode = "200", description = "The groups without access", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthGroupTargetAvailableRow.class))))
	@ApiResponse(responseCode = "400", description = "A required input is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAvailableGroupsForEngine(@Context HttpServletRequest request,
			@Parameter(description = "The engine id", required = true) @QueryParam("engineId") String engineId, @Parameter(description = "Text to match against the group id") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The page size; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "How many groups to skip") @QueryParam("offset") long offset) {
		String engine = WebUtility.inputSanitizer(engineId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "list the groups that can be given access to engine " + engine,
				user -> {
					if (engine == null || engine.isEmpty()) {
						throw new IllegalArgumentException("Must define the engineId");
					}
					return SecurityGroupEngineUtils.getAvailableGroupsForEngine(user, engine, term, limit, offset);
				});
	}

}
