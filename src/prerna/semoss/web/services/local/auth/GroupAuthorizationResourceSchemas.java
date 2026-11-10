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
 * The responses of {@link GroupAuthorizationResource} that differ from the
 * admin ones, for its OpenAPI annotations. The other rows and requests are in
 * {@link AdminGroupAuthorizationResourceSchemas}.
 */
public final class GroupAuthorizationResourceSchemas {

	private GroupAuthorizationResourceSchemas() {
	}

	@Schema(description = "A custom group the user manages")
	public record AuthGroupGetGroupsRow(
			@Schema(description = "The group id", requiredMode = REQUIRED) String id,
			@Schema(description = "The group type, always CUSTOM", requiredMode = REQUIRED) String type,
			@Schema(description = "The group description") String description,
			@JsonProperty("created_by_userid") @Schema(description = "The id of the user who created the group") String createdByUserid,
			@JsonProperty("created_by_useridtype") @Schema(description = "The login type of the user who created the group") String createdByUseridtype,
			@Schema(description = "When the group was added") String dateadded,
			@JsonProperty("member_count") @Schema(description = "How many members the group has") Long memberCount,
			@JsonProperty("manager_since") @Schema(description = "When the user became a manager of the group, the earliest of their logins when more than one manages it") String managerSince) {
	}
}
