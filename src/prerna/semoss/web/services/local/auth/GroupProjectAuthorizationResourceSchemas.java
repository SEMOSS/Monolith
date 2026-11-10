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
 * The requests and responses of {@link GroupProjectAuthorizationResource}, for
 * its OpenAPI annotations. The group shapes it shares with
 * {@link GroupInsightAuthorizationResource} and
 * {@link GroupEngineAuthorizationResource} live here too.
 */
public final class GroupProjectAuthorizationResourceSchemas {

	private GroupProjectAuthorizationResourceSchemas() {
	}

	@Schema(description = "A group's permission level on a project, insight or engine")
	public record AuthGroupTargetPermission(
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY; absent when the group has no access") String permission) {
	}

	@Schema(description = "A group that has access to a project, insight or engine")
	public record AuthGroupTargetAccessRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) @JsonProperty("ID") String id,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) @JsonProperty("TYPE") String type,
			@Schema(description = "The permission level id: 1 owner, 2 edit, 3 read only") @JsonProperty("PERMISSION") Integer permission,
			@Schema(description = "When the access was given, as yyyy-MM-dd HH:mm:ss") @JsonProperty("DATEADDED") String dateAdded,
			@Schema(description = "When the access ends, as yyyy-MM-dd HH:mm:ss; absent when it does not end, and never sent for insights") @JsonProperty("ENDDATE") String endDate) {
	}

	@Schema(description = "A page of the groups that have access to a project, insight or engine")
	public record AuthGroupTargetAccessPage(
			@Schema(description = "How many groups have access in total", requiredMode = REQUIRED) long totalGroups,
			@Schema(description = "The groups on this page", requiredMode = REQUIRED) List<AuthGroupTargetAccessRow> groups) {
	}

	@Schema(description = "A group that does not have access yet and can be given it")
	public record AuthGroupTargetAvailableRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The group's description") String description) {
	}

	@Schema(description = "A group and the permission it gets on a project")
	public record AuthGroupProjectGrantRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; empty or absent for never") String endDate) {
	}

	@Schema(description = "A group to take off a project")
	public record AuthGroupProjectRemoveRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The project id", requiredMode = REQUIRED) String projectId) {
	}
}
