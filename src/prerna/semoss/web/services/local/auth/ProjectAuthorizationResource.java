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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javatuples.Pair;

import com.google.gson.Gson;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEntityDefaultTokenUtils;
import prerna.auth.utils.SecurityGroupProjectUtils;
import prerna.auth.utils.SecurityPrincipalTokenLimitUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.om.Insight;
import prerna.project.api.IProject;
import prerna.reactor.project.MyProjectsReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Settings;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/project")
@PermitAll
public class ProjectAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(ProjectAuthorizationResource.class);

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

		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		projectFilter = WebUtility.inputSanitizer(projectFilter);
		metaKeys = WebUtility.inputSanitizer(metaKeys);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		MyProjectsReactor reactor = new MyProjectsReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		if (searchTerm != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(searchTerm, PixelDataType.CONST_STRING));
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
		if (projectFilter != null && !projectFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String project : projectFilter) {
				struct.add(new NounMetadata(project, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
		}
		if (metaKeys != null && !metaKeys.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String metaK : metaKeys) {
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

		NounMetadata outputNoun = reactor.execute();
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}

	@POST
	@Produces("application/json")
	@Path("getProjects")
	public Response getProjectsPOST(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			classLogger.error("Failed to resolve authenticated user while retrieving accessible projects from POST.",
					e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

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

		NounMetadata outputNoun = reactor.execute();
		return WebUtility.getResponse(outputNoun.getValue(), 200);
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

		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String permission = SecurityProjectUtils.getActualUserProjectPermission(user, projectId);
		if (permission == null) {
			// are you discoverable?
			if (SecurityProjectUtils.projectIsDiscoverable(projectId)) {
				permission = "DISCOVERABLE";
			} else {
				classLogger.warn("User is trying to pull permission details for project " + projectId
						+ " without having proper access");
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "User does not have access to this project");
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		Map<String, String> ret = new HashMap<String, String>();
		ret.put("permission", permission);
		return WebUtility.getResponse(ret, 200);
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
		projectId = WebUtility.inputSanitizer(projectId);
		userId = WebUtility.inputSQLSanitizer(userId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		permission = WebUtility.inputSanitizer(permission);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			String searchParam = searchTerm != null ? searchTerm : userId;
			List<Map<String, Object>> members = SecurityProjectUtils.getProjectUsers(user, projectId, searchParam,
					permission, limit, offset);
			long totalMembers = SecurityProjectUtils.getProjectUsersCount(user, projectId, searchParam, permission);
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull users for project " + projectId + " without having proper access");
			classLogger.error("Failed to retrieve users for project " + projectId
					+ " because access validation failed for the requester.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add a user for project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.addProjectUser(user, newUserId, projectId, permission, endDate);
		} catch (Exception e) {
			classLogger.warn("User is trying to add a user for project " + projectId + " without having proper access");
			classLogger.error("Failed to add user " + newUserId + " to project " + projectId + " with permission "
					+ permission + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user {} to project {} with permission {}", newUserId, projectId, permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Propagate project dependent permissions
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
		Map<String, Object> ret = new HashMap<String, Object>();

		User requester = null;
		try {
			requester = ResourceUtility.getUser(request);
			classLogger.info("User is attempting to modify engine permissions.");
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			classLogger.error("Failed to resolve authenticated user while propagating project dependency permission.",
					e);
			ret.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(ret, 401);
		}

		// Get form info

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String newUserType = WebUtility.inputSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String requestedPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		String usageRestriction = form.containsKey("usageRestriction")
				? WebUtility.inputSQLSanitizer(form.getFirst("usageRestriction"))
				: null;
		String usageFrequency = form.containsKey("usageFrequency")
				? WebUtility.inputSQLSanitizer(form.getFirst("usageFrequency"))
				: null;
		int maxTokens = 0;
		String maxTokensStr = WebUtility.inputSanitizer(request.getParameter("maxTokens"));
		if (maxTokensStr != null && !(maxTokensStr = maxTokensStr.trim()).isEmpty()) {
			// must be a valid integer
			try {
				maxTokens = Integer.parseInt(maxTokensStr);
			} catch (NumberFormatException e) {
				classLogger.error("Failed to parse maxTokens value '" + maxTokensStr
						+ "' while propagating project dependency permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxTokens must be a valid integer value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		double maxResponseTime = 0.0;
		String maxResponseTimeStr = WebUtility.inputSanitizer(request.getParameter("maxResponseTime"));
		if (maxResponseTimeStr != null && !(maxResponseTimeStr = maxResponseTimeStr.trim()).isEmpty()) {
			// must be a valid double
			try {
				maxResponseTime = Double.parseDouble(maxResponseTimeStr);
			} catch (NumberFormatException e) {
				classLogger.error("Failed to parse maxResponseTime value '" + maxResponseTimeStr
						+ "' while propagating project dependency permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		int maxInputTokens = 0;
		String maxInputTokensStr = WebUtility.inputSanitizer(request.getParameter("maxInputTokens"));
		if (maxInputTokensStr != null && !(maxInputTokensStr = maxInputTokensStr.trim()).isEmpty()) {
			try {
				maxInputTokens = Integer.parseInt(maxInputTokensStr);
			} catch (NumberFormatException e) {
				classLogger.error("Failed to parse maxInputTokens value '" + maxInputTokensStr
						+ "' while propagating project dependency permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxInputTokens must be a valid integer value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		int maxOutputTokens = 0;
		String maxOutputTokensStr = WebUtility.inputSanitizer(request.getParameter("maxOutputTokens"));
		if (maxOutputTokensStr != null && !(maxOutputTokensStr = maxOutputTokensStr.trim()).isEmpty()) {
			try {
				maxOutputTokens = Integer.parseInt(maxOutputTokensStr);
			} catch (NumberFormatException e) {
				classLogger.error("Failed to parse maxOutputTokens value '" + maxOutputTokensStr
						+ "' while propagating project dependency permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxOutputTokens must be a valid integer value");
				return WebUtility.getResponse(ret, 400);
			}
		}

		// Determine if admin right are required to add users and, if so, if requester
		// has those rights.
		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(requester)) {
			classLogger.warn("User is trying to add a user for project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> responses = SecurityProjectUtils.propagateProjectPermission(requester, projectId, newUserId,
				newUserType, requestedPermission, endDate, usageRestriction, usageFrequency, maxTokens,
				maxResponseTime, maxInputTokens, maxOutputTokens);

		return WebUtility.getResponse(responses, 200);
	}

	/**
	 * Propagate project dependent permissions
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
		Map<String, Object> ret = new HashMap<String, Object>();

		User requester = null;
		try {
			requester = ResourceUtility.getUser(request);
			classLogger.info("User is attempting to modify engine permissions.");
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			classLogger.error(
					"Failed to resolve authenticated user while propagating project dependency permissions in bulk.",
					e);
			ret.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(ret, 401);
		}

		// Get form info

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);

		// Determine if admin right are required to add users and, if so, if requester
		// has those rights.
		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(requester)) {
			classLogger.warn("User is trying to add a user for project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> userRet = new HashMap<String, Object>();

		for (Map<String, String> userRequest : requests) {
			String newUserId = userRequest.get("userid");
			String newUserType = userRequest.get("type");
			String requestedPermission = userRequest.get("permission");
			String usageRestriction = userRequest.containsKey("usageRestriction") ? userRequest.get("usageRestriction")
					: null;
			String usageFrequency = userRequest.containsKey("usageFrequency") ? userRequest.get("usageFrequency")
					: null;
			int maxTokens = 0;

			String maxTokensStr = userRequest.containsKey("maxTokens") ? userRequest.get("maxTokens") : null;

			if (maxTokensStr != null && !(maxTokensStr = maxTokensStr.trim()).isEmpty()) {
				// must be a valid integer
				try {
					maxTokens = Integer.parseInt(maxTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to parse maxTokens value '" + maxTokensStr + "' for user " + newUserId
							+ " while propagating project dependency permissions in bulk.", e);
					ret.put(Constants.ERROR_MESSAGE, "maxTokens must be a valid integer value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			double maxResponseTime = 0.0;
			String maxResponseTimeStr = userRequest.containsKey("maxResponseTime") ? userRequest.get("maxResponseTime")
					: null;
			if (maxResponseTimeStr != null && !(maxResponseTimeStr = maxResponseTimeStr.trim()).isEmpty()) {
				// must be a valid double
				try {
					maxResponseTime = Double.parseDouble(maxResponseTimeStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to parse maxResponseTime value '" + maxResponseTimeStr + "' for user "
							+ newUserId + " while propagating project dependency permissions in bulk.", e);
					ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			int maxInputTokens = 0;
			String maxInputTokensStr = userRequest.containsKey("maxInputTokens") ? userRequest.get("maxInputTokens") : null;
			if (maxInputTokensStr != null && !(maxInputTokensStr = maxInputTokensStr.trim()).isEmpty()) {
				try {
					maxInputTokens = Integer.parseInt(maxInputTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to parse maxInputTokens value '" + maxInputTokensStr + "' for user "
							+ newUserId + " while propagating project dependency permissions in bulk.", e);
					ret.put(Constants.ERROR_MESSAGE, "maxInputTokens must be a valid integer value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			int maxOutputTokens = 0;
			String maxOutputTokensStr = userRequest.containsKey("maxOutputTokens") ? userRequest.get("maxOutputTokens") : null;
			if (maxOutputTokensStr != null && !(maxOutputTokensStr = maxOutputTokensStr.trim()).isEmpty()) {
				try {
					maxOutputTokens = Integer.parseInt(maxOutputTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to parse maxOutputTokens value '" + maxOutputTokensStr + "' for user "
							+ newUserId + " while propagating project dependency permissions in bulk.", e);
					ret.put(Constants.ERROR_MESSAGE, "maxOutputTokens must be a valid integer value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			Map<String, Object> responses = SecurityProjectUtils.propagateProjectPermission(requester, projectId,
					newUserId, newUserType, requestedPermission, endDate, usageRestriction, usageFrequency, maxTokens,
					maxResponseTime, maxInputTokens, maxOutputTokens);
			userRet.put(newUserId, responses);
		}

		ret.put("success", true);
		ret.put("users", userRet);

		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String existingUserType = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for project " + projectId
					+ " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.editProjectUserPermission(user, existingUserId, existingUserType, projectId,
					newPermission, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for project " + projectId
					+ " without having proper access");
			classLogger.error("Failed to update permission for user " + existingUserId + " in project " + projectId
					+ " because access validation failed.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(
					"Failed to update permission for user " + existingUserId + " in project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user {} permission to project {} with level {}", existingUserId, projectId,
				newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			classLogger.error("Failed to resolve authenticated user while editing project user permissions in bulk.",
					e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger
					.warn("User is trying to edit user permissions for project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityProjectUtils.editProjectUserPermissions(user, projectId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user permissions for project " + projectId
					+ " without having proper access");
			classLogger.error("Failed to update project user permissions in bulk for project " + projectId
					+ " because access validation failed.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update project user permissions in bulk for project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permission to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to project "
					+ projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.removeProjectUser(user, existingUserId, projectId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to project "
					+ projectId + " without having proper access");
			classLogger.error("Failed to remove user " + existingUserId + " from project " + projectId
					+ " because access validation failed.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove user " + existingUserId + " from project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed user {} from having access to project {}", existingUserId, projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		if (AbstractSecurityUtils.adminOnlyProjectSetPublic() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to set the project " + projectId + logPublic + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.setProjectGlobal(user, projectId, isPublic);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the project " + projectId + logPublic + " without having proper access");
			classLogger.error("Failed to update global visibility for project " + projectId
					+ " because access validation failed.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update global visibility for project " + projectId + ".", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the project {} {}", projectId, logPublic.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		classLogger.info("THIS IS CALLED HERE: {}", request);
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		if (AbstractSecurityUtils.adminOnlyProjectSetDiscoverable() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger
					.warn("User is trying to set the project " + projectId + logDiscoverable + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.setProjectDiscoverable(user, projectId, isDiscoverable);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the project " + projectId + logDiscoverable
					+ " without having proper access");
			classLogger.error(
					"Failed to update discoverability for project " + projectId + " because access validation failed.",
					e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update discoverability for project " + projectId + ".", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the project {} {}", projectId, logDiscoverable.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String logVisible = visible ? " visible " : " not visible";

		try {
			SecurityProjectUtils.setProjectVisibility(user, projectId, visible);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the project " + projectId + logVisible + " without having proper access");
			classLogger.error(
					"Failed to update visibility for project " + projectId + " because access validation failed.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update visibility for project " + projectId + ".", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the project {} {}", projectId, logVisible.trim());

		return WebUtility.getResponse(true, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityProjectUtils.setProjectFavorite(user, projectId, isFavorite);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the project " + projectId + logFavorited + " without having proper access");
			classLogger.error(
					"Failed to update favorite status for project " + projectId + " because access validation failed.",
					e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update favorite status for project " + projectId + ".", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the project {} {}", projectId, logFavorited.trim());

		return WebUtility.getResponse(true, 200);
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
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  invalid user session trying to access authorization resources");
			classLogger.error(
					"Failed to resolve authenticated user while retrieving users without project credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean graphApi = Boolean
				.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// if not graph api
		// then we will look at our security db
		if (!graphApi) {
			List<Map<String, Object>> ret = null;
			try {
				ret = SecurityProjectUtils.getProjectUsersNoCredentials(user, projectId, searchTerm, limit, offset);
				return WebUtility.getResponse(ret, 200);
			} catch (IllegalAccessException e) {
				classLogger.warn("User  is trying to pull users for " + projectId
						+ " that do not have credentials without having proper access");
				classLogger.error("Failed to retrieve users without credentials for project " + projectId
						+ " because access validation failed.", e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		String graphApiGroupId = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_groupId");

		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.getProjectUsers(request, user, projectId,
					searchTerm, graphApiGroupId, limit, offset);
			return WebUtility.getResponse(filteredUsers, 200);
		} catch (Exception e) {
			classLogger.error(
					"Failed to retrieve users without credentials from Microsoft Graph for project " + projectId + ".",
					e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to approve user access to project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			SecurityProjectUtils.approveProjectUserAccessRequests(user, projectId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to grant user access to project " + projectId + " without having proper access");
			classLogger.error(
					"Failed to approve access requests for project " + projectId + " because access validation failed.",
					e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to approve access requests for project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access and added user permissions to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to deny user access to project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), List.class);
		requestids = WebUtility.inputSQLSanitizer(requestids);
		try {
			SecurityProjectUtils.denyProjectUserAccessRequests(user, projectId, requestids);
		} catch (Exception e) {
			classLogger.error("Failed to deny access requests for project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add user permissions to project " + projectId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean graphApi = Boolean
				.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// adding user permissions in bulk
		List<Map<String, Object>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			// if we are doing the grpah api
			// then the users might not already exist in the security db
			if (graphApi) {
				// filter out users that already exist
				List<Map<String, Object>> filteredUsers = permission.stream()
						.filter(map -> !SecurityQueryUtils.checkUserExist((String) map.get(Constants.MAP_USERID)))
						.collect(Collectors.toList());
				if (filteredUsers != null && !filteredUsers.isEmpty()) {
					AccessToken token = null;
					// Add new users to OAuth if they don't exist
					for (Map<String, Object> map : filteredUsers) {
						token = new AccessToken();
						token.setId((String) map.get(Constants.MAP_USERID));
						token.setEmail((String) map.get(Constants.MAP_EMAIL));
						token.setName((String) map.get(Constants.MAP_NAME));
						token.setUsername((String) map.get(Constants.MAP_USERNAME));
						token.setProvider(AuthProvider.MICROSOFT);
						SecurityUpdateUtils.addOAuthUser(token);
					}
				}
			}

			SecurityProjectUtils.addProjectUserPermissions(user, projectId, permission, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to add project user permissions in bulk for project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user permissions to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		Gson gson = new Gson();
		List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to remove users from having access to project " + projectId
					+ " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityProjectUtils.removeProjectUsers(user, ids, projectId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove users from having access to project " + projectId
					+ " without having proper access");
			classLogger.error("Failed to remove project users in bulk for project " + projectId
					+ " because access validation failed.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove project users in bulk for project " + projectId + ".", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to project {}", projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Produces("application/json")
	@Path("setProjectPortal")
	public Response setProjectPortal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		boolean hasPortal = Boolean.parseBoolean(form.getFirst("hasPortal"));
		String portalName = WebUtility.inputSanitizer(form.getFirst("portalName"));
		String logPortal = hasPortal ? " enable portal " : " disable portal";

		IProject project = Utility.getProject(projectId);
		try {
			SecurityProjectUtils.setProjectPortal(user, projectId, hasPortal, portalName);
			project.setHasPortal(hasPortal);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to " + logPortal + " for project " + projectId);
			classLogger.error(
					"Failed to update portal settings for project " + projectId + " because access validation failed.",
					e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update portal settings for project " + projectId + ".", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		try {
			String projectSmss = project.getSmssFilePath();
			Map<String, String> mods = new HashMap<>();
			mods.put(Settings.PUBLIC_HOME_ENABLE, hasPortal + "");
			Properties props = Utility.loadProperties(projectSmss);
			if (props.get(Settings.PUBLIC_HOME_ENABLE) == null) {
				classLogger.info(Utility.cleanLogString("Updating project smss to include public home property to "
						+ logPortal + " for project " + projectId));
				Utility.addKeysAtLocationIntoPropertiesFile(projectSmss, Constants.CONNECTION_URL, mods);
			} else {
				classLogger.info(
						Utility.cleanLogString("Modifying project smss to " + logPortal + " for project " + projectId));
				Utility.changePropertiesFileValue(projectSmss, Settings.PUBLIC_HOME_ENABLE, hasPortal + "");
			}

			// reload and set the prop again
			Properties newSmssProp = Utility.loadProperties(projectSmss);
			project.setSmssProp(newSmssProp);

			// push to cloud
			ClusterUtil.pushProjectSmss(projectId);
		} catch (Exception e) {
			// ignore
		}

		// log the operation
		classLogger.info("User is trying to {} for project {}", logPortal, projectId);

		return WebUtility.getResponse(true, 200);
	}

	/**
	 * Reset token usage for a user on a specific project.
	 */
	@POST
	@Produces("application/json")
	@Path("resetProjectUserTokenUsage")
	public Response resetProjectUserTokenUsage(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String targetUserId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));

		// Only editors/owners or admins can reset
		if (!SecurityProjectUtils.userCanEditProject(user, projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insufficient privileges to reset token usage.");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			ModelInferenceLogsUtils.resetUserTokenUsageForProject(targetUserId, projectId);
		} catch (Exception e) {
			classLogger.error("Failed to reset token usage for user " + targetUserId + " on project " + projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		classLogger.info("User reset token usage for user {} on project {}", targetUserId, projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Produces("application/json")
	@Path("getProjectDefaultTokenLimit")
	public Response getProjectDefaultTokenLimit(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		projectId = WebUtility.inputSanitizer(projectId);
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Project does not exist or user does not have access");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			return WebUtility.getResponse(SecurityEntityDefaultTokenUtils.getProjectDefaultTokenLimits(projectId), 200);
		} catch (Exception e) {
			classLogger.error("Failed to get project default token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Produces("application/json")
	@Path("setProjectDefaultTokenLimit")
	public Response setProjectDefaultTokenLimit(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String usageFrequency = WebUtility.inputSanitizer(form.getFirst("usageFrequency"));
		String existingUsageFrequency = WebUtility.inputSanitizer(form.getFirst("existingUsageFrequency"));
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanEditProject(user, projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insufficient privileges to set project default token limit.");
			return WebUtility.getResponse(errorMap, 403);
		}

		long maxTokens = parseLong(form.getFirst("maxTokens"), -1);
		long maxInputTokens = parseLong(form.getFirst("maxInputTokens"), -1);
		long maxOutputTokens = parseLong(form.getFirst("maxOutputTokens"), -1);
		double maxResponseTime = parseDouble(form.getFirst("maxResponseTime"), -1);
		boolean isActive = parseBoolean(form.getFirst("isActive"), true);
		boolean restrictPerModel = parseBoolean(form.getFirst("restrictPerModel"), false);
		Pair<String, String> userDetails = User.getPrimaryUserIdAndTypePair(user);

		try {
			SecurityEntityDefaultTokenUtils.setProjectDefaultTokenLimit(projectId, usageFrequency, maxTokens,
					maxInputTokens, maxOutputTokens, maxResponseTime, isActive, userDetails.getValue0(), userDetails.getValue1(),
					restrictPerModel, existingUsageFrequency);
			Map<String, Object> ret = new HashMap<String, Object>();
			ret.put("success", true);
			ret.put("projectId", projectId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to set project default token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Produces("application/json")
	@Path("removeProjectDefaultTokenLimit")
	public Response removeProjectDefaultTokenLimit(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String usageFrequency = WebUtility.inputSanitizer(form.getFirst("usageFrequency"));
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanEditProject(user, projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insufficient privileges to remove project default token limit.");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			SecurityEntityDefaultTokenUtils.removeProjectDefaultTokenLimit(projectId, usageFrequency);
			Map<String, Object> ret = new HashMap<String, Object>();
			ret.put("success", true);
			ret.put("projectId", projectId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to remove project default token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@GET
	@Produces("application/json")
	@Path("getProjectDefaultTeamTokenLimit")
	public Response getProjectDefaultTeamTokenLimit(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		projectId = WebUtility.inputSanitizer(projectId);
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Project does not exist or user does not have access");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			return WebUtility.getResponse(SecurityEntityDefaultTokenUtils.getProjectDefaultTeamTokenLimits(projectId), 200);
		} catch (Exception e) {
			classLogger.error("Failed to get project default team token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Produces("application/json")
	@Path("setProjectDefaultTeamTokenLimit")
	public Response setProjectDefaultTeamTokenLimit(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String usageFrequency = WebUtility.inputSanitizer(form.getFirst("usageFrequency"));
		String existingUsageFrequency = WebUtility.inputSanitizer(form.getFirst("existingUsageFrequency"));
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanEditProject(user, projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insufficient privileges to set project default team token limit.");
			return WebUtility.getResponse(errorMap, 403);
		}

		long maxTokens = parseLong(form.getFirst("maxTokens"), -1);
		long maxInputTokens = parseLong(form.getFirst("maxInputTokens"), -1);
		long maxOutputTokens = parseLong(form.getFirst("maxOutputTokens"), -1);
		double maxResponseTime = parseDouble(form.getFirst("maxResponseTime"), -1);
		boolean isActive = parseBoolean(form.getFirst("isActive"), true);
		Pair<String, String> userDetails = User.getPrimaryUserIdAndTypePair(user);

		try {
			SecurityEntityDefaultTokenUtils.setProjectDefaultTeamTokenLimit(projectId, usageFrequency, maxTokens,
					maxInputTokens, maxOutputTokens, maxResponseTime, isActive, userDetails.getValue0(), userDetails.getValue1(),
					existingUsageFrequency);
			Map<String, Object> ret = new HashMap<String, Object>();
			ret.put("success", true);
			ret.put("projectId", projectId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to set project default team token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Produces("application/json")
	@Path("removeProjectDefaultTeamTokenLimit")
	public Response removeProjectDefaultTeamTokenLimit(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String usageFrequency = WebUtility.inputSanitizer(form.getFirst("usageFrequency"));
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityProjectUtils.userCanEditProject(user, projectId) && !SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insufficient privileges to remove project default team token limit.");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			SecurityEntityDefaultTokenUtils.removeProjectDefaultTeamTokenLimit(projectId, usageFrequency);
			Map<String, Object> ret = new HashMap<String, Object>();
			ret.put("success", true);
			ret.put("projectId", projectId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to remove project default team token limit for project {}", projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@GET
	@Produces("application/json")
	@Path("getProjectTokenUsage")
	public Response getProjectTokenUsage(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		projectId = WebUtility.inputSanitizer(projectId);
		if (projectId == null || projectId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a projectId");
			return WebUtility.getResponse(errorMap, 400);
		}

		// Verify user has access to the project
		if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Project does not exist or user does not have access");
			return WebUtility.getResponse(errorMap, 403);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			List<Map<String, Object>> limits = buildProjectUsageLimits(user, projectId);
			ret.put("projectId", projectId);
			ret.put("configured", !limits.isEmpty());
			ret.put("limits", limits);
			if (limits.size() == 1) {
				ret.putAll(buildLegacyUsagePayload(limits.get(0)));
			}
		} catch (Exception e) {
			classLogger.error("Failed to get project token usage for project " + projectId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}

		return WebUtility.getResponse(ret, 200);
	}

	private List<Map<String, Object>> buildProjectUsageLimits(User user, String projectId) {
		List<Map<String, Object>> limits = new ArrayList<>();
		ZonedDateTime currentDateTime = Utility.getCurrentZonedDateTimeUTC();

		for (Map<String, Object> limit : getApplicableProjectUserTokenLimitsForAnyEngine(user, projectId)) {
			addProjectUsageLimit(limits, "user_limit_table", "user", projectId, limit.get("engineId"),
					limit.get("userId"), null, limit, "usageRestriction", "usageFrequency", "maxTokens",
					"maxInputTokens", "maxOutputTokens", "maxResponseTime", "restrictPerModel", user,
					currentDateTime);
		}
		for (Map<String, Object> limit : SecurityProjectUtils.getProjectUsagePermissionMap(user, projectId)) {
			addProjectUsageLimit(limits, "user_permission", "user", projectId, null, getCurrentUserId(user), null,
					limit, Constants.PROJECT_USAGE_RESTRICTION_KEY, Constants.PROJECT_USAGE_FREQUENCY_KEY,
					Constants.PROJECT_MAX_TOKEN_KEY, Constants.PROJECT_MAX_INPUT_TOKEN_KEY,
					Constants.PROJECT_MAX_OUTPUT_TOKEN_KEY, Constants.PROJECT_MAX_RESPONSE_TIME_KEY,
					Constants.PROJECT_RESTRICT_PER_MODEL_KEY, user, currentDateTime);
		}
		for (Map<String, Object> limit : SecurityEntityDefaultTokenUtils.getProjectDefaultTokenLimits(projectId)) {
			addProjectUsageLimit(limits, "default_user", "default_user", projectId, limit.get("engineId"), null, null,
					limit, "usageRestriction", "usageFrequency", "maxTokens", "maxInputTokens", "maxOutputTokens",
					"maxResponseTime", "restrictPerModel", user, currentDateTime);
		}
		for (Map<String, Object> limit : getApplicableProjectTeamTokenLimitsForAnyEngine(user, projectId)) {
			String groupRef = stringify(limit.get("groupType"));
			if (groupRef != null && limit.get("groupId") != null) {
				groupRef = groupRef + ":" + stringify(limit.get("groupId"));
			}
			addProjectUsageLimit(limits, "team_limit_table", "team", projectId, limit.get("engineId"), null, groupRef,
					limit, "usageRestriction", "usageFrequency", "maxTokens", "maxInputTokens", "maxOutputTokens",
					"maxResponseTime", "restrictPerModel", user, currentDateTime);
		}
		for (Map<String, Object> limit : SecurityGroupProjectUtils.getApplicableGroupProjectUsagePermissions(user,
				projectId)) {
			String groupRef = stringify(limit.get("groupType"));
			if (groupRef != null && limit.get("groupId") != null) {
				groupRef = groupRef + ":" + stringify(limit.get("groupId"));
			}
			addProjectUsageLimit(limits, "team_permission", "team", projectId, null, null, groupRef, limit,
					Constants.PROJECT_USAGE_RESTRICTION_KEY, Constants.PROJECT_USAGE_FREQUENCY_KEY,
					Constants.PROJECT_MAX_TOKEN_KEY, Constants.PROJECT_MAX_INPUT_TOKEN_KEY,
					Constants.PROJECT_MAX_OUTPUT_TOKEN_KEY, Constants.PROJECT_MAX_RESPONSE_TIME_KEY,
					Constants.PROJECT_RESTRICT_PER_MODEL_KEY, user, currentDateTime);
		}
		for (Map<String, Object> limit : SecurityEntityDefaultTokenUtils.getProjectDefaultTeamTokenLimits(projectId)) {
			addProjectUsageLimit(limits, "default_team", "default_team", projectId, limit.get("engineId"), null, null,
					limit, "usageRestriction", "usageFrequency", "maxTokens", "maxInputTokens", "maxOutputTokens",
					"maxResponseTime", "restrictPerModel", user, currentDateTime);
		}

		return limits;
	}

	private List<Map<String, Object>> getApplicableProjectUserTokenLimitsForAnyEngine(User user, String projectId) {
		List<Map<String, Object>> limits = new ArrayList<>();
		if (user == null) {
			return limits;
		}
		for (AuthProvider login : user.getLogins()) {
			AccessToken token = user.getAccessToken(login);
			if (token != null && token.getId() != null && !token.getId().trim().isEmpty()) {
				limits.addAll(SecurityPrincipalTokenLimitUtils.getProjectUserTokenLimitsForAnyEngine(projectId,
						token.getId()));
			}
		}
		return limits;
	}

	private List<Map<String, Object>> getApplicableProjectTeamTokenLimitsForAnyEngine(User user, String projectId) {
		List<Map<String, Object>> limits = new ArrayList<>();
		if (user == null) {
			return limits;
		}
		for (AuthProvider login : user.getLogins()) {
			AccessToken token = user.getAccessToken(login);
			if (token == null || token.getUserGroupType() == null || token.getUserGroupType().trim().isEmpty()
					|| token.getUserGroups() == null) {
				continue;
			}
			for (String groupId : token.getUserGroups()) {
				if (groupId != null && !groupId.trim().isEmpty()) {
					limits.addAll(SecurityPrincipalTokenLimitUtils.getProjectTeamTokenLimitsForAnyEngine(projectId,
							groupId, token.getUserGroupType()));
				}
			}
		}
		return limits;
	}

	private void addProjectUsageLimit(List<Map<String, Object>> limits, String source, String scope, String projectId,
			Object rawEngineId, Object userId, Object groupId, Map<String, Object> rawLimit, String restrictionKey,
			String frequencyKey, String maxTokensKey, String maxInputTokensKey, String maxOutputTokensKey,
			String maxResponseTimeKey, String restrictPerModelKey, User user, ZonedDateTime currentDateTime) {
		if (!isActive(rawLimit.get("isActive"))) {
			return;
		}
		String restrictionType = stringify(rawLimit.get(restrictionKey));
		String frequency = stringify(rawLimit.get(frequencyKey));
		Number maxTokens = numberValue(rawLimit.get(maxTokensKey));
		Number maxInputTokens = numberValue(rawLimit.get(maxInputTokensKey));
		Number maxOutputTokens = numberValue(rawLimit.get(maxOutputTokensKey));
		Number maxResponseTime = numberValue(rawLimit.get(maxResponseTimeKey));
		if (!hasConfiguredLimit(restrictionType, maxTokens, maxInputTokens, maxOutputTokens, maxResponseTime)) {
			return;
		}

		String storedEngineId = stringify(rawEngineId);
		boolean restrictPerModel = Boolean.TRUE.equals(rawLimit.get(restrictPerModelKey))
				|| (storedEngineId != null
						&& !SecurityPrincipalTokenLimitUtils.ALL_ENGINES_SENTINEL.equals(storedEngineId));
		String scopedEngineId = restrictPerModel ? storedEngineId : null;

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("source", source);
		row.put("scope", scope);
		row.put("projectId", projectId);
		row.put("engineId", storedEngineId);
		row.put("restrictPerModel", restrictPerModel);
		row.put("userId", userId);
		row.put("groupId", groupId);
		row.put("restrictionType", restrictionType);
		row.put("frequency", frequency);
		row.put("usageFrequency", frequency);
		row.put("tokenLimit", maxTokens);
		row.put("inputTokenLimit", maxInputTokens);
		row.put("outputTokenLimit", maxOutputTokens);
		row.put("computeTimeLimit", maxResponseTime);
		row.put("configured", true);
		row.put("isActive", true);
		row.put("tokensUsed", getProjectUsageMetric(Constants.MODEL_TOKEN_RESTRICTION_VALUE, user, projectId,
				scopedEngineId, currentDateTime, frequency, null, maxTokens));
		row.put("inputTokensUsed", getProjectUsageMetric(Constants.MODEL_TOKEN_RESTRICTION_VALUE, user, projectId,
				scopedEngineId, currentDateTime, frequency, "INPUT", maxInputTokens));
		row.put("outputTokensUsed", getProjectUsageMetric(Constants.MODEL_TOKEN_RESTRICTION_VALUE, user, projectId,
				scopedEngineId, currentDateTime, frequency, "RESPONSE", maxOutputTokens));
		row.put("computeTimeUsed", getProjectUsageMetric(Constants.MODEL_COMPUTE_TIME_RESTRICTION_VALUE, user,
				projectId, scopedEngineId, currentDateTime, frequency, null, maxResponseTime));
		limits.add(row);
	}

	private Number getProjectUsageMetric(String restrictionType, User user, String projectId, String scopedEngineId,
			ZonedDateTime currentDateTime, String frequency, String messageType, Number configuredLimit) {
		if (configuredLimit == null) {
			return Constants.MODEL_COMPUTE_TIME_RESTRICTION_VALUE.equalsIgnoreCase(restrictionType) ? 0.0 : 0;
		}
		Number usage = ModelInferenceLogsUtils.getTotalTokensForProject(restrictionType, user, projectId,
				normalizeProjectScopedEngineId(scopedEngineId), currentDateTime, frequency, messageType);
		if (usage != null) {
			return usage;
		}
		return Constants.MODEL_COMPUTE_TIME_RESTRICTION_VALUE.equalsIgnoreCase(restrictionType) ? 0.0 : 0;
	}

	private String normalizeProjectScopedEngineId(String scopedEngineId) {
		if (scopedEngineId == null || scopedEngineId.trim().isEmpty()
				|| SecurityPrincipalTokenLimitUtils.ALL_ENGINES_SENTINEL.equals(scopedEngineId)) {
			return null;
		}
		return scopedEngineId;
	}

	private Map<String, Object> buildLegacyUsagePayload(Map<String, Object> limit) {
		Map<String, Object> legacy = new HashMap<>();
		legacy.put("restrictionType", limit.get("restrictionType"));
		legacy.put("frequency", limit.get("frequency"));
		legacy.put("tokensUsed", limit.get("tokensUsed"));
		legacy.put("tokenLimit", limit.get("tokenLimit"));
		legacy.put("inputTokensUsed", limit.get("inputTokensUsed"));
		legacy.put("inputTokenLimit", limit.get("inputTokenLimit"));
		legacy.put("outputTokensUsed", limit.get("outputTokensUsed"));
		legacy.put("outputTokenLimit", limit.get("outputTokenLimit"));
		legacy.put("computeTimeUsed", limit.get("computeTimeUsed"));
		legacy.put("computeTimeLimit", limit.get("computeTimeLimit"));
		return legacy;
	}

	private boolean hasConfiguredLimit(String restrictionType, Number maxTokens, Number maxInputTokens,
			Number maxOutputTokens, Number maxResponseTime) {
		if (restrictionType == null || restrictionType.trim().isEmpty()) {
			return false;
		}
		return maxTokens != null || maxInputTokens != null || maxOutputTokens != null || maxResponseTime != null;
	}

	private boolean isActive(Object isActiveValue) {
		return isActiveValue == null || Boolean.TRUE.equals(isActiveValue);
	}

	private Number numberValue(Object value) {
		return value instanceof Number ? (Number) value : null;
	}

	private String stringify(Object value) {
		return value == null ? null : value.toString();
	}

	private String getCurrentUserId(User user) {
		if (user == null || user.getLogins() == null || user.getLogins().isEmpty()) {
			return null;
		}
		AccessToken token = user.getAccessToken(user.getLogins().get(0));
		return token == null ? null : token.getId();
	}

	private long parseLong(String val, long defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		try {
			return Long.parseLong(val.trim());
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private double parseDouble(String val, double defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		try {
			return Double.parseDouble(val.trim());
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private boolean parseBoolean(String val, boolean defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		return Boolean.parseBoolean(val.trim());
	}

}
