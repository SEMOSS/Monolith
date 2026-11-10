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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.om.Insight;
import prerna.reactor.security.MyEnginesReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.openapi.CommonSchemas.Success;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineAddUserPermissionRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineApproveAccessRequestsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineDenyAccessRequestsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineEditUserPermissionRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineGetEnginesRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineGetEnginesRow;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineGetUserEnginePermission;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineRemoveUserPermissionRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineRemoveUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetDiscoverableRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetFavoriteRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetGlobalRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineSetVisibilityRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUnexpectedError;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUserPermissionsRequest;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUsersNoCredentialsRow;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResourceSchemas.AuthEngineUsersPage;
import prerna.web.services.util.WebUtility;

@Path("/auth/engine")
@PermitAll
public class EngineAuthorizationResource {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
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
	@Operation(operationId = "getEnginesGet", summary = "List the engines the user can access", description = "Returns the engines the user can access, with their metadata unless noMeta is set. The query string version of getEngines.")
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthEngineGetEnginesRow.class))))
	@ApiResponse(responseCode = "400", description = "The filters are not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response getEngines(@Context HttpServletRequest request, @Parameter(description = "Engine ids to keep; repeat for each") @QueryParam("engineId") List<String> engineFilter,
			@Parameter(description = "Engine types to keep; repeat for each") @QueryParam("engineTypes") List<String> engineTypes, @Parameter(description = "Text to find in the engine id, name or display name") @QueryParam("filterWord") String searchTerm,
			@Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") Integer limit, @Parameter(description = "The rows to skip") @QueryParam("offset") Integer offset,
			@Parameter(description = "True to return only the engines the user favorited") @QueryParam("onlyFavorites") Boolean favoritesOnly, @Parameter(description = "Metadata keys to add to each engine; repeat for each") @QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@Parameter(description = "True to leave out the engines' metadata") @QueryParam("noMeta") Boolean noMeta, @Parameter(description = "True to add the upvote counts") @QueryParam("userT") Boolean includeUserTracking) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> engines = WebUtility.inputSanitizer(engineFilter);
		List<String> types = WebUtility.inputSanitizer(engineTypes);
		List<String> keys = WebUtility.inputSanitizer(metaKeys);
		return ResourceUtility.respond(request, "list the engines they can access", user -> {
			MyEnginesReactor reactor = new MyEnginesReactor();
			reactor.In();
			Insight temp = new Insight();
			temp.setUser(user);
			reactor.setInsight(temp);
			String filterWord = WebUtility.inputSQLSanitizer(term);
			if (filterWord != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(filterWord, PixelDataType.CONST_STRING));
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
			if (engines != null && !engines.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String engine : engines) {
					struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
			}
			if (types != null && !types.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String eType : types) {
					struct.add(new NounMetadata(eType, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
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

			return reactor.execute().getValue();
		});
	}

	@POST
	@Produces("application/json")
	@Path("getEngines")
	@Operation(operationId = "getEngines", summary = "List the engines the user can access", description = "Returns the engines the user can access, with their metadata unless noMeta is set. Takes the filters as a form, so metaFilters can be sent.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineGetEnginesRequest.class)))
	@ApiResponse(responseCode = "200", description = "The engines", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthEngineGetEnginesRow.class))))
	@ApiResponse(responseCode = "400", description = "The filters are not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response getEnginesPOST(@Context HttpServletRequest request) {
		return ResourceUtility.respond(request, "list the engines they can access", user -> {
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

			return reactor.execute().getValue();
		});
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
	@Operation(operationId = "getUserEnginePermission", summary = "Read the user's permission on an engine", description = "Returns the user's best permission on the engine, or DISCOVERABLE when they have none but the engine is discoverable.")
	@ApiResponse(responseCode = "200", description = "The user's permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineGetUserEnginePermission.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user has no access to the engine", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response getUserEnginePermission(@Context HttpServletRequest request,
			@Parameter(description = "The engine id") @QueryParam("engineId") String engineId) {
		String engine = WebUtility.inputSanitizer(engineId);
		String action = "read their permission on engine " + engine;
		return ResourceUtility.respond(request, action, user -> {
			String permission = SecurityEngineUtils.getActualUserEnginePermission(user, engine);
			if (permission == null) {
				// are you discoverable?
				if (SecurityEngineUtils.engineIsDiscoverable(engine)) {
					permission = "DISCOVERABLE";
				} else {
					throw new IllegalAccessException("User does not have access to this engine");
				}
			}

			Map<String, String> ret = new HashMap<>();
			ret.put("permission", permission);
			return ret;
		});
	}

	/**
	 * Get the engine users and their permissions. On a model, each row has its
	 * {@code usage_limit}.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsers")
	@Operation(operationId = "getEngineUsers", summary = "List the people with access to an engine", description = "Returns a page of the people with access to the engine, with how many match. On a model, each has their usage_limit.")
	@ApiResponse(responseCode = "200", description = "A page of the engine's people", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUsersPage.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user cannot view the engine", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response getEngineUsers(@Context HttpServletRequest request, @Parameter(description = "The engine id") @QueryParam("engineId") String engineId,
			@Parameter(description = "Text to find in the people's id, name, username or email, used when searchTerm is not given") @QueryParam("userId") String userId, @Parameter(description = "Text to find in the people's id, name, username or email") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The permission level to keep: OWNER, EDIT or READ_ONLY") @QueryParam("permission") String permission, @Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit,
			@Parameter(description = "The rows to skip") @QueryParam("offset") long offset) {
		String engine = WebUtility.inputSanitizer(engineId);
		String member = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String level = WebUtility.inputSanitizer(permission);
		String searchParam = term != null ? term : member;
		return ResourceUtility.respond(request, "list the users of engine " + engine, user -> {
			List<Map<String, Object>> members = SecurityEngineUtils.getEngineUsers(user, engine, searchParam, level,
					limit, offset);
			long totalMembers = SecurityEngineUtils.getEngineUsersCount(user, engine, searchParam, level);
			Map<String, Object> ret = new HashMap<>();
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
			return ret;
		});
	}

	/**
	 * Add a user to an engine. A model's usage limit is sent as {@code usageLimit}
	 * (JSON, empty for no limit defined); without it the person gets no limit. Only
	 * the model's owners give a limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermission")
	@Operation(operationId = "addEngineUserPermission", summary = "Add a person to an engine", description = "Gives a person a permission level on the engine, and on a model optionally a usage limit, which only the model's owners give.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineAddUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The person cannot be added, such as when they already have access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response addEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		String usageLimit = form.getFirst(USAGE_LIMIT);
		return ResourceUtility.respond(request, "add user " + newUserId + " to engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityEngineUtils.addEngineUser(user, newUserId, engineId, permission, endDate,
					SecurityModelUsageLimitUtils.readJson(usageLimit));
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Add user permissions in bulk to a engine. Each row of {@code userpermissions}
	 * can carry a model's usage limit as {@code usageLimit}, which only the model's
	 * owners give; a row without it gives no limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermissions")
	@Operation(operationId = "addEngineUserPermissions", summary = "Add people to an engine", description = "Gives several people permission levels on the engine, in one batch. People picked from the Microsoft directory are added as users first.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The people cannot be added, such as when some already have access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response addEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		return ResourceUtility.respond(request, "add users to engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// adding user permissions in bulk
			List<Map<String, Object>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
			// users picked from the Microsoft directory may not be in the security db yet
			if (MicrosoftGraphUserLookup.isEnabled()) {
				MicrosoftGraphUserLookup.addMissingUsers(user, permission);
			}

			// now add the permission
			SecurityEngineUtils.addEngineUserPermissions(user, engineId, permission);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit user permission for an engine. A model's usage limit is sent as
	 * {@code usageLimit} (JSON, empty for no limit defined); a form without it,
	 * such as a change of level only, leaves the limit as it is. Only the model's
	 * owners change a limit.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermission")
	@Operation(operationId = "editEngineUserPermission", summary = "Change a person's access to an engine", description = "Changes a person's permission level on the engine, and on a model their usage limit when usageLimit is sent.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineEditUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response editEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String existingUserType = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		boolean usageLimitSent = form.containsKey(USAGE_LIMIT);
		String usageLimit = form.getFirst(USAGE_LIMIT);
		return ResourceUtility.respond(request, "edit user " + existingUserId + " on engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			if (usageLimitSent) {
				SecurityEngineUtils.editEngineUserPermission(user, existingUserId, existingUserType, engineId,
						newPermission, endDate, SecurityModelUsageLimitUtils.readJson(usageLimit));
			} else {
				SecurityEngineUtils.editEngineUserPermission(user, existingUserId, existingUserType, engineId,
						newPermission, endDate);
			}
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit user permission for an engine, in bulk. Each row of
	 * {@code userpermissions} can carry a model's usage limit as
	 * {@code usageLimit}, which only the model's owners change; a row without it
	 * keeps the person's limit, and a row whose {@code usageLimit} is null removes
	 * it.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermissions")
	@Operation(operationId = "editEngineUserPermissions", summary = "Change people's access to an engine", description = "Changes several people's permission levels on the engine, in one batch.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The access cannot be changed, such as when some people have no access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response editEngineUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		return ResourceUtility.respond(request, "edit user permissions on engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
			SecurityEngineUtils.editEngineUserPermissions(user, engineId, requests);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
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
	@Operation(operationId = "removeEngineUserPermission", summary = "Remove a person from an engine", description = "Removes a person's access to the engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineRemoveUserPermissionRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The person cannot be removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response removeEngineUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String action = "remove user " + existingUserId + " from engine " + engineId;
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityEngineUtils.removeEngineUser(user, existingUserId, engineId);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
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
	@Operation(operationId = "removeEngineUserPermissions", summary = "Remove people from an engine", description = "Removes several people's access to the engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineRemoveUserPermissionsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The people cannot be removed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response removeEngineUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		return ResourceUtility.respond(request, "remove users from engine " + engineId, user -> {
			List<String> ids = new Gson().fromJson(form.getFirst("ids"), List.class);
			ids = WebUtility.inputSQLSanitizer(ids);
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityEngineUtils.removeEngineUsers(user, ids, engineId);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
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
	@Operation(operationId = "setEngineGlobal", summary = "Make an engine public or private", description = "Sets whether everyone on the server can use the engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetGlobalRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The engine cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response setEngineGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String action = "make engine " + engineId + (isPublic ? " public" : " private");
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineSetPublic(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			SecurityEngineUtils.setEngineGlobal(user, engineId, isPublic);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
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
	@Operation(operationId = "setEngineDiscoverable", summary = "Make an engine discoverable or not", description = "Sets whether people without access can find the engine and request access.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetDiscoverableRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The engine cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response setEngineDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String action = "make engine " + engineId + (isDiscoverable ? " discoverable" : " not discoverable");
		return ResourceUtility.respond(request, action, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineSetPublic(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException("User session is invalid");
			}
			SecurityEngineUtils.setEngineDiscoverable(user, engineId, isDiscoverable);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
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
	@Operation(operationId = "setEngineVisibility", summary = "Show or hide an engine for the user", description = "Sets whether the engine shows in the user's own engine lists.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetVisibilityRequest.class)))
	@ApiResponse(responseCode = "200", description = "Always true", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The engine cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user has no access to the engine", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response setEngineVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String action = (visible ? "show" : "hide") + " engine " + engineId;
		return ResourceUtility.respond(request, action, user -> {
			SecurityEngineUtils.setEngineVisibility(user, engineId, visible);
			return true;
		});
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
	@Operation(operationId = "setEngineFavorite", summary = "Favorite or unfavorite an engine", description = "Sets whether the user favorited the engine.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineSetFavoriteRequest.class)))
	@ApiResponse(responseCode = "200", description = "Always true", content = @Content(mediaType = "application/json", schema = @Schema(types = "boolean", description = "Always true")))
	@ApiResponse(responseCode = "400", description = "The engine cannot be changed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user has no access to the engine", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response setEngineFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String action = (isFavorite ? "favorite" : "unfavorite") + " engine " + engineId;
		return ResourceUtility.respond(request, action, user -> {
			SecurityEngineUtils.setEngineFavorite(user, engineId, isFavorite);
			return true;
		});
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
	@Operation(operationId = "getEngineUsersNoCredentials", summary = "Find people to add to an engine", description = "Returns people with no access to the engine, from the security database or, when the Microsoft directory is used, from the directory.")
	@ApiResponse(responseCode = "200", description = "The people with no access", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AuthEngineUsersNoCredentialsRow.class))))
	@ApiResponse(responseCode = "400", description = "The search failed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user cannot view the engine, or the directory search needs a Microsoft login", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response getEngineUsersNoCredentials(@Context HttpServletRequest request,
			@Parameter(description = "The engine id") @QueryParam("engineId") String engineId, @Parameter(description = "Text to find in the people's id, name, username or email") @QueryParam("searchTerm") String searchTerm,
			@Parameter(description = "The most rows to return; 0 or less for all") @QueryParam("limit") long limit, @Parameter(description = "The rows to skip") @QueryParam("offset") long offset,
			@Parameter(description = "True to search the Microsoft directory instead of the security database, when it is enabled") @QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		String engine = WebUtility.inputSanitizer(engineId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respond(request, "find people to add to engine " + engine, user -> {
			// when the directory is not used
			// then we will look at our security db
			if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
				return SecurityEngineUtils.getEngineUsersNoCredentials(user, engine, term, limit, offset);
			}
			return MsGraphUtility.getEngineUsers(request, user, engine, term, limit, offset, false);
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
	@Path("approveEngineUserAccessRequest")
	@Operation(operationId = "approveEngineUserAccessRequest", summary = "Approve access requests to an engine", description = "Approves the access requests, giving each person the permission level in their request.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineApproveAccessRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The requests cannot be approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response approveEngineUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respond(request, "approve access requests for engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// adding user permissions and updating user access requests in bulk
			List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
			SecurityEngineUtils.approveEngineUserAccessRequests(user, engineId, requests, endDate);
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
	@Path("denyEngineUserAccessRequest")
	@Operation(operationId = "denyEngineUserAccessRequest", summary = "Deny access requests to an engine", description = "Denies the access requests.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = AuthEngineDenyAccessRequestsRequest.class)))
	@ApiResponse(responseCode = "200", description = "The change was made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Success.class)))
	@ApiResponse(responseCode = "400", description = "The requests cannot be denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not change the engine's access, or only admins may on this server", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEngineUnexpectedError.class)))
	public Response denyEngineUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		return ResourceUtility.respond(request, "deny access requests for engine " + engineId, user -> {
			if (AbstractSecurityUtils.adminOnlyEngineAddAccess(engineId) && !SecurityAdminUtils.userIsAdmin(user)) {
				throw new IllegalAccessException(ADMINS_ONLY);
			}
			// updating user access requests in bulk
			List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
			requestIds = WebUtility.inputSQLSanitizer(requestIds);
			SecurityEngineUtils.denyEngineUserAccessRequests(user, engineId, requestIds);
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

}
