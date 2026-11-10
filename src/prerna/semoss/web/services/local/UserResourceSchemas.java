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
package prerna.semoss.web.services.local;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The inputs and responses of {@link UserResource}, for its OpenAPI
 * annotations.
 */
public final class UserResourceSchemas {

	private UserResourceSchemas() {
	}

	@Schema(description = "A failed sign out")
	public record AuthLogoutFailure(@Schema(description = "Always false", requiredMode = REQUIRED) boolean success,
			@Schema(description = "Why the sign out failed", requiredMode = REQUIRED) String errorMessage) {
	}

	@Schema(description = "The session user's profile, as the provider reports it now")
	public record AuthUserinfoProfile(
			@Schema(description = "The user's id at the provider; \"null\" when unknown", requiredMode = REQUIRED) String id,
			@Schema(description = "The user's display name; \"null\" when unknown", requiredMode = REQUIRED) String name,
			@Schema(description = "The user's username; \"null\" when unknown", requiredMode = REQUIRED) String username,
			@Schema(description = "The user's email; \"null\" when unknown", requiredMode = REQUIRED) String email,
			@Schema(description = "Extended user properties the provider sent, each a list of values, keyed by property name; the refresh token is left out") Map<String, List<String>> meta,
			@Schema(description = "The subject alternative names of the user's certificate, keyed by type") Map<String, String> san,
			@Schema(description = "The user's groups at the provider", requiredMode = REQUIRED) AuthUserinfoGroupInfo groupInfo) {
	}

	@Schema(description = "A user's groups at a provider")
	public record AuthUserinfoGroupInfo(@Schema(description = "The kind of groups, as the provider names it") String groupType,
			@Schema(description = "The names of the user's groups") List<String> groups) {
	}

	@Schema(description = "The form a native sign in posts")
	public record AuthLoginRequest(@Schema(description = "The username", requiredMode = REQUIRED) String username,
			@Schema(description = "The password", requiredMode = REQUIRED) String password,
			@Schema(description = "Where to send the user once signed in, instead of the main page") String redirect,
			@Schema(description = "\"true\" to answer with the user instead of redirecting") String disableRedirect) {
	}

	@Schema(description = "The user a native sign in signed in")
	public record AuthLoginResult(@Schema(description = "Always \"true\"", requiredMode = REQUIRED) String success,
			@Schema(description = "The username", requiredMode = REQUIRED) String username,
			@Schema(description = "The user's display name") String name,
			@Schema(description = "The user's email") String email) {
	}

	@Schema(description = "The form an LDAP sign in posts")
	public record AuthLoginLDAPRequest(@Schema(description = "The username", requiredMode = REQUIRED) String username,
			@Schema(description = "The password", requiredMode = REQUIRED) String password,
			@Schema(description = "Where to send the user once signed in, instead of the main page") String redirect,
			@Schema(description = "\"true\" to answer with the user instead of redirecting") String disableRedirect) {
	}

	@Schema(description = "The user an LDAP sign in signed in")
	public record AuthLoginLDAPResult(@Schema(description = "Always \"true\"", requiredMode = REQUIRED) String success,
			@Schema(description = "The username", requiredMode = REQUIRED) String username) {
	}

	@Schema(description = "A failed LDAP or LinOTP sign in")
	public record AuthLoginLDAPFailure(@Schema(description = "Why the sign in failed", requiredMode = REQUIRED) String errorMessage,
			@Schema(description = "True when the user must change their directory password before signing in") Boolean requirePwdChange) {
	}

	@Schema(description = "The form an Active Directory password change posts")
	public record AuthChangeADPasswordRequest(@Schema(description = "The username", requiredMode = REQUIRED) String username,
			@Schema(description = "The current password", requiredMode = REQUIRED) String curPassword,
			@Schema(description = "The new password", requiredMode = REQUIRED) String newPassword) {
	}

	@Schema(description = "A changed Active Directory password")
	public record AuthChangeADPasswordResult(@Schema(description = "Always \"true\"", requiredMode = REQUIRED) String success,
			@Schema(description = "The username", requiredMode = REQUIRED) String username) {
	}

	@Schema(description = "The form a LinOTP sign in posts: first the username and pin, then the one-time passcode")
	public record AuthLoginLinOTPRequest(@Schema(description = "The username; required with a pin") String username,
			@Schema(description = "The pin, or the directory password when LinOTP checks Active Directory; sent first") String pin,
			@Schema(description = "The one-time passcode; sent after the pin was accepted") String otp,
			@Schema(description = "Where to send the user once signed in, instead of the main page") String redirect,
			@Schema(description = "\"true\" to answer with the user instead of redirecting") String disableRedirect) {
	}

	@Schema(description = "A LinOTP sign in step: the signed in user, or a request for the one-time passcode")
	public record AuthLoginLinOTPResult(@Schema(description = "\"true\" when the user is signed in") String success,
			@Schema(description = "The username, when the user is signed in") String username,
			@Schema(description = "\"Please get OTP code.\" when the pin was accepted and the one-time passcode is needed next") String OTP) {
	}

	@Schema(description = "The form a LinOTP fail counter reset posts")
	public record AuthResetLinOTPFailCounterRequest(@Schema(description = "The user whose counter to reset", requiredMode = REQUIRED) String username) {
	}

	@Schema(description = "A reset LinOTP fail counter")
	public record AuthResetLinOTPFailCounterResult(@Schema(description = "Always \"true\"", requiredMode = REQUIRED) String success,
			@Schema(description = "Always \"Successful reset\"", requiredMode = REQUIRED) String message) {
	}

	@Schema(description = "The form a native user registration posts")
	public record AuthCreateUserRequest(@Schema(description = "The username, which is also the user's id", requiredMode = REQUIRED) String username,
			@Schema(description = "The user's display name") String name,
			@Schema(description = "The password; must meet the password rules", requiredMode = REQUIRED) String password,
			@Schema(description = "The user's email", requiredMode = REQUIRED) String email,
			@Schema(description = "The user's phone number") String phone,
			@Schema(description = "The user's phone extension") String phoneextension,
			@Schema(description = "The country code of the user's phone number") String countrycode) {
	}

	@Schema(description = "A registered native user")
	public record AuthCreateUserResult(@Schema(description = "Always \"true\"", requiredMode = REQUIRED) String success,
			@Schema(description = "The username", requiredMode = REQUIRED) String username) {
	}

	@Schema(description = "The form an API user is created with")
	public record AuthCreateAPIUserRequest(@Schema(description = "The API user's name") String name) {
	}

	@Schema(description = "The new API user's credentials; the secret is only shown once")
	public record AuthCreateAPIUserResult(@Schema(description = "The client id to sign in with", requiredMode = REQUIRED) String clientId,
			@Schema(description = "The secret key to sign in with", requiredMode = REQUIRED) String secretKey) {
	}

	@Schema(description = "The form one provider's login properties are changed with")
	public record AuthModifyLoginPropertiesRequest(
			@Schema(description = "JSON-encoded object of the properties to set, keyed by property name without the provider prefix", requiredMode = REQUIRED) String modifications) {
	}

	@Schema(description = "The form the whole social.properties file is replaced with")
	public record AuthModifyAllLoginPropertiesRequest(
			@Schema(description = "The new content of social.properties", requiredMode = REQUIRED) String socialProperties) {
	}

	@Schema(description = "The content of social.properties")
	public record AuthGetAllLoginPropertiesResult(@Schema(description = "The file's content", requiredMode = REQUIRED) String content) {
	}

	@Schema(description = "Who the request is signed in as")
	public record AuthWhoamiResult(@Schema(description = "The container principal's name, else the id of the session's primary login") String name,
			@Schema(description = "The Windows groups of a Windows principal") List<AuthWhoamiGroup> groups,
			@Schema(description = "\"null principal - grab from user\" when the name came from the session user") String warning,
			@Schema(description = "\"null principal\" when there is neither a principal nor a session user") String errorMessage) {
	}

	@Schema(description = "A Windows group of the principal")
	public record AuthWhoamiGroup(@Schema(description = "The group's name") String name,
			@Schema(description = "The group's domain") String domain,
			@Schema(description = "The group's fully qualified name") String fqn,
			@Schema(description = "The group's security id") String sid) {
	}
}
