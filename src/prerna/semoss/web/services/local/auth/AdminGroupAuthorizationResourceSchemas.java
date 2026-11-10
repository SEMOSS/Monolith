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

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link AdminGroupAuthorizationResource}, for its
 * OpenAPI annotations. The group rows are shared with
 * {@link GroupAuthorizationResource}, which reads them through the same queries.
 */
public final class AdminGroupAuthorizationResourceSchemas {

	private AdminGroupAuthorizationResourceSchemas() {
	}

	/*
	 * Groups
	 */

	@Schema(description = "A group")
	public record AuthGroupDetails(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type: CUSTOM for a custom group, otherwise the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The group description") String description,
			@JsonProperty("created_by_userid") @Schema(description = "The id of the user who created the group") String createdByUserid,
			@JsonProperty("created_by_useridtype") @Schema(description = "The login type of the user who created the group") String createdByUseridtype,
			@Schema(description = "When the group was added") String dateadded) {
	}

	@Schema(description = "A group in the admin group list")
	public record AdminGroupGetGroupsRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type: CUSTOM for a custom group, otherwise the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The group description") String description,
			@JsonProperty("created_by_userid") @Schema(description = "The id of the user who created the group") String createdByUserid,
			@JsonProperty("created_by_useridtype") @Schema(description = "The login type of the user who created the group") String createdByUseridtype,
			@Schema(description = "When the group was added") String dateadded,
			@JsonProperty("member_count") @Schema(description = "How many members a custom group has; absent for other group types, whose members come from the login") Long memberCount) {
	}

	/*
	 * Members and managers
	 */

	@Schema(description = "A member or manager of a custom group")
	public record AuthGroupPersonRow(
			@Schema(description = "The user id", requiredMode = REQUIRED) String userid,
			@Schema(description = "The user's login type", requiredMode = REQUIRED) String type,
			@Schema(description = "When the user was added to the group, or made its manager") String dateadded,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's username") String username,
			@Schema(description = "The user's email") String email) {
	}

	@Schema(description = "A person who is not a member of a custom group yet. From the security database a row has the SEMOSS user keys; from the Microsoft directory it also carries the Graph user fields (such as displayName, mail and userPrincipalName), and the SEMOSS keys can be remapped by ms_graphapi_jsonPattern")
	public record AuthGroupNonMemberRow(
			@Schema(description = "The user id, the Graph id for a directory user") String id,
			@Schema(description = "The user's login type, MICROSOFT for a directory user") String type,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's username") String username,
			@Schema(description = "The user's email") String email) {
	}

	/*
	 * Projects and engines
	 */

	@Schema(description = "A project a group has access to, with the group's grant")
	public record AuthGroupProjectRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type", requiredMode = REQUIRED) String type,
			@Schema(description = "The project id the grant is on", requiredMode = REQUIRED) String projectid,
			@Schema(description = "The group's permission: 1 owner, 2 editor, 3 read only") Integer permission,
			@Schema(description = "When the grant ends; absent for never") String enddate,
			@JsonProperty("project_id") @Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@JsonProperty("project_name") @Schema(description = "The project name") String projectName,
			@JsonProperty("low_project_name") @Schema(description = "The project name in lower case, which the list is sorted by") String lowProjectName,
			@JsonProperty("project_type") @Schema(description = "The project type") String projectType,
			@JsonProperty("project_cost") @Schema(description = "The project cost") String projectCost,
			@JsonProperty("project_global") @Schema(description = "Whether the project is public") Boolean projectGlobal,
			@JsonProperty("project_discoverable") @Schema(description = "Whether the project is discoverable") Boolean projectDiscoverable,
			@JsonProperty("project_catalog_name") @Schema(description = "The project's catalog name") String projectCatalogName,
			@JsonProperty("project_created_by") @Schema(description = "The id of the user who created the project") String projectCreatedBy,
			@JsonProperty("project_created_by_type") @Schema(description = "The login type of the user who created the project") String projectCreatedByType,
			@JsonProperty("project_date_created") @Schema(description = "When the project was created") String projectDateCreated,
			@JsonProperty("project_date_last_edited") @Schema(description = "When the project was last edited") String projectDateLastEdited,
			@JsonProperty("project_portal_published_date") @Schema(description = "When the project's portal was last published") String projectPortalPublishedDate,
			@JsonProperty("project_published_user") @Schema(description = "The id of the user who last published the portal") String projectPublishedUser,
			@JsonProperty("project_published_user_type") @Schema(description = "The login type of the user who last published the portal") String projectPublishedUserType,
			@JsonProperty("project_reactors_compiled_date") @Schema(description = "When the project's reactors were last compiled") String projectReactorsCompiledDate,
			@JsonProperty("project_reactors_compiled_user") @Schema(description = "The id of the user who last compiled the reactors") String projectReactorsCompiledUser,
			@JsonProperty("project_reactors_compiled_user_type") @Schema(description = "The login type of the user who last compiled the reactors") String projectReactorsCompiledUserType) {
	}

	@Schema(description = "A project a group has no access to yet")
	public record AdminGroupAvailableProjectRow(
			@JsonProperty("project_id") @Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@JsonProperty("project_name") @Schema(description = "The project name") String projectName,
			@JsonProperty("low_project_name") @Schema(description = "The project name in lower case, which the list is sorted by") String lowProjectName,
			@JsonProperty("project_type") @Schema(description = "The project type") String projectType,
			@JsonProperty("project_cost") @Schema(description = "The project cost") String projectCost,
			@JsonProperty("project_global") @Schema(description = "Whether the project is public") Boolean projectGlobal,
			@JsonProperty("project_discoverable") @Schema(description = "Whether the project is discoverable") Boolean projectDiscoverable,
			@JsonProperty("project_catalog_name") @Schema(description = "The project's catalog name") String projectCatalogName,
			@JsonProperty("project_created_by") @Schema(description = "The id of the user who created the project") String projectCreatedBy,
			@JsonProperty("project_created_by_type") @Schema(description = "The login type of the user who created the project") String projectCreatedByType,
			@JsonProperty("project_date_created") @Schema(description = "When the project was created") String projectDateCreated,
			@JsonProperty("project_portal_published_date") @Schema(description = "When the project's portal was last published") String projectPortalPublishedDate,
			@JsonProperty("project_published_user") @Schema(description = "The id of the user who last published the portal") String projectPublishedUser,
			@JsonProperty("project_published_user_type") @Schema(description = "The login type of the user who last published the portal") String projectPublishedUserType,
			@JsonProperty("project_reactors_compiled_date") @Schema(description = "When the project's reactors were last compiled") String projectReactorsCompiledDate,
			@JsonProperty("project_reactors_compiled_user") @Schema(description = "The id of the user who last compiled the reactors") String projectReactorsCompiledUser,
			@JsonProperty("project_reactors_compiled_user_type") @Schema(description = "The login type of the user who last compiled the reactors") String projectReactorsCompiledUserType) {
	}

	@Schema(description = "An engine a group has access to, with the group's grant")
	public record AuthGroupEngineRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type", requiredMode = REQUIRED) String type,
			@Schema(description = "The engine id the grant is on", requiredMode = REQUIRED) String engineid,
			@Schema(description = "The group's permission: 1 owner, 2 editor, 3 read only") Integer permission,
			@Schema(description = "When the grant ends; absent for never") String enddate,
			@JsonProperty("engine_id") @Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@JsonProperty("engine_name") @Schema(description = "The engine name") String engineName,
			@JsonProperty("low_engine_name") @Schema(description = "The engine name in lower case, which the list is sorted by") String lowEngineName,
			@JsonProperty("engine_type") @Schema(description = "The engine type, such as DATABASE or MODEL") String engineType,
			@JsonProperty("engine_subtype") @Schema(description = "The engine subtype") String engineSubtype,
			@JsonProperty("engine_cost") @Schema(description = "The engine cost") String engineCost,
			@JsonProperty("engine_discoverable") @Schema(description = "Whether the engine is discoverable") Boolean engineDiscoverable,
			@JsonProperty("engine_global") @Schema(description = "Whether the engine is public") Boolean engineGlobal,
			@JsonProperty("engine_created_by") @Schema(description = "The id of the user who created the engine") String engineCreatedBy,
			@JsonProperty("engine_created_by_type") @Schema(description = "The login type of the user who created the engine") String engineCreatedByType,
			@JsonProperty("engine_date_created") @Schema(description = "When the engine was created") String engineDateCreated) {
	}

	@Schema(description = "An engine a group has no access to yet")
	public record AdminGroupAvailableEngineRow(
			@JsonProperty("engine_id") @Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@JsonProperty("engine_name") @Schema(description = "The engine name") String engineName,
			@JsonProperty("low_engine_name") @Schema(description = "The engine name in lower case, which the list is sorted by") String lowEngineName,
			@JsonProperty("engine_type") @Schema(description = "The engine type, such as DATABASE or MODEL") String engineType,
			@JsonProperty("engine_subtype") @Schema(description = "The engine subtype") String engineSubtype,
			@JsonProperty("engine_cost") @Schema(description = "The engine cost") String engineCost,
			@JsonProperty("engine_discoverable") @Schema(description = "Whether the engine is discoverable") Boolean engineDiscoverable,
			@JsonProperty("engine_global") @Schema(description = "Whether the engine is public") Boolean engineGlobal,
			@JsonProperty("engine_created_by") @Schema(description = "The id of the user who created the engine") String engineCreatedBy,
			@JsonProperty("engine_created_by_type") @Schema(description = "The login type of the user who created the engine") String engineCreatedByType,
			@JsonProperty("engine_date_created") @Schema(description = "When the engine was created") String engineDateCreated) {
	}

	/*
	 * Requests
	 */

	@Schema(description = "The group to add")
	public record AdminGroupAddGroupRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider", requiredMode = REQUIRED) String type,
			@Schema(description = "The group description") String description) {
	}

	@Schema(description = "The group to delete")
	public record AdminGroupDeleteGroupRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type) {
	}

	@Schema(description = "The group to edit and its new details")
	public record AdminGroupEditGroupDetailsRequest(
			@Schema(description = "The current group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The new group id, the current one to keep it", requiredMode = REQUIRED) String newGroupId,
			@Schema(description = "The group type, CUSTOM or a login provider", requiredMode = REQUIRED) String type,
			@Schema(description = "The new group description") String newDescription) {
	}

	@Schema(description = "The person to add to a custom group")
	public record AuthGroupAddMemberRequest(
			@Schema(description = "The custom group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The user's login type", requiredMode = REQUIRED) String type,
			@Schema(description = "When the membership ends; blank or absent for never") String endDate) {
	}

	@Schema(description = "A person in a custom group")
	public record AuthGroupPersonRequest(
			@Schema(description = "The custom group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The user's login type", requiredMode = REQUIRED) String type) {
	}

	@Schema(description = "A group's access to a project")
	public record AdminGroupProjectPermissionRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission: 1 owner, 2 editor, 3 read only", types = "string", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; absent for never") String endDate) {
	}

	@Schema(description = "The group and project whose access to take away")
	public record AdminGroupRemoveProjectPermissionRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId) {
	}

	@Schema(description = "A group's new access to an engine")
	public record AdminGroupAddEnginePermissionRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The permission: 1 owner, 2 editor, 3 read only", types = "string", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; absent for never") String endDate,
			@Schema(description = "For a model, the JSON-encoded team usage limit, such as {\"unlimited\": false, \"rules\": [{\"metric\": \"TOKENS\", \"amount\": 100000, \"window\": \"MINUTE\", \"windowLength\": 10, \"counts\": \"EACH_PERSON\"}]}; empty for no limit defined, absent to leave the limit alone") String usageLimit) {
	}

	@Schema(description = "A group's access to an engine")
	public record AdminGroupEnginePermissionRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The permission: 1 owner, 2 editor, 3 read only", types = "string", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; absent for never") String endDate) {
	}

	@Schema(description = "The group and engine whose access to take away")
	public record AdminGroupRemoveEnginePermissionRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, CUSTOM or a login provider") String type,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId) {
	}
}
