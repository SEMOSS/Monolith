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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityGroupManagerUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class GroupAuthorizationResourceTest {

	private final GroupAuthorizationResource resource = new GroupAuthorizationResource();

	@Test
	void listsOnlyTheGroupsTheCallerManages() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = requestFor(user);
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getManagedGroups(user, "sales", 10, 5))
					.thenReturn(List.of(Map.of("id", "Sales")));

			Response response = resource.getGroups(request, "sales", 10, 5);

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("id", "Sales")), body(response));
		}
	}

	@Test
	void countsTheManagedGroups() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getNumManagedGroups(user, null)).thenReturn(3L);

			Response response = resource.getNumGroups(requestFor(user), null);

			assertEquals(200, response.getStatus());
			assertEquals(3.0, body(response));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.getGroups(signedOut(), "x", 10, 0);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorOf(response));
			statics.groups.verifyNoInteractions();
		}
	}

	@Test
	void answers403WhenTheCallerDoesNotManageTheGroup() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getGroupDetails(user, "Sales"))
					.thenThrow(new IllegalAccessException("Only this team's managers can see it"));

			Response response = resource.getGroupDetails(requestFor(user), "Sales");

			assertEquals(403, response.getStatus());
			assertEquals("Only this team's managers can see it", errorOf(response));
		}
	}

	@Test
	void passesTheProjectAndEngineThroughWhenListingManagers() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getGroupManagers(user, "Sales", "p1", "e1"))
					.thenReturn(List.of());

			Response response = resource.getGroupManagers(requestFor(user), "Sales", "p1", "e1");

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupManagerUtils.getGroupManagers(user, "Sales", "p1", "e1"));
		}
	}

	@Test
	void listsAndCountsMembersForTheSamePeople() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getGroupMembers(user, "Sales", "p1", null, "bo", 20, 40))
					.thenReturn(List.of(Map.of("id", "bob")));
			statics.groups.when(() -> SecurityGroupManagerUtils.getNumMembersInGroup(user, "Sales", "p1", null, "bo"))
					.thenReturn(1L);

			Response members = resource.getGroupMembers(requestFor(user), "Sales", "p1", null, "bo", 20, 40);
			Response count = resource.getNumMembersInGroup(requestFor(user), "Sales", "p1", null, "bo");

			assertEquals(List.of(Map.of("id", "bob")), body(members));
			assertEquals(1.0, body(count));
		}
	}

	@Test
	void searchesTheSecurityDatabaseForNonMembersWhenTheDirectoryIsNotUsed() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = requestFor(user);
		try (Statics statics = new Statics()) {
			statics.directory.when(() -> MicrosoftGraphUserLookup.useDirectory(null)).thenReturn(false);
			statics.groups.when(() -> SecurityGroupManagerUtils.getNonGroupMembers(user, "Sales", "bo", 10, 0))
					.thenReturn(List.of());

			Response response = resource.getNonGroupMembers(request, "Sales", "bo", 10, 0, null);

			assertEquals(200, response.getStatus());
			statics.graph.verifyNoInteractions();
		}
	}

	@Test
	void searchesTheDirectoryLeavingOutCurrentMembersWithAClampedPage() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = requestFor(user);
		List<Map<String, Object>> members = List.of(Map.of("id", "bob"));
		try (Statics statics = new Statics()) {
			statics.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			statics.groups.when(() -> SecurityGroupManagerUtils.getAllGroupMembers(user, "Sales")).thenReturn(members);
			statics.groups.when(() -> SecurityGroupManagerUtils.clampPageSize(5000)).thenReturn(100L);
			statics.graph.when(() -> MsGraphUtility.getGroupUsers(request, user, "Sales", members, "bo", 100L, 0L))
					.thenReturn(List.of());

			Response response = resource.getNonGroupMembers(request, "Sales", "bo", 5000, 0, "true");

			assertEquals(200, response.getStatus());
			statics.graph.verify(() -> MsGraphUtility.getGroupUsers(request, user, "Sales", members, "bo", 100L, 0L));
			statics.groups.verify(() -> SecurityGroupManagerUtils.getNonGroupMembers(any(), anyString(), anyString(),
					anyLong(), anyLong()), Mockito.never());
		}
	}

	@Test
	void listsAndCountsTheProjectsAndEnginesOfAGroup() throws Exception {
		User user = signedIn("alice");
		List<String> types = List.of("CODE");
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.getProjectsForGroup(user, "Sales", "x", 10, 0, types))
					.thenReturn(List.of());
			statics.groups.when(() -> SecurityGroupManagerUtils.getNumProjectsForGroup(user, "Sales", "x", types))
					.thenReturn(0L);
			statics.groups.when(() -> SecurityGroupManagerUtils.getEnginesForGroup(user, "Sales", "x", 10, 0))
					.thenReturn(List.of());
			statics.groups.when(() -> SecurityGroupManagerUtils.getNumEnginesForGroup(user, "Sales", "x"))
					.thenReturn(0L);

			assertEquals(200, resource.getProjectsForGroup(requestFor(user), "Sales", "x", 10, 0, types).getStatus());
			assertEquals(200, resource.getNumProjectsForGroup(requestFor(user), "Sales", "x", types).getStatus());
			assertEquals(200, resource.getEnginesForGroup(requestFor(user), "Sales", "x", 10, 0).getStatus());
			assertEquals(200, resource.getNumEnginesForGroup(requestFor(user), "Sales", "x").getStatus());
			statics.groups
					.verify(() -> SecurityGroupManagerUtils.getProjectsForGroup(user, "Sales", "x", 10, 0, types));
			statics.groups.verify(() -> SecurityGroupManagerUtils.getEnginesForGroup(user, "Sales", "x", 10, 0));
		}
	}

	@Test
	void addsAMemberWithTheirLoginTypeAndEndDate() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = postWith(user,
				Map.of("groupId", "Sales", "userId", "bob", "type", "NATIVE", "endDate", "2027-01-01"));
		try (Statics statics = new Statics()) {
			Response response = resource.addGroupMember(request);

			assertEquals(200, response.getStatus());
			assertEquals(true, body(response));
			statics.groups.verify(
					() -> SecurityGroupManagerUtils.addUserToGroup(user, "Sales", "bob", "NATIVE", "2027-01-01"));
		}
	}

	@Test
	void removesAMemberByIdAndLoginType() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = postWith(user, Map.of("groupId", "Sales", "userId", "bob", "type", "LDAP"));
		try (Statics statics = new Statics()) {
			Response response = resource.deleteGroupMember(request);

			assertEquals(200, response.getStatus());
			statics.groups.verify(() -> SecurityGroupManagerUtils.removeUserFromGroup(user, "Sales", "bob", "LDAP"));
		}
	}

	@Test
	void addsAndRemovesAManagerByIdAndLoginType() throws Exception {
		User user = signedIn("alice");
		Map<String, String> form = Map.of("groupId", "Sales", "userId", "bob", "type", "NATIVE");
		try (Statics statics = new Statics()) {
			assertEquals(200, resource.addGroupManager(postWith(user, form)).getStatus());
			assertEquals(200, resource.removeGroupManager(postWith(user, form)).getStatus());

			statics.groups.verify(() -> SecurityGroupManagerUtils.addGroupManager(user, "Sales", "bob", "NATIVE"));
			statics.groups.verify(() -> SecurityGroupManagerUtils.removeGroupManager(user, "Sales", "bob", "NATIVE"));
		}
	}

	@Test
	void refusesANonManagerChangingMembersWith403() throws Exception {
		User user = signedIn("mallory");
		HttpServletRequest request = postWith(user, Map.of("groupId", "Sales", "userId", "mallory", "type", "NATIVE"));
		try (Statics statics = new Statics()) {
			statics.groups
					.when(() -> SecurityGroupManagerUtils.addUserToGroup(eq(user), eq("Sales"), eq("mallory"), eq("NATIVE"),
							any()))
					.thenThrow(new IllegalAccessException("Only this team's managers can change its members"));
			statics.groups.when(() -> SecurityGroupManagerUtils.addGroupManager(user, "Sales", "mallory", "NATIVE"))
					.thenThrow(new IllegalAccessException("Only this team's managers can change who manages it"));

			Response member = resource.addGroupMember(request);
			Response manager = resource.addGroupManager(request);

			assertEquals(403, member.getStatus());
			assertEquals("Only this team's managers can change its members", errorOf(member));
			assertEquals(403, manager.getStatus());
		}
	}

	@Test
	void answers400ForAMissingMember() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = postWith(user, Map.of("groupId", "Sales"));
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.removeUserFromGroup(user, "Sales", null, null))
					.thenThrow(new IllegalArgumentException("The user id cannot be null or empty"));

			Response response = resource.deleteGroupMember(request);

			assertEquals(400, response.getStatus());
			assertEquals("The user id cannot be null or empty", errorOf(response));
		}
	}

	@Test
	void hidesTheCauseOfAnUnexpectedFailureBehindA500() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = postWith(user, Map.of("groupId", "Sales", "userId", "bob", "type", "NATIVE"));
		try (Statics statics = new Statics()) {
			statics.groups.when(() -> SecurityGroupManagerUtils.addGroupManager(user, "Sales", "bob", "NATIVE"))
					.thenThrow(new IllegalStateException("db down"));

			Response response = resource.addGroupManager(request);

			assertEquals(500, response.getStatus());
			Map<?, ?> error = (Map<?, ?>) body(response);
			assertEquals("An unexpected error happened. Please reach out to an admin.",
					error.get(Constants.ERROR_MESSAGE));
			assertEquals("db down", error.get(Constants.TECH_ERROR_MESSAGE));
		}
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
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest postWith(User user, Map<String, String> params) {
		HttpServletRequest request = requestFor(user);
		when(request.getMethod()).thenReturn("POST");
		when(request.getParameter(anyString())).thenAnswer(call -> params.get(call.getArgument(0)));
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/group");
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
		private final MockedStatic<SecurityGroupManagerUtils> groups = Mockito
				.mockStatic(SecurityGroupManagerUtils.class);
		private final MockedStatic<MicrosoftGraphUserLookup> directory = Mockito
				.mockStatic(MicrosoftGraphUserLookup.class);
		private final MockedStatic<MsGraphUtility> graph = Mockito.mockStatic(MsGraphUtility.class);

		@Override
		public void close() {
			graph.close();
			directory.close();
			groups.close();
			audit.close();
		}
	}
}
