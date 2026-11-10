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

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import prerna.semoss.web.services.local.auth.AdminModelUsageResourceSchemas.AuthAdminModelUsageLimit;

/**
 * The requests and responses of {@link EngineAuthorizationResource}, for its
 * OpenAPI annotations. {@link AdminEngineAuthorizationResource} shares the ones
 * whose shape is the same.
 */
public final class EngineAuthorizationResourceSchemas {

	private EngineAuthorizationResourceSchemas() {
	}

	private static final String USERPERMISSIONS_DESCRIPTION = "JSON-encoded list of the people's access, each {\"userid\": string, \"type\": string, \"permission\": \"OWNER\" | \"EDIT\" | \"READ_ONLY\", \"usageLimit\"?: usage limit}";

	/*
	 * Errors
	 */

	@Schema(description = "An unexpected failure")
	public record AuthEngineUnexpectedError(
			@Schema(description = "A message to show the user", requiredMode = REQUIRED) String errorMessage,
			@Schema(description = "The technical cause of the failure") String techErrorMessage) {
	}

	/*
	 * Engine lists
	 */

	@Schema(description = "An engine the user can access. Unless noMeta is set, the engine's metadata values are added under their metadata keys, as a string or a list of strings when the key has several values, so other keys can appear", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthEngineGetEnginesRow(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The engine name") String engine_name,
			@Schema(description = "The engine display name") String engine_display_name,
			@Schema(description = "The engine type, such as DATABASE, MODEL, STORAGE or VECTOR") String engine_type,
			@Schema(description = "The engine subtype") String engine_subtype,
			@Schema(description = "The engine cost") String engine_cost,
			@Schema(description = "Whether the engine is discoverable") Boolean engine_discoverable,
			@Schema(description = "Whether the engine is public") Boolean engine_global,
			@Schema(description = "The tool app of the engine") String engine_tool_app,
			@Schema(description = "The id of the user who created the engine") String engine_created_by,
			@Schema(description = "The login type of the user who created the engine") String engine_created_by_type,
			@Schema(description = "When the engine was created") String engine_date_created,
			@Schema(description = "The display name, or the name when there is none, in lower case") String low_engine_name,
			@Schema(description = "The user's own permission level (1 owner, 2 edit, 3 read only)", types = "integer") Integer engine_user_permission,
			@Schema(description = "The best permission level the user gets through a group (1 owner, 2 edit, 3 read only)", types = "integer") Integer engine_group_permission,
			@Schema(description = "1 when the user favorited the engine, otherwise 0", types = "integer", requiredMode = REQUIRED) Integer engine_favorite,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "Legacy alias of engine_name") String app_name,
			@Schema(description = "Legacy alias of engine_type") String app_type,
			@Schema(description = "Legacy alias of engine_subtype") String app_subtype,
			@Schema(description = "Legacy alias of engine_cost") String app_cost,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String database_id,
			@Schema(description = "Legacy alias of engine_name") String database_name,
			@Schema(description = "Legacy alias of engine_type") String database_type,
			@Schema(description = "Legacy alias of engine_discoverable") Boolean database_discoverable,
			@Schema(description = "The engine's description metadata") String engine_description,
			@Schema(description = "The best of the user's own and group permission levels (1 owner, 2 edit, 3 read only); absent for a public engine the user has no grant on", types = "integer") Integer permission,
			@Schema(description = "A model's capabilities, from its model metadata; only on models, unless noMeta is set", types = "object") Map<String, Object> capabilities,
			@Schema(description = "How many upvotes the engine has; only with userT and user tracking enabled", types = "integer") Integer upvotes,
			@Schema(description = "Whether the user upvoted the engine; only with userT and user tracking enabled") Boolean hasUpvoted) {
	}

	@Schema(description = "Filters for the engines the user can access")
	public record AuthEngineGetEnginesRequest(
			@Schema(description = "Text to find in the engine id, name or display name") String filterWord,
			@Schema(description = "The most engines to return", types = "integer") Integer limit,
			@Schema(description = "The engines to skip", types = "integer") Integer offset,
			@Schema(description = "Engine ids to keep; repeat the field for each") List<String> engineId,
			@Schema(description = "Engine types to keep; repeat the field for each") List<String> engineTypes,
			@Schema(description = "Metadata keys to add to each engine; repeat the field for each") List<String> metaKeys,
			@Schema(description = "JSON-encoded object of metadata key to value(s) the engines must have") String metaFilters,
			@Schema(description = "\"true\" to leave out the engines' metadata") Boolean noMeta,
			@Schema(description = "\"true\" to add the upvote counts") Boolean userT) {
	}

	@Schema(description = "The user's permission on an engine")
	public record AuthEngineGetUserEnginePermission(
			@Schema(description = "The user's best permission, or DISCOVERABLE when they have none but the engine is discoverable", allowableValues = {
					"OWNER", "EDIT", "READ_ONLY", "DISCOVERABLE" }, requiredMode = REQUIRED) String permission) {
	}

	/*
	 * Engine members
	 */

	@Schema(description = "A page of the people with access to an engine")
	public record AuthEngineUsersPage(
			@Schema(description = "How many people match, over all pages", types = "integer", format = "int64", requiredMode = REQUIRED) long totalMembers,
			@Schema(description = "The people on this page", requiredMode = REQUIRED) List<AuthEngineUsersMember> members) {
	}

	@Schema(description = "A person with access to an engine")
	public record AuthEngineUsersMember(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's login type") String type,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email,
			@Schema(description = "The permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }) String permission,
			@Schema(description = "When the access ends; absent for never") String end_date,
			@Schema(description = "The id of the user who gave the access") String permission_granted_by,
			@Schema(description = "The login type of the user who gave the access") String permission_granted_by_type,
			@Schema(description = "When the access was given") String date_added,
			@Schema(description = "The person's model usage limit on the engine; absent when no limit is defined") AuthAdminModelUsageLimit usage_limit) {
	}

	@Schema(description = "A person with no access to an engine. Found in the Microsoft directory, they also carry the Microsoft Graph user's fields", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthEngineUsersNoCredentialsRow(
			@Schema(description = "The user id") String id,
			@Schema(description = "The user's login type") String type,
			@Schema(description = "The user's username") String username,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email) {
	}

	/*
	 * Changes to members
	 */

	@Schema(description = "A person to add to an engine")
	public record AuthEngineAddUserPermissionRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The id of the user to add", requiredMode = REQUIRED) String id,
			@Schema(description = "The permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }, requiredMode = REQUIRED) String permission,
			@Schema(description = "JSON-encoded model usage limit for a model engine; empty or absent for no limit. Outside the admin endpoints, only the model's owners give one") String usageLimit) {
	}

	@Schema(description = "People's access to an engine, in bulk")
	public record AuthEngineUserPermissionsRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = USERPERMISSIONS_DESCRIPTION + ". On an edit, a row without usageLimit keeps the person's limit and a null one removes it", requiredMode = REQUIRED) String userpermissions) {
	}

	@Schema(description = "A change to a person's access to an engine")
	public record AuthEngineEditUserPermissionRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The id of the user", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's login type, for the notification") String type,
			@Schema(description = "The new permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }, requiredMode = REQUIRED) String permission,
			@Schema(description = "JSON-encoded model usage limit for a model engine, empty for no limit; absent keeps the person's limit. Only the model's owners change it") String usageLimit) {
	}

	@Schema(description = "A person to remove from an engine")
	public record AuthEngineRemoveUserPermissionRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The id of the user to remove", requiredMode = REQUIRED) String id) {
	}

	@Schema(description = "People to remove from an engine")
	public record AuthEngineRemoveUserPermissionsRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "JSON-encoded list of the ids of the users to remove", requiredMode = REQUIRED) String ids) {
	}

	@Schema(description = "Approvals of access requests to an engine")
	public record AuthEngineApproveAccessRequestsRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "JSON-encoded list of the requests to approve, each {\"requestid\": string, \"userid\": string, \"type\": string, \"permission\": \"OWNER\" | \"EDIT\" | \"READ_ONLY\"}", requiredMode = REQUIRED) String requests) {
	}

	@Schema(description = "Access requests to an engine to deny")
	public record AuthEngineDenyAccessRequestsRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "JSON-encoded list of the ids of the requests to deny", requiredMode = REQUIRED) String requestIds) {
	}

	/*
	 * Engine settings
	 */

	@Schema(description = "Whether an engine is public")
	public record AuthEngineSetGlobalRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@JsonProperty("public") @Schema(name = "public", description = "\"true\" to make the engine public, anything else to make it private") Boolean isPublic) {
	}

	@Schema(description = "Whether an engine is discoverable")
	public record AuthEngineSetDiscoverableRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "\"true\" to make the engine discoverable, anything else to hide it from people without access") Boolean discoverable) {
	}

	@Schema(description = "Whether the user sees an engine")
	public record AuthEngineSetVisibilityRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "\"true\" to show the engine in the user's lists, anything else to hide it") Boolean visibility) {
	}

	@Schema(description = "Whether the user favorited an engine")
	public record AuthEngineSetFavoriteRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "\"true\" to favorite the engine, anything else to unfavorite it") Boolean isFavorite) {
	}
}
