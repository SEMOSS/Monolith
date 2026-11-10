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
package prerna.semoss.web.github;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The responses of {@link AdminGitHubService}, for its OpenAPI annotations.
 */
public final class AdminGitHubServiceSchemas {

	private AdminGitHubServiceSchemas() {
	}

	@Schema(description = "The GitHub Apps configured on this instance")
	public record GitHubManifestApps(@Schema(description = "The apps, at most one today; credentials are left out", requiredMode = REQUIRED) List<GitHubManifestAppsRow> apps) {
	}

	@Schema(description = "One configured GitHub App, without its credentials")
	public record GitHubManifestAppsRow(@Schema(description = "The GitHub App id", requiredMode = REQUIRED) long appId,
			@Schema(description = "The app's slug, used in its install url") String slug,
			@Schema(description = "The app's name") String appName,
			@Schema(description = "The login of the account that owns the app") String ownerLogin,
			@Schema(description = "The app's page on GitHub") String htmlUrl,
			@Schema(description = "Where GitHub posts the app's webhooks") String webhookUrl,
			@Schema(description = "The app's OAuth client id") String clientId,
			@Schema(description = "When the app was saved") String createdOn,
			@Schema(description = "When the app was last changed") String updatedOn) {
	}

	@Schema(description = "Every project linked to a GitHub repository on this instance")
	public record GitHubManifestProjects(@Schema(description = "The project links", requiredMode = REQUIRED) List<GitHubManifestProjectsRow> projects) {
	}

	@Schema(description = "One project's GitHub repository link")
	public record GitHubManifestProjectsRow(@Schema(description = "The project id", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The GitHub App id") Long appId,
			@Schema(description = "The installation id") Long installationId,
			@Schema(description = "The repository id") Long repoId,
			@Schema(description = "The owner/name of the repository") String repoFullName,
			@Schema(description = "The branch the project tracks") String branch,
			@Schema(description = "The subdirectory synced; absent for the whole repository") String subdir,
			@Schema(description = "When the link was made") String createdOn,
			@Schema(description = "When the link was last changed") String updatedOn) {
	}

	@Schema(description = "The GitHub App that was removed from this instance")
	public record GitHubManifestAppDeleted(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The removed app's id", requiredMode = REQUIRED) long appId,
			@Schema(description = "The removed app's slug; empty when unknown", requiredMode = REQUIRED) String slug,
			@Schema(description = "The app's page on GitHub, where an owner deletes the registration; empty when unknown", requiredMode = REQUIRED) String htmlUrl,
			@Schema(description = "What was done and what is left to do on GitHub", requiredMode = REQUIRED) String message) {
	}
}
