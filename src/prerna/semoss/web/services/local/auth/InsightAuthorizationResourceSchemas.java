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
 * The requests and responses of {@link InsightAuthorizationResource}, for its
 * OpenAPI annotations. {@link AdminInsightAuthorizationResource} reuses the
 * shapes the two share.
 */
public final class InsightAuthorizationResourceSchemas {

	private InsightAuthorizationResourceSchemas() {
	}

	@Schema(description = "An insight the user can see, with the user's permissions on it and its project")
	public record AuthInsightGetInsightsRow(
			@Schema(description = "The project id; deprecated, use project_id", requiredMode = REQUIRED) @JsonProperty("app_id") String appId,
			@Schema(description = "The project name; deprecated, use project_name") @JsonProperty("app_name") String appName,
			@Schema(description = "The insight id; deprecated, use project_insight_id", requiredMode = REQUIRED) @JsonProperty("app_insight_id") String appInsightId,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The project name") @JsonProperty("project_name") String projectName,
			@Schema(description = "True when the project is public") @JsonProperty("project_global") Boolean projectGlobal,
			@Schema(description = "The project's catalog name") @JsonProperty("project_catalog_name") String projectCatalogName,
			@Schema(description = "The insight id", requiredMode = REQUIRED) @JsonProperty("project_insight_id") String projectInsightId,
			@Schema(description = "The insight name") String name,
			@Schema(description = "How many times the insight was run") @JsonProperty("view_count") Integer viewCount,
			@Schema(description = "The insight's layout") String layout,
			@Schema(description = "When the insight was created, as yyyy-MM-dd HH:mm:ss") @JsonProperty("created_on") String createdOn,
			@Schema(description = "When the insight was last changed, as yyyy-MM-dd HH:mm:ss") @JsonProperty("last_modified_on") String lastModifiedOn,
			@Schema(description = "True when the insight's results are cached") Boolean cacheable,
			@Schema(description = "How many minutes the cache lasts") Integer cacheMinutes,
			@Schema(description = "The cron expression the cache is refreshed on") String cacheCron,
			@Schema(description = "When the insight was last cached, as yyyy-MM-dd HH:mm:ss") String cachedOn,
			@Schema(description = "True when the cache is encrypted") Boolean cacheEncrypt,
			@Schema(description = "True when the insight is public within its project") @JsonProperty("insight_global") Boolean insightGlobal,
			@Schema(description = "The insight's schema name") @JsonProperty("insight_schema_name") String insightSchemaName,
			@Schema(description = "The insight name in lower case, for sorting") @JsonProperty("low_name") String lowName,
			@Schema(description = "The user's own permission level id on the insight: 1 owner, 2 edit, 3 read only") @JsonProperty("insight_permission") Integer insightPermission,
			@Schema(description = "The user's own permission level id on the project") @JsonProperty("project_permission") Integer projectPermission,
			@Schema(description = "The permission level id the user's groups have on the insight") @JsonProperty("insight_group_permission") Integer insightGroupPermission,
			@Schema(description = "The permission level id the user's groups have on the project") @JsonProperty("project_group_permission") Integer projectGroupPermission,
			@Schema(description = "Whether the user marked the insight a favorite: true or 1 when they did, false or 0 when not", types = {
					"boolean", "integer" }) @JsonProperty("insight_favorite") Object insightFavorite,
			@Schema(description = "The user's effective permission level id on the insight: 1 when they own the project, otherwise the higher of their own and their groups' levels") Integer permission) {
	}

	@Schema(description = "An insight of a project the user can edit")
	public record AuthInsightEditableInsightRow(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) @JsonProperty("project_insight_id") String projectInsightId,
			@Schema(description = "The insight name") String name,
			@Schema(description = "True when the insight is public within its project") @JsonProperty("insight_global") Boolean insightGlobal,
			@Schema(description = "How many times the insight was run") @JsonProperty("exec_count") Integer execCount,
			@Schema(description = "When the insight was created, as yyyy-MM-dd HH:mm:ss") @JsonProperty("created_on") String createdOn,
			@Schema(description = "When the insight was last changed, as yyyy-MM-dd HH:mm:ss") @JsonProperty("last_modified_on") String lastModifiedOn,
			@Schema(description = "True when the insight's results are cached") Boolean cacheable,
			@Schema(description = "The user's permission on the insight, OWNER or EDIT; sent only when the user edits the project rather than owns it") String permission,
			@Schema(description = "Always OWNER; sent instead of permission when the user owns the project") @JsonProperty("CONSTANT_OWNER") String constantOwner) {
	}

	@Schema(description = "The user's permission on an insight")
	public record AuthInsightUserPermission(
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "A user who has access to an insight")
	public record AuthInsightMemberRow(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email,
			@Schema(description = "The user's permission level, such as OWNER, EDIT or READ_ONLY") String permission) {
	}

	@Schema(description = "A page of the users who have access to an insight")
	public record AuthInsightMembersPage(
			@Schema(description = "How many users match in total", requiredMode = REQUIRED) long totalMembers,
			@Schema(description = "The users on this page", requiredMode = REQUIRED) List<AuthInsightMemberRow> members) {
	}

	@Schema(description = "A user who does not have access to an insight yet")
	public record AuthInsightUserNoCredentialsRow(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's username") String username,
			@Schema(description = "The user's name") String name,
			@Schema(description = "The user's email") String email) {
	}

	@Schema(description = "A user and the permission they get on an insight")
	public record AuthInsightUserPermissionRequest(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "A user to take off an insight")
	public record AuthInsightRemoveUserRequest(
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId) {
	}

	@Schema(description = "Users and the permissions they get on an insight")
	public record AuthInsightUserPermissionsRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "JSON-encoded array of the users and their levels, each {\"userid\": \"...\", \"permission\": \"EDIT\"}", requiredMode = REQUIRED) String userpermissions) {
	}

	@Schema(description = "Users to take off an insight")
	public record AuthInsightRemoveUsersRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "JSON-encoded array of the user ids", requiredMode = REQUIRED) String ids) {
	}

	@Schema(description = "Whether an insight is public within its project")
	public record AuthInsightSetGlobalRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "\"true\" to make the insight public, anything else to make it private") String isPublic) {
	}

	@Schema(description = "Whether the user marks an insight a favorite")
	public record AuthInsightSetFavoriteRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "\"true\" to mark the insight a favorite, anything else to unmark it") String isFavorite) {
	}

	@Schema(description = "Whether the user sees an insight in their lists")
	public record AuthInsightSetVisibilityRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "\"true\" to show the insight, anything else to hide it") String visibility) {
	}

	@Schema(description = "Requests for access to an insight to approve")
	public record AuthInsightApproveRequestsRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "JSON-encoded array of the requests, each {\"requestid\": \"...\", \"userid\": \"...\", \"permission\": \"READ_ONLY\"}", requiredMode = REQUIRED) String requests) {
	}

	@Schema(description = "Requests for access to an insight to deny")
	public record AuthInsightDenyRequestsRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "JSON-encoded array of the request ids", requiredMode = REQUIRED) String requestIds) {
	}
}
