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
 * The requests and responses of {@link GitHubService}, for its OpenAPI
 * annotations; {@link AdminGitHubService} shares {@link GitHubError}.
 */
public final class GitHubServiceSchemas {

	private GitHubServiceSchemas() {
	}

	@Schema(description = "A refused GitHub request")
	public record GitHubError(@Schema(description = "Always \"error\"", allowableValues = "error", requiredMode = REQUIRED) String status,
			@Schema(description = "Why the request was refused", requiredMode = REQUIRED) String reason,
			@Schema(description = "True when the user has to run /github/user/authorize and retry") Boolean needsAuth) {
	}

	@Schema(description = "A change was made")
	public record GitHubOk(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status) {
	}

	@Schema(description = "Whether this instance has a GitHub App configured")
	public record GitHubAvailable(@Schema(description = "True when a GitHub App is configured", requiredMode = REQUIRED) boolean available) {
	}

	@Schema(description = "The GitHub App installations the user can access")
	public record GitHubInstallInstallations(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The installations", requiredMode = REQUIRED) List<GitHubInstallInstallationsRow> installations) {
	}

	@Schema(description = "One GitHub App installation")
	public record GitHubInstallInstallationsRow(@Schema(description = "The installation id", requiredMode = REQUIRED) long installationId,
			@Schema(description = "The login of the account it is installed on") String account,
			@Schema(description = "The type of that account, User or Organization") String accountType,
			@Schema(description = "Which repositories it grants: all or selected") String repositorySelection,
			@Schema(description = "True when the installation is suspended", requiredMode = REQUIRED) boolean suspended) {
	}

	@Schema(description = "The repositories of an installation the user can access")
	public record GitHubInstallRepos(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The installation id, as sent", requiredMode = REQUIRED) String installationId,
			@Schema(description = "The repositories", requiredMode = REQUIRED) List<GitHubInstallReposRow> repos) {
	}

	@Schema(description = "One GitHub repository")
	public record GitHubInstallReposRow(@Schema(description = "The repository id", requiredMode = REQUIRED) long id,
			@Schema(description = "The owner/name of the repository", requiredMode = REQUIRED) String fullName,
			@Schema(description = "The default branch; main for an empty repository", requiredMode = REQUIRED) String defaultBranch) {
	}

	@Schema(description = "The branches of a repository")
	public record GitHubInstallBranches(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The repository, as sent", requiredMode = REQUIRED) String repoFullName,
			@Schema(description = "The branch names", requiredMode = REQUIRED) List<String> branches) {
	}

	@Schema(description = "The form a project is linked to a repository with")
	public record GitHubInstallSelectRequest(@Schema(description = "The project to link; the user must own it", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The installation the repository is in", requiredMode = REQUIRED) Long installationId,
			@Schema(description = "The repository id", requiredMode = REQUIRED) Long repoId,
			@Schema(description = "The branch to track; the repository's default branch when not sent") String branch,
			@Schema(description = "A subdirectory of the repository to sync, for a monorepo; the whole repository when not sent") String subdir) {
	}

	@Schema(description = "The repository a project was linked to")
	public record GitHubInstallSelectResult(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The repository id", requiredMode = REQUIRED) long repoId,
			@Schema(description = "The owner/name of the repository, as GitHub reports it", requiredMode = REQUIRED) String repoFullName) {
	}

	@Schema(description = "The GitHub repository a project is linked to; only linked when there is none")
	public record GitHubProjectLink(@Schema(description = "True when the project is linked to a repository", requiredMode = REQUIRED) boolean linked,
			@Schema(description = "The project id") String projectId,
			@Schema(description = "The GitHub App id") Long appId,
			@Schema(description = "The installation id") Long installationId,
			@Schema(description = "The repository id") Long repoId,
			@Schema(description = "The owner/name of the repository") String repoFullName,
			@Schema(description = "The branch the project tracks") String branch,
			@Schema(description = "The subdirectory synced; absent for the whole repository") String subdir,
			@Schema(description = "When the link was made") String createdOn,
			@Schema(description = "When the link was last changed") String updatedOn) {
	}

	@Schema(description = "The form a project's tracked branch is set with")
	public record GitHubProjectBranchRequest(@Schema(description = "The project; the user must own it", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The branch to track", requiredMode = REQUIRED) String branch) {
	}

	@Schema(description = "The branch a project now tracks")
	public record GitHubProjectBranchResult(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The branch, trimmed", requiredMode = REQUIRED) String branch) {
	}

	@Schema(description = "What the webhook did with a delivery")
	public record GitHubWebhookResult(@Schema(description = "\"Push event processed\", or \"Event ignored: <event>\" for anything but a push", requiredMode = REQUIRED) String message) {
	}
}
