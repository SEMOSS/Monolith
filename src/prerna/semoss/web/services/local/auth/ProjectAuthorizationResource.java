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
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

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
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.om.Insight;
import prerna.reactor.project.MyProjectsReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectAddUserPermissionRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectApproveUserAccessRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectDenyUserAccessRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectEditUserPermissionRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectGetProjectsRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectGetProjectsRow;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectMembersPage;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectPropagateDependencyPermissionRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectPropagateDependencyPermissionsRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectPropagationResult;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectPropagationResults;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectRemoveUserPermissionRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectRemoveUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetDiscoverableRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetFavoriteRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetGlobalRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetTemplateRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectSetVisibilityRequest;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectUserCandidate;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectUserPermission;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResourceSchemas.AuthProjectUserPermissionsRequest;
import prerna.web.services.util.WebUtility;

@Path("/auth/project")
@PermitAll
public class ProjectAuthorizationResource {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
	/**
	 * The field for a model usage limit, as JSON, empty for no limit defined. A
	 * propagation without it keeps the limit of a person whose access to a model
	 * changes.
	 */
	private static final String USAGE_LIMIT = "usageLimit";
	/** Reads JSON rows, keeping whole numbers whole */
	private static final Gson ROW_GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create();

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
	@Operation(operationId = "getProjectsGet", summary = "List the user's projects", description = "Returns the projects the signed in user can access: those they or their groups have a permission on, and public ones, with their metadata unless noMeta is true.")
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthProjectGetProjectsRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectsGET(@Context HttpServletRequest request,
			@Parameter(description = "Only these project ids") @QueryParam("projectId") List<String> projectFilter,
			@Parameter(description = "Text to search the project ids and names for") @QueryParam("filterWord") String searchTerm,
			@Parameter(description = "The most projects to return") @QueryParam("limit") Integer limit,
			@Parameter(description = "How many projects to skip") @QueryParam("offset") Integer offset,
			@Parameter(description = "When true, only the user's favorite projects") @QueryParam("onlyFavorites") Boolean favoritesOnly,
			@Parameter(description = "The metadata keys to add to each project") @QueryParam("metaKeys") List<String> metaKeys,
			// @QueryParam("metaFilters") Map<String, Object> metaFilters,
			@Parameter(description = "When true, no metadata is added to the projects") @QueryParam("noMeta") Boolean noMeta,
			@Parameter(description = "Whether to include user tracking information") @QueryParam("userT") Boolean includeUserTracking) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> projects = WebUtility.inputSanitizer(projectFilter);
		List<String> keys = WebUtility.inputSanitizer(metaKeys);
		return ResourceUtility.respond(request, "list the projects they can access", user -> {
			MyProjectsReactor reactor = new MyProjectsReactor();
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
			if (favoritesOnly != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(favoritesOnly, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.ONLY_FAVORITES.getKey(), struct);
			}
			if (projects != null && !projects.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String project : projects) {
					struct.add(new NounMetadata(project, PixelDataType.CONST_STRING));
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
			// if(metaFilters != null) {
			// GenRowStruct struct = new GenRowStruct();
			// struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
			// reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(),
			// struct);
			// }
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

			return reactor.execute().getValue();
		});
	}

	@POST
	@Produces("application/json")
	@Path("getProjects")
	@Operation(operationId = "getProjects", summary = "List the user's projects", description = "Returns the projects the signed in user can access, as getProjectsGet does, with the filters sent as a form, which takes metaFilters but not onlyFavorites.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectGetProjectsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The projects", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthProjectGetProjectsRow.class))))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getProjectsPOST(@Context HttpServletRequest request) {
		return ResourceUtility.respond(request, "list the projects they can access", user -> {
			MyProjectsReactor reactor = new MyProjectsReactor();
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
				Map<String, Object> metaFilters = new Gson()
						.fromJson(WebUtility.jsonSanitizer(parameterMap.get("metaFilters")[0]), Map.class);
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

			return reactor.execute().getValue();
		});
	}

	/**
	 * Get the user app permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserProjectPermission")
	@Operation(operationId = "getUserProjectPermission", summary = "Read the user's permission on a project", description = "Returns the signed in user's permission on a project, or DISCOVERABLE when they have none and the project is discoverable.")
	@ApiResponse(responseCode = "200", description = "The permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthProjectUserPermission.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUserProjectPermission(@Context HttpServletRequest request,
			@Parameter(description = "The project id") @QueryParam("projectId") String projectId,
			@Parameter(description = "Not used") @QueryParam("searchTerm") String searchTerm) {
		String project = WebUtility.inputSanitizer(projectId);
		String action = "read their permission on project " + project;
		return ResourceUtility.respond(request, action, user -> {
			String permission = SecurityProjectUtils.getActualUserProjectPermission(user, project);
			if (permission == null) {
				// are you discoverable?
				if (SecurityProjectUtils.projectIsDiscoverable(project)) {
					permission = "DISCOVERABLE";
				} else {
					throw new IllegalAccessException("User does not have access to this project");
				}
			}

			Map<String, String> ret = new HashMap<>();
			ret.put("permission", permission);
			return ret;
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
	@Operation(operationId = "getProjectUsers", summary = "List the people with access to a project", description = "Returns a page of the people with a permission on a project the signed in user can view, and how many match in all.")
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
		String project = WebUtility.inputSanitizer(projectId);
		String member = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String level = WebUtility.inputSanitizer(permission);
		String searchParam = term != null ? term : member;
		return ResourceUtility.respond(request, "list the users of project " + project, user -> {
			List<Map<String, Object>> members = SecurityProjectUtils.getProjectUsers(user, project, searchParam, level,
					limit, offset);
			long totalMembers = SecurityProjectUtils.getProjectUsersCount(user, project, searchParam, level);
			Map<String, Object> ret = new HashMap<>();
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
			return ret;
		});
	}

	/**
	 * Add a user to an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermission")
	@Operation(operationId = "addProjectUserPermission", summary = "Give a person access to a project", description = "Gives a person a permission on a project. Only admins may do it when adding access is limited to admins.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectAddUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The person was given access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "add user " + newUserId + " to project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.addProjectUser(user, newUserId, projectId, permission, endDate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Propagate project dependent permissions. The person's usage limit on the
	 * models the project depends on is sent as {@code usageLimit} (JSON, empty for
	 * no limit defined). Without it, a person added to a model gets no limit and a
	 * person whose access to a model changes keeps their limit. Only a model's
	 * owners set a limit that differs from the current one.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("propagateProjectDependencyPermission")
	@Operation(operationId = "propagateProjectDependencyPermission", summary = "Give a person access to a project's dependencies", description = "Gives a person the requested permission on each engine and project a project depends on, or sends an access request to its owners when the requester cannot share it. Only a model's owners set a usage limit that differs from the current one.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectPropagateDependencyPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "What was done for each dependency", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthProjectPropagationResult.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response propagateProjectDependencyPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String newUserType = WebUtility.inputSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String requestedPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		boolean usageLimitSent = form.containsKey(USAGE_LIMIT);
		String usageLimit = form.getFirst(USAGE_LIMIT);
		return ResourceUtility.respond(request, "give user " + newUserId + " access to project " + projectId, user -> {
			// Determine if admin right are required to add users and, if so, if requester
			// has those rights.
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}

			if (usageLimitSent) {
				return SecurityProjectUtils.propagateProjectPermission(user, projectId, newUserId, newUserType,
						requestedPermission, endDate, SecurityModelUsageLimitUtils.readJson(usageLimit));
			}
			return SecurityProjectUtils.propagateProjectPermission(user, projectId, newUserId, newUserType,
					requestedPermission, endDate);
		});
	}

	/**
	 * Propagate project dependent permissions, in bulk. Each row of
	 * {@code userpermissions} can carry the person's usage limit on the models the
	 * project depends on as {@code usageLimit}. Without it, a person whose access
	 * to a model changes keeps their limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("propagateProjectDependencyPermissions")
	@Operation(operationId = "propagateProjectDependencyPermissions", summary = "Give people access to a project's dependencies", description = "Does what propagateProjectDependencyPermission does for each person sent.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectPropagateDependencyPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "What was done for each person", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthProjectPropagationResults.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response propagateProjectDependencyPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "give users access to project " + projectId, user -> {
			List<Map<String, Object>> requests = ROW_GSON.fromJson(form.getFirst("userpermissions"), List.class);

			// Determine if admin right are required to add users and, if so, if requester
			// has those rights.
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}

			Map<String, Object> userRet = new HashMap<>();
			for (Map<String, Object> userRequest : requests) {
				String newUserId = textOf(userRequest.get("userid"));
				String newUserType = textOf(userRequest.get("type"));
				String requestedPermission = textOf(userRequest.get("permission"));
				Map<String, Object> responses;
				if (userRequest.containsKey(USAGE_LIMIT)) {
					responses = SecurityProjectUtils.propagateProjectPermission(user, projectId, newUserId, newUserType,
							requestedPermission, endDate, readUsageLimit(userRequest.get(USAGE_LIMIT)));
				} else {
					responses = SecurityProjectUtils.propagateProjectPermission(user, projectId, newUserId, newUserType,
							requestedPermission, endDate);
				}
				userRet.put(newUserId, responses);
			}

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			ret.put("users", userRet);
			return ret;
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
	@Path("editProjectUserPermission")
	@Operation(operationId = "editProjectUserPermission", summary = "Change a person's permission on a project", description = "Changes the permission a person has on a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectEditUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permission was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String existingUserType = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit user " + existingUserId + " on project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.editProjectUserPermission(user, existingUserId, existingUserType, projectId,
					newPermission, endDate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit user permission for project, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermissions")
	@Operation(operationId = "editProjectUserPermissions", summary = "Change people's permissions on a project", description = "Changes the permissions several people have on a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The permissions were changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit user permissions on project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
			SecurityProjectUtils.editProjectUserPermissions(user, projectId, requests, endDate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Remove user permission for an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermission")
	@Operation(operationId = "removeProjectUserPermission", summary = "Remove a person from a project", description = "Removes a person's permission on a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectRemoveUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The person was removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String action = "remove user " + existingUserId + " from project " + projectId;
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.removeProjectUser(user, existingUserId, projectId);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
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
	@Operation(operationId = "setProjectTemplate", summary = "Set whether a project is a template", description = "Sets whether users who can view a project may clone it as a template.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetTemplateRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectTemplate(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectIdInput = form.getFirst("projectId");
		String templateInput = form.getFirst("template");
		String projectId = WebUtility.inputSanitizer(projectIdInput);
		boolean isTemplate = Boolean.parseBoolean(templateInput);
		return ResourceUtility.respond(request, "set whether project " + projectId + " is a template", user -> {
			if (projectIdInput == null || projectIdInput.trim().isEmpty() || templateInput == null
					|| !("true".equalsIgnoreCase(templateInput) || "false".equalsIgnoreCase(templateInput))) {
				throw new IllegalArgumentException("projectId and a true or false template value are required");
			}
			SecurityProjectUtils.setProjectTemplate(user, projectId, isTemplate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Get the app as being global (read only) for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectGlobal")
	@Operation(operationId = "setProjectGlobal", summary = "Make a project public or private", description = "Sets whether a project is public. Only admins may do it when making projects public is limited to admins.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String action = "make project " + projectId + (isPublic ? " public" : " private");
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectSetPublic(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.setProjectGlobal(user, projectId, isPublic);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
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
	@Operation(operationId = "setProjectDiscoverable", summary = "Set whether a project is discoverable", description = "Sets whether users without access can discover a project. Only admins may do it when it is limited to admins.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetDiscoverableRequest.class)))
	@ApiResponse(responseCode = "200", description = "The project was changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String action = "make project " + projectId + (isDiscoverable ? " discoverable" : " not discoverable");
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectSetDiscoverable(projectId)
					&& !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.setProjectDiscoverable(user, projectId, isDiscoverable);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Set the app visibility for the user to be seen
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectVisibility")
	@Operation(operationId = "setProjectVisibility", summary = "Show or hide a project for the user", description = "Sets whether a project shows in the signed in user's project lists.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetVisibilityRequest.class)))
	@ApiResponse(responseCode = "200", description = "The visibility was set", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String action = (visible ? "show" : "hide") + " project " + projectId;
		return ResourceUtility.respond(request, action, user -> {
			SecurityProjectUtils.setProjectVisibility(user, projectId, visible);
			return true;
		});
	}

	/**
	 * Set the app as favorited by the user
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectFavorite")
	@Operation(operationId = "setProjectFavorite", summary = "Favorite or unfavorite a project", description = "Sets whether a project is one of the signed in user's favorites.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectSetFavoriteRequest.class)))
	@ApiResponse(responseCode = "200", description = "The favorite was set", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setProjectFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String action = (isFavorite ? "favorite" : "unfavorite") + " project " + projectId;
		return ResourceUtility.respond(request, action, user -> {
			SecurityProjectUtils.setProjectFavorite(user, projectId, isFavorite);
			return true;
		});
	}

	/**
	 * Get users with no access to a given app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsersNoCredentials")
	@Operation(operationId = "getProjectUsersNoCredentials", summary = "Find people to add to a project", description = "Returns people who have no permission on a project yet, from the security database or, when it is enabled and asked for, the Microsoft directory. A directory search is paged with offset 0 to start and any other offset to continue.")
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
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "find people to add to project " + project, user -> {
			// when the directory is not used
			// then we will look at our security db
			if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
				return SecurityProjectUtils.getProjectUsersNoCredentials(user, project, term, limit, offset);
			}
			return MsGraphUtility.getProjectUsers(request, user, project, term, limit, offset, false);
		});
	}

	/**
	 * approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveProjectUserAccessRequest")
	@Operation(operationId = "approveProjectUserAccessRequest", summary = "Approve access requests to a project", description = "Gives each requester the permission they asked for and marks their requests approved.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectApproveUserAccessRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response approveProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "approve access requests for project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// adding user permissions and updating user access requests in bulk
			List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
			SecurityProjectUtils.approveProjectUserAccessRequests(user, projectId, requests, endDate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyProjectUserAccessRequest")
	@Operation(operationId = "denyProjectUserAccessRequest", summary = "Deny access requests to a project", description = "Marks access requests to a project denied.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectDenyUserAccessRequest.class)))
	@ApiResponse(responseCode = "200", description = "The requests were denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response denyProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respond(request, "deny access requests for project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// updating user access requests in bulk
			List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), List.class);
			requestids = WebUtility.inputSQLSanitizer(requestids);
			SecurityProjectUtils.denyProjectUserAccessRequests(user, projectId, requestids);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
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
	@Operation(operationId = "addProjectUserPermissions", summary = "Give people access to a project", description = "Gives several people permissions on a project. People picked from the Microsoft directory are added to the security database first.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The people were given access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "add users to project " + projectId, user -> {
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// adding user permissions in bulk
			List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
			// users picked from the Microsoft directory may not be in the security db yet
			if (MicrosoftGraphUserLookup.isEnabled()) {
				MicrosoftGraphUserLookup.addMissingUsers(user, permission);
			}

			SecurityProjectUtils.addProjectUserPermissions(user, projectId, permission, endDate);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Remove user permissions for an project, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermissions")
	@Operation(operationId = "removeProjectUserPermissions", summary = "Remove people from a project", description = "Removes several people's permissions on a project.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthProjectRemoveUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The people were removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error happened", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respond(request, "remove users from project " + projectId, user -> {
			List<String> ids = new Gson().fromJson(form.getFirst("ids"), List.class);
			if (AbstractSecurityUtils.adminOnlyProjectAddAccess(projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityProjectUtils.removeProjectUsers(user, ids, projectId);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Reads a row's {@code usageLimit}, sent as a JSON object or as JSON text.
	 *
	 * @param value the value sent, which may be null
	 * @return the limit, or null for no limit defined
	 * @throws IllegalArgumentException when the value is not a limit
	 */
	private static ModelUsageLimit readUsageLimit(Object value) {
		if (value == null || value instanceof String) {
			return SecurityModelUsageLimitUtils.readJson((String) value);
		}
		return SecurityModelUsageLimitUtils.readJson(ROW_GSON.toJson(value));
	}

	/**
	 * Reads a value from one of the posted user permission requests as text.
	 *
	 * @param value a row's value, which may be null
	 * @return the value as text, or null
	 */
	private static String textOf(Object value) {
		return value == null ? null : value.toString();
	}

}
