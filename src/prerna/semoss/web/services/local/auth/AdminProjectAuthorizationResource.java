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

import com.google.gson.Gson;

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
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.auth.AccessToken;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.reactors.admin.AdminMyProjectsReactor;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.om.Insight;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectApproveUserAccessRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectGetProjectsRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectGetProjectsRow;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectGrantAllProjectsRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectUserNoAccessProjectRow;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectUserPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResourceSchemas.AuthAdminProjectUserProjectRow;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectDenyUserAccessRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectMembersPage;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectRemoveUserPermissionRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectRemoveUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetDiscoverableRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetGlobalRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetTemplateRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectUserCandidate;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/project")
@PermitAll
public class AdminProjectAuthorizationResource {

	@Context
	protected ServletContext context;

	/**
	 * Get the projects the user has access to
	 * 
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjects")
	@Operation(operationId = "getProjectsGet", summary = "List every project, as an admin", description = "Returns the projects on the server, with their metadata unless noMeta is true. Admins only.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminProjectGetProjectsRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectsGET(@Context HttpServletRequest request,
			@Parameter(description = "Only these project ids") @QueryParam("projectId") List<String> projectFilter,
			@Parameter(description = "Text to search the project ids and names for") @QueryParam("filterWord") String searchTerm,
			@Parameter(description = "The most projects to return") @QueryParam("limit") Integer limit,
			@Parameter(description = "How many projects to skip") @QueryParam("offset") Integer offset,
			@Parameter(description = "The metadata keys to add to each project") @QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@Parameter(description = "When true, no metadata is added to the projects") @QueryParam("noMeta") Boolean noMeta,
			@Parameter(description = "Whether to include user tracking information") @QueryParam("userT") Boolean includeUserTracking,
			@Parameter(description = "Only the projects this user id has a permission on, directly or through a group, with effectivePermissions read for this user") @QueryParam("permissionUser") String permissionUser,
			@Parameter(description = "Only the projects whose effective permission is one of these levels: 1 owner, 2 edit, 3 read only") @QueryParam("effectivePermissions") List<Integer> effectivePermissions,
			@Parameter(description = "JSON-encoded array of creators, each an object with the creator's login id and type, such as [{\"id\": \"abc\", \"type\": \"MICROSOFT\"}]; only projects one of them created are listed") @QueryParam("createdBy") String createdBy,
			@Parameter(description = "When true, only the projects the signed in admin created") @QueryParam("createdByMe") Boolean createdByMe) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> projects = WebUtility.inputSQLSanitizer(projectFilter);
		List<String> keys = WebUtility.inputSQLSanitizer(metaKeys);
		return ResourceUtility.respondAsAdmin(request, "list all projects", user -> {
			AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
			reactor.In();
			Insight temp = new Insight();
			temp.setUser(user);
			reactor.setInsight(temp);

			if (term != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(term, PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
			}
			if (limit != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(limit, PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
			}
			if (offset != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(offset, PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
			}
			if (projects != null && !projects.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String engine : projects) {
					struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
			}
			if (keys != null && !keys.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String metaK : keys) {
					struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
			}
//			if(metaFilters != null) {
//				GenRowStruct struct = new GenRowStruct();
//				struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
//				reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
//			}
			if (noMeta != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(noMeta, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
			}
			if (includeUserTracking != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(includeUserTracking, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
			}

			if (permissionUser != null && !permissionUser.trim().isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(permissionUser.trim(), PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun("permissionUser", struct);
			}
			if (effectivePermissions != null && !effectivePermissions.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (Integer permission : effectivePermissions) {
					struct.add(new NounMetadata(permission, PixelDataType.CONST_INT));
				}
				reactor.getNounStore().addNoun("effectivePermissions", struct);
			}
			if (createdBy != null && !createdBy.trim().isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (Object creator : new Gson().fromJson(createdBy, List.class)) {
					struct.add(new NounMetadata(creator, PixelDataType.MAP));
				}
				reactor.getNounStore().addNoun("createdBy", struct);
			}
			if (createdByMe != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(createdByMe, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun("createdByMe", struct);
			}

			NounMetadata outputNoun = reactor.execute();
			return outputNoun.getValue();
		});
	}

	@POST
	@Produces("application/json")
	@Path("getProjects")
	@Operation(operationId = "getProjects", summary = "List every project, as an admin", description = "Returns the projects on the server, as getProjectsGet does, with the filters sent as a form, which also takes metaFilters. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectGetProjectsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminProjectGetProjectsRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectsPOST(@Context HttpServletRequest request) {
		return ResourceUtility.respondAsAdmin(request, "list all projects", user -> {
			AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
			reactor.In();
			Insight temp = new Insight();
			temp.setUser(user);
			reactor.setInsight(temp);

			Map<String, String[]> parameterMap = request.getParameterMap();

			if (parameterMap.containsKey("filterWord") && parameterMap.get("filterWord") != null
					&& parameterMap.get("filterWord").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("filterWord")[0], PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
			}
			if (parameterMap.containsKey("limit") && parameterMap.get("limit") != null
					&& parameterMap.get("limit").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("limit")[0], PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
			}
			if (parameterMap.containsKey("offset") && parameterMap.get("offset") != null
					&& parameterMap.get("offset").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("offset")[0], PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
			}
			if (parameterMap.containsKey("projectId") && parameterMap.get("projectId") != null
					&& parameterMap.get("projectId").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				String[] projectFilter = parameterMap.get("projectId");
				for (String project : projectFilter) {
					struct.add(new NounMetadata(project, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
			}
			if (parameterMap.containsKey("metaKeys") && parameterMap.get("metaKeys") != null
					&& parameterMap.get("metaKeys").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				String[] metaKeys = parameterMap.get("metaKeys");
				for (String metaK : metaKeys) {
					struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
			}
			if (parameterMap.containsKey("metaFilters") && parameterMap.get("metaFilters") != null
					&& parameterMap.get("metaFilters").length > 0) {
				Map<String, Object> metaFilters = new Gson().fromJson(parameterMap.get("metaFilters")[0], Map.class);
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
			}
			if (parameterMap.containsKey("noMeta") && parameterMap.get("noMeta") != null
					&& parameterMap.get("noMeta").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("noMeta")[0], PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
			}
			if (parameterMap.containsKey("userT") && parameterMap.get("userT") != null
					&& parameterMap.get("userT").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("userT")[0], PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
			}

			if (parameterMap.containsKey("permissionUser") && parameterMap.get("permissionUser") != null
					&& parameterMap.get("permissionUser").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("permissionUser")[0].trim(), PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun("permissionUser", struct);
			}
			if (parameterMap.containsKey("effectivePermissions") && parameterMap.get("effectivePermissions") != null
					&& parameterMap.get("effectivePermissions").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				for (String permission : parameterMap.get("effectivePermissions")) {
					struct.add(new NounMetadata(Integer.valueOf(permission.trim()), PixelDataType.CONST_INT));
				}
				reactor.getNounStore().addNoun("effectivePermissions", struct);
			}
			if (parameterMap.containsKey("createdBy") && parameterMap.get("createdBy") != null
					&& parameterMap.get("createdBy").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				for (Object creator : new Gson().fromJson(parameterMap.get("createdBy")[0], List.class)) {
					struct.add(new NounMetadata(creator, PixelDataType.MAP));
				}
				reactor.getNounStore().addNoun("createdBy", struct);
			}
			if (parameterMap.containsKey("createdByMe") && parameterMap.get("createdByMe") != null
					&& parameterMap.get("createdByMe").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(Boolean.parseBoolean(parameterMap.get("createdByMe")[0].trim()),
						PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun("createdByMe", struct);
			}

			NounMetadata outputNoun = reactor.execute();
			return outputNoun.getValue();
		});
	}

	@GET
	@Path("/getAllUserProjects")
	@Produces("application/json")
	@Operation(operationId = "getAllUserProjects", summary = "List a user's projects, as an admin", description = "Returns the projects a user has a direct permission on. Admins only.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminProjectUserProjectRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAllUserProjects(@Context HttpServletRequest request,
			@Parameter(description = "The user id") @QueryParam("userId") String userId,
			@Parameter(description = "Only projects of these types, such as CODE or INSIGHTS") @QueryParam("projectTypes") List<String> projectTypes,
			@Parameter(description = "Text to search the project ids and names for") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most projects to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many projects to skip") @QueryParam("offset") long offset) {
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> types = WebUtility.inputSanitizer(projectTypes);
		return ResourceUtility.respondAsAdmin(request, "list the projects of user " + id,
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> adminUtils.getAllUserProjects(id, types, term, limit, offset));
	}

	@GET
	@Path("/getUserProjectsNoCredentials")
	@Produces("application/json")
	@Operation(operationId = "getUserProjectsNoCredentials", summary = "List the projects a user cannot access, as an admin", description = "Returns the projects a user has no direct permission on, to grant them new access. Admins only.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminProjectUserNoAccessProjectRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUserProjectsNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The user id") @QueryParam("userId") String userId,
			@Parameter(description = "Only projects of these types, such as CODE or INSIGHTS") @QueryParam("projectTypes") List<String> projectTypes,
			@Parameter(description = "Text to search the project ids and names for") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most projects to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many projects to skip") @QueryParam("offset") long offset) {
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> types = WebUtility.inputSanitizer(projectTypes);
		return ResourceUtility.respondAsAdmin(request, "list the projects user " + id + " does not have access to",
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> adminUtils.getUserProjectsNoCredentials(id, types, term, limit, offset));
	}

	@POST
	@Path("/grantAllProjects")
	@Produces("application/json")
	@Operation(operationId = "grantAllProjects", summary = "Give a user a permission on every project", description = "With isAddNew, gives a user the permission on every project they have no access to; otherwise changes the projects they can access to it. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectGrantAllProjectsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantAllProjects(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");
		return ResourceUtility.respondAsAdmin(request,
				"grant all projects to user " + userId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.grantAllProjects(userId, permission, isAddNew, user);
					return success();
				});
	}

	@POST
	@Path("/grantNewUsersProjectAccess")
	@Produces("application/json")
	@Operation(operationId = "grantNewUsersProjectAccess", summary = "Give new users access to a project", description = "Gives users who sign in for the first time a permission on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The access was set", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantNewUsersProjectAccess(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"grant project " + projectId + " to new users with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.grantNewUsersProjectAccess(projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Get the project users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsers")
	@Operation(operationId = "getProjectUsers", summary = "List the people with access to a project, as an admin", description = "Returns a page of the people with a permission on a project, and how many match in all. Admins only.")
	@ApiResponse(responseCode = "200", description = "The page of people", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthProjectMembersPage.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectUsers(@Context HttpServletRequest request,
			@Parameter(description = "The project id") @QueryParam("projectId") String projectId,
			@Parameter(description = "Text to search the people's ids, names, user names and emails for, used when searchTerm is not sent") @QueryParam("userId") String userId,
			@Parameter(description = "Text to search the people's ids, names, user names and emails for") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "Only people with this permission: OWNER, EDIT or READ_ONLY") @QueryParam("permission") String permission,
			@Parameter(description = "The most people to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "How many people to skip") @QueryParam("offset") long offset) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String level = WebUtility.inputSQLSanitizer(permission);
		return ResourceUtility.respondAsAdmin(request, "list the users of project " + project,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					String searchParam = term != null ? term : id;
					List<Map<String, Object>> members = adminUtils.getProjectUsers(project, searchParam, level, limit,
							offset);
					long totalMembers = adminUtils.getProjectUsersCount(project, searchParam, level);
					Map<String, Object> ret = new HashMap<String, Object>();
					ret.put("totalMembers", totalMembers);
					ret.put("members", members);
					return ret;
				});
	}

	/**
	 * Add a user to an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermission")
	@Operation(operationId = "addProjectUserPermission", summary = "Give a person access to a project, as an admin", description = "Gives a person a permission on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The person was given access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"add user " + newUserId + " to project " + projectId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.addProjectUser(newUserId, projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Add all users to an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(operationId = "addAllUsers", summary = "Give every user access to a project", description = "Gives every user a permission on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The users were given access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"add all users to project " + projectId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.addAllProjectUsers(projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Edit user permission for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermission")
	@Operation(operationId = "editProjectUserPermission", summary = "Change a person's permission on a project, as an admin", description = "Changes the permission a person has on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"change the permission of user " + existingUserId + " on project " + projectId + " to " + newPermission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.editProjectUserPermission(existingUserId, projectId, newPermission, user, endDate);
					return success();
				});
	}

	/**
	 * Edit user permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermissions")
	@Operation(operationId = "editProjectUserPermissions", summary = "Change people's permissions on a project, as an admin", description = "Changes the permissions several people have on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request, "edit the user permissions on project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					adminUtils.editProjectUserPermissions(projectId, requests, user, endDate);
					return success();
				});
	}

	/**
	 * update all user's permission level to new permission level for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateProjectUserPermissions")
	@Operation(operationId = "updateProjectUserPermissions", summary = "Change everyone's permission on a project", description = "Changes the permission of every person with access to a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response updateProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"change the permission of every user on project " + projectId + " to " + newPermission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.updateProjectUserPermissions(projectId, newPermission, user, endDate);
					return success();
				});
	}

	/**
	 * Remove user permission for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermission")
	@Operation(operationId = "removeProjectUserPermission", summary = "Remove a person from a project, as an admin", description = "Removes a person's permission on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectRemoveUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The person was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "remove user " + existingUserId + " from project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.removeProjectUser(existingUserId, projectId);
					return success();
				});
	}

	/**
	 * Set whether a project can be cloned as a template by users who can view it.
	 *
	 * @param request current request
	 * @param form    projectId and template boolean
	 * @return operation status
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectTemplate")
	@Operation(operationId = "setProjectTemplate", summary = "Set whether a project is a template, as an admin", description = "Sets whether users who can view a project may clone it as a template. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetTemplateRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectTemplate(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectIdInput = form.getFirst("projectId");
		String templateInput = form.getFirst("template");
		String projectId = WebUtility.inputSQLSanitizer(projectIdInput);
		boolean isTemplate = Boolean.parseBoolean(templateInput);
		return ResourceUtility.respondAsAdmin(request,
				"set the template status of project " + projectId + " to " + isTemplate,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					boolean validTemplate = "true".equalsIgnoreCase(templateInput)
							|| "false".equalsIgnoreCase(templateInput);
					if (projectIdInput == null || projectIdInput.trim().isEmpty() || !validTemplate) {
						throw new IllegalArgumentException("projectId and a true or false template value are required");
					}
					adminUtils.setProjectTemplate(projectId, isTemplate);
					return success();
				});
	}

	@POST
	@Produces("application/json")
	@Path("setProjectGlobal")
	@Operation(operationId = "setProjectGlobal", summary = "Make a project public or private, as an admin", description = "Sets whether a project is public. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		return ResourceUtility.respondAsAdmin(request,
				"make project " + projectId + (isPublic ? " public" : " private"), SecurityAdminUtils::getInstance,
				(user, adminUtils) -> {
					adminUtils.setProjectGlobal(projectId, isPublic);
					return success();
				});
	}

	/**
	 * Set the project as being discoverable for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectDiscoverable")
	@Operation(operationId = "setProjectDiscoverable", summary = "Set whether a project is discoverable, as an admin", description = "Sets whether users without access can discover a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetDiscoverableRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		return ResourceUtility.respondAsAdmin(request,
				"make project " + projectId + (isDiscoverable ? " discoverable" : " not discoverable"),
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.setProjectDiscoverable(projectId, isDiscoverable);
					return success();
				});
	}

	/**
	 * Get users with no access to a given project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsersNoCredentials")
	@Operation(operationId = "getProjectUsersNoCredentials", summary = "Find people to add to a project, as an admin", description = "Returns people who have no permission on a project yet, from the security database or, when it is enabled and asked for, the Microsoft directory. A directory search is paged with offset 0 to start and any other offset to continue. Admins only.")
	@ApiResponse(responseCode = "200", description = "The people", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthProjectUserCandidate.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectUsersNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The project id") @QueryParam("projectId") String projectId,
			@Parameter(description = "Text to search the people's ids, names, user names and emails for") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most people to return; 0 or less for all, or one directory page") @QueryParam("limit") long limit,
			@Parameter(description = "How many people to skip; for a directory search, 0 starts the search and anything else continues it") @QueryParam("offset") long offset,
			@Parameter(description = "false to search the security database instead of the Microsoft directory, when the directory is enabled; the directory is searched by default") @QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "search for people to add to project " + project,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// when the directory is not used
					// then we will look at our security db
					if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
						return adminUtils.getProjectUsersNoCredentials(project, term, limit, offset);
					}
					try {
						return MsGraphUtility.getProjectUsers(request, user, project, term, limit, offset, true);
					} catch (IllegalAccessException e) {
						// not being signed in to Microsoft is a bad request, not a missing session
						throw new IllegalArgumentException(e.getMessage(), e);
					}
				});
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
	@Path("approveProjectUserAccessRequest")
	@Operation(operationId = "approveProjectUserAccessRequest", summary = "Approve access requests to a project, as an admin", description = "Gives each requester the permission they asked for and marks their requests approved by the signed in admin. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectApproveUserAccessRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response approveProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request, "approve the access requests to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// adding user permissions and updating user access requests in bulk
					List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					String userId = token.getId();
					String userType = token.getProvider().toString();
					adminUtils.approveProjectUserAccessRequests(userId, userType, projectId, requests, endDate);
					return success();
				});
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
	@Path("denyProjectUserAccessRequest")
	@Operation(operationId = "denyProjectUserAccessRequest", summary = "Deny access requests to a project, as an admin", description = "Marks access requests to a project denied by the signed in admin. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectDenyUserAccessRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response denyProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "deny the access requests to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// updating user access requests in bulk
					List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), List.class);
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					String userId = token.getId();
					String userType = token.getProvider().toString();
					adminUtils.denyProjectUserAccessRequests(userId, userType, projectId, requestids);
					return success();
				});
	}

	/**
	 * Add user permissions in bulk to a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermissions")
	@Operation(operationId = "addProjectUserPermissions", summary = "Give people access to a project, as an admin", description = "Gives several people permissions on a project. People picked from the Microsoft directory are added to the security database first. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminProjectUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The people were given access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "add user permissions to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// adding user permissions in bulk
					List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					// users picked from the Microsoft directory may not be in the security db yet
					if (MicrosoftGraphUserLookup.isEnabled()) {
						MicrosoftGraphUserLookup.addMissingUsers(user, permission);
					}
					adminUtils.addProjectUserPermissions(projectId, permission, user);
					return success();
				});
	}

	/**
	 * Remove user permissions for a project, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermissions")
	@Operation(operationId = "removeProjectUserPermissions", summary = "Remove people from a project, as an admin", description = "Removes several people's permissions on a project. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectRemoveUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The people were removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "remove users from project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					Gson gson = new Gson();
					List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);
					ids = WebUtility.inputSQLSanitizer(ids);
					adminUtils.removeProjectUsers(ids, projectId);
					return success();
				});
	}

	/**
	 * @return the body of a change that was made
	 */
	private static Map<String, Object> success() {
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return ret;
	}

}
