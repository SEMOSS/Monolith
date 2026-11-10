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

/**
 * The requests and responses of {@link ProjectAuthorizationResource}, for its
 * OpenAPI annotations. {@link AdminProjectAuthorizationResource} shares the ones
 * whose shape is the same.
 */
public final class ProjectAuthorizationResourceSchemas {

	private ProjectAuthorizationResourceSchemas() {
	}

	/*
	 * Responses
	 */

	@Schema(description = "A project the user can access. Unless noMeta is true, each project metadata value is added under its metadata key (such as tag or description): a single value as a string, several as an array of strings.", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthProjectGetProjectsRow(
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
			@Schema(description = "The lower case display name, or name when there is none, which the list is sorted by") @JsonProperty("low_project_name") String lowProjectName,
			@Schema(description = "1 when the user has favorited the project, 0 otherwise") @JsonProperty("project_favorite") Integer projectFavorite,
			@Schema(description = "The user's own permission: 1 owner, 2 edit, 3 read only; absent without a direct grant") @JsonProperty("user_permission") Integer userPermission,
			@Schema(description = "The best permission the user's groups have: 1 owner, 2 edit, 3 read only; absent without a group grant") @JsonProperty("group_permission") Integer groupPermission,
			@Schema(description = "The user's effective permission, the better of user_permission and group_permission: 1 owner, 2 edit, 3 read only; absent for a public project without a grant") Integer permission,
			@Schema(description = "The project description, from its description metadata") @JsonProperty("project_description") String projectDescription) {
	}

	@Schema(description = "The user's permission on a project")
	public record AuthProjectUserPermission(
			@Schema(description = "The permission: OWNER, EDIT, READ_ONLY, or DISCOVERABLE when the user has no access but the project is discoverable", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "A person with access to a project")
	public record AuthProjectMember(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's login type") String type,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY") String permission,
			@Schema(description = "When the access ends; absent when it does not") @JsonProperty("end_date") String endDate,
			@Schema(description = "The id of the user who granted the access") @JsonProperty("permission_granted_by") String permissionGrantedBy,
			@Schema(description = "The login type of the user who granted the access") @JsonProperty("permission_granted_by_type") String permissionGrantedByType,
			@Schema(description = "When the access was granted") @JsonProperty("date_added") String dateAdded) {
	}

	@Schema(description = "A page of the people with access to a project")
	public record AuthProjectMembersPage(
			@Schema(description = "How many people match the search, across all pages", requiredMode = REQUIRED) long totalMembers,
			@Schema(description = "The people on this page", requiredMode = REQUIRED) List<AuthProjectMember> members) {
	}

	@Schema(description = "A person who does not have access to a project yet. When the Microsoft directory is searched, the Microsoft Graph user's own fields are included too.", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
	public record AuthProjectUserCandidate(
			@Schema(description = "The user id") String id,
			@Schema(description = "The user's login type, MICROSOFT for a directory user") String type,
			@Schema(description = "The user's user name") String username,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email) {
	}

	@Schema(description = "What giving a person access to a project's dependencies did, by the ids of the engines and projects it depends on")
	public record AuthProjectPropagationResult(
			@Schema(description = "Always true", requiredMode = REQUIRED) @JsonProperty("Successfully processed permission propagation") boolean processed,
			@Schema(description = "Dependencies the person already had the requested permission on", requiredMode = REQUIRED) List<String> alreadyHaveAccess,
			@Schema(description = "Dependencies the person already had a pending request for at this permission", requiredMode = REQUIRED) List<String> requestAlreadyExists,
			@Schema(description = "Dependencies the requester cannot share, for which an access request was sent to their owners", requiredMode = REQUIRED) List<String> newRequestAdded,
			@Schema(description = "Dependencies the person was given or changed to the requested permission", requiredMode = REQUIRED) List<String> accessGranted,
			@Schema(description = "Dependencies for which neither access nor an access request could be made", requiredMode = REQUIRED) List<String> couldNotAddRequest) {
	}

	@Schema(description = "What giving several people access to a project's dependencies did")
	public record AuthProjectPropagationResults(
			@Schema(description = "Always true", requiredMode = REQUIRED) boolean success,
			@Schema(description = "What was done for each person, by user id", requiredMode = REQUIRED) Map<String, AuthProjectPropagationResult> users) {
	}

	/*
	 * Requests
	 */

	@Schema(description = "Which projects to list")
	public record AuthProjectGetProjectsRequest(
			@Schema(description = "Text to search the project ids and names for") String filterWord,
			@Schema(description = "The most projects to return") Integer limit,
			@Schema(description = "How many projects to skip") Integer offset,
			@Schema(description = "Only these project ids; repeat the field for each") List<String> projectId,
			@Schema(description = "The metadata keys to add to each project; repeat the field for each") List<String> metaKeys,
			@Schema(description = "JSON-encoded object of metadata key to value(s); only projects with those metadata values are listed") String metaFilters,
			@Schema(description = "When true, no metadata is added to the projects") Boolean noMeta,
			@Schema(description = "Whether to include user tracking information") Boolean userT) {
	}

	@Schema(description = "A person to give access to a project")
	public record AuthProjectAddUserPermissionRequest(
			@Schema(description = "The id of the person", requiredMode = REQUIRED) String id,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; leave out for no end") String endDate) {
	}

	@Schema(description = "A person to give access to a project's dependencies")
	public record AuthProjectPropagateDependencyPermissionRequest(
			@Schema(description = "The id of the person", requiredMode = REQUIRED) String id,
			@Schema(description = "The person's login type") String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; leave out for no end") String endDate,
			@Schema(description = "JSON-encoded model usage limit for the models the project depends on, such as {\"unlimited\": false, \"rules\": [{\"metric\": \"TOKENS\", \"amount\": 100000, \"window\": \"MINUTE\", \"windowLength\": 10, \"counts\": \"EACH_PERSON\"}]}; empty for no limit. Leave out to keep the limit of a person whose access to a model changes.") String usageLimit) {
	}

	@Schema(description = "People to give access to a project's dependencies")
	public record AuthProjectPropagateDependencyPermissionsRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "When the access ends; leave out for no end") String endDate,
			@Schema(description = "JSON-encoded array of objects with userid, type, permission (OWNER, EDIT or READ_ONLY) and, optionally, usageLimit (a model usage limit, as an object or JSON text)", requiredMode = REQUIRED) String userpermissions) {
	}

	@Schema(description = "A change to a person's permission on a project")
	public record AuthProjectEditUserPermissionRequest(
			@Schema(description = "The id of the person", requiredMode = REQUIRED) String id,
			@Schema(description = "The person's login type") String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The new permission: OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; leave out for no end") String endDate) {
	}

	@Schema(description = "People's permissions on a project")
	public record AuthProjectUserPermissionsRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "When the access ends; leave out for no end") String endDate,
			@Schema(description = "JSON-encoded array of objects with userid, type and permission (OWNER, EDIT or READ_ONLY)", requiredMode = REQUIRED) String userpermissions) {
	}

	@Schema(description = "A person to remove from a project")
	public record AuthProjectRemoveUserPermissionRequest(
			@Schema(description = "The id of the person", requiredMode = REQUIRED) String id,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId) {
	}

	@Schema(description = "People to remove from a project")
	public record AuthProjectRemoveUserPermissionsRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "JSON-encoded array of the user ids to remove", requiredMode = REQUIRED) String ids) {
	}

	@Schema(description = "Whether a project is a template")
	public record AuthProjectSetTemplateRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "true or false: whether users who can view the project may clone it as a template", allowableValues = {
					"true", "false" }, requiredMode = REQUIRED) String template) {
	}

	@Schema(description = "Whether a project is public")
	public record AuthProjectSetGlobalRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "true to make the project public, anything else to make it private") @JsonProperty("public") Boolean isPublic) {
	}

	@Schema(description = "Whether a project is discoverable")
	public record AuthProjectSetDiscoverableRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "true to make the project discoverable by users without access, anything else to stop it") Boolean discoverable) {
	}

	@Schema(description = "Whether the user sees a project")
	public record AuthProjectSetVisibilityRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "true to show the project in the user's lists, anything else to hide it") Boolean visibility) {
	}

	@Schema(description = "Whether a project is one of the user's favorites")
	public record AuthProjectSetFavoriteRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "true to favorite the project, anything else to unfavorite it") Boolean isFavorite) {
	}

	@Schema(description = "Access requests to approve")
	public record AuthProjectApproveUserAccessRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "When the access ends; leave out for no end") String endDate,
			@Schema(description = "JSON-encoded array of the requests, each an object with requestid, userid, type and permission (OWNER, EDIT or READ_ONLY)", requiredMode = REQUIRED) String requests) {
	}

	@Schema(description = "Access requests to deny")
	public record AuthProjectDenyUserAccessRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "JSON-encoded array of the ids of the requests to deny", requiredMode = REQUIRED) String requestids) {
	}
}
