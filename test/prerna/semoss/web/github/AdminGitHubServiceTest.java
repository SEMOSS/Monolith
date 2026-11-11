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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityExternalConnectorsUtils;
import prerna.io.connector.github.GitHubAppClient;
import prerna.util.Constants;

class AdminGitHubServiceTest {

	private static final String PUBLIC_BASE = "https://semoss.example.com/Monolith";
	private static final String WEBHOOK_URL = PUBLIC_BASE + "/github/webhook";
	private static final String STATE_KEY = "github_manifest_state";
	private static final Pattern ACTION = Pattern.compile("<form action=\"([^\"]*)\"");
	private static final Pattern MANIFEST = Pattern.compile("name=\"manifest\" value=\"([^\"]*)\"");

	private final AdminGitHubService service = new AdminGitHubService();

	@Test
	void everyEndpointIsForSignedInInstanceAdminsOnly() {
		List<Function<HttpServletRequest, Response>> endpoints = List.of(service::createApp, service::manifestCallback,
				service::listApps, service::listProjects, service::deleteApp);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			for (Function<HttpServletRequest, Response> endpoint : endpoints) {
				WebApplicationException anonymous = assertThrows(WebApplicationException.class,
						() -> endpoint.apply(request(null, Map.of())));
				assertEquals(401, anonymous.getResponse().getStatus());
				assertEquals("login required", json(anonymous.getResponse()).get("reason").getAsString());

				WebApplicationException user = assertThrows(WebApplicationException.class,
						() -> endpoint.apply(request(session(mock(User.class)), Map.of())));
				assertEquals(403, user.getResponse().getStatus());
				assertEquals("admin access required", json(user.getResponse()).get("reason").getAsString());
			}
			connectors.verifyNoInteractions();
			client.verifyNoInteractions();
		}
	}

	@Test
	void createAppPostsAManifestThatCannotBreakOutOfTheForm() {
		User admin = mock(User.class);
		HttpSession session = session(admin);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(GitHubAppClient::webhookUrl).thenReturn(WEBHOOK_URL);

			String name = "Team \"><script>alert(1)</script> & co";
			Response response = service.createApp(request(session, Map.of("name", name)));

			assertEquals(200, response.getStatus());
			assertEquals("text/html", response.getMediaType().toString());
			String html = (String) response.getEntity();
			assertFalse(html.contains("<script>"), html);
			assertEquals(1, html.split("<form ", -1).length - 1, "one form only");

			String state = (String) session.getAttribute(STATE_KEY);
			assertEquals("https://github.com/settings/apps/new?state=" + state, attribute(ACTION, html));

			JsonObject manifest = JsonParser.parseString(attribute(MANIFEST, html)).getAsJsonObject();
			assertEquals(name, manifest.get("name").getAsString());
			assertEquals(PUBLIC_BASE, manifest.get("url").getAsString());
			assertEquals(WEBHOOK_URL, manifest.getAsJsonObject("hook_attributes").get("url").getAsString());
			assertEquals(PUBLIC_BASE + "/github/manifest/callback", manifest.get("redirect_url").getAsString());
			assertEquals(PUBLIC_BASE + "/github/install/callback", manifest.get("setup_url").getAsString());
			assertTrue(manifest.get("setup_on_update").getAsBoolean());
			JsonArray callbacks = manifest.getAsJsonArray("callback_urls");
			assertEquals(PUBLIC_BASE + "/github/install/callback", callbacks.get(0).getAsString());
			assertEquals(PUBLIC_BASE + "/github/user/callback", callbacks.get(1).getAsString());
			assertFalse(manifest.get("public").getAsBoolean(), "an app is private unless asked otherwise");
			JsonObject permissions = manifest.getAsJsonObject("default_permissions");
			assertEquals("read", permissions.get("contents").getAsString());
			assertEquals("read", permissions.get("metadata").getAsString());
			assertEquals(2, permissions.size(), "only read access is asked for");
			assertEquals("push", manifest.getAsJsonArray("default_events").get(0).getAsString());
		}
	}

	@Test
	void createAppCreatesTheAppUnderAnEncodedOrganization() {
		User admin = mock(User.class);
		HttpSession session = session(admin);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);

			String html = (String) service
					.createApp(request(session, Map.of("org", " my org/../x ", "public", "true"))).getEntity();

			String state = (String) session.getAttribute(STATE_KEY);
			assertEquals("https://github.com/organizations/my+org%2F..%2Fx/settings/apps/new?state=" + state,
					attribute(ACTION, html));
			JsonObject manifest = JsonParser.parseString(attribute(MANIFEST, html)).getAsJsonObject();
			assertEquals("Semoss GitHub App", manifest.get("name").getAsString());
			assertTrue(manifest.get("public").getAsBoolean());
		}
	}

	@Test
	void manifestCallbackOnlyAcceptsTheStateItIssued() {
		User admin = mock(User.class);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			HttpSession session = session(admin);
			session.setAttribute(STATE_KEY, "issued");

			assertEquals("missing code",
					json(service.manifestCallback(request(session, Map.of("state", "issued")))).get("reason")
							.getAsString());
			Response forged = service.manifestCallback(request(session, Map.of("code", "c", "state", "forged")));
			assertEquals(400, forged.getStatus());
			assertEquals("invalid state", json(forged).get("reason").getAsString());
			assertEquals(400, service.manifestCallback(request(session, Map.of("code", "c"))).getStatus());
			assertEquals("issued", session.getAttribute(STATE_KEY), "a rejected attempt leaves the state in place");

			client.verify(() -> GitHubAppClient.convertManifest(anyString()), never());
		}
	}

	@Test
	void manifestCallbackSavesTheConvertedAppAndReturnsToTheSettings() {
		User admin = mock(User.class);
		HttpSession session = session(admin);
		session.setAttribute(STATE_KEY, "issued");
		JsonObject app = JsonParser.parseString("{\"id\":321,\"slug\":\"semoss-app\",\"name\":\"Semoss\","
				+ "\"owner\":{\"login\":\"octo\"},\"html_url\":\"https://github.com/apps/semoss-app\","
				+ "\"client_id\":\"cid\",\"client_secret\":\"csecret\",\"webhook_secret\":\"wsecret\",\"pem\":\"PEM\"}")
				.getAsJsonObject();
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			client.when(() -> GitHubAppClient.convertManifest("code-1")).thenReturn(app);
			client.when(GitHubAppClient::webhookUrl).thenReturn(WEBHOOK_URL);
			client.when(() -> GitHubAppClient.frontendUrl(anyString()))
					.thenAnswer(inv -> "https://semoss.example.com/ui/#" + inv.getArgument(0));

			HttpServletRequest req = request(session, Map.of("code", "code-1", "state", "issued"));
			Response response = service.manifestCallback(req);

			assertEquals(303, response.getStatus());
			assertEquals(URI.create("https://semoss.example.com/ui/#/settings/github-app?githubApp=created"),
					response.getLocation());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubApp(321L, "semoss-app", "Semoss", "octo",
					"https://github.com/apps/semoss-app", WEBHOOK_URL, "cid", "csecret", "wsecret", "PEM"));
			assertNull(session.getAttribute(STATE_KEY));
			assertEquals(400, service.manifestCallback(req).getStatus(), "the state is accepted once");
		}
	}

	@Test
	void manifestCallbackSendsAFailureBackToTheSettings() {
		User admin = mock(User.class);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			client.when(() -> GitHubAppClient.frontendUrl(anyString()))
					.thenAnswer(inv -> "https://semoss.example.com/ui/#" + inv.getArgument(0));
			client.when(() -> GitHubAppClient.convertManifest("expired")).thenThrow(new IllegalStateException("410"));
			client.when(() -> GitHubAppClient.convertManifest("ok")).thenReturn(new JsonObject());
			connectors.when(() -> SecurityExternalConnectorsUtils.upsertGitHubApp(anyLong(), anyString(), anyString(),
					anyString(), anyString(), ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString()))
					.thenThrow(new IllegalArgumentException("Slug must not be empty."));

			HttpSession session = session(admin);
			session.setAttribute(STATE_KEY, "s1");
			Response conversion = service.manifestCallback(request(session, Map.of("code", "expired", "state", "s1")));
			assertEquals("/settings/github-app?githubApp=error&reason=conversion_failed",
					conversion.getLocation().getFragment());

			session.setAttribute(STATE_KEY, "s2");
			Response save = service.manifestCallback(request(session, Map.of("code", "ok", "state", "s2")));
			assertEquals("/settings/github-app?githubApp=error&reason=save_failed", save.getLocation().getFragment());
		}
	}

	@Test
	void listAppsLeavesOutEveryCredential() {
		User admin = mock(User.class);
		Map<String, Object> stored = new HashMap<>();
		stored.put("appId", 321L);
		stored.put("slug", "semoss-app");
		stored.put("clientId", "cid");
		stored.put("clientSecret", "csecret");
		stored.put("webhookSecret", "wsecret");
		stored.put("privateKey", "PEM");
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(stored);

			Response response = service.listApps(request(session(admin), Map.of()));

			assertEquals(200, response.getStatus());
			JsonObject app = json(response).getAsJsonArray("apps").get(0).getAsJsonObject();
			assertEquals("semoss-app", app.get("slug").getAsString());
			assertEquals("cid", app.get("clientId").getAsString());
			assertFalse(app.has("clientSecret"));
			assertFalse(app.has("webhookSecret"));
			assertFalse(app.has("privateKey"));
			assertEquals("csecret", stored.get("clientSecret"), "the stored record is not changed");

			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(null);
			assertEquals(0, json(service.listApps(request(session(admin), Map.of()))).getAsJsonArray("apps").size());
		}
	}

	@Test
	void listProjectsReturnsEveryLink() {
		User admin = mock(User.class);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getAllGitHubProjectLinks)
					.thenReturn(List.of(Map.of("projectId", "p1"), Map.of("projectId", "p2")));

			JsonArray projects = json(service.listProjects(request(session(admin), Map.of()))).getAsJsonArray("projects");

			assertEquals(2, projects.size());
			assertEquals("p2", projects.get(1).getAsJsonObject().get("projectId").getAsString());
		}
	}

	@Test
	void deleteAppForgetsTheChosenApp() throws Exception {
		User admin = mock(User.class);
		Map<String, Object> stored = new HashMap<>();
		stored.put("appId", 321L);
		stored.put("slug", "semoss-app");
		stored.put("htmlUrl", "https://github.com/apps/semoss-app");
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(null);
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubApp(anyLong())).thenReturn(null);
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubApp(321L)).thenReturn(stored);

			assertEquals("appId must be a number",
					json(service.deleteApp(request(session(admin), Map.of("appId", "x")))).get("reason").getAsString());
			assertEquals(404, service.deleteApp(request(session(admin), Map.of())).getStatus());
			assertEquals(404, service.deleteApp(request(session(admin), Map.of("appId", "999"))).getStatus());

			Response response = service.deleteApp(request(session(admin), Map.of("appId", " 321 ")));
			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertEquals(321L, body.get("appId").getAsLong());
			assertEquals("semoss-app", body.get("slug").getAsString());
			assertEquals("https://github.com/apps/semoss-app", body.get("htmlUrl").getAsString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.deleteGitHubApp(321L));

			connectors.when(() -> SecurityExternalConnectorsUtils.deleteGitHubApp(321L))
					.thenThrow(new SQLException("locked"));
			assertEquals(500, service.deleteApp(request(session(admin), Map.of("appId", "321"))).getStatus());
		}
	}

	private static String attribute(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		assertTrue(matcher.find(), html);
		return matcher.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;",
				"&");
	}

	private static HttpSession session(User user) {
		Map<String, Object> attributes = new HashMap<>();
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
		doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1))).when(session).setAttribute(anyString(),
				ArgumentMatchers.any());
		doAnswer(inv -> attributes.remove(inv.getArgument(0))).when(session).removeAttribute(anyString());
		if (user != null) {
			attributes.put(Constants.SESSION_USER, user);
		}
		return session;
	}

	private static HttpServletRequest request(HttpSession session, Map<String, String> params) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter(anyString())).thenAnswer(inv -> params.get(inv.getArgument(0)));
		HttpSession created = session == null ? session(null) : session;
		when(req.getSession(false)).thenReturn(session);
		when(req.getSession(true)).thenReturn(created);
		when(req.getSession()).thenReturn(created);
		return req;
	}

	private static JsonObject json(Response response) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
