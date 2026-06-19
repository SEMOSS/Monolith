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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import prerna.auth.utils.SecurityExternalConnectorsUtils;
import prerna.io.connector.github.GitHubAppClient;
import prerna.web.services.util.WebUtility;

/**
 * Admin-only GitHub App-manifest lifecycle endpoints, served under
 * {@code /github}.
 * <p>
 * Creating, listing, and deleting the instance's GitHub App registration - the
 * record holds the app's private key, so every endpoint is gated by
 * {@link GitHubAdminGuard}. The per-project install/linking flow and the
 * inbound webhook live in {@link GitHubService}; GitHub REST calls and URL
 * helpers live in {@link GitHubAppClient}.
 */
@Path("/")
public class AdminGitHubService {

	private static final Logger classLogger = LogManager.getLogger(AdminGitHubService.class);

	// Manifest building only: disableHtmlEscaping keeps the URLs in the manifest
	// readable (GitHub parses either form).
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** Client hash-route the admin lands back on after the manifest round-trip. */
	private static final String SETTINGS_ROUTE = "/settings/github-app";

	/** Credential fields that must never be sent to the frontend. */
	private static final List<String> SECRET_FIELDS = List.of("clientSecret", "webhookSecret", "privateKey");

	/**
	 * Phase A, step 1: kicks off the GitHub App "manifest" flow by rendering an
	 * auto-submitting form that POSTs the manifest to GitHub.
	 * <p>
	 * {@code GET /github/manifest/new} (optionally {@code ?org=...&name=...})
	 */
	@GET
	@Path("/manifest/new")
	public Response createApp(@Context HttpServletRequest req) {
		// Creating a GitHub App is an instance-wide, privileged operation.
		GitHubAdminGuard.requireAdmin(req);

		// base url GitHub redirects the browser back to (ngrok origin in local testing)
		String baseUrl = GitHubAppClient.publicBaseUrl();

		String appName = req.getParameter("name");
		if (appName == null || appName.trim().isEmpty()) {
			appName = "Semoss GitHub App";
		}
		String org = req.getParameter("org");
		boolean isPublic = Boolean.parseBoolean(req.getParameter("public") + "");

		// 1. CSRF token - stashed in session, echoed back by GitHub, verified in the
		// callback
		String state = UUID.randomUUID().toString();
		HttpSession session = req.getSession(true);
		session.setAttribute("github_manifest_state", state);

		// 2. Build the manifest (the app's settings as JSON)
		String manifestJson;
		try {
			manifestJson = buildManifest(appName, baseUrl, isPublic);
		} catch (Exception e) {
			classLogger.error("Failed to build GitHub manifest", e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to build manifest"), 500);
		}

		// 3. Where GitHub creates the app: a user account vs an organization
		String action = (org == null || org.trim().isEmpty()) ? "https://github.com/settings/apps/new"
				: "https://github.com/organizations/" + url(org.trim()) + "/settings/apps/new";
		action += "?state=" + url(state);

		// 4. Render an auto-submitting form. The manifest must go in a POST body
		// field (it is too big and structured for a query string).
		String html = "<!DOCTYPE html><html><head><title>Create GitHub App</title></head>"
				+ "<body onload=\"document.forms[0].submit()\">" + "<form action=\"" + attr(action)
				+ "\" method=\"post\">" + "<input type=\"hidden\" name=\"manifest\" value=\"" + attr(manifestJson)
				+ "\">" + "<noscript><button type=\"submit\">Create GitHub App</button></noscript>"
				+ "</form></body></html>";
		return Response.ok(html, MediaType.TEXT_HTML).build();
	}

	/**
	 * Phase A, step 2: GitHub redirects here after the admin clicks "Create GitHub
	 * App". Exchanges the one-time code for the app's config and persists it, then
	 * redirects the admin back to the settings UI.
	 * <p>
	 * {@code GET /github/manifest/callback?code=...&state=...}
	 */
	@GET
	@Path("/manifest/callback")
	public Response manifestCallback(@Context HttpServletRequest req) {
		// This persists the app's private key, so it stays admin-only. GitHub
		// redirects the admin's own browser here, so their session is present.
		GitHubAdminGuard.requireAdmin(req);

		// 1. Extract what GitHub sent back
		String code = req.getParameter("code");
		String state = req.getParameter("state");
		if (code == null || code.isEmpty()) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "missing code"), 400);
		}

		// 2. Verify the CSRF state we stashed in createApp
		HttpSession session = req.getSession(false);
		String expectedState = session == null ? null : (String) session.getAttribute("github_manifest_state");
		if (expectedState == null || !expectedState.equals(state)) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "invalid state"), 400);
		}
		session.removeAttribute("github_manifest_state");

		// 3. Exchange the one-time code for the app's full config
		JsonObject app;
		try {
			app = GitHubAppClient.convertManifest(code);
		} catch (Exception e) {
			classLogger.error("Failed to convert GitHub manifest code", e);
			return redirect(GitHubAppClient.frontendUrl(SETTINGS_ROUTE + "?githubApp=error&reason=conversion_failed"));
		}

		// 4. Persist it. The webhook url is the one we configured in the manifest.
		long appId = app.has("id") && !app.get("id").isJsonNull() ? app.get("id").getAsLong() : 0L;
		String slug = str(app, "slug");
		String appName = str(app, "name");
		JsonObject owner = app.getAsJsonObject("owner");
		String ownerLogin = owner == null ? "" : str(owner, "login");
		String htmlUrl = str(app, "html_url");
		String clientId = str(app, "client_id");
		String clientSecret = str(app, "client_secret");
		String webhookSecret = str(app, "webhook_secret");
		String privateKey = str(app, "pem");
		String webhookUrl = GitHubAppClient.webhookUrl();

		try {
			SecurityExternalConnectorsUtils.upsertGitHubApp(appId, slug, appName, ownerLogin, htmlUrl, webhookUrl,
					clientId, clientSecret, webhookSecret, privateKey);
		} catch (Exception e) {
			classLogger.error("Failed to persist GitHub App", e);
			return redirect(GitHubAppClient.frontendUrl(SETTINGS_ROUTE + "?githubApp=error&reason=save_failed"));
		}

		classLogger.info("Saved GitHub App {} (slug {}) for owner {}", appId, slug, ownerLogin);

		// 5. Send the admin back to the settings UI. It shows a success toast and
		// refetches the configured app from /github/manifest/apps.
		return redirect(GitHubAppClient.frontendUrl(SETTINGS_ROUTE + "?githubApp=created"));
	}

	/**
	 * Lists the GitHub App(s) configured for this instance (secrets stripped).
	 * <p>
	 * {@code GET /github/manifest/apps}
	 */
	@GET
	@Path("/manifest/apps")
	public Response listApps(@Context HttpServletRequest req) {
		// Reading the configured app (and its credentials) is admin-only.
		GitHubAdminGuard.requireAdmin(req);

		// Today the instance has at most one configured app. We still return a
		// list so the FE contract does not change if multi-app support is added.
		Map<String, Object> app = SecurityExternalConnectorsUtils.getGitHubApp();
		List<Map<String, Object>> apps = new ArrayList<>();
		if (app != null) {
			apps.add(sanitize(app));
		}
		return WebUtility.getResponse(Map.of("apps", apps), 200);
	}

	/**
	 * Lists every project -> GitHub repository link configured on this instance, so
	 * the admin can see which projects are connected to GitHub.
	 * <p>
	 * {@code GET /github/manifest/projects} -> {@code { "projects": [ {
	 * "projectId", "appId", "installationId", "repoId", "repoFullName",
	 * "createdOn", "updatedOn" } ] }}
	 */
	@GET
	@Path("/manifest/projects")
	public Response listProjects(@Context HttpServletRequest req) {
		// Reading the instance-wide link table is admin-only.
		GitHubAdminGuard.requireAdmin(req);

		List<Map<String, Object>> projects = SecurityExternalConnectorsUtils.getAllGitHubProjectLinks();
		return WebUtility.getResponse(Map.of("projects", projects), 200);
	}

	/**
	 * Removes a configured GitHub App from this instance's security database. Only
	 * forgets the app locally; an owner must delete the registration on GitHub.
	 * <p>
	 * {@code DELETE /github/manifest/app} (optionally {@code ?appId=...})
	 */
	@DELETE
	@Path("/manifest/app")
	public Response deleteApp(@Context HttpServletRequest req) {
		// Deleting the configured app (and its credentials) is admin-only.
		GitHubAdminGuard.requireAdmin(req);

		// Resolve which app to remove: an explicit appId, else the single
		// configured app (today the instance has at most one).
		String appIdParam = req.getParameter("appId");
		Map<String, Object> app;
		if (appIdParam != null && !appIdParam.trim().isEmpty()) {
			long requestedId;
			try {
				requestedId = Long.parseLong(appIdParam.trim());
			} catch (NumberFormatException e) {
				return WebUtility.getResponse(Map.of("status", "error", "reason", "appId must be a number"), 400);
			}
			app = SecurityExternalConnectorsUtils.getGitHubApp(requestedId);
		} else {
			app = SecurityExternalConnectorsUtils.getGitHubApp();
		}

		if (app == null || app.get("appId") == null) {
			return WebUtility.getResponse(Map.of("status", "error", "reason", "no GitHub App configured"), 404);
		}

		long appId = ((Number) app.get("appId")).longValue();
		String slug = (String) app.get("slug");
		String htmlUrl = (String) app.get("htmlUrl");

		try {
			SecurityExternalConnectorsUtils.deleteGitHubApp(appId);
		} catch (SQLException e) {
			classLogger.error("Failed to delete GitHub App {} from the security database", appId, e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "unable to delete GitHub App"), 500);
		}

		classLogger.info("Deleted GitHub App {} (slug {}) from this instance", appId, slug);

		// GitHub-side deletion is manual, so hand the frontend the slug/url to send
		// the admin to GitHub.
		return WebUtility.getResponse(Map.of("status", "ok", "appId", appId, "slug", slug == null ? "" : slug,
				"htmlUrl", htmlUrl == null ? "" : htmlUrl, "message",
				"Removed the GitHub App from this instance. To fully delete it, an owner must remove it in "
						+ "GitHub (Settings > Developer settings > GitHub Apps > Advanced)."),
				200);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/** Builds a 303 redirect to the given absolute url. */
	private static Response redirect(String url) {
		return Response.seeOther(URI.create(url)).build();
	}

	/** The app's configuration, serialized as the manifest GitHub expects. */
	private String buildManifest(String appName, String baseUrl, boolean isPublic) {
		JsonObject manifest = new JsonObject();
		manifest.addProperty("name", appName);
		manifest.addProperty("url", baseUrl);

		JsonObject hook = new JsonObject();
		hook.addProperty("url", GitHubAppClient.webhookUrl());
		manifest.add("hook_attributes", hook);

		// where GitHub sends the one-time code after the app is created
		manifest.addProperty("redirect_url", baseUrl + "/github/manifest/callback");

		// Setup URL: GitHub redirects the browser here after an installation,
		// carrying installation_id (handled by GitHubService#installCallback). This
		// is what drives the install flow - without it GitHub never redirects back.
		manifest.addProperty("setup_url", baseUrl + "/github/install/callback");
		// do not bounce the user back to the setup URL on every installation update
		manifest.addProperty("setup_on_update", false);

		// OAuth user-authorization callback (registered only; used if we ever
		// request OAuth during installation)
		JsonArray callbacks = new JsonArray();
		callbacks.add(baseUrl + "/github/install/callback");
		manifest.add("callback_urls", callbacks);

		manifest.addProperty("public", isPublic);

		// least privilege: read code + receive push webhooks. metadata is mandatory.
		JsonObject perms = new JsonObject();
		perms.addProperty("contents", "read");
		perms.addProperty("metadata", "read");
		manifest.add("default_permissions", perms);

		JsonArray events = new JsonArray();
		events.add("push");
		manifest.add("default_events", events);

		return GSON.toJson(manifest);
	}

	private static String url(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Escapes a value for use inside a double-quoted HTML attribute. */
	private static String attr(String value) {
		return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Returns a copy of the app fields with the secret credentials removed. */
	private static Map<String, Object> sanitize(Map<String, Object> app) {
		Map<String, Object> safe = new LinkedHashMap<>(app);
		SECRET_FIELDS.forEach(safe::remove);
		return safe;
	}

	/**
	 * Reads a string field from {@code obj}, returning "" when the field is absent
	 * or null. Mirrors the null-tolerant behaviour the prior Jackson
	 * {@code path(...).asText()} reads relied on.
	 */
	private static String str(JsonObject obj, String field) {
		JsonElement el = obj.get(field);
		return el == null || el.isJsonNull() ? "" : el.getAsString();
	}
}
