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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests of {@link GroupInsightAuthorizationResource}, for its OpenAPI
 * annotations. Its responses use the group shapes in
 * {@link GroupProjectAuthorizationResourceSchemas}.
 */
public final class GroupInsightAuthorizationResourceSchemas {

	private GroupInsightAuthorizationResourceSchemas() {
	}

	@Schema(description = "A group and the permission it gets on an insight")
	public record AuthGroupInsightGrantRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId,
			@Schema(description = "The permission level, such as OWNER, EDIT or READ_ONLY", requiredMode = REQUIRED) String permission,
			@Schema(description = "When the access ends; empty or absent for never") String endDate) {
	}

	@Schema(description = "A group to take off an insight")
	public record AuthGroupInsightRemoveRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, the login provider the group comes from", requiredMode = REQUIRED) String type,
			@Schema(description = "The id of the project the insight belongs to", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId) {
	}
}
