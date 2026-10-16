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
import prerna.semoss.web.services.local.ResourceUtility;
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
	public Response getProjectsGET(@Context HttpServletRequest request,
			@QueryParam("projectId") List<String> projectFilter, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset,
			@QueryParam("onlyFavorites") Boolean favoritesOnly, @QueryParam("metaKeys") List<String> metaKeys,
			// @QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta, @QueryParam("userT") Boolean includeUserTracking) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> projects = WebUtility.inputSanitizer(projectFilter);
		List<String> keys = WebUtility.inputSanitizer(metaKeys);
		return ResourceUtility.respondAllowingAnonymous(request, "list the projects they can access", user -> {
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
	public Response getProjectsPOST(@Context HttpServletRequest request) {
		return ResourceUtility.respondAllowingAnonymous(request, "list the projects they can access", user -> {
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
	public Response getUserProjectPermission(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("searchTerm") String searchTerm) {
		String project = WebUtility.inputSanitizer(projectId);
		String action = "read their permission on project " + project;
		return ResourceUtility.respondAllowingAnonymous(request, action, user -> {
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
	public Response getProjectUsers(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("userId") String userId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String project = WebUtility.inputSanitizer(projectId);
		String member = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String level = WebUtility.inputSanitizer(permission);
		String searchParam = term != null ? term : member;
		return ResourceUtility.respondAllowingAnonymous(request, "list the users of project " + project, user -> {
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
	public Response getProjectUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset,
			@QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		String project = WebUtility.inputSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAllowingAnonymous(request, "find people to add to project " + project, user -> {
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
