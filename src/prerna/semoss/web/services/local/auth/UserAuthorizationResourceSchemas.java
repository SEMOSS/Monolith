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
 * The requests and responses of {@link UserAuthorizationResource}, for its
 * OpenAPI annotations. Most of its endpoints take the same fields as a JSON
 * body or as form fields.
 */
public final class UserAuthorizationResourceSchemas {

	private UserAuthorizationResourceSchemas() {
	}

	@Schema(description = "The signed in user's new details, as a form")
	public record AuthUserEditUserForm(@Schema(description = "JSON-encoded object with the user's new name and newEmail", requiredMode = REQUIRED) String user) {
	}

	@Schema(description = "The signed in user's new details")
	public record AuthUserEditUserBody(@Schema(description = "The new display name; empty clears it") String name,
			@Schema(description = "The new email address; empty clears it") String newEmail) {
	}

	@Schema(description = "The details the user sent, echoed back once they are saved")
	public record AuthUserEditUserResult(@Schema(description = "The display name sent") String name,
			@Schema(description = "The email address sent") String newEmail) {
	}

	@Schema(description = "The access key to create")
	public record AuthUserCreateAccessKeyRequest(
			@Schema(description = "The key's name, up to 255 characters") String tokenName,
			@Schema(description = "The key's description, up to 500 characters") String tokenDescription) {
	}

	@Schema(description = "A new access key, with its secret, shown only this once")
	public record AuthUserCreatedAccessKey(@Schema(description = "The access key", requiredMode = REQUIRED) String ACCESSKEY,
			@Schema(description = "The secret key", requiredMode = REQUIRED) String SECRETKEY,
			@Schema(description = "The key's name") String TOKENNAME,
			@Schema(description = "The key's description") String TOKENDESCRIPTION) {
	}

	@Schema(description = "The access key to delete")
	public record AuthUserDeleteAccessKeyRequest(@Schema(description = "The access key", requiredMode = REQUIRED) String accessKey) {
	}

	@Schema(description = "One of the signed in user's access keys, without its secret")
	public record AuthUserAccessKeyRow(@Schema(description = "The key's name") String TOKENNAME,
			@Schema(description = "The key's description") String TOKENDESCRIPTION,
			@Schema(description = "The access key", requiredMode = REQUIRED) String ACCESSKEY,
			@Schema(description = "When the key was created") String DATECREATED,
			@Schema(description = "When the key was last used; left out when never") String LASTUSED) {
	}

	@Schema(description = "Who asks for a password reset email")
	public record AuthUserSetupResetPasswordRequest(@Schema(description = "The user's email address") String email,
			@Schema(description = "The user's login type") String type,
			@Schema(description = "The reset page the email links to, given ?token=...; the server's own reset page when left out") String url,
			@Schema(description = "The email's subject") String subject) {
	}

	@Schema(description = "A password reset email was sent")
	public record AuthUserSetupResetPasswordResult(@Schema(description = "Always true", requiredMode = REQUIRED) boolean success,
			@Schema(description = "Which address the email was sent to", requiredMode = REQUIRED) String message) {
	}

	@Schema(description = "A password reset")
	public record AuthUserResetPasswordRequest(@Schema(description = "The token from the reset email") String token,
			@Schema(description = "The new password") String password,
			@Schema(description = "Who the confirmation email is sent from") String sender) {
	}

	@Schema(description = "What a password change or reset did")
	public record AuthUserPasswordResult(@Schema(description = "Whether the password was changed", requiredMode = REQUIRED) boolean success,
			@Schema(description = "The user whose password it is") String userId,
			@Schema(description = "What happened", requiredMode = REQUIRED) String message) {
	}

	@Schema(description = "A user metadata value to set, as a form")
	public record AuthUserSetUserMetadataForm(@Schema(description = "The metadata key", requiredMode = REQUIRED) String metaKey,
			@Schema(description = "The metadata values; repeat the field for each value", requiredMode = REQUIRED) List<String> metaValue) {
	}

	@Schema(description = "A user metadata value to set")
	public record AuthUserSetUserMetadataBody(@Schema(description = "The metadata key", requiredMode = REQUIRED) String metaKey,
			@Schema(description = "The metadata value: a string, or a list of values", types = { "string",
					"array" }, requiredMode = REQUIRED) Object metaValue) {
	}

	@Schema(description = "A user metadata value was set")
	public record AuthUserSetUserMetadataResult(@Schema(description = "Which key was set to what", requiredMode = REQUIRED) String userMetadata) {
	}

	@Schema(description = "A password change")
	public record AuthUserChangePasswordRequest(@Schema(description = "The current password", requiredMode = REQUIRED) String currentPassword,
			@Schema(description = "The new password", requiredMode = REQUIRED) String newPassword) {
	}
}
