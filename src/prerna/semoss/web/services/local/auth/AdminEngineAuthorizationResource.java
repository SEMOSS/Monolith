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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.auth.utils.reactors.admin.AdminMyEnginesReactor;
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
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineEditUserPermissionRequest;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineGetAllUserEnginesRow;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineGetEnginesRequest;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineGetEnginesRow;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineGetUserEnginesNoCredentialsRow;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEngineGrantAllEnginesRequest;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResourceSchemas.AuthAdminEnginePermissionLevelRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineAddUserPermissionRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineApproveAccessRequestsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineDenyAccessRequestsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineRemoveUserPermissionRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineRemoveUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetDiscoverableRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetGlobalRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUsersNoCredentialsRow;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUsersPage;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/engine")
@PermitAll
public class AdminEngineAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminEngineAuthorizationResource.class);
	/**
	 * The form field for a model usage limit, as JSON, empty for no limit defined.
	 * An edit without it keeps the person's limit.
	 */
	private static final String USAGE_LIMIT = "usageLimit";

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
	@Operation(operationId = "getEnginesGet", summary = "List the engines on the server", description = "Returns every engine on the server, or those the permission user can access, with their metadata unless noMeta is set. The query string version of getEngines. Admins only.")
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminEngineGetEnginesRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEnginesGET(@Context HttpServletRequest request,
			@Parameter(description = "Engine ids to keep; repeat for each") @QueryParam("engineId") List<String> engineFilter, @Parameter(description = "Engine types to keep; repeat for each") @QueryParam("engineTypes") List<String> engineTypes,
			@Parameter(description = "Text to find in the engine id, name or display name") @QueryParam("filterWord") String searchTerm, @Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") Integer limit,
			@Parameter(description = "The rows to skip") @QueryParam("offset") Integer offset, @Parameter(description = "Metadata keys to add to each engine; repeat for each") @QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@Parameter(description = "True to leave out the engines' metadata") @QueryParam("noMeta") Boolean noMeta, @Parameter(description = "True to add the upvote counts") @QueryParam("userT") Boolean includeUserTracking,
			@Parameter(description = "Keep the engines this user id can access") @QueryParam("permissionUser") String permissionUser,
			@Parameter(description = "Permission levels (1 owner, 2 edit, 3 read only) the permission user must have; repeat for each") @QueryParam("effectivePermissions") List<Integer> effectivePermissions,
			@Parameter(description = "JSON-encoded list of creators to keep, each {\"id\": string, \"type\": string}") @QueryParam("createdBy") String createdBy, @Parameter(description = "True to keep only the engines the signed in user created") @QueryParam("createdByMe") Boolean createdByMe) {
		engineFilter = WebUtility.inputSanitizer(engineFilter);
		engineTypes = WebUtility.inputSanitizer(engineTypes);
		metaKeys = WebUtility.inputSanitizer(metaKeys);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all engines when not an admin");
			classLogger.error("Failed to retrieve engines.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminMyEnginesReactor reactor = new AdminMyEnginesReactor();
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
		if (engineFilter != null && !engineFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String engine : engineFilter) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(engine), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if (engineTypes != null && !engineTypes.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String eType : engineTypes) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(eType), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		if (metaKeys != null && !metaKeys.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for (String metaK : metaKeys) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(metaK), PixelDataType.CONST_STRING));
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
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}

	@POST
	@Produces("application/json")
	@Path("getEngines")
	@Operation(operationId = "getEngines", summary = "List the engines on the server", description = "Returns every engine on the server, or those the permission user can access, with their metadata unless noMeta is set. Takes the filters as a form, so metaFilters can be sent. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEngineGetEnginesRequest.class)))
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminEngineGetEnginesRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEnginesPOST(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all engines when not an admin");
			classLogger.error("Failed to retrieve engines.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminMyEnginesReactor reactor = new AdminMyEnginesReactor();
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
				struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if (parameterMap.containsKey("engineTypes") && parameterMap.get("engineTypes") != null
				&& parameterMap.get("engineTypes").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] engineTypes = parameterMap.get("engineTypes");
			for (String eType : engineTypes) {
				struct.add(new NounMetadata(eType, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
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
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}

	@GET
	@Path("/getAllUserEngines")
	@Produces("application/json")
	@Operation(operationId = "getAllUserEngines", summary = "List the engines a user has access to", description = "Returns the engines the user has a direct permission on. Admins only.")
	@ApiResponse(responseCode = "200", description = "The user's engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminEngineGetAllUserEnginesRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getAllUserEngines(@Context HttpServletRequest request, @Parameter(description = "The user id") @QueryParam("userId") String userId,
			@Parameter(description = "Engine types to keep; repeat for each") @QueryParam("engineTypes") List<String> engineTypes, @Parameter(description = "Text to find in the engine id, name or display name") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "The rows to skip") @QueryParam("offset") long offset) {
		userId = WebUtility.inputSQLSanitizer(userId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		engineTypes = WebUtility.inputSanitizer(engineTypes);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull the engines that user {} has access to when not an admin", userId);
			classLogger.error("Failed to retrieve all user engines.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserEngines(userId, engineTypes, searchTerm, limit, offset),
				200);
	}

	@GET
	@Path("/getUserEnginesNoCredentials")
	@Produces("application/json")
	@Operation(operationId = "getUserEnginesNoCredentials", summary = "List the engines a user has no access to", description = "Returns the engines the user has no direct permission on, to grant them new access. Admins only.")
	@ApiResponse(responseCode = "200", description = "The engines the user has no access to", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthAdminEngineGetUserEnginesNoCredentialsRow.class))))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUserEnginesNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The user id") @QueryParam("userId") String userId, @Parameter(description = "Engine types to keep; repeat for each") @QueryParam("engineTypes") List<String> engineTypes,
			@Parameter(description = "Text to find in the engine id, name or display name") @QueryParam("searchTerm") String searchTerm, @Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "The rows to skip") @QueryParam("offset") long offset) {
		userId = WebUtility.inputSQLSanitizer(userId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		engineTypes = WebUtility.inputSanitizer(engineTypes);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("Non-admin user tried to list the engines that user {} does not have access to", userId);
			classLogger.error("Failed to list the engines that user {} does not have access to", userId, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(
				adminUtils.getUserEnginesNoCredentials(userId, engineTypes, searchTerm, limit, offset), 200);
	}

	@POST
	@Path("/grantAllEngines")
	@Produces("application/json")
	@Operation(operationId = "grantAllEngines", summary = "Give a user a level on every engine", description = "Gives the user the permission level on every engine of the given types. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEngineGrantAllEnginesRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantAllEngines(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");
		List<String> engineTypes = null;
		if (form.getFirst("engineTypes") != null) {
			engineTypes = new Gson().fromJson(form.getFirst("engineTypes"), List.class);
			engineTypes = WebUtility.inputSanitizer(engineTypes);
		}

		String logETypes = (engineTypes == null || engineTypes.isEmpty()) ? "[ALL]"
				: ("[" + String.join(", ", engineTypes) + "]");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant all the engines of type {} to user {} when not an admin",
					logETypes, userId);
			classLogger.error("Failed to grant all engines.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllEngines(userId, permission, isAddNew, engineTypes, user);
		} catch (Exception e) {
			classLogger.error("Failed to grant all engines.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted all engines of type {} to {} with permission {}", logETypes, userId,
				permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Path("/grantNewUsersEngineAccess")
	@Produces("application/json")
	@Operation(operationId = "grantNewUsersEngineAccess", summary = "Give new users access to an engine", description = "Gives every new user the permission level on the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEnginePermissionLevelRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be given", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response grantNewUsersEngineAccess(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant engine to new users when not an admin");
			classLogger.error("Failed to grant new users engine access.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersEngineAccess(engineId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to grant new users engine access.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted engine {} to new users with permission {}", engineId, permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the engine users and their permissions. On a model, each row has its
	 * {@code usage_limit}.
	 *
	 * @param request
	 * @param engineId
	 * @param userId
	 * @param permission
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsers")
	@Operation(operationId = "getEngineUsers", summary = "List the people with access to an engine", description = "Returns a page of the people with access to the engine, with how many match. On a model, each has their usage_limit. Admins only.")
	@ApiResponse(responseCode = "200", description = "A page of the engine's people", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUsersPage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEngineUsers(@Context HttpServletRequest request, @Parameter(description = "The engine id") @QueryParam("engineId") String engineId,
			@Parameter(description = "Text to find in the people's id, name, username or email, used when searchTerm is not given") @QueryParam("userId") String userId, @Parameter(description = "Text to find in the people's id, name, username or email") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The permission level to keep: OWNER, EDIT or READ_ONLY") @QueryParam("permission") String permission, @Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "The rows to skip") @QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
		userId = WebUtility.inputSQLSanitizer(userId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		permission = WebUtility.inputSanitizer(permission);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull all the users who use engine {} when not an admin", engineId);
			classLogger.error("Failed to retrieve engine users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Map<String, Object> ret = new HashMap<String, Object>();
		String searchParam = searchTerm != null ? searchTerm : userId;
		List<Map<String, Object>> members = adminUtils.getEngineUsers(engineId, searchParam, permission, limit, offset);
		long totalMembers = adminUtils.getEngineUsersCount(engineId, searchParam, permission);
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add a user to a engine. A model's usage limit is sent as {@code usageLimit}
	 * (JSON, empty for no limit defined); without it the person gets no limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermission")
	@Operation(operationId = "addEngineUserPermission", summary = "Add a person to an engine", description = "Gives a person a permission level on the engine, and on a model optionally a usage limit. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineAddUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The person cannot be added, such as when they already have access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user {} to engine {} when not an admin", newUserId, engineId);
			classLogger.error("Failed to add engine user permission.", e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}

		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			adminUtils.addEngineUser(newUserId, engineId, permission, user, endDate,
					SecurityModelUsageLimitUtils.readJson(form.getFirst(USAGE_LIMIT)));
		} catch (Exception e) {
			classLogger.error("Failed to add engine user permission.", e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}

		// log the operation
		classLogger.info("User has added user {} to engine {} with permission {}", newUserId, engineId, permission);
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to a engine. Each row of {@code userpermissions}
	 * can carry a model's usage limit as {@code usageLimit}; a row without it gives
	 * no limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermissions")
	@Operation(operationId = "addEngineUserPermissions", summary = "Add people to an engine", description = "Gives several people permission levels on the engine, in one batch. People picked from the Microsoft directory are added as users first. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The people cannot be added, such as when some already have access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user permission to engine {} when not an admin", engineId);
			classLogger.error("Failed to add engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions in bulk
		List<Map<String, Object>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			// users picked from the Microsoft directory may not be in the security db yet
			if (MicrosoftGraphUserLookup.isEnabled()) {
				MicrosoftGraphUserLookup.addMissingUsers(user, permission);
			}
			adminUtils.addEngineUserPermissions(engineId, permission, user);
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
	 * Add all users to a engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(operationId = "addAllUsers", summary = "Add every user to an engine", description = "Gives every user on the server the permission level on the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEnginePermissionLevelRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The users cannot be added", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add all users to engine {} when not an admin", engineId);
			classLogger.error("Failed to add all users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addAllEngineUsers(engineId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to add all users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to engine {} with permission {}", engineId, permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for a engine. A model's usage limit is sent as
	 * {@code usageLimit} (JSON, empty for no limit defined); a form without it,
	 * such as a change of level only, leaves the limit as it is.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermission")
	@Operation(operationId = "editEngineUserPermission", summary = "Change a person's access to an engine", description = "Changes a person's permission level on the engine, and on a model their usage limit when usageLimit is sent. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEngineEditUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update engine user permission.", e);
			classLogger.warn("User is trying to edit user {} permissions for engine {} when not an admin",
					existingUserId, engineId);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}

		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			if (form.containsKey(USAGE_LIMIT)) {
				adminUtils.editEngineUserPermission(existingUserId, engineId, newPermission, user, endDate,
						SecurityModelUsageLimitUtils.readJson(form.getFirst(USAGE_LIMIT)));
			} else {
				adminUtils.editEngineUserPermission(existingUserId, engineId, newPermission, user, endDate);
			}
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
	 * Edit user permission for a engine, in bulk. Each row of
	 * {@code userpermissions} can carry a model's usage limit as
	 * {@code usageLimit}; a row without it keeps the person's limit, and a row
	 * whose {@code usageLimit} is null removes it.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermissions")
	@Operation(operationId = "editEngineUserPermissions", summary = "Change people's access to an engine", description = "Changes several people's permission levels on the engine, in one batch. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be changed, such as when some people have no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response editEngineUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		SecurityAdminUtils adminUtils = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update engine user permissions.", e);
			classLogger.warn("User is trying to edit user access permissions for engine {} when not an admin",
					engineId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			adminUtils.editEngineUserPermissions(engineId, requests, user);
		} catch (Exception e) {
			classLogger.error("Failed to update engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user access permissions to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * update all user's permission level to new permission level for a engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateEngineUserPermissions")
	@Operation(operationId = "updateEngineUserPermissions", summary = "Change everyone's access to an engine", description = "Changes the permission level of everyone with access to the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthAdminEnginePermissionLevelRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response updateEngineUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update engine user permissions.", e);
			classLogger.warn("User is trying to edit user permissions for engine {} when not an admin", engineId);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.updateEngineUserPermissions(engineId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to update engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permissions to engine {} with level {}", engineId, newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permission for a engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermission")
	@Operation(operationId = "removeEngineUserPermission", summary = "Remove a person from an engine", description = "Removes a person's access to the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineRemoveUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The person cannot be removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeEngineUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user {} from having access to engine {} when not an admin",
					existingUserId, engineId);
			classLogger.error("Failed to remove engine user permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.removeEngineUser(existingUserId, engineId);
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
	 * Remove user permissions for a engine, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermissions")
	@Operation(operationId = "removeEngineUserPermissions", summary = "Remove people from an engine", description = "Removes several people's access to the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineRemoveUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The people cannot be removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response removeEngineUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove usersfrom having access to engine {} when not an admin",
					engineId);
			classLogger.error("Failed to remove engine user permissions.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Gson gson = new Gson();
		List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);
		ids = WebUtility.inputSQLSanitizer(ids);
		try {
			adminUtils.removeEngineUsers(ids, engineId);
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

	@POST
	@Produces("application/json")
	@Path("setEngineGlobal")
	@Operation(operationId = "setEngineGlobal", summary = "Make an engine public or private", description = "Sets whether everyone on the server can use the engine. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "The engine could not be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setEngineGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the engine {}{} when not an admin", engineId, logPublic);
			classLogger.error("Failed to update engine global.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineGlobal(engineId, isPublic);
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
	@Operation(operationId = "setEngineDiscoverable", summary = "Make an engine discoverable or not", description = "Sets whether people without access can find the engine and request access. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetDiscoverableRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "The engine could not be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response setEngineDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the engine {}{} when not an admin", engineId, logDiscoverable);
			classLogger.error("Failed to update engine discoverable.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineDiscoverable(engineId, isDiscoverable);
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
	 * Get users with no access to a given engine
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsersNoCredentials")
	@Operation(operationId = "getEngineUsersNoCredentials", summary = "Find people to add to an engine", description = "Returns people with no access to the engine, from the security database or, when the Microsoft directory is used, from the directory. Admins only.")
	@ApiResponse(responseCode = "200", description = "The people with no access", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthEngineUsersNoCredentialsRow.class))))
	@ApiResponse(responseCode = "400", description = "The directory search needs a Microsoft login, or it failed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEngineUsersNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The engine id") @QueryParam("engineId") String engineId, @Parameter(description = "Text to find in the people's id, name, username or email") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "The rows to skip") @QueryParam("offset") long offset,
			@Parameter(description = "True to search the Microsoft directory instead of the security database, when it is enabled") @QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		engineId = WebUtility.inputSanitizer(engineId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all users when not an admin");
			classLogger.error("Failed to retrieve engine users no credentials.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// when the directory is not used
		// then we will look at our security db
		if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
			List<Map<String, Object>> ret = adminUtils.getEngineUsersNoCredentials(engineId, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		}

		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.getEngineUsers(request, user, engineId, searchTerm,
					limit, offset, true);
			return WebUtility.getResponse(filteredUsers, 200);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to retrieve engine users no credentials.", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
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
	@Path("approveEngineUserAccessRequest")
	@Operation(operationId = "approveEngineUserAccessRequest", summary = "Approve access requests to an engine", description = "Approves the access requests, giving each person the permission level in their request. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineApproveAccessRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The requests cannot be approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response approveEngineUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to approve user request for permission to engine {} when not an admin",
					engineId);
			classLogger.error("Failed to approve engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveEngineUserAccessRequests(userId, userType, engineId, requests, endDate);
		} catch (Exception e) {
			classLogger.error("Failed to approve engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access requests and added user permissions to engine {}", engineId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
	@Path("denyEngineUserAccessRequest")
	@Operation(operationId = "denyEngineUserAccessRequest", summary = "Deny access requests to an engine", description = "Denies the access requests. Admins only.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineDenyAccessRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The requests cannot be denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user, or the user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response denyEngineUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to deny user request for permission to engine {} when not an admin",
					engineId);
			classLogger.error("Failed to deny engine user access request.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
		requestIds = WebUtility.inputSQLSanitizer(requestIds);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyEngineUserAccessRequests(userId, userType, engineId, requestIds);
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
