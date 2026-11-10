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
package prerna.semoss.web.services.config;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The response of {@code GET /api/config}.
 * <p>
 * For now this documents the map {@link ServerConfigurationResource} builds,
 * and {@code ServerConfigContractTest} checks the two keep the same keys. A key
 * whose value can be null is optional, since the response leaves out null
 * values. Returning this record from the endpoint itself is a later change.
 */
@Schema(description = "Server configuration returned by /api/config")
public record ServerConfig(@Schema(description = "Whether R is enabled", requiredMode = REQUIRED) boolean r,
		@Schema(description = "Whether Python is enabled", requiredMode = REQUIRED) boolean python,
		@Schema(description = "Whether security is enabled; always true", requiredMode = REQUIRED) boolean security,

		@Schema(description = "Whether only admins may add projects", requiredMode = REQUIRED) boolean adminOnlyProjectAdd,
		@Schema(description = "Whether only admins may delete projects", requiredMode = REQUIRED) boolean adminOnlyProjectDelete,
		@Schema(description = "Whether only admins may give access to projects", requiredMode = REQUIRED) boolean adminOnlyProjectAddAccess,
		@Schema(description = "Whether only admins may make projects public", requiredMode = REQUIRED) boolean adminOnlyProjectSetPublic,
		@Schema(description = "Whether only admins may make projects discoverable", requiredMode = REQUIRED) boolean adminOnlyProjectSetDiscoverable,
		@Schema(description = "Whether only admins may add workspaces", requiredMode = REQUIRED) boolean adminOnlyWorkspaceAdd,
		@Schema(description = "Whether only admins may delete workspaces", requiredMode = REQUIRED) boolean adminOnlyWorkspaceDelete,
		@Schema(description = "Whether only admins may give access to workspaces", requiredMode = REQUIRED) boolean adminOnlyWorkspaceAddAccess,
		@Schema(description = "Whether only admins may make workspaces public", requiredMode = REQUIRED) boolean adminOnlyWorkspaceSetPublic,
		@Schema(description = "Whether only admins may make workspaces discoverable", requiredMode = REQUIRED) boolean adminOnlyWorkspaceSetDiscoverable,
		@Schema(description = "Whether only admins may add skills", requiredMode = REQUIRED) boolean adminOnlySkillAdd,
		@Schema(description = "Whether only admins may delete skills", requiredMode = REQUIRED) boolean adminOnlySkillDelete,
		@Schema(description = "Whether only admins may give access to skills", requiredMode = REQUIRED) boolean adminOnlySkillAddAccess,
		@Schema(description = "Whether only admins may make skills public", requiredMode = REQUIRED) boolean adminOnlySkillSetPublic,
		@Schema(description = "Whether only admins may make skills discoverable", requiredMode = REQUIRED) boolean adminOnlySkillSetDiscoverable,
		@Schema(description = "Whether only admins may add databases", requiredMode = REQUIRED) boolean adminOnlyDbAdd,
		@Schema(description = "Whether only admins may delete databases", requiredMode = REQUIRED) boolean adminOnlyDbDelete,
		@Schema(description = "Whether only admins may give access to databases", requiredMode = REQUIRED) boolean adminOnlyDbAddAccess,
		@Schema(description = "Whether only admins may make databases public", requiredMode = REQUIRED) boolean adminOnlyDbSetPublic,
		@Schema(description = "Whether only admins may make databases discoverable", requiredMode = REQUIRED) boolean adminOnlyDbSetDiscoverable,
		@Schema(description = "Whether only admins may add models", requiredMode = REQUIRED) boolean adminOnlyModelAdd,
		@Schema(description = "Whether only admins may delete models", requiredMode = REQUIRED) boolean adminOnlyModelDelete,
		@Schema(description = "Whether only admins may give access to models", requiredMode = REQUIRED) boolean adminOnlyModelAddAccess,
		@Schema(description = "Whether only admins may make models public", requiredMode = REQUIRED) boolean adminOnlyModelSetPublic,
		@Schema(description = "Whether only admins may make models discoverable", requiredMode = REQUIRED) boolean adminOnlyModelSetDiscoverable,
		@Schema(description = "Whether only admins may add storages", requiredMode = REQUIRED) boolean adminOnlyStorageAdd,
		@Schema(description = "Whether only admins may delete storages", requiredMode = REQUIRED) boolean adminOnlyStorageDelete,
		@Schema(description = "Whether only admins may give access to storages", requiredMode = REQUIRED) boolean adminOnlyStorageAddAccess,
		@Schema(description = "Whether only admins may make storages public", requiredMode = REQUIRED) boolean adminOnlyStorageSetPublic,
		@Schema(description = "Whether only admins may make storages discoverable", requiredMode = REQUIRED) boolean adminOnlyStorageSetDiscoverable,
		@Schema(description = "Whether only admins may add vector databases", requiredMode = REQUIRED) boolean adminOnlyVectorAdd,
		@Schema(description = "Whether only admins may delete vector databases", requiredMode = REQUIRED) boolean adminOnlyVectorDelete,
		@Schema(description = "Whether only admins may give access to vector databases", requiredMode = REQUIRED) boolean adminOnlyVectorAddAccess,
		@Schema(description = "Whether only admins may make vector databases public", requiredMode = REQUIRED) boolean adminOnlyVectorSetPublic,
		@Schema(description = "Whether only admins may make vector databases discoverable", requiredMode = REQUIRED) boolean adminOnlyVectorSetDiscoverable,
		@Schema(description = "Whether only admins may add functions", requiredMode = REQUIRED) boolean adminOnlyFunctionAdd,
		@Schema(description = "Whether only admins may delete functions", requiredMode = REQUIRED) boolean adminOnlyFunctionDelete,
		@Schema(description = "Whether only admins may give access to functions", requiredMode = REQUIRED) boolean adminOnlyFunctionAddAccess,
		@Schema(description = "Whether only admins may make functions public", requiredMode = REQUIRED) boolean adminOnlyFunctionSetPublic,
		@Schema(description = "Whether only admins may make functions discoverable", requiredMode = REQUIRED) boolean adminOnlyFunctionSetDiscoverable,
		@Schema(description = "Whether only admins may give access to insights", requiredMode = REQUIRED) boolean adminOnlyInsightAddAccess,
		@Schema(description = "Whether only admins may make insights public", requiredMode = REQUIRED) boolean adminOnlyInsightSetPublic,
		@Schema(description = "Whether only admins may share insights", requiredMode = REQUIRED) boolean adminOnlyInsightShare,

		@Schema(description = "Whether signing out shows a logout page instead of the login page", requiredMode = REQUIRED) boolean useLogoutPage,
		@JsonProperty("file-limit") @Schema(description = "Largest file transfer allowed, in megabytes; only present when configured") Integer fileLimit,
		@Schema(description = "Shared file path; only present when configured") String fileSharedPath,
		@Schema(description = "Build version of the server; only present when it can be read") VersionInfo version,
		@Schema(description = "Frame type new insights use, GRID by default", requiredMode = REQUIRED) String defaultFrameType,
		@Schema(description = "Scripting language new insights use, R by default", requiredMode = REQUIRED) String defaultScriptingLanguage,
		@Schema(description = "Whether this is a local deployment", requiredMode = REQUIRED) boolean localDeployment,
		@Schema(description = "Whether insights are cached by default", requiredMode = REQUIRED) boolean cacheInsightByDefault,
		@Schema(description = "How long a cached insight is kept, in minutes; -1 for no limit", requiredMode = REQUIRED) int cacheInsightMinutes,
		@Schema(description = "Whether cached insights are encrypted", requiredMode = REQUIRED) boolean cacheInsightEncrypt,
		@Schema(description = "Cron expression that clears the insight cache; only present when configured and valid") String cacheCron,
		@Schema(description = "Whether the welcome banner is shown", requiredMode = REQUIRED) boolean showWelcomeBanner,
		@Schema(description = "Each permission name mapped to its id", requiredMode = REQUIRED) Map<String, Integer> permissionMappingString,
		@Schema(description = "Each permission id mapped to its name", requiredMode = REQUIRED) Map<String, String> permissionMappingInteger,
		@Schema(description = "Pipeline landing filter; only present when configured") List<String> pipelineLandingFilter,
		@Schema(description = "Pipeline source filter; only present when configured") List<String> pipelineSourceFilter,
		@Schema(description = "Widget tab share and export options; only present when configured") List<String> widgetTabShareExportList,
		@Schema(description = "Whether only admins see the menu bar", requiredMode = REQUIRED) boolean adminOnlyViewMenuBarFlag,
		@Schema(description = "Whether only admins see non-approved items", requiredMode = REQUIRED) boolean adminOnlyNonAprrovedFlag,
		@Schema(description = "Base URL of the application", requiredMode = REQUIRED) String applicationUrl,

		@Schema(description = "Session timeout in minutes", requiredMode = REQUIRED) double timeout,
		@Schema(description = "Each login provider key mapped to whether it is allowed, plus registration", requiredMode = REQUIRED) Map<String, Boolean> loginsAllowed,
		@Schema(description = "Login providers that are enabled", requiredMode = REQUIRED) List<AvailableProvider> availableProviders,
		@Schema(description = "Whether users may register native accounts", requiredMode = REQUIRED) boolean nativeRegistration,
		@Schema(description = "Whether user searches can look in the Microsoft directory", requiredMode = REQUIRED) boolean msGraphLookup,
		@Schema(description = "Rules native account passwords must follow; only present when they can be read") PasswordRequirements passwordRequirements,
		@Schema(description = "Each provider the session is signed in to mapped to the user's name; empty when signed out", requiredMode = REQUIRED) Map<String, String> logins,
		@Schema(description = "Each provider the session is signed in to mapped to the login's details; empty when signed out", requiredMode = REQUIRED) Map<String, LoginDetail> loginDetails,
		@Schema(description = "The provider the session belongs to, upper case; only present when signed in") String primaryLogin,
		@Schema(description = "For each OAuth provider, whether each connector app can work with its sign in; only present when signed in") Map<String, Map<String, Boolean>> connectorAccess,
		@Schema(description = "The active admin theme's database row, or empty when there is none", requiredMode = REQUIRED) Map<String, Object> theme,
		@Schema(description = "Whether CSRF tokens are required on POST requests", requiredMode = REQUIRED) boolean csrf,
		@Schema(description = "Metadata keys available on databases", requiredMode = REQUIRED) List<MetakeyOption> databaseMetaKeys,
		@Schema(description = "Metadata keys available on engines", requiredMode = REQUIRED) List<MetakeyOption> engineMetaKeys,
		@Schema(description = "Metadata keys available on projects", requiredMode = REQUIRED) List<MetakeyOption> projectMetaKeys,
		@Schema(description = "Metadata keys available on insights", requiredMode = REQUIRED) List<MetakeyOption> insightMetaKeys,
		@Schema(description = "Metadata keys available on users", requiredMode = REQUIRED) List<MetakeyOption> userMetaKeys,
		@Schema(description = "Whether notifications are enabled", requiredMode = REQUIRED) boolean notificationEnabled,
		@Schema(description = "Whether audit logs are enabled", requiredMode = REQUIRED) boolean auditLogEnabled,
		@Schema(description = "Current server time in UTC, formatted yyyy-MM-dd HH:mm:ss", requiredMode = REQUIRED) String systemDate) {

	@Schema(description = "Build version of the server; only present when it can be read")
	public record VersionInfo(@Schema(description = "Version number") String version,
			@Schema(description = "Build date and time") String datetime) {
	}

	@Schema(description = "One enabled login provider")
	public record AvailableProvider(@Schema(description = "Display name", requiredMode = REQUIRED) String name,
			@Schema(description = "Provider key", requiredMode = REQUIRED) String provider,
			@Schema(description = "Whether it signs in with OAuth", requiredMode = REQUIRED) boolean isOauth,
			@Schema(description = "Provider label, such as MICROSOFT", requiredMode = REQUIRED) String label) {
	}

	@Schema(description = "Rules native account passwords must follow")
	public record PasswordRequirements(
			@Schema(description = "Minimum length", requiredMode = REQUIRED) int minPassLength,
			@Schema(description = "Whether an upper case letter is required", requiredMode = REQUIRED) boolean requireUpperCase,
			@Schema(description = "Whether a lower case letter is required", requiredMode = REQUIRED) boolean requireLowerCase,
			@Schema(description = "Whether a digit is required", requiredMode = REQUIRED) boolean requireNumeric,
			@Schema(description = "Whether a special character is required", requiredMode = REQUIRED) boolean requireSpecial,
			@Schema(description = "Days until a password expires; -1 for never", requiredMode = REQUIRED) int passwordExpirationDays,
			@Schema(description = "Whether an admin must reset an expired password", requiredMode = REQUIRED) boolean requireAdminResetForExpiration,
			@Schema(description = "Whether users may change their own password", requiredMode = REQUIRED) boolean allowUserChangePassword,
			@Schema(description = "How many previous passwords cannot be reused; -1 for no limit", requiredMode = REQUIRED) int passReuseCount,
			@Schema(description = "Days of inactivity before an account locks; -1 for never", requiredMode = REQUIRED) int daysToLock,
			@Schema(description = "Days before locking that a warning email is sent", requiredMode = REQUIRED) int daysToLockEmail) {
	}

	@Schema(description = "One login of the session")
	public record LoginDetail(@Schema(description = "The user's id with the provider") String id,
			@Schema(description = "The user's name with the provider", requiredMode = REQUIRED) String name,
			@Schema(description = "Subject alternative names, when there are any") Map<String, String> san) {
	}

	@Schema(description = "One metadata key and how it is shown")
	public record MetakeyOption(@Schema(description = "Key name", requiredMode = REQUIRED) String metakey,
			@JsonProperty("single_multi") @Schema(description = "Whether it holds one value or many", requiredMode = REQUIRED) String singleMulti,
			@JsonProperty("display_order") @Schema(description = "Display position", requiredMode = REQUIRED) int displayOrder,
			@JsonProperty("display_options") @Schema(description = "How it is shown") String displayOptions,
			@JsonProperty("display_values") @Schema(description = "Values to choose from") String displayValues) {
	}
}
