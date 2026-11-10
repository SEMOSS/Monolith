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

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link AdminProjectAuthorizationResource}, for
 * its OpenAPI annotations. The ones it shares with
 * {@link ProjectAuthorizationResource} are in
 * {@link ProjectAuthorizationResourceSchemas}.
 */
public final class AdminProjectAuthorizationResourceSchemas {

	private AdminProjectAuthorizationResourceSchemas() {
	}

	/*
	 * Responses
	 */

	@Schema(description = "A project on the server. Unless noMeta is true, each project metadata value is added under its metadata key (such as tag or description): a single value as a string, several as an array of strings.", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthAdminProjectGetProjectsRow(
			@Schema(description = "The project id", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The project name") @JsonProperty("project_name") String projectName,
			@Schema(description = "The name shown for the project") @JsonProperty("project_display_name") String projectDisplayName,
			@Schema(description = "The project type, such as CODE or INSIGHTS") @JsonProperty("project_type") String projectType,
			@Schema(description = "The project cost") @JsonProperty("project_cost") String projectCost,
			@Schema(description = "Whether the project is public") @JsonProperty("project_global") Boolean projectGlobal,
			@Schema(description = "Whether the project is discoverable by users without access") @JsonProperty("project_discoverable") Boolean projectDiscoverable,
			@Schema(description = "Whether users who can view the project may clone it as a template") @JsonProperty("project_is_template") Boolean projectIsTemplate,
			@Schema(description = "The project catalog name") @JsonProperty("project_catalog_name") String projectCatalogName,
			@Schema(description = "The id of the user who created the project") @JsonProperty("project_created_by") String projectCreatedBy,
			@Schema(description = "The login type of the user who created the project") @JsonProperty("project_created_by_type") String projectCreatedByType,
			@Schema(description = "When the project was created") @JsonProperty("project_date_created") String projectDateCreated,
			@Schema(description = "When the project was last edited") @JsonProperty("project_date_last_edited") String projectDateLastEdited,
			@Schema(description = "When the project's portal was last published") @JsonProperty("project_portal_published_date") String projectPortalPublishedDate,
			@Schema(description = "The id of the user who last published the portal") @JsonProperty("project_published_user") String projectPublishedUser,
			@Schema(description = "The login type of the user who last published the portal") @JsonProperty("project_published_user_type") String projectPublishedUserType,
			@Schema(description = "When the project's reactors were last compiled") @JsonProperty("project_reactors_compiled_date") String projectReactorsCompiledDate,
			@Schema(description = "The id of the user who last compiled the reactors") @JsonProperty("project_reactors_compiled_user") String projectReactorsCompiledUser,
			@Schema(description = "The login type of the user who last compiled the reactors") @JsonProperty("project_reactors_compiled_user_type") String projectReactorsCompiledUserType,
			@Schema(description = "The lower case display name, or name when there is none, which the list is sorted by") @JsonProperty("low_project_name") String lowProjectName) {
	}

	@Schema(description = "A project a user has a permission on")
	public record AuthAdminProjectUserProjectRow(
			@Schema(description = "The user id") @JsonProperty("user_id") String userId,
			@Schema(description = "The user's permission on the project; the query selects both the permission number and its name (OWNER, EDIT or READ_ONLY) under this key, so which one is sent depends on the database driver", types = {
					"integer", "string" }) @JsonProperty("project_permission") Object projectPermission,
			@Schema(description = "The project id", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The project name") @JsonProperty("project_name") String projectName,
			@Schema(description = "The name shown for the project") @JsonProperty("project_display_name") String projectDisplayName,
			@Schema(description = "The project type, such as CODE or INSIGHTS") @JsonProperty("project_type") String projectType,
			@Schema(description = "The lower case display name, which the list is sorted by") @JsonProperty("low_project_name") String lowProjectName) {
	}

	@Schema(description = "A project a user has no permission on")
	public record AuthAdminProjectUserNoAccessProjectRow(
			@Schema(description = "The project id", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The project name") @JsonProperty("project_name") String projectName,
			@Schema(description = "The name shown for the project") @JsonProperty("project_display_name") String projectDisplayName,
			@Schema(description = "The project type, such as CODE or INSIGHTS") @JsonProperty("project_type") String projectType,
			@Schema(description = "The lower case display name, which the list is sorted by") @JsonProperty("low_project_name") String lowProjectName) {
	}

	/*
	 * Requests
	 */

	@Schema(description = "Which projects to list")
	public record AuthAdminProjectGetProjectsRequest(
			@Schema(description = "Text to search the project ids and names for") String filterWord,
			@Schema(description = "The most projects to return") Integer limit,
			@Schema(description = "How many projects to skip") Integer offset,
			@Schema(description = "Only these project ids; repeat the field for each") List<String> projectId,
			@Schema(description = "The metadata keys to add to each project; repeat the field for each") List<String> metaKeys,
			@Schema(description = "JSON-encoded object of metadata key to value(s); only projects with those metadata values are listed") String metaFilters,
			@Schema(description = "When true, no metadata is added to the projects") Boolean noMeta,
			@Schema(description = "Whether to include user tracking information") Boolean userT,
			@Schema(description = "Only the projects this user id has a permission on, directly or through a group, with effectivePermissions read for this user") String permissionUser,
			@Schema(description = "Only the projects whose effective permission is one of these levels: 1 owner, 2 edit, 3 read only; repeat the field for each") List<Integer> effectivePermissions,
			@Schema(description = "JSON-encoded array of creators, each an object with the creator's login id and type, such as [{\"id\": \"abc\", \"type\": \"MICROSOFT\"}]; only projects one of them created are listed") String createdBy,
			@Schema(description = "When true, only the projects the signed in admin created") Boolean createdByMe) {
	}

	@Schema(description = "A user to give access to every project")
	public record AuthAdminProjectGrantAllProjectsRequest(
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "true to give the permission on every project the user has no access to; otherwise the projects the user can access change to it and their end dates are cleared") Boolean isAddNew) {
	}

	@Schema(description = "A project and a permission")
	public record AuthAdminProjectPermissionRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "A person's permission on a project")
	public record AuthAdminProjectUserPermissionRequest(
			@Schema(description = "The id of the person", requiredMode = REQUIRED) String id,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "People's permissions on a project")
	public record AuthAdminProjectUserPermissionsRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "JSON-encoded array of objects with userid, type and permission (OWNER, EDIT or READ_ONLY)", requiredMode = REQUIRED) String userpermissions) {
	}

	@Schema(description = "Access requests to approve")
	public record AuthAdminProjectApproveUserAccessRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "JSON-encoded array of the requests, each an object with requestid, userid, type and permission (OWNER, EDIT or READ_ONLY)", requiredMode = REQUIRED) String requests) {
	}
}
