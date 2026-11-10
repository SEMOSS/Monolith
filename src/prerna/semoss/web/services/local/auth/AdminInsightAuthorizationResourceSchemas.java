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
 * The requests and responses of {@link AdminInsightAuthorizationResource}, for
 * its OpenAPI annotations. The shapes it shares with
 * {@link InsightAuthorizationResource} are in
 * {@link InsightAuthorizationResourceSchemas}.
 */
public final class AdminInsightAuthorizationResourceSchemas {

	private AdminInsightAuthorizationResourceSchemas() {
	}

	@Schema(description = "An insight, with the admin's own permissions on it and its project")
	public record AuthAdminInsightGetInsightsRow(
			@Schema(description = "The project id; deprecated, use project_id", requiredMode = REQUIRED) @JsonProperty("app_id") String appId,
			@Schema(description = "The project name; deprecated, use project_name") @JsonProperty("app_name") String appName,
			@Schema(description = "The insight id; deprecated, use project_insight_id", requiredMode = REQUIRED) @JsonProperty("app_insight_id") String appInsightId,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The project name") @JsonProperty("project_name") String projectName,
			@Schema(description = "True when the project is public") @JsonProperty("project_global") Boolean projectGlobal,
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
			@Schema(description = "The insight name in lower case, for sorting") @JsonProperty("low_name") String lowName,
			@Schema(description = "The admin's own permission level id on the insight: 1 owner, 2 edit, 3 read only") @JsonProperty("insight_permission") Integer insightPermission,
			@Schema(description = "The admin's own permission level id on the project") @JsonProperty("project_permission") Integer projectPermission,
			@Schema(description = "The permission level id the admin's groups have on the insight") @JsonProperty("insight_group_permission") Integer insightGroupPermission,
			@Schema(description = "The permission level id the admin's groups have on the project") @JsonProperty("project_group_permission") Integer projectGroupPermission,
			@Schema(description = "Whether the admin marked the insight a favorite: true or 1 when they did, false or 0 when not", types = {
					"boolean", "integer" }) @JsonProperty("insight_favorite") Object insightFavorite,
			@Schema(description = "The admin's effective permission level id on the insight: 1 when they own the project, otherwise the higher of their own and their groups' levels") Integer permission) {
	}

	@Schema(description = "An insight of a project")
	public record AuthAdminInsightProjectInsightRow(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) @JsonProperty("project_insight_id") String projectInsightId,
			@Schema(description = "The insight name") String name,
			@Schema(description = "True when the insight is public within its project") @JsonProperty("insight_global") Boolean insightGlobal,
			@Schema(description = "How many times the insight was run") @JsonProperty("exec_count") Integer execCount,
			@Schema(description = "When the insight was created, as yyyy-MM-dd HH:mm:ss") @JsonProperty("created_on") String createdOn,
			@Schema(description = "When the insight was last changed, as yyyy-MM-dd HH:mm:ss") @JsonProperty("last_modified_on") String lastModifiedOn,
			@Schema(description = "True when the insight's results are cached") Boolean cacheable) {
	}

	@Schema(description = "An insight of a project and the permission one user has on it")
	public record AuthAdminInsightUserAccessRow(
			@Schema(description = "The insight id", requiredMode = REQUIRED) @JsonProperty("insight_id") String insightId,
			@Schema(description = "The insight name") @JsonProperty("insight_name") String insightName,
			@Schema(description = "True when the insight is public within its project") @JsonProperty("insight_public") Boolean insightPublic,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) @JsonProperty("project_id") String projectId,
			@Schema(description = "The user's permission level on the insight, such as OWNER, EDIT or READ_ONLY; absent when they have none") @JsonProperty("insight_permission") String insightPermission,
			@Schema(description = "The user id; absent when the user has no permission on the insight") @JsonProperty("user_id") String userId) {
	}

	@Schema(description = "The project and user to list insight access for")
	public record AuthAdminInsightProjectUserRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId) {
	}

	@Schema(description = "Insights to delete from a project")
	public record AuthAdminInsightDeleteRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "JSON-encoded array of the ids of the insights to delete", requiredMode = REQUIRED) String insightId) {
	}

	@Schema(description = "A user and the permission they get on every insight of a project")
	public record AuthAdminInsightGrantAllRequest(
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The user id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "An insight and the permission level to apply to its users")
	public record AuthAdminInsightPermissionRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission) {
	}

	@Schema(description = "Requests for access to an insight to deny")
	public record AuthAdminInsightDenyRequestsRequest(
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "JSON-encoded array of the request ids", requiredMode = REQUIRED) String requestids) {
	}
}
