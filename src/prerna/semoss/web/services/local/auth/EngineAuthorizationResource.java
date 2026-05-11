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

import com.google.gson.Gson;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.om.Insight;
import prerna.reactor.security.MyEnginesReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

@Path("/auth/engine")
@PermitAll
public class EngineAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(EngineAuthorizationResource.class);

	@Context
	protected ServletContext context;

	/**
	 * Get the engines the user has access to
	 * 
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngines")
	public Response getEngines(@Context HttpServletRequest request, @QueryParam("engineId") List<String> engineFilter,
			@QueryParam("engineTypes") List<String> engineTypes, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset,
			@QueryParam("onlyFavorites") Boolean favoritesOnly, @QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta, @QueryParam("userT") Boolean includeUserTracking) {

		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		engineFilter = WebUtility.inputSanitizer(engineFilter);
		engineTypes = WebUtility.inputSanitizer(engineTypes);
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

		MyEnginesReactor reactor = new MyEnginesReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
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
		if (engineFilter != null && !engineFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String engine : engineFilter) {
				struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if (engineTypes != null && !engineTypes.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String eType : engineTypes) {
				struct.add(new NounMetadata(eType, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		if (metaKeys != null && !metaKeys.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String metaK : metaKeys) {
				struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
		}
//		if(metaFilters != null) {
//			GenRowStruct struct = new GenRowStruct();
//			struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
//			reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
//		}
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
	@Path("getEngines")
	public Response getEnginesPOST(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		MyEnginesReactor reactor = new MyEnginesReactor();
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
		if (parameterMap.containsKey("engineId") && parameterMap.get("engineId") != null
				&& parameterMap.get("engineId").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] engineFilter = parameterMap.get("engineId");
			for (String engine : engineFilter) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(engine), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if (parameterMap.containsKey("engineTypes") && parameterMap.get("engineTypes") != null
				&& parameterMap.get("engineTypes").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] engineTypes = parameterMap.get("engineTypes");
			for (String eType : engineTypes) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(eType), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		if (parameterMap.containsKey("metaKeys") && parameterMap.get("metaKeys") != null
				&& parameterMap.get("metaKeys").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] metaKeys = parameterMap.get("metaKeys");
			for (String metaK : metaKeys) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(metaK), PixelDataType.CONST_STRING));
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
	 * Get the user engine permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserEnginePermission")
	public Response getUserEnginePermission(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId) {
		engineId = WebUtility.inputSanitizer(engineId);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String permission = SecurityEngineUtils.getActualUserEnginePermission(user, engineId);
		if (permission == null) {
			// are you discoverable?
			if (SecurityEngineUtils.engineIsDiscoverable(engineId)) {
				permission = "DISCOVERABLE";
			} else {
				classLogger.warn("User is trying to pull permission details for engine " + engineId
						+ " without having proper access");
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "User does not have access to this engine");
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		Map<String, String> ret = new HashMap<String, String>();
		ret.put("permission", permission);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the engine users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsers")
	public Response getEngineUsers(@Context HttpServletRequest request, @QueryParam("engineId") String engineId,
			@QueryParam("userId") String userId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
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
			List<Map<String, Object>> members = SecurityEngineUtils.getEngineUsers(user, engineId, searchParam,
					permission, limit, offset);
			long totalMembers = SecurityEngineUtils.getEngineUsersCount(user, engineId, searchParam, permission);
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull users for engine " + engineId + " without having proper access");
			classLogger.error("Failed to retrieve engine users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add a user to an engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermission")
	public Response addEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add users for engine " + engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

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
				classLogger.error("Failed to add engine user permission.", e);
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
				classLogger.error("Failed to add engine user permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
				return WebUtility.getResponse(ret, 400);
			}
		}

		try {
			SecurityEngineUtils.addEngineUser(user, newUserId, engineId, permission, endDate, usageRestriction,
					usageFrequency, maxTokens, maxResponseTime);
		} catch (Exception e) {
			classLogger.warn("User is trying to add users for engine " + engineId + " without having proper access");
			classLogger.error("Failed to add engine user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger
				.info("User has added user " + newUserId + " to engine " + engineId + " with permission " + permission);
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to a engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermissions")
	public Response addEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add user permissions to engine " + engineId + " but is not an admin");
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

			// now add the permission
			SecurityEngineUtils.addEngineUserPermissions(user, engineId, permission);
		} catch (Exception e) {
			classLogger.error("Failed to add engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user permissions to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for an engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermission")
	public Response editEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			ret.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(ret, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String existingUserType = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for engine " + engineId
					+ " but is not an admin");
			ret.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(ret, 401);
		}

		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

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
				classLogger.error("Failed to update engine user permission.", e);
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
				classLogger.error("Failed to update engine user permission.", e);
				ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
				return WebUtility.getResponse(ret, 400);
			}
		}

		try {
			SecurityEngineUtils.editEngineUserPermission(user, existingUserId, existingUserType, engineId,
					newPermission, endDate, usageRestriction, usageFrequency, maxTokens, maxResponseTime);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for engine " + engineId
					+ " without having proper access");
			classLogger.error("Failed to update engine user permission.", e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine user permission.", e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}

		// log the operation
		classLogger.info("User has edited user {} permission to engine {} with level {}", existingUserId, engineId,
				newPermission);
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for an engine, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermissions")
	public Response editEngineUserPermissions(@Context HttpServletRequest request,
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

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to edit user permissions for engine " + engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityEngineUtils.editEngineUserPermissions(user, engineId, requests);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to edit user permissions for engine " + engineId + " without having proper access");
			classLogger.error("Failed to update engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permission to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permission for an engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermission")
	public Response removeEngineUserPermission(@Context HttpServletRequest request,
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
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to engine "
					+ engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityEngineUtils.removeEngineUser(user, existingUserId, engineId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to engine "
					+ engineId + " without having proper access");
			classLogger.error("Failed to remove engine user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove engine user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed user {} from having access to engine {}", existingUserId, engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permissions for an engine, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermissions")
	public Response removeEngineUserPermissions(@Context HttpServletRequest request,
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
		ids = WebUtility.inputSQLSanitizer(ids);
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(
					"User is trying to remove users from having access to engine " + engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityEngineUtils.removeEngineUsers(user, ids, engineId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove users from having access to engine " + engineId
					+ " without having proper access");
			classLogger.error("Failed to remove engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the engine as being global (read only) for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setEngineGlobal")
	public Response setEngineGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		if (AbstractSecurityUtils.adminOnlyEngineSetPublic(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to set the engine " + engineId + logPublic + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityEngineUtils.setEngineGlobal(user, engineId, isPublic);
		} catch (IllegalAccessException e) {
			classLogger
					.warn("User is trying to set the engine " + engineId + logPublic + " without having proper access");
			classLogger.error("Failed to update engine global.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine global.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the engine {} {}", engineId, logPublic.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the engine as being discoverable for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setEngineDiscoverable")
	public Response setEngineDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		if (AbstractSecurityUtils.adminOnlyEngineSetPublic(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to set the engine " + engineId + logDiscoverable + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityEngineUtils.setEngineDiscoverable(user, engineId, isDiscoverable);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the engine " + engineId + logDiscoverable + " without having proper access");
			classLogger.error("Failed to update engine discoverable.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine discoverable.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the engine {} {}", engineId, logDiscoverable.trim());

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the engine visibility for the user to be seen
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setEngineVisibility")
	public Response setEngineVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String logVisible = visible ? " visible " : " not visible";

		try {
			SecurityEngineUtils.setEngineVisibility(user, engineId, visible);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the engine " + engineId + logVisible + " without having proper access");
			classLogger.error("Failed to update engine visibility.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine visibility.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the engine {} {}", engineId, logVisible.trim());

		return WebUtility.getResponse(true, 200);
	}

	/**
	 * Set the engine as favorited by the user
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setEngineFavorite")
	public Response setEngineFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityEngineUtils.setEngineFavorite(user, engineId, isFavorite);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to set the engine " + engineId + logFavorited + " without having proper access");
			classLogger.error("Failed to update engine favorite.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update engine favorite.", e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the engine {} {}", engineId, logFavorited.trim());

		return WebUtility.getResponse(true, 200);
	}

	/**
	 * Get users with no access to a given engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsersNoCredentials")
	public Response getEngineUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  invalid user session trying to access authorization resources");
			classLogger.error("Failed to retrieve engine users no credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean graphApi = Boolean
				.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// if not graph api
		// then we will look at our security db
		if (!graphApi) {
			try {
				List<Map<String, Object>> ret = SecurityEngineUtils.getEngineUsersNoCredentials(user, engineId,
						searchTerm, limit, offset);
				return WebUtility.getResponse(ret, 200);
			} catch (IllegalAccessException e) {
				classLogger.warn("User is trying to pull users for " + engineId
						+ " that do not have credentials without having proper access");
				classLogger.error("Failed to retrieve engine users no credentials.", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		String graphApiGroupId = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_groupId");

		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.getEngineUsers(request, user, engineId, searchTerm,
					graphApiGroupId, limit, offset, false);
			return WebUtility.getResponse(filteredUsers, 200);
		} catch (Exception e) {
			classLogger.error("Failed to retrieve engine users no credentials.", e);
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
	@Path("approveEngineUserAccessRequest")
	public Response approveEngineUserAccessRequest(@Context HttpServletRequest request,
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

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to approve user access to engine " + engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			SecurityEngineUtils.approveEngineUserAccessRequests(user, engineId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to grant user access to engine " + engineId + " without having proper access");
			classLogger.error("Failed to approve engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to approve engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access and added user permissions to engine {}", engineId);

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
	@Path("denyEngineUserAccessRequest")
	public Response denyEngineUserAccessRequest(@Context HttpServletRequest request,
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

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to deny user access to engine " + engineId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
		requestIds = WebUtility.inputSQLSanitizer(requestIds);
		try {
			SecurityEngineUtils.denyEngineUserAccessRequests(user, engineId, requestIds);
		} catch (Exception e) {
			classLogger.error("Failed to deny engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

}
