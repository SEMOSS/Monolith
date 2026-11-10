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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link AdminEngineAuthorizationResource} whose
 * shape differs from {@link EngineAuthorizationResourceSchemas}, for its OpenAPI
 * annotations.
 */
public final class AdminEngineAuthorizationResourceSchemas {

	private AdminEngineAuthorizationResourceSchemas() {
	}

	@Schema(description = "An engine on the server. Unless noMeta is set, the engine's metadata values are added under their metadata keys, as a string or a list of strings when the key has several values, so other keys can appear", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthAdminEngineGetEnginesRow(
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
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String database_id,
			@Schema(description = "Legacy alias of engine_name") String database_name,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "Legacy alias of engine_name") String app_name,
			@Schema(description = "Legacy alias of engine_global") Boolean app_global,
			@Schema(description = "How many upvotes the engine has; only with userT", types = "integer") Integer upvotes,
			@Schema(description = "Whether the admin upvoted the engine; only with userT") Boolean hasUpvoted) {
	}

	@Schema(description = "Filters for the engines on the server")
	public record AuthAdminEngineGetEnginesRequest(
			@Schema(description = "Text to find in the engine id, name or display name") String filterWord,
			@Schema(description = "The most engines to return", types = "integer") Integer limit,
			@Schema(description = "The engines to skip", types = "integer") Integer offset,
			@Schema(description = "Engine ids to keep; repeat the field for each") List<String> engineId,
			@Schema(description = "Engine types to keep; repeat the field for each") List<String> engineTypes,
			@Schema(description = "Metadata keys to add to each engine; repeat the field for each") List<String> metaKeys,
			@Schema(description = "JSON-encoded object of metadata key to value(s) the engines must have") String metaFilters,
			@Schema(description = "\"true\" to leave out the engines' metadata") Boolean noMeta,
			@Schema(description = "\"true\" to add the upvote counts") Boolean userT,
			@Schema(description = "Keep the engines this user id can access") String permissionUser,
			@Schema(description = "Permission levels (1 owner, 2 edit, 3 read only) the permission user must have; repeat the field for each") List<Integer> effectivePermissions,
			@Schema(description = "JSON-encoded list of creators to keep, each {\"id\": string, \"type\": string}") String createdBy,
			@Schema(description = "\"true\" to keep only the engines the admin created") Boolean createdByMe) {
	}

	@Schema(description = "An engine a user has direct access to")
	public record AuthAdminEngineGetAllUserEnginesRow(
			@Schema(description = "The user id", requiredMode = REQUIRED) String user_id,
			@Schema(description = "Legacy alias of engine_permission", types = { "string", "integer" }) Object app_permission,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "Legacy alias of engine_name") String app_name,
			@Schema(description = "Legacy alias of engine_display_name") String app_display_name,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The engine name") String engine_name,
			@Schema(description = "The engine display name") String engine_display_name,
			@Schema(description = "The engine type") String engine_type,
			@Schema(description = "The engine subtype") String engine_subtype,
			@Schema(description = "The user's permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }) String engine_permission,
			@Schema(description = "The engine display name in lower case") String low_engine_name) {
	}

	@Schema(description = "An engine a user has no direct access to")
	public record AuthAdminEngineGetUserEnginesNoCredentialsRow(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The engine name") String engine_name,
			@Schema(description = "The engine display name") String engine_display_name,
			@Schema(description = "The engine type") String engine_type,
			@Schema(description = "The engine subtype") String engine_subtype,
			@Schema(description = "The engine display name in lower case") String low_engine_name) {
	}

	@Schema(description = "A user to give access to every engine")
	public record AuthAdminEngineGrantAllEnginesRequest(
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }, requiredMode = REQUIRED) String permission,
			@Schema(description = "\"true\" to add the level on the engines the user has no access to; otherwise the engines they can access change to it, and their end dates and model limits are cleared") Boolean isAddNew,
			@Schema(description = "JSON-encoded list of the engine types to grant; absent for all") String engineTypes) {
	}

	@Schema(description = "An engine and the permission level to give")
	public record AuthAdminEnginePermissionLevelRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }, requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "A change to a person's access to an engine")
	public record AuthAdminEngineEditUserPermissionRequest(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The id of the user", requiredMode = REQUIRED) String id,
			@Schema(description = "The new permission level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }, requiredMode = REQUIRED) String permission,
			@Schema(description = "JSON-encoded model usage limit for a model engine, empty for no limit; absent keeps the person's limit") String usageLimit) {
	}
}
