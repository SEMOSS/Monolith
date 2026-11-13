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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityGroupManagerUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.util.Constants;

class AdminGroupAuthorizationResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
	private static final String USAGE_LIMIT_JSON = "{\"unlimited\":true}";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminGroupAuthorizationResource resource, HttpServletRequest request) throws Exception;
	}

	/**
	 * The static Semoss calls the resource makes, mocked for one test, with the
	 * signed in user an admin or not.
	 */
	private static final class Fixture implements AutoCloseable {
		final User user;
		final HttpServletRequest request;
		final Map<String, String[]> parameters = new HashMap<>();
		final AdminSecurityGroupUtils groups = Mockito.mock(AdminSecurityGroupUtils.class);
		final MockedStatic<AdminSecurityGroupUtils> admin = Mockito.mockStatic(AdminSecurityGroupUtils.class);
		final MockedStatic<SecurityAdminUtils> adminUtils = Mockito.mockStatic(SecurityAdminUtils.class);
		final MockedStatic<SecurityGroupManagerUtils> managers = Mockito.mockStatic(SecurityGroupManagerUtils.class);
		final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);
		final MockedStatic<MicrosoftGraphUserLookup> directory = Mockito.mockStatic(MicrosoftGraphUserLookup.class);
		final MockedStatic<MsGraphUtility> graph = Mockito.mockStatic(MsGraphUtility.class);

		Fixture(User user, boolean isAdmin) {
			this.user = user;
			this.request = requestFor(user);
			when(request.getParameterMap()).thenReturn(parameters);
			admin.when(() -> AdminSecurityGroupUtils.getInstance(user)).thenReturn(isAdmin ? groups : null);
			adminUtils.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(isAdmin);
		}

		static Fixture asAdmin() {
			return new Fixture(signedIn("admin1"), true);
		}

		Fixture params(String... pairs) {
			for (int i = 0; i < pairs.length; i += 2) {
				parameters.put(pairs[i], new String[] { pairs[i + 1] });
				when(request.getParameter(pairs[i])).thenReturn(pairs[i + 1]);
			}
			return this;
		}

		@Override
		public void close() {
			graph.close();
			directory.close();
			limits.close();
			managers.close();
			adminUtils.close();
			admin.close();
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
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/group");
		if (user != null) {
			HttpSession session = Mockito.mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private static JsonElement body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
	}

	private static String errorMessage(Response response) throws IOException {
		return body(response).getAsJsonObject().get(Constants.ERROR_MESSAGE).getAsString();
	}

	private static void assertTrueBody(Response response) throws IOException {
		assertEquals(200, response.getStatus());
		assertTrue(body(response).getAsBoolean());
	}

	private static Named<Endpoint> endpoint(String name, Endpoint call) {
		return Named.of(name, call);
	}

	static Stream<Arguments> levelsOutOfRange() {
		List<Named<Endpoint>> writes = List.of( endpoint("addGroupProjectPermission", (r,
				q) -> r.addGroupProjectPermission(q)), endpoint("editGroupProjectPermission", (r,
				q) -> r.editGroupProjectPermission(q)), endpoint("addGroupEnginePermission", (r,
				q) -> r.addGroupEnginePermission(q)), endpoint("editGroupEnginePermission", (r,
				q) -> r.editGroupEnginePermission(q)));
		return writes.stream().flatMap(write -> Stream.of("0", "4", "7", "-1").map(level -> Arguments.of(write,
				level)));
	}

	@ParameterizedTest
	@MethodSource("levelsOutOfRange")
	void refusesALevelOutsideOneToThreeWithoutSavingIt(Endpoint write, String level) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "projectId", "p1", "engineId", "e1", "permission", level);

			Response response = write.call(new AdminGroupAuthorizationResource(), f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The permission must be 1 (OWNER), 2 (EDIT) or 3 (READ_ONLY). Received value = " + level,
					errorMessage(response));
			Mockito.verifyNoInteractions(f.groups);
		}
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(endpoint("getGroups", (r, q) -> r.getGroups(q, null, 0, 0)),
				endpoint("getGroupDetails", (r, q) -> r.getGroupDetails(q, "g1", "CUSTOM")),
				endpoint("getNumGroups", (r, q) -> r.getNumGroups(q, null)),
				endpoint("addGroup", (r, q) -> r.addGroup(q)), endpoint("deleteGroup", (r, q) -> r.deleteGroup(q)),
				endpoint("editGroupDetails", (r, q) -> r.editGroupDetails(q)),
				endpoint("getGroupMembers", (r, q) -> r.getGroupMembers(q, "g1", null, 0, 0)),
				endpoint("getNumMembersInGroup", (r, q) -> r.getNumMembersInGroup(q, "g1", null)),
				endpoint("getNonGroupMembers", (r, q) -> r.getNonGroupMembers(q, "g1", null, 0, 0, null)),
				endpoint("addGroupMember", (r, q) -> r.addGroupMember(q)),
				endpoint("deleteGroupMember", (r, q) -> r.deleteGroupMember(q)),
				endpoint("getGroupManagers", (r, q) -> r.getGroupManagers(q, "g1")),
				endpoint("addGroupManager", (r, q) -> r.addGroupManager(q)),
				endpoint("removeGroupManager", (r, q) -> r.removeGroupManager(q)),
				endpoint("addGroupProjectPermission", (r, q) -> r.addGroupProjectPermission(q)),
				endpoint("editGroupProjectPermission", (r, q) -> r.editGroupProjectPermission(q)),
				endpoint("removeGroupProjectPermission", (r, q) -> r.removeGroupProjectPermission(q)),
				endpoint("getProjectsForGroup", (r, q) -> r.getProjectsForGroup(q, "g1", "CUSTOM", null, 0, 0, null)),
				endpoint("getNumProjectsForGroup", (r, q) -> r.getNumProjectsForGroup(q, "g1", "CUSTOM", null, null)),
				endpoint("getAvailableProjectsForGroup",
						(r, q) -> r.getAvailableProjectsForGroup(q, "g1", "CUSTOM", null, 0, 0, null)),
				endpoint("addGroupEnginePermission", (r, q) -> r.addGroupEnginePermission(q)),
				endpoint("editGroupEnginePermission", (r, q) -> r.editGroupEnginePermission(q)),
				endpoint("removeGroupEnginePermission", (r, q) -> r.removeGroupEnginePermission(q)),
				endpoint("getEnginesForGroup", (r, q) -> r.getEnginesForGroup(q, "g1", "CUSTOM", null, 0, 0)),
				endpoint("getNumEnginesForGroup", (r, q) -> r.getNumEnginesForGroup(q, "g1", "CUSTOM", null)),
				endpoint("getAvailableEnginesForGroup",
						(r, q) -> r.getAvailableEnginesForGroup(q, "g1", "CUSTOM", null, 0, 0)));
	}

	static Stream<Named<Endpoint>> memberAndManagerWrites() {
		return Stream.of(endpoint("addGroupMember", (r, q) -> r.addGroupMember(q)),
				endpoint("deleteGroupMember", (r, q) -> r.deleteGroupMember(q)),
				endpoint("addGroupManager", (r, q) -> r.addGroupManager(q)),
				endpoint("removeGroupManager", (r, q) -> r.removeGroupManager(q)));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith403BeforeDoingAnything(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			f.params("groupId", "g1", "type", "CUSTOM", "userId", "u1", "projectId", "p1", "engineId", "e1",
					"permission", "1", "usageLimit", USAGE_LIMIT_JSON);

			Response response = endpoint.call(new AdminGroupAuthorizationResource(), f.request);

			assertEquals(403, response.getStatus());
			assertEquals(ADMINS_ONLY, errorMessage(response));
			f.managers.verifyNoInteractions();
			f.limits.verifyNoInteractions();
			f.directory.verifyNoInteractions();
			f.graph.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false)) {
			Response response = endpoint.call(new AdminGroupAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verifyNoInteractions();
		}
	}

	@Test
	void listsTheGroups() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getGroups("sales", 10, 5)).thenReturn(List.of(Map.of("id", "Sales")));

			Response response = new AdminGroupAuthorizationResource().getGroups(f.request, "sales", 10, 5);

			assertEquals("Sales", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void readsTheDetailsOfAGroupByItsTypeLabel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getGroupDetails("g1", "MICROSOFT")).thenReturn(Map.of("id", "g1"));

			Response response = new AdminGroupAuthorizationResource().getGroupDetails(f.request, "g1", "ms");

			assertEquals("g1", body(response).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void countsNoGroupsAsZero() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getNumGroups(null)).thenReturn(null);

			Response response = new AdminGroupAuthorizationResource().getNumGroups(f.request, null);

			assertEquals(0, body(response).getAsLong());
		}
	}

	@Test
	void addsAGroupWithItsDescriptionTrimmed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "type", "CUSTOM", "description", "  The sales team  ");

			assertTrueBody(new AdminGroupAuthorizationResource().addGroup(f.request));
			verify(f.groups).addGroup(f.user, "Sales", "CUSTOM", "The sales team");
		}
	}

	@Test
	void addsAGroupWithAnEmptyDescriptionWhenNoneIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "type", "custom");

			assertTrueBody(new AdminGroupAuthorizationResource().addGroup(f.request));
			verify(f.groups).addGroup(f.user, "Sales", "custom", "");
		}
	}

	@Test
	void rejectsANewGroupWithoutAnIdOrAType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();
			f.params("type", "CUSTOM");
			Response noId = resource.addGroup(f.request);
			assertEquals(400, noId.getStatus());
			assertEquals("The group id cannot be null or empty", errorMessage(noId));

			f.params("groupId", "Sales", "type", " ");
			Response noType = resource.addGroup(f.request);
			assertEquals(400, noType.getStatus());
			assertEquals("The group type cannot be null", errorMessage(noType));

			verify(f.groups, never()).addGroup(any(), any(), any(), any());
		}
	}

	@Test
	void answersAGroupThatAlreadyExistsWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "type", "CUSTOM");
			doThrow(new IllegalArgumentException("Group Sales with type CUSTOM already exists")).when(f.groups)
					.addGroup(any(), any(), any(), any());

			Response response = new AdminGroupAuthorizationResource().addGroup(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("Group Sales with type CUSTOM already exists", errorMessage(response));
		}
	}

	@Test
	void deletesAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "type", "CUSTOM");

			assertTrueBody(new AdminGroupAuthorizationResource().deleteGroup(f.request));
			verify(f.groups).deleteGroupAndPropagate("Sales", "CUSTOM");
		}
	}

	@Test
	void rejectsADeleteWithoutAGroupId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("type", "CUSTOM");

			Response response = new AdminGroupAuthorizationResource().deleteGroup(f.request);

			assertEquals(400, response.getStatus());
			verify(f.groups, never()).deleteGroupAndPropagate(any(), any());
		}
	}

	@Test
	void editsAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "newGroupId", " Sales EMEA ", "type", "CUSTOM", "newDescription", "EMEA");

			assertTrueBody(new AdminGroupAuthorizationResource().editGroupDetails(f.request));
			verify(f.groups).editGroupDetailsAndPropagate(f.user, "Sales", "CUSTOM", "Sales EMEA", "EMEA");
		}
	}

	@Test
	void rejectsAnEditWithoutANewGroupId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "Sales", "type", "CUSTOM");

			Response response = new AdminGroupAuthorizationResource().editGroupDetails(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The new group id cannot be null or empty", errorMessage(response));
			verify(f.groups, never()).editGroupDetailsAndPropagate(any(), any(), any(), any(), any());
		}
	}

	@Test
	void listsAndCountsTheMembersOfAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getGroupMembers("g1", "smith", 10, 0)).thenReturn(List.of(Map.of("id", "u1")));
			when(f.groups.getNumMembersInGroup("g1", "smith")).thenReturn(1L);
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			Response members = resource.getGroupMembers(f.request, " g1 ", "smith", 10, 0);
			Response count = resource.getNumMembersInGroup(f.request, "g1", "smith");

			assertEquals("u1", body(members).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			assertEquals(1, body(count).getAsLong());
		}
	}

	@Test
	void rejectsAMemberListWithoutAGroupId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminGroupAuthorizationResource().getGroupMembers(f.request, null, null, 0, 0);

			assertEquals(400, response.getStatus());
			assertEquals("Must define the group id", errorMessage(response));
		}
	}

	@Test
	void searchesTheSecurityDatabaseForPeopleOutsideAGroupWhenTheDirectoryIsNotUsed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getNonGroupMembers("g1", "smith", 10, 0)).thenReturn(List.of(Map.of("id", "u9")));

			Response response = new AdminGroupAuthorizationResource().getNonGroupMembers(f.request, "g1", "smith", 10,
					0, "false");

			assertEquals("u9", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			f.graph.verifyNoInteractions();
		}
	}

	@Test
	void searchesTheDirectoryForPeopleOutsideAGroupLeavingOutItsMembers() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			List<Map<String, Object>> members = List.of(Map.of("id", "u1"));
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			when(f.groups.getGroupMembers("g1", null, -1, -1)).thenReturn(members);
			f.graph.when(() -> MsGraphUtility.getGroupUsers(f.request, f.user, "g1", members, "smith", 10, 0))
					.thenReturn(List.of(Map.of("id", "ms1")));

			Response response = new AdminGroupAuthorizationResource().getNonGroupMembers(f.request, "g1", "smith", 10,
					0, "true");

			assertEquals("ms1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			verify(f.groups, never()).getNonGroupMembers(any(), any(), anyLong(), anyLong());
		}
	}

	@Test
	void addsAMemberWithTheirLoginTypeAndEndDate() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "userId", "u1", "type", "NATIVE", "endDate", "2030-01-01");

			assertTrueBody(new AdminGroupAuthorizationResource().addGroupMember(f.request));
			f.managers.verify(() -> SecurityGroupManagerUtils.addUserToGroup(f.groups, f.user, "g1", "u1", "NATIVE",
					"2030-01-01"));
		}
	}

	@Test
	void removesAMemberWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "userId", "u1", "type", "MICROSOFT");

			assertTrueBody(new AdminGroupAuthorizationResource().deleteGroupMember(f.request));
			f.managers.verify(() -> SecurityGroupManagerUtils.removeUserFromGroup(f.groups, "g1", "u1", "MICROSOFT"));
		}
	}

	@Test
	void listsAddsAndRemovesTheManagersOfAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.managers.when(() -> SecurityGroupManagerUtils.getGroupManagers(f.groups, "g1"))
					.thenReturn(List.of(Map.of("id", "m1")));
			f.params("groupId", "g1", "userId", "m2", "type", "NATIVE");
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			Response managers = resource.getGroupManagers(f.request, "g1");
			assertTrueBody(resource.addGroupManager(f.request));
			assertTrueBody(resource.removeGroupManager(f.request));

			assertEquals("m1", body(managers).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			f.managers.verify(() -> SecurityGroupManagerUtils.addGroupManager(f.groups, f.user, "g1", "m2", "NATIVE"));
			f.managers.verify(() -> SecurityGroupManagerUtils.removeGroupManager(f.groups, "g1", "m2", "NATIVE"));
		}
	}

	@ParameterizedTest
	@MethodSource("memberAndManagerWrites")
	void rejectsAMemberOrManagerChangeWithoutTheUserLoginType(Endpoint endpoint) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "userId", "u1");

			Response response = endpoint.call(new AdminGroupAuthorizationResource(), f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The user login type ('type') cannot be null or empty", errorMessage(response));
			f.managers.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("memberAndManagerWrites")
	void rejectsAMemberOrManagerChangeWithoutTheUserId(Endpoint endpoint) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "NATIVE");

			Response response = endpoint.call(new AdminGroupAuthorizationResource(), f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The user id ('userId') cannot be null or empty", errorMessage(response));
			f.managers.verifyNoInteractions();
		}
	}

	@Test
	void givesChangesAndTakesAwayTheAccessOfAGroupToAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "projectId", "p1", "permission", "2", "endDate",
					"2030-01-01");
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			assertTrueBody(resource.addGroupProjectPermission(f.request));
			assertTrueBody(resource.editGroupProjectPermission(f.request));
			assertTrueBody(resource.removeGroupProjectPermission(f.request));

			verify(f.groups).addGroupProjectPermission(f.user, "g1", "CUSTOM", "p1", 2, "2030-01-01");
			verify(f.groups).editGroupProjectPermission(f.user, "g1", "CUSTOM", "p1", 2, "2030-01-01");
			verify(f.groups).removeGroupProjectPermission(f.user, "g1", "CUSTOM", "p1");
		}
	}

	@Test
	void rejectsAProjectPermissionThatIsNotAWholeNumber() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "projectId", "p1", "permission", "EDIT");

			Response response = new AdminGroupAuthorizationResource().addGroupProjectPermission(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("Must pass a valid integer value. Received value = EDIT", errorMessage(response));
			verify(f.groups, never()).addGroupProjectPermission(any(), any(), any(), any(), anyInt(), any());
		}
	}

	@Test
	void rejectsAProjectPermissionChangeWithoutALevelOrAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();
			f.params("groupId", "g1", "type", "CUSTOM", "projectId", "p1");
			Response noLevel = resource.editGroupProjectPermission(f.request);
			assertEquals(400, noLevel.getStatus());
			assertEquals("The permission integer value ('permission') cannot be null or empty", errorMessage(noLevel));

			f.params("projectId", "", "permission", "1");
			Response noProject = resource.editGroupProjectPermission(f.request);
			assertEquals(400, noProject.getStatus());
			assertEquals("The project id ('projectId') cannot be null or empty", errorMessage(noProject));

			verify(f.groups, never()).editGroupProjectPermission(any(), any(), any(), any(), anyInt(), any());
		}
	}

	@Test
	void listsCountsAndOffersProjectsForAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			List<String> types = List.of("CODE");
			when(f.groups.getProjectsForGroup("g1", "CUSTOM", "s", 10, 0, types))
					.thenReturn(List.of(Map.of("id", "p1")));
			when(f.groups.getNumProjectsForGroup("g1", "CUSTOM", "s", types)).thenReturn(1L);
			when(f.groups.getAvailableProjectsForGroup("g1", "CUSTOM", "s", 10, 0, types))
					.thenReturn(List.of(Map.of("id", "p2")));
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			assertEquals("p1", body(resource.getProjectsForGroup(f.request, "g1", "CUSTOM", "s", 10, 0, types))
					.getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			assertEquals(1, body(resource.getNumProjectsForGroup(f.request, "g1", "CUSTOM", "s", types)).getAsLong());
			assertEquals("p2", body(resource.getAvailableProjectsForGroup(f.request, "g1", "CUSTOM", "s", 10, 0, types))
					.getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void givesAGroupAccessToAModelWithATeamLimitWhenALimitIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson(USAGE_LIMIT_JSON)).thenReturn(limit);
			f.params("groupId", "g1", "type", "CUSTOM", "engineId", "e1", "permission", "3", "usageLimit",
					USAGE_LIMIT_JSON);

			assertTrueBody(new AdminGroupAuthorizationResource().addGroupEnginePermission(f.request));
			verify(f.groups).addGroupEnginePermission(f.user, "g1", "CUSTOM", "e1", 3, null, limit);
			verify(f.groups, never()).addGroupEnginePermission(any(), any(), any(), any(), anyInt(), any());
		}
	}

	@Test
	void givesAGroupAccessToAnEngineWithoutALimitWhenNoneIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "engineId", "e1", "permission", "1");

			assertTrueBody(new AdminGroupAuthorizationResource().addGroupEnginePermission(f.request));
			verify(f.groups).addGroupEnginePermission(f.user, "g1", "CUSTOM", "e1", 1, null);
			verify(f.groups, never()).addGroupEnginePermission(any(), any(), any(), any(), anyInt(), any(), any());
			f.limits.verifyNoInteractions();
		}
	}

	@Test
	void changesAndTakesAwayTheAccessOfAGroupToAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "ms", "engineId", "e1", "permission", "2");
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			assertTrueBody(resource.editGroupEnginePermission(f.request));
			assertTrueBody(resource.removeGroupEnginePermission(f.request));

			verify(f.groups).editGroupEnginePermission(f.user, "g1", "MICROSOFT", "e1", 2, null);
			verify(f.groups).removeGroupEnginePermission(f.user, "g1", "MICROSOFT", "e1");
		}
	}

	@Test
	void rejectsAnEnginePermissionChangeWithoutAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "permission", "2");

			Response response = new AdminGroupAuthorizationResource().removeGroupEnginePermission(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The engine id ('engineId') cannot be null or empty", errorMessage(response));
			verify(f.groups, never()).removeGroupEnginePermission(any(), any(), any(), any());
		}
	}

	@Test
	void answersAFailedGroupEngineWriteWithItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "g1", "type", "CUSTOM", "engineId", "e1", "permission", "2");
			doThrow(new IllegalArgumentException("Group g1 does not currently have access to engine e1 to edit"))
					.when(f.groups).editGroupEnginePermission(any(), any(), any(), any(), anyInt(), any());

			Response response = new AdminGroupAuthorizationResource().editGroupEnginePermission(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("Group g1 does not currently have access to engine e1 to edit", errorMessage(response));
		}
	}

	@Test
	void listsCountsAndOffersEnginesForAGroup() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.groups.getEnginesForGroup("g1", "CUSTOM", "s", 10, 0)).thenReturn(List.of(Map.of("id", "e1")));
			when(f.groups.getNumEnginesForGroup("g1", "CUSTOM", "s")).thenReturn(2L);
			when(f.groups.getAvailableEnginesForGroup("g1", "CUSTOM", "s", 10, 0))
					.thenReturn(List.of(Map.of("id", "e2")));
			AdminGroupAuthorizationResource resource = new AdminGroupAuthorizationResource();

			assertEquals("e1", body(resource.getEnginesForGroup(f.request, "g1", "CUSTOM", "s", 10, 0)).getAsJsonArray()
					.get(0).getAsJsonObject().get("id").getAsString());
			assertEquals(2, body(resource.getNumEnginesForGroup(f.request, "g1", "CUSTOM", "s")).getAsLong());
			assertEquals("e2", body(resource.getAvailableEnginesForGroup(f.request, "g1", "CUSTOM", "s", 10, 0))
					.getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		}
	}
}
