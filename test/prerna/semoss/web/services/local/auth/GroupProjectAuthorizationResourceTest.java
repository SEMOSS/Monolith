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
import prerna.auth.utils.SecurityGroupProjectUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class GroupProjectAuthorizationResourceTest {

	private final GroupProjectAuthorizationResource resource = new GroupProjectAuthorizationResource();

	@Test
	void readsAGroupsPermissionOnAProjectByName() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.getGroupProjectPermission("Sales", "CUSTOM", "p1"))
					.thenReturn(AccessPermissionEnum.OWNER.getId());

			Response response = resource.getGroupProjectPermission(requestFor(user), "Sales", "CUSTOM", "p1");

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("permission", AccessPermissionEnum.OWNER.getPermission()), body(response));
		}
	}

	@Test
	void readsNoPermissionForAGroupWithoutAccess() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.getGroupProjectPermission("Sales", "CUSTOM", "p1"))
					.thenReturn(null);

			Response response = resource.getGroupProjectPermission(requestFor(user), "Sales", "CUSTOM", "p1");

			assertEquals(200, response.getStatus());
			assertNull(((Map<?, ?>) body(response)).get("permission"));
		}
	}

	@Test
	void requiresTheProjectToReadAPermission() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.getGroupProjectPermission(requestFor(user), "Sales", "CUSTOM", "");

			assertEquals(400, response.getStatus());
			assertEquals("The projectId cannot be null or empty", errorOf(response));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void addsAGroupWithItsTrimmedValuesAndEndDate() throws Exception {
		User user = signedIn("alice");
		MultivaluedMap<String, String> form = form("groupId", " Sales ", "type", "ldap", "projectId", "p1",
				"permission", "EDIT", "endDate", "2027-01-01");
		try (Statics statics = new Statics()) {
			Response response = resource.addGroupProjectPermission(requestFor(user), form);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
			statics.groups.verify(() -> SecurityGroupProjectUtils.addProjectGroupPermission(user, "Sales", "LDAP", "p1",
					"EDIT", "2027-01-01"));
		}
	}

	@Test
	void requiresEveryPartOfAnAdd() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response noType = resource.addGroupProjectPermission(requestFor(user),
					form("groupId", "Sales", "projectId", "p1", "permission", "EDIT"));
			Response noPermission = resource.addGroupProjectPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "projectId", "p1"));

			assertEquals(400, noType.getStatus());
			assertEquals("The group type cannot be null or empty", errorOf(noType));
			assertEquals("The permission cannot be null or empty", errorOf(noPermission));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void refusesANonOwnerGivingAGroupAccessWith403() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.addProjectGroupPermission(user, "Sales", "CUSTOM", "p1",
					"OWNER", null)).thenThrow(new IllegalAccessException("Only owners can give teams access"));

			Response response = resource.addGroupProjectPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "projectId", "p1", "permission", "OWNER"));

			assertEquals(403, response.getStatus());
			assertEquals("Only owners can give teams access", errorOf(response));
		}
	}

	@Test
	void editsAGroupsPermissionOnTheProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editGroupProjectPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "projectId", "p1", "permission", "READ_ONLY"));

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupProjectUtils.editProjectGroupPermission(user, "Sales", "CUSTOM",
					"p1", "READ_ONLY", null));
		}
	}

	@Test
	void removesAGroupFromTheProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.removeGroupProjectPermission(requestFor(user),
					form("groupId", "Sales", "type", "CUSTOM", "projectId", "p1"));

			assertEquals(200, response.getStatus());
			statics.groups
					.verify(() -> SecurityGroupProjectUtils.removeProjectGroupPermission(user, "Sales", "CUSTOM", "p1"));
		}
	}

	@Test
	void requiresTheGroupToRemoveIt() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.removeGroupProjectPermission(requestFor(user),
					form("type", "CUSTOM", "projectId", "p1"));

			assertEquals(400, response.getStatus());
			assertEquals("The group id cannot be null or empty", errorOf(response));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void listsAndCountsTheGroupsWithAccess() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.getGroupsWithAccessToProject(user, "p1", 10, 0))
					.thenReturn(List.of(Map.of("id", "Sales")));
			statics.groups.when(() -> SecurityGroupProjectUtils.getNumGroupsWithAccessToProject(user, "p1"))
					.thenReturn(1L);

			Response response = resource.getAllGroupsWithAccessToProject(requestFor(user), "p1", 10, 0);

			assertEquals(200, response.getStatus());
			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(1.0, payload.get("totalGroups"));
			assertEquals(List.of(Map.of("id", "Sales")), payload.get("groups"));
		}
	}

	@Test
	void refusesListingGroupsForACallerWhoCannotSeeTheProject() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.getGroupsWithAccessToProject(user, "p1", 10, 0))
					.thenThrow(new IllegalAccessException("The user does not have access to view this project"));

			Response response = resource.getAllGroupsWithAccessToProject(requestFor(user), "p1", 10, 0);

			assertEquals(403, response.getStatus());
			statics.groups.verify(() -> SecurityGroupProjectUtils.getNumGroupsWithAccessToProject(any(), anyString()),
					never());
		}
	}

	@Test
	void requiresTheProjectToListGroups() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response withAccess = resource.getAllGroupsWithAccessToProject(requestFor(user), null, 10, 0);
			Response available = resource.getAvailableGroupsForProject(requestFor(user), "", "x", 10, 0);

			assertEquals("Must define the projectId", errorOf(withAccess));
			assertEquals(400, available.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void listsTheGroupsAvailableToTheProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupProjectUtils.getAvailableGroupsForProject(user, "p1", "sa", 10, 0))
					.thenReturn(List.of(Map.of("id", "Sales")));

			Response response = resource.getAvailableGroupsForProject(requestFor(user), "p1", "sa", 10, 0);

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("id", "Sales")), body(response));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.removeGroupProjectPermission(signedOut(),
					form("groupId", "Sales", "type", "CUSTOM", "projectId", "p1"));

			assertEquals(401, response.getStatus());
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void refusesToReadAGroupsPermissionOnAProjectTheUserCannotView() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.projects.when(() -> SecurityProjectUtils.userCanViewProject(user, "p1")).thenReturn(false);

			Response response = resource.getGroupProjectPermission(requestFor(user), "Sales", "CUSTOM", "p1");

			assertEquals(403, response.getStatus());
			assertEquals("The user does not have access to view this project", errorOf(response));
			statics.groups.verify(() -> SecurityGroupProjectUtils.getGroupProjectPermission(anyString(), anyString(),
					anyString()), never());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "OWNR", "7", "admin" })
	void refusesAnUnknownLevelWithoutSavingIt(String level) throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response add = resource.addGroupProjectPermission(requestFor(user), form("groupId", "Sales", "type",
					"CUSTOM", "projectId", "p1", "permission", level));
			Response edit = resource.editGroupProjectPermission(requestFor(user), form("groupId", "Sales", "type",
					"CUSTOM", "projectId", "p1", "permission", level));

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
			Response response = resource.addGroupProjectPermission(requestFor(user), form("groupId", "Sales", "type",
					"CUSTOM", "projectId", "p1", "permission", " edit "));

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupProjectUtils.addProjectGroupPermission(user, "Sales", "CUSTOM",
					"p1", "EDIT", null));
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group/project");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group/project");
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
		private final MockedStatic<SecurityGroupProjectUtils> groups = Mockito
				.mockStatic(SecurityGroupProjectUtils.class);
		/** whether the user can view the project, which every read here needs; yes unless a test says no */
		private final MockedStatic<SecurityProjectUtils> projects = Mockito.mockStatic(SecurityProjectUtils.class);

		private Statics() {
			projects.when(() -> SecurityProjectUtils.userCanViewProject(any(), anyString())).thenReturn(true);
		}

		@Override
		public void close() {
			projects.close();
			groups.close();
			audit.close();
		}
	}
}
