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
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	 * Step 2: GitHub redirects here after the user installs the app. Links the
	 * single granted repo automatically, or sends the user to a picker when more
	 * than one repo was granted.
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

		// 4. Ask GitHub which repos this installation can access. The callback
		// itself never tells us - we have to authenticate as the app and query.
		List<GitHubAppClient.Repo> repos;
		try {
			repos = GitHubAppClient.getInstallationRepositories(installationId);
		} catch (Exception e) {
			classLogger.error("Failed to read repositories for installation {}", installationId, e);
			return WebUtility
					.getResponse(Map.of("status", "error", "reason", "unable to read installation repositories"), 400);
		}

		if (repos.isEmpty()) {
			return WebUtility.getResponse(
					Map.of("status", "error", "reason", "installation granted access to no repositories"), 400);
		}

		// 5. A project maps to exactly one repo.
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
				long installId = Long.parseLong(installationId);
				// GitHub's default_branch is null only for an empty repo - fall back to main
				String branch = (repo.defaultBranch() != null && !repo.defaultBranch().isBlank()) ? repo.defaultBranch()
						: "main";
				SecurityExternalConnectorsUtils.upsertGitHubProjectLink(projectId, appId, installId, repo.id(),
						repo.fullName(), branch);
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

		// 6. Redirect back to the frontend - user is now connected.
		return redirect(redirectUrl);
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

		List<GitHubAppClient.Repo> repos;
		try {
			repos = GitHubAppClient.getInstallationRepositories(installationId.trim());
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

		List<String> branches;
		try {
			branches = GitHubAppClient.listRepositoryBranches(installationId.trim(), repoFullName.trim());
		} catch (Exception e) {
			classLogger.error("Failed to list branches for repo {}", repoFullName, e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to read repository branches"), 502);
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

		// Validate the chosen repo is one the installation can access, and take the
		// authoritative full name from GitHub rather than trusting the client.
		GitHubAppClient.Repo chosen;
		try {
			chosen = GitHubAppClient.getInstallationRepositories(installationIdParam.trim()).stream()
					.filter(r -> r.id() == repoId).findFirst().orElse(null);
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
		try {
			SecurityExternalConnectorsUtils.upsertGitHubProjectLink(projectId, appId, installationId, chosen.id(),
					chosen.fullName(), branch);
		} catch (Exception e) {
			classLogger.error("Failed to link project {} to repo {}", projectId, chosen.fullName(), e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to save repository link"), 500);
		}

		classLogger.info("Linked project {} to repo {} (installation {})", projectId, chosen.fullName(),
				installationId);
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

	private void handlePushEvent(JsonObject payload) {
		String ref = payload.get("ref").getAsString();
		String branch = ref.replace("refs/heads/", "");
		String commitSha = payload.get("after").getAsString();
		String pusherName = payload.getAsJsonObject("pusher").get("name").getAsString();
		String repoName = payload.getAsJsonObject("repository").get("full_name").getAsString();
		long repoId = payload.getAsJsonObject("repository").get("id").getAsLong();
		JsonArray commits = payload.getAsJsonArray("commits");

		// Route this push to the project linked to the repo (matched on stable repo id)
		Map<String, Object> link = SecurityExternalConnectorsUtils.getGitHubProjectLinkByRepoId(repoId);
		if (link == null) {
			classLogger.info("No project linked to repo {} (id {}); ignoring push.", repoName, repoId);
			return;
		}
		String projectId = (String) link.get("projectId");

		classLogger.info("Push received - repo: {}, branch: {}, sha: {}, pusher: {}", repoName, branch, commitSha,
				pusherName);

		for (JsonElement commitEle : commits) {
			JsonObject commit = commitEle.getAsJsonObject();
			String id = commit.get("id").getAsString().substring(0, 7);
			String message = commit.get("message").getAsString();
			String author = commit.getAsJsonObject("author").get("name").getAsString();
			classLogger.info("  [{}] {} - by {}", id, message, author);
		}

		// Sync the project's local repo off the request thread so the webhook ACKs
		// within GitHub's delivery timeout. syncProjectFromGitHub no-ops when the
		// pushed branch is not the project's tracked branch.
		SYNC_EXECUTOR.submit(() -> {
			try {
				String result = GitHubProjectSync.syncProjectFromGitHub(projectId, branch);
				classLogger.info("Sync complete for project {} after push to branch {}: {}", projectId, branch, result);
			} catch (Exception e) {
				classLogger.error("Failed to sync project {} after push to branch {}", projectId, branch, e);
			}
		});
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
