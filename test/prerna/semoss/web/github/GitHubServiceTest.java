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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityExternalConnectorsUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.io.connector.github.GitHubAppClient;
import prerna.io.connector.github.GitHubAppClient.Installation;
import prerna.io.connector.github.GitHubAppClient.Repo;
import prerna.util.Constants;

class GitHubServiceTest {

	private static final String SECRET = "webhook-secret";
	private static final String PROJECT = "p1";
	private static final String PUBLIC_BASE = "https://semoss.example.com/Monolith";
	private static final String USER_TOKEN_KEY = "github_user_token";
	private static final String OAUTH_STATE_KEY = "github_user_oauth_state";
	private static final String PENDING_PROJECT_KEY = "github_pending_project_id";
	private static final String PENDING_INSTALL_KEY = "github_pending_install_id";
	private static final String PUSH_BODY = "{\"ref\":\"refs/heads/main\",\"after\":\"abcdef1234567\","
			+ "\"pusher\":{\"name\":\"octo\"},\"repository\":{\"full_name\":\"octo/repo\",\"id\":77},\"commits\":[]}";

	private final GitHubService service = new GitHubService();

	@Test
	void availableRequiresASignedInUser() {
		Response response = service.available(request(null, Map.of()));
		assertEquals(401, response.getStatus());
		assertEquals("login required", json(response).get("reason").getAsString());
	}

	@Test
	void availableReportsWhetherAnAppIsConfigured() {
		HttpServletRequest req = request(session(mock(User.class)), Map.of());
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(null);
			assertFalse(json(service.available(req)).get("available").getAsBoolean());

			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			Response response = service.available(req);
			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertTrue(body.get("available").getAsBoolean());
			assertEquals(1, body.size(), "nothing about the app's credentials is sent");
		}
	}

	@Test
	void installAppRequiresAProjectIdAndAConfiguredApp() {
		WebApplicationException noProject = assertThrows(WebApplicationException.class,
				() -> service.installApp(request(session(mock(User.class)), Map.of())));
		assertEquals(400, noProject.getResponse().getStatus());

		User owner = mock(User.class);
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			Response response = service.installApp(request(session(owner), Map.of("projectId", PROJECT)));
			assertEquals(400, response.getStatus());
			assertEquals("no GitHub App is configured", json(response).get("reason").getAsString());
		}
	}

	@Test
	void installAppRemembersTheProjectAndSendsTheUserToTheAppsInstallPage() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			Response response = service.installApp(request(session, Map.of("projectId", PROJECT)));

			assertEquals(303, response.getStatus());
			assertEquals(URI.create("https://github.com/apps/semoss-app/installations/new"), response.getLocation());
			assertEquals(PROJECT, session.getAttribute(PENDING_PROJECT_KEY));
		}
	}

	@Test
	void installAppIsForTheProjectOwnerOnly() {
		WebApplicationException noSession = assertThrows(WebApplicationException.class,
				() -> service.installApp(request(null, Map.of("projectId", PROJECT))));
		assertEquals(401, noSession.getResponse().getStatus());

		HttpSession session = session(mock(User.class));
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			WebApplicationException notOwner = assertThrows(WebApplicationException.class,
					() -> service.installApp(request(session, Map.of("projectId", PROJECT))));
			assertEquals(403, notOwner.getResponse().getStatus());
		}
		assertNull(session.getAttribute(PENDING_PROJECT_KEY), "nothing is remembered for a caller who cannot link");
	}

	@Test
	void installCallbackRefusesToLinkAProjectTheCallerDoesNotOwn() {
		HttpSession session = session(mock(User.class));
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			WebApplicationException notOwner = assertThrows(WebApplicationException.class,
					() -> service.installCallback(request(session, Map.of("installation_id", "42"))));

			assertEquals(403, notOwner.getResponse().getStatus());
			client.verify(() -> GitHubAppClient.listUserInstallations(anyString()), never());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(anyString(), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any()), never());
		}
	}

	@Test
	void installCallbackNeedsAnInstallationAndAPendingProject() {
		assertEquals("missing installation_id",
				json(service.installCallback(request(session(null), Map.of()))).get("reason").getAsString());
		assertEquals("session expired",
				json(service.installCallback(request(null, Map.of("installation_id", "42")))).get("reason")
						.getAsString());
		Response response = service.installCallback(request(session(null), Map.of("installation_id", "42")));
		assertEquals(400, response.getStatus());
		assertEquals("no pending project", json(response).get("reason").getAsString());
	}

	@Test
	void installCallbackAuthorizesTheUserBeforeTrustingTheInstallation() {
		HttpSession session = session(null);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);

			Response response = service.installCallback(request(session, Map.of("installation_id", "42")));

			assertEquals(303, response.getStatus());
			URI location = response.getLocation();
			assertEquals("github.com", location.getHost());
			assertEquals("/login/oauth/authorize", location.getPath());
			Map<String, String> query = query(location);
			assertEquals("client-1", query.get("client_id"));
			assertEquals(PUBLIC_BASE + "/github/user/callback", query.get("redirect_uri"));
			assertEquals(session.getAttribute(OAUTH_STATE_KEY) + ":" + PROJECT, query.get("state"));
			assertEquals("42", session.getAttribute(PENDING_INSTALL_KEY));
			client.verify(() -> GitHubAppClient.listUserInstallations(anyString()), never());
		}
	}

	@Test
	void installCallbackRefusesAnInstallationTheUserCannotSee() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			client.when(() -> GitHubAppClient.listUserInstallations("user-token"))
					.thenReturn(List.of(installation(99)));

			Response response = service.installCallback(request(session, Map.of("installation_id", "42")));

			assertEquals(403, response.getStatus());
			assertEquals("installation is not accessible to this user", json(response).get("reason").getAsString());
			assertNull(session.getAttribute(PENDING_PROJECT_KEY), "the pending project is used once");
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(anyString(), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any()), never());
		}
	}

	@Test
	void installCallbackRejectsAnInstallationIdThatIsNotANumber() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner)) {
			Response response = service.installCallback(request(session, Map.of("installation_id", "4x2")));
			assertEquals(400, response.getStatus());
			assertEquals("invalid installation_id", json(response).get("reason").getAsString());
		}
	}

	@Test
	void installCallbackLinksTheOnlyGrantedRepositoryAndReturnsToTheProject() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.listUserInstallations("user-token")).thenReturn(List.of(installation(42)));
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/repo", null)));

			Response response = service.installCallback(request(session, Map.of("installation_id", "42")));

			assertEquals(303, response.getStatus());
			assertEquals(URI.create("https://semoss.example.com/SemossWeb/packages/client/dist/#/app/" + PROJECT),
					response.getLocation());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(PROJECT, 123L, 42L, 7L,
					"octo/repo", "main", null));
		}
	}

	@Test
	void installCallbackSendsSeveralGrantedRepositoriesToThePicker() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.listUserInstallations("user-token")).thenReturn(List.of(installation(42)));
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/a", "main"), new Repo(8L, "octo/b", "main")));

			Response response = service.installCallback(request(session, Map.of("installation_id", "42")));

			assertEquals(303, response.getStatus());
			assertTrue(response.getLocation().toString().endsWith("#/app/" + PROJECT + "/github/select-repo?installation_id=42"),
					response.getLocation().toString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(anyString(), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any()), never());
		}
	}

	@Test
	void installCallbackAuthorizesAgainWhenTheUserTokenHasExpired() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		session.setAttribute(USER_TOKEN_KEY, "stale-token");
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.listUserInstallations("stale-token"))
					.thenThrow(new GitHubAppClient.UserAuthRequiredException("expired"));

			Response response = service.installCallback(request(session, Map.of("installation_id", "42")));

			assertEquals(303, response.getStatus());
			assertEquals("/login/oauth/authorize", response.getLocation().getPath());
			assertNull(session.getAttribute(USER_TOKEN_KEY));
			assertEquals("42", session.getAttribute(PENDING_INSTALL_KEY));
		}
	}

	@Test
	void userAuthorizeIsForTheProjectOwnerOnly() {
		WebApplicationException noProject = assertThrows(WebApplicationException.class,
				() -> service.userAuthorize(request(session(mock(User.class)), Map.of())));
		assertEquals(400, noProject.getResponse().getStatus());

		WebApplicationException noSession = assertThrows(WebApplicationException.class,
				() -> service.userAuthorize(request(null, Map.of("projectId", PROJECT))));
		assertEquals(401, noSession.getResponse().getStatus());

		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			WebApplicationException notOwner = assertThrows(WebApplicationException.class,
					() -> service.userAuthorize(request(session(mock(User.class)), Map.of("projectId", PROJECT))));
			assertEquals(403, notOwner.getResponse().getStatus());
			assertEquals("owner access to this project is required",
					json(notOwner.getResponse()).get("reason").getAsString());
		}
	}

	@Test
	void userAuthorizeCarriesAFreshNonceAndTheProjectInTheState() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);

			Response first = service.userAuthorize(request(session, Map.of("projectId", PROJECT)));
			String firstNonce = (String) session.getAttribute(OAUTH_STATE_KEY);
			Response second = service.userAuthorize(request(session, Map.of("projectId", PROJECT)));
			String secondNonce = (String) session.getAttribute(OAUTH_STATE_KEY);

			assertEquals(303, first.getStatus());
			assertEquals(firstNonce + ":" + PROJECT, query(first.getLocation()).get("state"));
			assertEquals(secondNonce + ":" + PROJECT, query(second.getLocation()).get("state"));
			assertFalse(firstNonce.equals(secondNonce), "every authorization gets its own nonce");
		}
	}

	@Test
	void userCallbackRejectsAMissingOrForeignState() {
		try (MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			assertEquals("missing code or state",
					json(service.userCallback(request(session(null), Map.of("code", "c")))).get("reason").getAsString());

			HttpSession session = session(null);
			session.setAttribute(OAUTH_STATE_KEY, "expected-nonce");
			Response forged = service.userCallback(request(session, Map.of("code", "c", "state", "other-nonce:p1")));
			assertEquals(400, forged.getStatus());
			assertEquals("invalid state", json(forged).get("reason").getAsString());

			Response noSession = service.userCallback(request(null, Map.of("code", "c", "state", "expected-nonce:p1")));
			assertEquals(400, noSession.getStatus());

			client.verify(() -> GitHubAppClient.exchangeUserCode(anyString()), never());
		}
	}

	@Test
	void userCallbackKeepsTheTokenInTheSessionAndAcceptsTheStateOnce() {
		HttpSession session = session(null);
		session.setAttribute(OAUTH_STATE_KEY, "nonce-1");
		try (MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.exchangeUserCode("code-1")).thenReturn("user-token");

			HttpServletRequest req = request(session, Map.of("code", "code-1", "state", "nonce-1:" + PROJECT));
			Response response = service.userCallback(req);

			assertEquals(303, response.getStatus());
			assertEquals(URI.create("https://semoss.example.com/SemossWeb/packages/client/dist/#/app/" + PROJECT),
					response.getLocation());
			assertEquals("user-token", session.getAttribute(USER_TOKEN_KEY));
			assertNull(session.getAttribute(OAUTH_STATE_KEY));

			Response replay = service.userCallback(req);
			assertEquals(400, replay.getStatus());
			assertEquals("invalid state", json(replay).get("reason").getAsString());
		}
	}

	@Test
	void userCallbackReportsAFailedCodeExchange() {
		HttpSession session = session(null);
		session.setAttribute(OAUTH_STATE_KEY, "nonce-1");
		try (MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			client.when(() -> GitHubAppClient.exchangeUserCode("bad")).thenThrow(new IllegalStateException("bad code"));

			Response response = service.userCallback(request(session, Map.of("code", "bad", "state", "nonce-1:p1")));

			assertEquals(400, response.getStatus());
			assertEquals("unable to complete GitHub authorization", json(response).get("reason").getAsString());
			assertNull(session.getAttribute(USER_TOKEN_KEY));
		}
	}

	@Test
	void userCallbackResumesThePendingInstallation() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(OAUTH_STATE_KEY, "nonce-1");
		session.setAttribute(PENDING_INSTALL_KEY, "42");
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.exchangeUserCode("code-1")).thenReturn("user-token");
			client.when(() -> GitHubAppClient.listUserInstallations("user-token")).thenReturn(List.of(installation(42)));
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/repo", "dev")));

			Response response = service
					.userCallback(request(session, Map.of("code", "code-1", "state", "nonce-1:" + PROJECT)));

			assertEquals(303, response.getStatus());
			assertNull(session.getAttribute(PENDING_INSTALL_KEY));
			assertNull(session.getAttribute(PENDING_PROJECT_KEY));
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(PROJECT, 123L, 42L, 7L,
					"octo/repo", "dev", null));
		}
	}

	@Test
	void userCallbackResumesTheInstallForTheSessionsProjectNotTheOneInState() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(OAUTH_STATE_KEY, "nonce-1");
		session.setAttribute(PENDING_INSTALL_KEY, "42");
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, "victim")).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(GitHubAppClient::publicBaseUrl).thenReturn(PUBLIC_BASE);
			client.when(() -> GitHubAppClient.exchangeUserCode("code-1")).thenReturn("user-token");
			client.when(() -> GitHubAppClient.listUserInstallations("user-token")).thenReturn(List.of(installation(42)));
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/repo", "main")));

			service.userCallback(request(session, Map.of("code", "code-1", "state", "nonce-1:victim")));

			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(PROJECT, 123L, 42L, 7L,
					"octo/repo", "main", null));
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(eq("victim"), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any()), never());
		}
	}

	@Test
	void userCallbackDoesNotAuthorizeAgainWhenTheResumedInstallRejectsTheNewToken() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(OAUTH_STATE_KEY, "nonce-1");
		session.setAttribute(PENDING_INSTALL_KEY, "42");
		session.setAttribute(PENDING_PROJECT_KEY, PROJECT);
		try (MockedStatic<SecurityProjectUtils> projects = owning(owner);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			client.when(() -> GitHubAppClient.exchangeUserCode("code-1")).thenReturn("user-token");
			client.when(() -> GitHubAppClient.listUserInstallations("user-token"))
					.thenThrow(new GitHubAppClient.UserAuthRequiredException("revoked"));

			Response response = service
					.userCallback(request(session, Map.of("code", "code-1", "state", "nonce-1:" + PROJECT)));

			assertEquals(401, response.getStatus());
			assertTrue(json(response).get("needsAuth").getAsBoolean());
			assertNull(session.getAttribute(USER_TOKEN_KEY));
		}
	}

	@Test
	void installInstallationsListsWhatTheUserCanAccess() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			HttpServletRequest req = request(session, Map.of("projectId", PROJECT));

			assertEquals("no GitHub App is configured", json(service.installInstallations(req)).get("reason").getAsString());

			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			Response needsAuth = service.installInstallations(req);
			assertEquals(401, needsAuth.getStatus());
			assertTrue(json(needsAuth).get("needsAuth").getAsBoolean());

			session.setAttribute(USER_TOKEN_KEY, "user-token");
			client.when(() -> GitHubAppClient.listUserInstallations("user-token"))
					.thenReturn(List.of(new Installation(42L, "octo", "Organization", "selected", false)));
			Response response = service.installInstallations(req);
			assertEquals(200, response.getStatus());
			JsonObject row = json(response).getAsJsonArray("installations").get(0).getAsJsonObject();
			assertEquals(42L, row.get("installationId").getAsLong());
			assertEquals("octo", row.get("account").getAsString());
			assertEquals("Organization", row.get("accountType").getAsString());
			assertEquals("selected", row.get("repositorySelection").getAsString());
			assertFalse(row.get("suspended").getAsBoolean());

			client.when(() -> GitHubAppClient.listUserInstallations("user-token"))
					.thenThrow(new IllegalArgumentException("GitHub is down"));
			assertEquals(502, service.installInstallations(req).getStatus());

			client.when(() -> GitHubAppClient.listUserInstallations("user-token"))
					.thenThrow(new GitHubAppClient.UserAuthRequiredException("revoked"));
			assertEquals(401, service.installInstallations(req).getStatus());
			assertNull(session.getAttribute(USER_TOKEN_KEY), "a rejected token is dropped");
		}
	}

	@Test
	void installReposListsTheUsersRepositoriesWithAFallbackBranch() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);

			assertEquals(400, service.installRepos(request(session, Map.of("projectId", PROJECT))).getStatus());

			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/repo", null)));
			Response response = service
					.installRepos(request(session, Map.of("projectId", PROJECT, "installationId", " 42 ")));

			assertEquals(200, response.getStatus());
			JsonObject repo = json(response).getAsJsonArray("repos").get(0).getAsJsonObject();
			assertEquals(7L, repo.get("id").getAsLong());
			assertEquals("octo/repo", repo.get("fullName").getAsString());
			assertEquals("main", repo.get("defaultBranch").getAsString());
		}
	}

	@Test
	void installBranchesOnlyReadsARepositoryTheUserCanSee() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "Octo/Repo", "main")));

			assertEquals(400, service.installBranches(request(session, Map.of("projectId", PROJECT, "installationId", "42")))
					.getStatus());

			Response hidden = service.installBranches(
					request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoFullName", "other/repo")));
			assertEquals(403, hidden.getStatus());
			client.verify(() -> GitHubAppClient.listRepositoryBranches(anyString(), anyString()), never());

			client.when(() -> GitHubAppClient.listRepositoryBranches("42", "octo/repo"))
					.thenReturn(List.of("main", "dev"));
			Response response = service.installBranches(
					request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoFullName", "octo/repo")));
			assertEquals(200, response.getStatus());
			assertEquals(2, json(response).getAsJsonArray("branches").size());
		}
	}

	@Test
	void selectRepoValidatesItsInputBeforeLinking() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);

			assertEquals("installationId and repoId are required",
					json(service.selectRepo(request(session, Map.of("projectId", PROJECT)))).get("reason").getAsString());
			assertEquals("installationId and repoId must be numbers", json(service.selectRepo(
					request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoId", "seven"))))
					.get("reason").getAsString());
			assertEquals("no GitHub App is configured", json(service.selectRepo(
					request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoId", "7"))))
					.get("reason").getAsString());

			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			assertEquals(401, service
					.selectRepo(request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoId", "7")))
					.getStatus());

			session.setAttribute(USER_TOKEN_KEY, "user-token");
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(8L, "octo/other", "main")));
			Response unseen = service
					.selectRepo(request(session, Map.of("projectId", PROJECT, "installationId", "42", "repoId", "7")));
			assertEquals(400, unseen.getStatus());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(anyString(), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any()), never());
		}
	}

	@Test
	void selectRepoLinksTheRepositoryNamedByGitHubWithTheChosenBranch() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		session.setAttribute(USER_TOKEN_KEY, "user-token");
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class);
				MockedStatic<GitHubAppClient> client = mockStatic(GitHubAppClient.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			client.when(() -> GitHubAppClient.listUserInstallationRepositories("user-token", "42"))
					.thenReturn(List.of(new Repo(7L, "octo/repo", "main")));

			Map<String, String> params = new HashMap<>(Map.of("projectId", PROJECT, "installationId", "42", "repoId",
					"7", "branch", " release ", "subdir", "apps/web"));
			Response response = service.selectRepo(request(session, params));

			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertEquals(7L, body.get("repoId").getAsLong());
			assertEquals("octo/repo", body.get("repoFullName").getAsString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(PROJECT, 123L, 42L, 7L,
					"octo/repo", "release", "apps/web"));

			connectors.when(() -> SecurityExternalConnectorsUtils.upsertGitHubProjectLink(anyString(), anyLong(),
					anyLong(), anyLong(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("db"));
			assertEquals(500, service.selectRepo(request(session, params)).getStatus());
		}
	}

	@Test
	void getLinkReportsTheLinkedRepositoryOrNone() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			HttpServletRequest req = request(session, Map.of("projectId", PROJECT));
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubProjectLink(PROJECT)).thenReturn(null);

			JsonObject none = json(service.getLink(req));
			assertFalse(none.get("linked").getAsBoolean());
			assertEquals(1, none.size());

			Map<String, Object> link = new LinkedHashMap<>();
			link.put("projectId", PROJECT);
			link.put("repoFullName", "octo/repo");
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubProjectLink(PROJECT)).thenReturn(link);
			JsonObject linked = json(service.getLink(req));
			assertTrue(linked.get("linked").getAsBoolean());
			assertEquals("octo/repo", linked.get("repoFullName").getAsString());
		}
	}

	@Test
	void disconnectUnlinksTheProject() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			HttpServletRequest req = request(session, Map.of("projectId", PROJECT));

			assertEquals(200, service.disconnect(req).getStatus());
			connectors.verify(() -> SecurityExternalConnectorsUtils.deleteGitHubProjectLink(PROJECT));

			connectors.when(() -> SecurityExternalConnectorsUtils.deleteGitHubProjectLink(PROJECT))
					.thenThrow(new IllegalStateException("db"));
			assertEquals(500, service.disconnect(req).getStatus());
		}
	}

	@Test
	void disconnectIsRefusedToSomeoneWhoDoesNotOwnTheProject() {
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			WebApplicationException error = assertThrows(WebApplicationException.class,
					() -> service.disconnect(request(session(mock(User.class)), Map.of("projectId", PROJECT))));
			assertEquals(403, error.getResponse().getStatus());
			connectors.verify(() -> SecurityExternalConnectorsUtils.deleteGitHubProjectLink(anyString()), never());
		}
	}

	@Test
	void setBranchNeedsALinkedProjectAndTrimsTheBranch() {
		User owner = mock(User.class);
		HttpSession session = session(owner);
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubProjectLink(PROJECT)).thenReturn(null);

			assertEquals("branch is required",
					json(service.setBranch(request(session, Map.of("projectId", PROJECT, "branch", " "))))
							.get("reason").getAsString());
			assertEquals("project is not linked to a repository",
					json(service.setBranch(request(session, Map.of("projectId", PROJECT, "branch", "dev"))))
							.get("reason").getAsString());

			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubProjectLink(PROJECT))
					.thenReturn(Map.of("projectId", PROJECT));
			Response response = service.setBranch(request(session, Map.of("projectId", PROJECT, "branch", " dev ")));
			assertEquals(200, response.getStatus());
			assertEquals("dev", json(response).get("branch").getAsString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.updateGitHubProjectLinkBranch(PROJECT, "dev"));
		}
	}

	@Test
	void rejectsAWebhookWithoutAValidSignature() throws Exception {
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			assertEquals(401, service.webhook(webhook(PUSH_BODY, "push", sign(SECRET, PUSH_BODY))).getStatus(),
					"no app means no secret to check against");

			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			assertEquals(401, service.webhook(webhook(PUSH_BODY, "push", null)).getStatus());
			assertEquals(401, service.webhook(webhook(PUSH_BODY, "push", sign("wrong-secret", PUSH_BODY))).getStatus());
			assertEquals(401, service.webhook(webhook(PUSH_BODY, "push", sign(SECRET, PUSH_BODY).substring(7)))
					.getStatus(), "the sha256= prefix is required");
			String tampered = PUSH_BODY.replace("octo/repo", "evil/repo");
			Response response = service.webhook(webhook(tampered, "push", sign(SECRET, PUSH_BODY)));
			assertEquals(401, response.getStatus());
			assertEquals("invalid signature", json(response).get("reason").getAsString());

			connectors.verify(() -> SecurityExternalConnectorsUtils.getGitHubProjectLinksByRepoId(anyLong()), never());
		}
	}

	@Test
	void acknowledgesASignedEventThatIsNotAPush() throws Exception {
		String body = "{\"zen\":\"Keep it logically awesome.\"}";
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());

			Response response = service.webhook(webhook(body, "ping", sign(SECRET, body)));

			assertEquals(200, response.getStatus());
			assertEquals("Event ignored: ping", json(response).get("message").getAsString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.getGitHubProjectLinksByRepoId(anyLong()), never());
		}
	}

	@Test
	void routesASignedPushByTheRepositoryId() throws Exception {
		String body = PUSH_BODY.replace("\"octo\"}", "\"octo \u00e9\"}");
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(SecurityExternalConnectorsUtils::getGitHubApp).thenReturn(app());
			connectors.when(() -> SecurityExternalConnectorsUtils.getGitHubProjectLinksByRepoId(77L)).thenReturn(List.of());

			Response response = service.webhook(webhook(body, "push", sign(SECRET, body)));

			assertEquals(200, response.getStatus());
			assertEquals("Push event processed", json(response).get("message").getAsString());
			connectors.verify(() -> SecurityExternalConnectorsUtils.getGitHubProjectLinksByRepoId(77L));
		}
	}

	private static Map<String, Object> app() {
		Map<String, Object> app = new HashMap<>();
		app.put("appId", 123L);
		app.put("slug", "semoss-app");
		app.put("clientId", "client-1");
		app.put("clientSecret", "client-secret");
		app.put("webhookSecret", SECRET);
		app.put("privateKey", "pem");
		return app;
	}

	private static Installation installation(long id) {
		return new Installation(id, "octo", "User", "selected", false);
	}

	private static MockedStatic<SecurityProjectUtils> owning(User owner) {
		MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
		projects.when(() -> SecurityProjectUtils.userIsOwner(owner, PROJECT)).thenReturn(true);
		return projects;
	}

	private static HttpSession session(User user) {
		Map<String, Object> attributes = new HashMap<>();
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
		doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1))).when(session).setAttribute(anyString(),
				any());
		doAnswer(inv -> attributes.remove(inv.getArgument(0))).when(session).removeAttribute(anyString());
		if (user != null) {
			attributes.put(Constants.SESSION_USER, user);
		}
		return session;
	}

	/**
	 * A request carrying the given query parameters. A null session means the
	 * caller has none, and asking to create one hands out a fresh empty session.
	 */
	private static HttpServletRequest request(HttpSession session, Map<String, String> params) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter(anyString())).thenAnswer(inv -> params.get(inv.getArgument(0)));
		HttpSession created = session == null ? session(null) : session;
		when(req.getSession(false)).thenReturn(session);
		when(req.getSession(true)).thenReturn(created);
		when(req.getSession()).thenReturn(created);
		return req;
	}

	private static HttpServletRequest webhook(String body, String event, String signature) throws IOException {
		HttpServletRequest req = request(null, Map.of());
		ByteArrayInputStream bytes = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		when(req.getInputStream()).thenReturn(new ServletInputStream() {
			@Override
			public int read() {
				return bytes.read();
			}

			@Override
			public boolean isFinished() {
				return bytes.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				throw new UnsupportedOperationException();
			}
		});
		when(req.getHeader("X-GitHub-Event")).thenReturn(event);
		when(req.getHeader("X-Hub-Signature-256")).thenReturn(signature);
		return req;
	}

	private static String sign(String secret, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
	}

	private static Map<String, String> query(URI location) {
		Map<String, String> query = new HashMap<>();
		for (String pair : location.getRawQuery().split("&")) {
			int eq = pair.indexOf('=');
			query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return query;
	}

	private static JsonObject json(Response response) {
		assertNotNull(response.getEntity(), "the response has a body");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
