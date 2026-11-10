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
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchUsageLimit;

/**
 * The requests and responses of {@link AdminUserAuthorizationResource}, for its
 * OpenAPI annotations. User rows keep the lower case aliases
 * {@code SecurityAdminUtils} reads the {@code SMSS_USER} table with.
 */
public final class AdminUserAuthorizationResourceSchemas {

	private AdminUserAuthorizationResourceSchemas() {
	}

	@Schema(description = "The person to register")
	public record AdminUserRegisterUserRequest(
			@Schema(description = "The new user's id", requiredMode = REQUIRED) String userId,
			@Schema(description = "The login type, such as NATIVE") String type,
			@Schema(description = "The display name") String name,
			@Schema(description = "The email address; validated when given") String email,
			@Schema(description = "The phone number; validated when given") String phone,
			@Schema(description = "The phone extension") String phoneextension,
			@Schema(description = "The phone country code") String countrycode,
			@Schema(description = "\"true\" to make the user an admin") String admin,
			@Schema(description = "\"true\" to let the user publish") String publisher,
			@Schema(description = "\"true\" to let the user export") String exporter,
			@Schema(description = "The password, for a NATIVE login; validated when given") String password,
			@Schema(description = "JSON-encoded model usage limit ({\"unlimited\", \"rules\"}) for the person's all-models default; empty or left out for no limit defined") String modelUsageLimit) {
	}

	@Schema(description = "Whether a user may publish")
	public record AdminUserSetUserPublisherRequest(@Schema(description = "The user id") String userId,
			@Schema(description = "\"true\" to let the user publish, anything else to stop them") String isPublisher) {
	}

	@Schema(description = "Whether a user is locked")
	public record AdminUserSetUserLockedRequest(@Schema(description = "The user id") String userId,
			@Schema(description = "The user's login type") String type,
			@Schema(description = "\"true\" to lock the user, anything else to unlock them") String isLocked) {
	}

	@Schema(description = "The user to edit")
	public record AdminUserEditUserRequest(@Schema(description = "JSON-encoded user object with the keys id and type, which pick the user, and any of name, username, email, password, phone, phoneextension, countrycode, admin, publisher, exporter and model_usage_limit (a model usage limit, null for no limit defined; without the key the all-models default is kept)", requiredMode = REQUIRED) String user) {
	}

	@Schema(description = "The user to delete")
	public record AdminUserDeleteUserRequest(@Schema(description = "The user id") String userId,
			@Schema(description = "The user's login type") String type) {
	}

	@Schema(description = "A user, as the admin user list shows them")
	public record AdminUserGetAllUsersRow(@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The login type", requiredMode = REQUIRED) String type,
			@Schema(description = "The display name") String name,
			@Schema(description = "The user name") String username,
			@Schema(description = "The email address") String email,
			@Schema(description = "Whether the user is an admin") Boolean admin,
			@Schema(description = "Whether the user may publish") Boolean publisher,
			@Schema(description = "Whether the user may export") Boolean exporter,
			@Schema(description = "The phone number") String phone,
			@Schema(description = "The phone extension") String phoneextension,
			@Schema(description = "The phone country code") String countrycode,
			@Schema(description = "Whether the user is locked") Boolean locked,
			@Schema(description = "The person's all-models default usage limit; left out for no limit defined") PermissionBatchUsageLimit model_usage_limit) {
	}

	@Schema(description = "An API user, as the admin service account list shows them")
	public record AdminUserGetAllAPIUsersRow(@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The login type, always API_USER", requiredMode = REQUIRED) String type,
			@Schema(description = "The display name") String name,
			@Schema(description = "The user name") String username,
			@Schema(description = "The email address") String email,
			@Schema(description = "Whether the user is an admin") Boolean admin,
			@Schema(description = "Whether the user may publish") Boolean publisher,
			@Schema(description = "Whether the user may export") Boolean exporter,
			@Schema(description = "The phone number") String phone,
			@Schema(description = "The phone extension") String phoneextension,
			@Schema(description = "The phone country code") String countrycode,
			@Schema(description = "When the user was created") String date_created,
			@Schema(description = "The user's all-models default usage limit; left out for no limit defined") PermissionBatchUsageLimit model_usage_limit) {
	}
}
