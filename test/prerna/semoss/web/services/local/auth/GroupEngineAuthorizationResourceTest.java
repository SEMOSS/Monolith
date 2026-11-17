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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessPermissionEnum;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityGroupEngineUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class GroupEngineAuthorizationResourceTest {

	private final GroupEngineAuthorizationResource resource = new GroupEngineAuthorizationResource();

	@Test
	void readsAGroupsPermissionByNameWithItsProviderLabel() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.getGroupDatabasePermission("Sales", "MICROSOFT", "e1"))
					.thenReturn(AccessPermissionEnum.EDIT.getId());

			Response response = resource.getGroupAppPermission(requestFor(user), "Sales", "ms", "e1");

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("permission", AccessPermissionEnum.EDIT.getPermission()), body(response));
		}
	}

	@Test
	void readsNoPermissionForAGroupWithoutAccess() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.getGroupDatabasePermission("Sales", "CUSTOM", "e1"))
					.thenReturn(null);

			Response response = resource.getGroupAppPermission(requestFor(user), "Sales", "CUSTOM", "e1");

			assertEquals(200, response.getStatus());
			assertNull(((Map<?, ?>) body(response)).get("permission"));
		}
	}

	@Test
	void requiresTheGroupTypeAndEngineToReadAPermission() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response noType = resource.getGroupAppPermission(requestFor(user), "Sales", " ", "e1");
			Response noEngine = resource.getGroupAppPermission(requestFor(user), "Sales", "CUSTOM", null);

			assertEquals(400, noType.getStatus());
			assertEquals("The group type cannot be null or empty", errorOf(noType));
			assertEquals(400, noEngine.getStatus());
			assertEquals("The appId cannot be null or empty", errorOf(noEngine));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void addsAGroupWithoutTouchingItsLimitWhenNoneIsSent() throws Exception {
		User user = signedIn("alice");
		MultivaluedMap<String, String> form = form("groupId", "Sales", "type", "custom", "engineId", "e1",
				"permission", "EDIT", "endDate", "2027-01-01");
		try (Statics statics = new Statics()) {
			Response response = resource.addGroupEnginePermission(requestFor(user), form);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
			statics.groups.verify(() -> SecurityGroupEngineUtils.addEngineGroupPermission(user, "Sales", "custom", "e1",
					"EDIT", "2027-01-01"));
			statics.limits.verifyNoInteractions();
		}
	}

	@Test
	void addsAGroupWithTheTeamLimitItIsSent() throws Exception {
		User user = signedIn("alice");
		ModelUsageLimit limit = new ModelUsageLimit();
		MultivaluedMap<String, String> form = form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1",
				"permission", "READ_ONLY", "usageLimit", "{\"rules\":[]}");
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{\"rules\":[]}")).thenReturn(limit);

			Response response = resource.addGroupEnginePermission(requestFor(user), form);

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupEngineUtils.addEngineGroupPermission(user, "Sales", "CUSTOM", "e1",
					"READ_ONLY", null, limit));
		}
	}

	@Test
	void addsAGroupWithNoLimitDefinedForAnEmptyLimit() throws Exception {
		User user = signedIn("alice");
		MultivaluedMap<String, String> form = form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1",
				"permission", "READ_ONLY", "usageLimit", "");
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson(anyString())).thenCallRealMethod();

			Response response = resource.addGroupEnginePermission(requestFor(user), form);

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupEngineUtils.addEngineGroupPermission(user, "Sales", "CUSTOM", "e1",
					"READ_ONLY", null, null));
		}
	}

	@Test
	void refusesAnUnreadableLimitWith400() throws Exception {
		User user = signedIn("alice");
		MultivaluedMap<String, String> form = form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1",
				"permission", "READ_ONLY", "usageLimit", "{\"rules\":5}");
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson(anyString())).thenCallRealMethod();

			Response response = resource.addGroupEnginePermission(requestFor(user), form);

			assertEquals(400, response.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void requiresEveryPartOfAnAdd() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response noGroup = resource.addGroupEnginePermission(requestFor(user),
					form("type", "CUSTOM", "engineId", "e1", "permission", "EDIT"));
			Response noEngine = resource.addGroupEnginePermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "permission", "EDIT"));
			Response noPermission = resource.addGroupEnginePermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1"));

			assertEquals("The group id cannot be null or empty", errorOf(noGroup));
			assertEquals("The engineId cannot be null or empty", errorOf(noEngine));
			assertEquals("The permission cannot be null or empty", errorOf(noPermission));
			assertEquals(400, noPermission.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void refusesANonOwnerGivingAGroupAccessWith403() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.addEngineGroupPermission(user, "Sales", "CUSTOM", "e1",
					"OWNER", null)).thenThrow(new IllegalAccessException("Only owners can give teams access"));

			Response response = resource.addGroupEnginePermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1", "permission", "OWNER"));

			assertEquals(403, response.getStatus());
			assertEquals("Only owners can give teams access", errorOf(response));
		}
	}

	@Test
	void editsAGroupsPermissionOnTheEngine() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editGroupAppPermission(requestFor(user), form("groupId", "Sales", "type",
					"native", "appId", "e1", "permission", "OWNER", "endDate", "2027-01-01"));

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupEngineUtils.editDatabaseGroupPermission(user, "Sales", "NATIVE",
					"e1", "OWNER", "2027-01-01"));
		}
	}

	@Test
	void requiresThePermissionToEditAGroup() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editGroupAppPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "appId", "e1"));

			assertEquals(400, response.getStatus());
			assertEquals("The permission cannot be null or empty", errorOf(response));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void removesAGroupFromTheEngine() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.removeGroupAppPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "appId", "e1"));

			assertEquals(200, response.getStatus());
			statics.groups.verify(
					() -> SecurityGroupEngineUtils.removeDatabaseGroupPermission(user, "Sales", "CUSTOM", "e1"));
		}
	}

	@Test
	void listsAndCountsTheGroupsWithAccess() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.getGroupsWithAccessToEngine(user, "e1", 10, 20))
					.thenReturn(List.of(Map.of("id", "Sales")));
			statics.groups.when(() -> SecurityGroupEngineUtils.getNumGroupsWithAccessToEngine(user, "e1"))
					.thenReturn(7L);

			Response response = resource.getAllGroupsWithAccessToEngine(requestFor(user), "e1", 10, 20);

			assertEquals(200, response.getStatus());
			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(7.0, payload.get("totalGroups"));
			assertEquals(List.of(Map.of("id", "Sales")), payload.get("groups"));
		}
	}

	@Test
	void requiresTheEngineToListGroups() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response withAccess = resource.getAllGroupsWithAccessToEngine(requestFor(user), "", 10, 0);
			Response available = resource.getAvailableGroupsForEngine(requestFor(user), null, "x", 10, 0);

			assertEquals(400, withAccess.getStatus());
			assertEquals("Must define the engineId", errorOf(withAccess));
			assertEquals(400, available.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void listsTheGroupsAvailableToTheEngine() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.getAvailableGroupsForEngine(user, "e1", "sa", 10, 0))
					.thenReturn(List.of(Map.of("id", "Sales")));

			Response response = resource.getAvailableGroupsForEngine(requestFor(user), "e1", "sa", 10, 0);

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("id", "Sales")), body(response));
		}
	}

	@Test
	void refusesListingGroupsForACallerWhoCannotSeeTheEngine() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupEngineUtils.getGroupsWithAccessToEngine(user, "e1", 10, 0))
					.thenThrow(new IllegalAccessException("The user does not have access to view this engine"));

			Response response = resource.getAllGroupsWithAccessToEngine(requestFor(user), "e1", 10, 0);

			assertEquals(403, response.getStatus());
			statics.groups.verify(() -> SecurityGroupEngineUtils.getNumGroupsWithAccessToEngine(any(), anyString()),
					never());
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.addGroupEnginePermission(signedOut(),
					form("groupId", "Sales", "type", "CUSTOM", "engineId", "e1", "permission", "OWNER"));

			assertEquals(401, response.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void refusesToReadAGroupsPermissionOnAEngineTheUserCannotView() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.engines.when(() -> SecurityEngineUtils.userCanViewEngine(user, "e1")).thenReturn(false);

			Response response = resource.getGroupAppPermission(requestFor(user), "Sales", "CUSTOM", "e1");

			assertEquals(403, response.getStatus());
			assertEquals("The user does not have access to view this engine", errorOf(response));
			statics.groups.verify(() -> SecurityGroupEngineUtils.getGroupDatabasePermission(anyString(), anyString(),
					anyString()), never());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "OWNR", "7", "admin" })
	void refusesAnUnknownLevelWithoutSavingIt(String level) throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response add = resource.addGroupEnginePermission(requestFor(user), form("groupId", "Sales", "type",
					"CUSTOM", "engineId", "e1", "permission", level));
			Response edit = resource.editGroupAppPermission(requestFor(user), form("groupId", "Sales", "type", "CUSTOM",
					"appId", "e1", "permission", level));

			for (Response response : List.of(add, edit)) {
				assertEquals(400, response.getStatus());
				assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
						errorOf(response));
			}
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.addGroupEnginePermission(requestFor(user), form("groupId", "Sales", "type",
					"CUSTOM", "engineId", "e1", "permission", " edit "));

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupEngineUtils.addEngineGroupPermission(user, "Sales", "CUSTOM", "e1",
					"EDIT", null));
		}
	}

	private static MultivaluedMap<String, String> form(String... pairs) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			form.add(pairs[i], pairs[i + 1]);
		}
		return form;
	}

	private static User signedIn(String id) {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId(id);
		User user = new User();
		user.setAccessToken(token);
		return user;
	}

	private static HttpServletRequest requestFor(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group/engine");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group/engine");
		return request;
	}

	private static Object body(Response response) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return new Gson().fromJson(out.toString(StandardCharsets.UTF_8), Object.class);
	}

	private static String errorOf(Response response) throws Exception {
		return (String) ((Map<?, ?>) body(response)).get(Constants.ERROR_MESSAGE);
	}

	/**
	 * The Semoss statics these endpoints call, mocked for one test.
	 */
	private static final class Statics implements AutoCloseable {

		private final MockedStatic<UserAuditTrailUtils> audit = Mockito.mockStatic(UserAuditTrailUtils.class);
		private final MockedStatic<SecurityGroupEngineUtils> groups = Mockito
				.mockStatic(SecurityGroupEngineUtils.class);
		/** whether the user can view the engine, which every read here needs; yes unless a test says no */
		private final MockedStatic<SecurityEngineUtils> engines = Mockito.mockStatic(SecurityEngineUtils.class);

		private Statics() {
			engines.when(() -> SecurityEngineUtils.userCanViewEngine(any(), anyString())).thenReturn(true);
		}
		private final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);

		@Override
		public void close() {
			limits.close();
			engines.close();
			groups.close();
			audit.close();
		}
	}
}
