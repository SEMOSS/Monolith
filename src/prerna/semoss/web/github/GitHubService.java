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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.auth.utils.SecurityExternalConnectorsUtils;
import prerna.io.connector.github.GitHubAppClient;
import prerna.io.connector.github.GitHubProjectSync;
import prerna.semoss.web.app.GitHubApplication;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * The per-project GitHub install/linking endpoints and the inbound webhook,
 * served under {@code /github} (see {@link GitHubApplication}).
 * <p>
 * Connect a project to a repository (install, repo picker, re-point,
 * disconnect) and receive push events. Per-project checks live in
 * {@link GitHubProjectGuard}; the admin-only app-manifest lifecycle lives in
 * {@link AdminGitHubService}. GitHub REST calls and URL helpers live in
 * {@link GitHubAppClient}.
 */
@Path("/")
public class GitHubService {

	private static final Logger classLogger = LogManager.getLogger(GitHubService.class);

	// ---------------------------------------------------------------------
	// Per-user authorization (scopes the picker to what each user can access)
	// ---------------------------------------------------------------------

	/** Session key holding the ephemeral GitHub user access token (memory only). */
	private static final String GH_SESSION_USER_TOKEN = "github_user_token";
	/** Session key holding the CSRF nonce for the user-authorization round-trip. */
	private static final String GH_SESSION_USER_OAUTH_STATE = "github_user_oauth_state";

	/**
	 * Runs push-triggered repo syncs off the request thread so the webhook can ACK
	 * within GitHub's delivery timeout. Single-threaded to serialize git
	 * operations; daemon-threaded so it never blocks JVM shutdown, and stopped on
	 * web app shutdown by {@link GitHubLifecycleListener}.
	 */
	private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "github-project-sync");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * Reports whether this instance has a GitHub App configured (pre-flight for the
	 * connect UI). Any logged-in user may call it; never exposes credentials.
	 * <p>
	 * {@code GET /github/available} -> {@code { "available": true|false } }
	 */
	@GET
	@Path("/available")
	public Response available(@Context HttpServletRequest req) {
		try {
			ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "login required"), 401);
		}

		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		boolean configured = app != null && app.get("slug") != null;
		return WebUtility.getResponse(Map.of("available", configured), 200);
	}

	/**
	 * Step 1: redirects the user to GitHub to install the GitHub App for a project.
	 * <p>
	 * {@code GET /github/install/app?projectId=...}
	 */
	@GET
	@Path("/install/app")
	public Response installApp(@Context HttpServletRequest req) {
		// 1. Grab the projectId the frontend passed in
		String projectId = req.getParameter("projectId");
		if (projectId == null || projectId.isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "projectId is required"), 400);
		}

		// 2. Look up the configured app slug (set by the app-manifest flow)
		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		if (app == null || app.get("slug") == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no GitHub App is configured"), 400);
		}
		String slug = (String) app.get("slug");

		// 3. Store projectId in session so we can retrieve it in the callback
		HttpSession session = req.getSession(true);
		session.setAttribute("github_pending_project_id", projectId);

		// 4. Redirect to GitHub's install page (it lets the user pick repos)
		return redirect("https://github.com/apps/" + slug + "/installations/new");
	}

	/**
	 * Step 2: GitHub redirects here after the user installs the app. Confirms the
	 * installation belongs to this user (authorizing first if needed), then links
	 * the single granted repo automatically, or sends the user to a picker when
	 * more than one repo was granted.
	 * <p>
	 * {@code GET /github/install/callback?installation_id=...&setup_action=install}
	 */
	@GET
	@Path("/install/callback")
	public Response installCallback(@Context HttpServletRequest req) {
		// 1. Extract what GitHub sent back
		String installationId = req.getParameter("installation_id");

		if (installationId == null || installationId.isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "missing installation_id"), 400);
		}

		// 2. Retrieve the projectId we stored in session before the redirect
		HttpSession session = req.getSession(false);
		if (session == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "session expired"), 400);
		}

		String projectId = (String) session.getAttribute("github_pending_project_id");
		if (projectId == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no pending project"), 400);
		}

		// 3. Clean up session
		session.removeAttribute("github_pending_project_id");

		// 4. Validate the installation belongs to THIS user before linking anything.
		// The installation_id is attacker-controllable (it rides in the query string),
		// so we must not trust it: a user could otherwise link another tenant's
		// installation by guessing its id. Validation needs a user token; if we don't
		// have one yet, authorize first and resume here (see userCallback).
		String userToken = sessionUserToken(req);
		if (userToken == null) {
			return beginInstallAuthorize(req, session, projectId, installationId);
		}
		return completeInstallLink(req, projectId, installationId, userToken, true);
	}

	/**
	 * Links (or routes to the picker for) a freshly installed app, after confirming
	 * the installation is one the current user can actually access. Shared by the
	 * install callback and its just-in-time authorization resume.
	 *
	 * @param canReauth whether an expired token may trigger another authorization
	 *                  redirect (true from the install callback; false on the
	 *                  resume path, to avoid an auth loop)
	 */
	private Response completeInstallLink(HttpServletRequest req, String projectId, String installationId,
			String userToken, boolean canReauth) {
		long installId;
		try {
			installId = Long.parseLong(installationId);
		} catch (NumberFormatException e) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "invalid installation_id"), 400);
		}

		// The installation must be one this user can access - this is what stops a
		// user linking another tenant's installation. Then read repos user-scoped.
		List<GitHubAppClient.Repo> repos;
		try {
			boolean ownsInstall = GitHubAppClient.listUserInstallations(userToken).stream()
					.anyMatch(i -> i.id() == installId);
			if (!ownsInstall) {
				return WebUtility.getResponse(
						Map.of("status", "error", "reason", "installation is not accessible to this user"), 403);
			}
			repos = GitHubAppClient.listUserInstallationRepositories(userToken, installationId);
		} catch (GitHubAppClient.UserAuthRequiredException e) {
			clearUserToken(req);
			if (canReauth) {
				return beginInstallAuthorize(req, req.getSession(true), projectId, installationId);
			}
			return needsAuth();
		} catch (Exception e) {
			classLogger.error("Failed to read repositories for installation {}", installationId, e);
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "unable to read installation repositories"), 400);
		}

		if (repos.isEmpty()) {
			return WebUtility.getResponse(
					Map.of("status", "error", "reason", "installation granted access to no repositories"), 400);
		}

		// A project maps to exactly one repo.
		// - Exactly one repo selected -> link it automatically (best UX).
		// - More than one (or "all") -> we cannot guess; send them to a picker.
		String redirectUrl = GitHubAppClient.publicBaseUrl();
		redirectUrl = redirectUrl.substring(0, redirectUrl.lastIndexOf("/"));
		redirectUrl += "/" + Utility.getFEWebAppName() + "/packages/client/dist/#/app/" + projectId;

		if (repos.size() == 1) {
			GitHubAppClient.Repo repo = repos.get(0);
			try {
				Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
				long appId = ((Number) app.get("appId")).longValue();
				// GitHub's default_branch is null only for an empty repo - fall back to main
				String branch = (repo.defaultBranch() != null && !repo.defaultBranch().isBlank()) ? repo.defaultBranch()
						: "main";
				// no subdir for auto-linked single-repo installs; user can set one later via
				// the selectRepo flow
				SecurityExternalConnectorsUtils.upsertGitHubProjectLink(projectId, appId, installId, repo.id(),
						repo.fullName(), branch, null);
			} catch (Exception e) {
				classLogger.error("Failed to save installation for project {}", projectId, e);
				return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to save installation"), 400);
			}
			classLogger.info("Linked project {} to repo {} via installation {}", projectId, repo.fullName(),
					installationId);
		} else {
			// User granted access to multiple repos - let them choose which one
			// belongs to this project. The picker calls back to persist the choice.
			classLogger.info("Installation {} grants {} repos to project {}; routing to repo picker", installationId,
					repos.size(), projectId);
			redirectUrl += "/github/select-repo?installation_id=" + installationId;
		}

		// Redirect back to the frontend - user is now connected.
		return redirect(redirectUrl);
	}

	/**
	 * Stashes the pending install + project and redirects the user through GitHub
	 * authorization; {@link #userCallback} resumes {@link #completeInstallLink}
	 * once a user token is available.
	 */
	private Response beginInstallAuthorize(HttpServletRequest req, HttpSession session, String projectId,
			String installationId) {
		session.setAttribute("github_pending_install_id", installationId);
		session.setAttribute("github_pending_project_id", projectId);
		return userAuthorizeRedirect(req, projectId);
	}

	/**
	 * Step 1 of per-user authorization: redirects the user to GitHub to authorize
	 * the app on their behalf. The resulting user token (obtained in
	 * {@link #userCallback}) scopes the installation/repo pickers to only what this
	 * user can access on GitHub, so one tenant never sees another's installations.
	 * <p>
	 * {@code GET /github/user/authorize?projectId=...}
	 */
	@GET
	@Path("/user/authorize")
	public Response userAuthorize(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);
		return userAuthorizeRedirect(req, projectId);
	}

	/**
	 * Builds the GitHub user-authorization redirect for a project: stashes a CSRF
	 * nonce in session and carries the projectId in {@code state} so the callback
	 * can resume on the right project even on a fresh session. Shared by the
	 * explicit authorize endpoint and the install callback's just-in-time
	 * authorization.
	 */
	private Response userAuthorizeRedirect(HttpServletRequest req, String projectId) {
		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		if (app == null || app.get("clientId") == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no GitHub App is configured"), 400);
		}
		String clientId = (String) app.get("clientId");

		// CSRF nonce in session; projectId carried in state so the callback can return
		// the user to the right project picker even on a fresh session
		String nonce = UUID.randomUUID().toString();
		HttpSession session = req.getSession(true);
		session.setAttribute(GH_SESSION_USER_OAUTH_STATE, nonce);
		String state = nonce + ":" + projectId;

		String redirectUri = GitHubAppClient.publicBaseUrl() + "/github/user/callback";
		String url = "https://github.com/login/oauth/authorize?client_id=" + enc(clientId) + "&redirect_uri="
				+ enc(redirectUri) + "&state=" + enc(state);
		return redirect(url);
	}

	/**
	 * Step 2 of per-user authorization: GitHub redirects here with a one-time code.
	 * Exchanges it for a user access token, stashes it in the session (memory only,
	 * never persisted), and returns the user to the project's GitHub picker.
	 * <p>
	 * {@code GET /github/user/callback?code=...&state=...}
	 */
	@GET
	@Path("/user/callback")
	public Response userCallback(@Context HttpServletRequest req) {
		String code = req.getParameter("code");
		String state = req.getParameter("state");
		if (code == null || code.isEmpty() || state == null || state.isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "missing code or state"), 400);
		}

		// verify the CSRF nonce, then split projectId back out of state
		HttpSession session = req.getSession(false);
		String expectedNonce = session == null ? null : (String) session.getAttribute(GH_SESSION_USER_OAUTH_STATE);
		int sep = state.indexOf(':');
		String nonce = sep < 0 ? state : state.substring(0, sep);
		String projectId = sep < 0 ? null : state.substring(sep + 1);
		if (expectedNonce == null || !expectedNonce.equals(nonce)) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "invalid state"), 400);
		}
		session.removeAttribute(GH_SESSION_USER_OAUTH_STATE);

		String userToken;
		try {
			userToken = GitHubAppClient.exchangeUserCode(code);
			session.setAttribute(GH_SESSION_USER_TOKEN, userToken);
		} catch (Exception e) {
			classLogger.error("Failed to exchange GitHub user authorization code", e);
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "unable to complete GitHub authorization"), 400);
		}

		// If we were sent here to authorize an install callback that needs validation,
		// resume it now that we have a user token (canReauth=false to avoid a loop).
		String pendingInstall = (String) session.getAttribute("github_pending_install_id");
		if (pendingInstall != null) {
			session.removeAttribute("github_pending_install_id");
			session.removeAttribute("github_pending_project_id");
			return completeInstallLink(req, projectId, pendingInstall, userToken, false);
		}

		String redirectUrl = GitHubAppClient.publicBaseUrl();
		redirectUrl = redirectUrl.substring(0, redirectUrl.lastIndexOf("/"));
		redirectUrl += "/" + Utility.getFEWebAppName() + "/packages/client/dist/#/app/"
				+ (projectId == null ? "" : projectId);
		return redirect(redirectUrl);
	}

	/**
	 * Lists every installation of the app the calling user can access, so they can
	 * choose which one to link a project to, instead of relying on the single
	 * installation GitHub hands back on the install/setup redirect. This lets a
	 * project connect to an app already installed on GitHub (out of band, or
	 * installed for a previous project) without re-running the install redirect at
	 * all. Scoped to the user's own GitHub access, so one tenant never sees
	 * another's installations.
	 * <p>
	 * Connecting a project is owner-gated, so this is too. Requires a user token
	 * (returns {@code 401 needsAuth} to send the user through
	 * {@code /user/authorize}).
	 * <p>
	 * {@code GET /github/install/installations?projectId=...}
	 */
	@GET
	@Path("/install/installations")
	public Response installInstallations(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		if (app == null || app.get("appId") == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no GitHub App is configured"), 400);
		}

		String userToken = sessionUserToken(req);
		if (userToken == null) {
			return needsAuth();
		}

		List<GitHubAppClient.Installation> installations;
		try {
			installations = GitHubAppClient.listUserInstallations(userToken);
		} catch (GitHubAppClient.UserAuthRequiredException e) {
			clearUserToken(req);
			return needsAuth();
		} catch (Exception e) {
			classLogger.error("Failed to list GitHub App installations", e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to read installations"), 502);
		}

		List<Map<String, Object>> out = new ArrayList<>();
		for (GitHubAppClient.Installation inst : installations) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("installationId", inst.id());
			row.put("account", inst.accountLogin());
			row.put("accountType", inst.accountType());
			row.put("repositorySelection", inst.repositorySelection());
			row.put("suspended", inst.suspended());
			out.add(row);
		}
		return WebUtility.getResponse(Map.of("status", "ok", "installations", out), 200);
	}

	/**
	 * Lists the repositories an installation can access, for the repo picker (and
	 * the "change repository" flow).
	 * <p>
	 * {@code GET /github/install/repos?projectId=...&installationId=...}
	 */
	@GET
	@Path("/install/repos")
	public Response installRepos(@Context HttpServletRequest req) {
		// Reading an installation's repos is part of linking a project, so it
		// requires owner access to that project.
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		String installationId = req.getParameter("installationId");
		if (installationId == null || installationId.trim().isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "installationId is required"), 400);
		}

		String userToken = sessionUserToken(req);
		if (userToken == null) {
			return needsAuth();
		}

		List<GitHubAppClient.Repo> repos;
		try {
			// user-scoped: only repos this user can see in the installation
			repos = GitHubAppClient.listUserInstallationRepositories(userToken, installationId.trim());
		} catch (GitHubAppClient.UserAuthRequiredException e) {
			clearUserToken(req);
			return needsAuth();
		} catch (Exception e) {
			classLogger.error("Failed to list repositories for installation {}", installationId, e);
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "unable to read installation repositories"), 502);
		}

		List<Map<String, Object>> out = new ArrayList<>();
		for (GitHubAppClient.Repo repo : repos) {
			out.add(Map.of("id", repo.id(), "fullName", repo.fullName(), "defaultBranch",
					repo.defaultBranch() != null ? repo.defaultBranch() : "main"));
		}
		return WebUtility.getResponse(Map.of("status", "ok", "installationId", installationId, "repos", out), 200);
	}

	/**
	 * Lists the branches of a repository the installation can access, for the
	 * branch picker in the connect / change-branch flows.
	 * <p>
	 * {@code GET /github/install/branches?projectId=...&installationId=...&repoFullName=owner/repo}
	 */
	@GET
	@Path("/install/branches")
	public Response installBranches(@Context HttpServletRequest req) {
		// listing a repo's branches is part of linking/configuring a project, so it
		// requires owner access to that project
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		String installationId = req.getParameter("installationId");
		String repoFullName = req.getParameter("repoFullName");
		if (installationId == null || installationId.trim().isEmpty() || repoFullName == null
				|| repoFullName.trim().isEmpty()) {
			return WebUtility.getResponse(
					Map.of("status", "error", "reason", "installationId and repoFullName are required"), 400);
		}

		String userToken = sessionUserToken(req);
		if (userToken == null) {
			return needsAuth();
		}

		List<String> branches;
		try {
			// confirm the user can actually see this repo before exposing its branches,
			// then read branches with the app token (branch listing is app-scoped)
			boolean accessible = GitHubAppClient.listUserInstallationRepositories(userToken, installationId.trim())
					.stream().anyMatch(r -> r.fullName().equalsIgnoreCase(repoFullName.trim()));
			if (!accessible) {
				return WebUtility.getResponse(
						Map.of("status", "error", "reason", "repository is not accessible to this user"), 403);
			}
			branches = GitHubAppClient.listRepositoryBranches(installationId.trim(), repoFullName.trim());
		} catch (GitHubAppClient.UserAuthRequiredException e) {
			clearUserToken(req);
			return needsAuth();
		} catch (Exception e) {
			classLogger.error("Failed to list branches for repo {}", repoFullName, e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to read repository branches"),
					502);
		}

		return WebUtility.getResponse(Map.of("status", "ok", "repoFullName", repoFullName, "branches", branches), 200);
	}

	/**
	 * Persists which repository a project uses (creates or re-points the link). The
	 * chosen repo id is validated against the installation's accessible repos.
	 * <p>
	 * {@code POST /github/install/select?projectId=...&installationId=...&repoId=...}
	 */
	@POST
	@Path("/install/select")
	public Response selectRepo(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		String installationIdParam = req.getParameter("installationId");
		String repoIdParam = req.getParameter("repoId");
		if (installationIdParam == null || installationIdParam.trim().isEmpty() || repoIdParam == null
				|| repoIdParam.trim().isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "installationId and repoId are required"),
					400);
		}

		long installationId;
		long repoId;
		try {
			installationId = Long.parseLong(installationIdParam.trim());
			repoId = Long.parseLong(repoIdParam.trim());
		} catch (NumberFormatException e) {
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "installationId and repoId must be numbers"), 400);
		}

		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		if (app == null || app.get("appId") == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no GitHub App is configured"), 400);
		}
		long appId = ((Number) app.get("appId")).longValue();

		String userToken = sessionUserToken(req);
		if (userToken == null) {
			return needsAuth();
		}

		// Validate the chosen repo against the USER's accessible repos (not the
		// app-wide list), so a user cannot link a repo they cannot see. Also take the
		// authoritative full name from GitHub rather than trusting the client.
		GitHubAppClient.Repo chosen;
		try {
			chosen = GitHubAppClient.listUserInstallationRepositories(userToken, installationIdParam.trim()).stream()
					.filter(r -> r.id() == repoId).findFirst().orElse(null);
		} catch (GitHubAppClient.UserAuthRequiredException e) {
			clearUserToken(req);
			return needsAuth();
		} catch (Exception e) {
			classLogger.error("Failed to verify repo {} for installation {}", repoId, installationId, e);
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "unable to read installation repositories"), 502);
		}
		if (chosen == null) {
			return WebUtility.getResponse(
					Map.of("status", "error", "reason", "selected repository is not accessible to this installation"),
					400);
		}

		// the FE may pass an explicit branch to track; otherwise default to the repo's
		// default branch (and main for the rare empty repo with no default)
		String branchParam = req.getParameter("branch");
		String branch = (branchParam != null && !branchParam.trim().isEmpty()) ? branchParam.trim()
				: (chosen.defaultBranch() != null && !chosen.defaultBranch().isBlank() ? chosen.defaultBranch()
						: "main");
		// optional subdir for monorepo support; null/blank = full-repo sync
		String subdir = req.getParameter("subdir");
		try {
			SecurityExternalConnectorsUtils.upsertGitHubProjectLink(projectId, appId, installationId, chosen.id(),
					chosen.fullName(), branch, subdir);
		} catch (Exception e) {
			classLogger.error("Failed to link project {} to repo {}", projectId, chosen.fullName(), e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to save repository link"), 500);
		}

		classLogger.info("Linked project {} to repo {} (installation {})", projectId, chosen.fullName(),
				installationId);
		// TODO: should we just sync after initial setup? why wait for a webhook
		return WebUtility.getResponse(Map.of("status", "ok", "repoId", chosen.id(), "repoFullName", chosen.fullName()),
				200);
	}

	/**
	 * Returns the GitHub repository a project is linked to, if any. Used by the
	 * project's GitHub tab to render its connected / not-connected state.
	 * <p>
	 * {@code GET /github/project/link?projectId=...} -> {@code { "linked": true,
	 * "projectId", "repoId", "repoFullName", "installationId", ... }} or {@code {
	 * "linked": false }}
	 */
	@GET
	@Path("/project/link")
	public Response getLink(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		Map<String, Object> link = SecurityExternalConnectorsUtils.getGitHubProjectLink(projectId);
		if (link == null) {
			return WebUtility.getResponse(Map.of("linked", false), 200);
		}

		Map<String, Object> out = new LinkedHashMap<>(link);
		out.put("linked", true);
		return WebUtility.getResponse(out, 200);
	}

	/**
	 * Disconnects a project from its linked GitHub repository (unlinks only; does
	 * not uninstall the app).
	 * <p>
	 * {@code DELETE /github/project/link?projectId=...}
	 */
	@DELETE
	@Path("/project/link")
	public Response disconnect(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		try {
			SecurityExternalConnectorsUtils.deleteGitHubProjectLink(projectId);
		} catch (Exception e) {
			classLogger.error("Failed to disconnect GitHub repository from project {}", projectId, e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to disconnect repository"), 500);
		}

		classLogger.info("Disconnected GitHub repository from project {}", projectId);
		return WebUtility.getResponse(Map.of("status", "ok"), 200);
	}

	/**
	 * Sets the branch a project tracks - the branch the push webhook syncs the
	 * project's local repo to.
	 * <p>
	 * {@code POST /github/project/branch?projectId=...&branch=...}
	 */
	@POST
	@Path("/project/branch")
	public Response setBranch(@Context HttpServletRequest req) {
		String projectId = req.getParameter("projectId");
		GitHubProjectGuard.requireProjectOwner(req, projectId);

		String branch = req.getParameter("branch");
		if (branch == null || branch.trim().isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "branch is required"), 400);
		}

		if (SecurityExternalConnectorsUtils.getGitHubProjectLink(projectId) == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "project is not linked to a repository"),
					400);
		}

		try {
			SecurityExternalConnectorsUtils.updateGitHubProjectLinkBranch(projectId, branch.trim());
		} catch (Exception e) {
			classLogger.error("Failed to set tracked branch for project {}", projectId, e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to set branch"), 500);
		}

		classLogger.info("Set tracked branch for project {} to {}", projectId, branch.trim());
		return WebUtility.getResponse(Map.of("status", "ok", "branch", branch.trim()), 200);
	}

	// ---------------------------------------------------------------------
	// Inbound webhook (authenticated by signature, not session)
	// ---------------------------------------------------------------------

	/**
	 * Receives the webhook events GitHub POSTs. Each delivery is authenticated by
	 * verifying the {@code X-Hub-Signature-256} HMAC against the app's webhook
	 * secret before any processing.
	 * <p>
	 * {@code POST /github/webhook}
	 */
	@POST
	@Path("/webhook")
	public Response webhook(@Context HttpServletRequest req) throws IOException {
		// 1. Read the exact raw body (the signature is an HMAC over these bytes)
		String body = readBody(req);

		// 2. Verify the payload signature: HMAC-SHA256 of the body keyed with the
		// app's webhook secret (from the DB) must match the X-Hub-Signature-256 header.
		String signature = req.getHeader("X-Hub-Signature-256");
		if (!verifySignature(body, signature)) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "invalid signature"), 401);
		}

		// 3. Check event type
		String event = req.getHeader("X-GitHub-Event");
		if (!"push".equals(event)) {
			return WebUtility.getResponse(Map.of("message", "Event ignored: " + event), 200);
		}

		// 4. Parse and handle the push payload
		JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
		handlePushEvent(payload);

		return WebUtility.getResponse(Map.of("message", "Push event processed"), 200);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/**
	 * Stops the background sync executor, waiting briefly for an in-flight sync to
	 * finish. Invoked by {@link GitHubLifecycleListener} on web app shutdown.
	 */
	static void shutdownSyncExecutor() {
		SYNC_EXECUTOR.shutdown();
		try {
			if (!SYNC_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
				SYNC_EXECUTOR.shutdownNow();
			}
		} catch (InterruptedException e) {
			SYNC_EXECUTOR.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** Builds a 303 redirect to the given absolute url. */
	private static Response redirect(String url) {
		return Response.seeOther(URI.create(url)).build();
	}

	/** URL-encodes a query value (UTF-8). */
	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** The ephemeral GitHub user token stashed in session, or null if absent. */
	private static String sessionUserToken(HttpServletRequest req) {
		HttpSession session = req.getSession(false);
		return session == null ? null : (String) session.getAttribute(GH_SESSION_USER_TOKEN);
	}

	/** Drops a stale user token so the next call re-runs authorization. */
	private static void clearUserToken(HttpServletRequest req) {
		HttpSession session = req.getSession(false);
		if (session != null) {
			session.removeAttribute(GH_SESSION_USER_TOKEN);
		}
	}

	/**
	 * 401 telling the FE to (re)run {@code /github/user/authorize} and retry. Any
	 * missing/expired/revoked user token funnels through this single signal.
	 */
	private static Response needsAuth() {
		return WebUtility.getResponse(
				Map.of("status", "error", "needsAuth", true, "reason", "GitHub authorization required"), 401);
	}

	private void handlePushEvent(JsonObject payload) {
		String ref = payload.get("ref").getAsString();
		String branch = ref.replace("refs/heads/", "");
		String commitSha = payload.get("after").getAsString();
		String pusherName = payload.getAsJsonObject("pusher").get("name").getAsString();
		String repoName = payload.getAsJsonObject("repository").get("full_name").getAsString();
		long repoId = payload.getAsJsonObject("repository").get("id").getAsLong();
		JsonArray commits = payload.getAsJsonArray("commits");

		// Route this push to EVERY project linked to the repo (matched on stable repo
		// id). A repo can feed multiple projects, each tracking the same or a different
		// branch, so we must fan out rather than pick one.
		List<Map<String, Object>> links = SecurityExternalConnectorsUtils.getGitHubProjectLinksByRepoId(repoId);
		if (links.isEmpty()) {
			classLogger.info("No project linked to repo {} (id {}); ignoring push.", repoName, repoId);
			return;
		}

		classLogger.info("Push received - repo: {}, branch: {}, sha: {}, pusher: {} ({} linked project(s))", repoName,
				branch, commitSha, pusherName, links.size());

		for (JsonElement commitEle : commits) {
			JsonObject commit = commitEle.getAsJsonObject();
			String id = commit.get("id").getAsString().substring(0, 7);
			String message = commit.get("message").getAsString();
			String author = commit.getAsJsonObject("author").get("name").getAsString();
			classLogger.info("  [{}] {} - by {}", id, message, author);
		}

		// Sync each linked project's local repo off the request thread so the webhook
		// ACKs within GitHub's delivery timeout. syncProjectFromGitHub no-ops when the
		// pushed branch is not that project's tracked branch, so projects tracking
		// other branches are skipped automatically.
		for (Map<String, Object> link : links) {
			String projectId = (String) link.get("projectId");
			SYNC_EXECUTOR.submit(() -> {
				try {
					String result = GitHubProjectSync.syncProjectFromGitHub(projectId, branch);
					classLogger.info("Sync complete for project {} after push to branch {}: {}", projectId, branch,
							result);
				} catch (Exception e) {
					classLogger.error("Failed to sync project {} after push to branch {}", projectId, branch, e);
				}
			});
		}
	}

	// Read the exact raw body. The signature is an HMAC over these bytes, so it
	// must not be reflowed or trimmed.
	private String readBody(HttpServletRequest req) throws IOException {
		return new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	private boolean verifySignature(String body, String signatureHeader) {
		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		String webhookSecret = app == null ? null : (String) app.get("webhookSecret");
		if (webhookSecret == null || signatureHeader == null) {
			return false;
		}
		if (!signatureHeader.startsWith("sha256=")) {
			return false;
		}

		try {
			String receivedHex = signatureHeader.substring("sha256=".length());

			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] computedBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
			String computedHex = HexFormat.of().formatHex(computedBytes);

			// Constant-time comparison to prevent timing attacks
			return MessageDigest.isEqual(receivedHex.getBytes(StandardCharsets.UTF_8),
					computedHex.getBytes(StandardCharsets.UTF_8));

		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			classLogger.error("Signature verification error", e);
			return false;
		}
	}
}
