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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.impl.InsightAdministrator;
import prerna.project.api.IProject;
import prerna.util.Constants;
import prerna.util.Utility;

class AdminInsightAuthorizationResourceTest {

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminInsightAuthorizationResource resource, HttpServletRequest request) throws Exception;
	}

	/**
	 * The admin check mocked for one test, with the signed in user an admin or not.
	 */
	private static final class Fixture implements AutoCloseable {
		final User user;
		final HttpServletRequest request;
		final SecurityAdminUtils utils = Mockito.mock(SecurityAdminUtils.class);
		final MockedStatic<SecurityAdminUtils> admin = Mockito.mockStatic(SecurityAdminUtils.class);

		Fixture(User user, boolean isAdmin) {
			this.user = user;
			this.request = requestFor(user);
			admin.when(() -> SecurityAdminUtils.getInstance(user)).thenReturn(isAdmin ? utils : null);
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(isAdmin);
		}

		static Fixture asAdmin() {
			return new Fixture(signedIn("admin1"), true);
		}

		@Override
		public void close() {
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/insight");
		if (user != null) {
			HttpSession session = Mockito.mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private static MultivaluedMap<String, String> form(String... pairs) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			form.putSingle(pairs[i], pairs[i + 1]);
		}
		return form;
	}

	private static JsonElement body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
	}

	private static String errorMessage(Response response) throws IOException {
		return body(response).getAsJsonObject().get(Constants.ERROR_MESSAGE).getAsString();
	}

	private static void assertSuccess(Response response) throws IOException {
		assertEquals(200, response.getStatus());
		assertTrue(body(response).getAsJsonObject().get("success").getAsBoolean());
	}

	private static Named<Endpoint> endpoint(String name, Endpoint call) {
		return Named.of(name, call);
	}

	@FunctionalInterface
	interface LevelWrite {
		Response call(AdminInsightAuthorizationResource resource, HttpServletRequest request,
				String level) throws Exception;
	}

	private static Named<LevelWrite> write(String name, LevelWrite call) {
		return Named.of(name, call);
	}

	/** A bulk request with one valid row and one with the level, so no row may be saved */
	private static String rows(String level) {
		return "[{\"userid\":\"u1\",\"type\":\"NATIVE\",\"permission\":\"EDIT\"},{\"userid\":\"u2\","
				+ "\"type\":\"NATIVE\",\"permission\":" + (level == null ? "null" : "\"" + level + "\"") + "}]";
	}

	static Stream<Arguments> unknownLevels() {
		List<Named<LevelWrite>> writes = List.of(
				write("addInsightUserPermission", (r, q, l) -> r.addInsightUserPermission(q,
						form("id", "u1", "projectId", "p1", "insightId", "i1", "permission", l))),
				write("grantAllProjectInsights", (r, q, l) -> r.grantAllProjectInsights(q,
						form("projectId", "p1", "userId", "u1", "permission", l))),
				write("grantNewUsersInsightAccess", (r, q, l) -> r.grantNewUsersInsightAccess(q,
						form("projectId", "p1", "insightId", "i1", "permission", l))),
				write("addAllUsers", (r, q, l) -> r.addAllUsers(q,
						form("projectId", "p1", "insightId", "i1", "permission", l))),
				write("editInsightUserPermission", (r, q, l) -> r.editInsightUserPermission(q,
						form("id", "u1", "projectId", "p1", "insightId", "i1", "permission", l))),
				write("editInsightUserPermissions", (r, q, l) -> r.editInsightUserPermissions(q,
						form("projectId", "p1", "insightId", "i1", "userpermissions", rows(l)))),
				write("updateInsightUserPermissions", (r, q, l) -> r.updateAppUserPermissions(q,
						form("projectId", "p1", "insightId", "i1", "permission", l))),
				write("addInsightUserPermissions", (r, q, l) -> r.addInsightUserPermissions(q,
						form("projectId", "p1", "insightId", "i1", "userpermissions", rows(l)))),
				write("approveInsightUserAccessRequest", (r, q, l) -> r.approveInsightUserAccessRequest(q,
						form("projectId", "p1", "insightId", "i1", "requests", rows(l)))));
		return writes.stream().flatMap(write -> Stream.of(null, "", "OWNR", "7", "admin")
				.map(level -> Arguments.of(write, level)));
	}

	@ParameterizedTest
	@MethodSource("unknownLevels")
	void refusesAMissingOrUnknownLevelWithoutSavingAnything(LevelWrite write, String level) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = write.call(new AdminInsightAuthorizationResource(), f.request, level);

			assertEquals(400, response.getStatus());
			assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
					errorMessage(response));
			Mockito.verifyNoInteractions(f.utils);
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().editInsightUserPermission(f.request, form("id",
					"u1", "projectId", "p1", "insightId", "i1", "permission", " owner "));

			assertEquals(200, response.getStatus());
			verify(f.utils).editInsightUserPermission("u1", "p1", "i1", "OWNER", f.user, null);
		}
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(endpoint("getInsights", (r, q) -> r.getInsights(q, "p1", null, 0, 0)),
				endpoint("getProjectInsights", (r, q) -> r.getProjectInsights(q, "p1", null)),
				endpoint("getAllProjectInsightUsers", (r, q) -> r.getAllProjectInsightUsers(q, form("projectId", "p1"))),
				endpoint("deleteProjectInsights",
						(r, q) -> r.deleteProjectInsights(q, form("projectId", "p1", "insightId", "[\"i1\"]"))),
				endpoint("getInsightUsers", (r, q) -> r.getInsightUsers(q, "p1", "i1", null, null, 0, 0)),
				endpoint("addInsightUserPermission", (r, q) -> r.addInsightUserPermission(q, form("id", "u1"))),
				endpoint("grantAllProjectInsights", (r, q) -> r.grantAllProjectInsights(q, form("projectId", "p1"))),
				endpoint("grantNewUsersInsightAccess",
						(r, q) -> r.grantNewUsersInsightAccess(q, form("projectId", "p1"))),
				endpoint("addAllUsers", (r, q) -> r.addAllUsers(q, form("projectId", "p1"))),
				endpoint("editInsightUserPermission", (r, q) -> r.editInsightUserPermission(q, form("id", "u1"))),
				endpoint("editInsightUserPermissions",
						(r, q) -> r.editInsightUserPermissions(q, form("projectId", "p1"))),
				endpoint("updateInsightUserPermissions",
						(r, q) -> r.updateAppUserPermissions(q, form("projectId", "p1"))),
				endpoint("removeInsightUserPermission",
						(r, q) -> r.removeInsightUserPermission(q, form("id", "u1"))),
				endpoint("setInsightGlobal", (r, q) -> r.setInsightGlobal(q, form("projectId", "p1"))),
				endpoint("getInsightUsersNoCredentials",
						(r, q) -> r.getInsightUsersNoCredentials(q, "p1", "i1", null, 0, 0)),
				endpoint("addInsightUserPermissions",
						(r, q) -> r.addInsightUserPermissions(q, form("projectId", "p1"))),
				endpoint("removeInsightUserPermissions",
						(r, q) -> r.removeInsightUserPermissions(q, form("projectId", "p1"))),
				endpoint("approveInsightUserAccessRequest",
						(r, q) -> r.approveInsightUserAccessRequest(q, form("projectId", "p1"))),
				endpoint("denyInsightUserAccessRequest",
						(r, q) -> r.denyInsightUserAccessRequest(q, form("projectId", "p1"))));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith401BeforeDoingAnything(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false);
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			Response response = endpoint.call(new AdminInsightAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User is not an admin", errorMessage(response));
			utility.verify(() -> Utility.getProject(any()), never());
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false)) {
			Response response = endpoint.call(new AdminInsightAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verify(() -> SecurityAdminUtils.getInstance(any()), never());
		}
	}

	@Test
	void listsTheInsightsOfOneProjectWhenAProjectIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllUserInsights(f.user, List.of("p1"), "sales", 10, 5))
					.thenReturn(List.of(Map.of("app_insight_id", "i1")));

			Response response = new AdminInsightAuthorizationResource().getInsights(f.request, " p1 ", "sales", 10,
					5);

			assertEquals(200, response.getStatus());
			assertEquals("i1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("app_insight_id")
					.getAsString());
		}
	}

	@Test
	void listsTheInsightsOfEveryProjectWhenNoProjectIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			new AdminInsightAuthorizationResource().getInsights(f.request, "  ", null, 0, 0);

			verify(f.utils).getAllUserInsights(f.user, null, null, 0, 0);
		}
	}

	@Test
	void listsTheInsightsOfAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getProjectInsights("p1")).thenReturn(List.of(Map.of("name", "Sales")));

			Response response = new AdminInsightAuthorizationResource().getProjectInsights(f.request, "p1", null);

			assertEquals("Sales", body(response).getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString());
		}
	}

	@Test
	void listsTheInsightAccessOfAUserInAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllUserInsightAccess("p1", "u1")).thenReturn(List.of(Map.of("insight_id", "i1")));

			Response response = new AdminInsightAuthorizationResource().getAllProjectInsightUsers(f.request,
					form("projectId", "p1", "userId", "u1"));

			assertEquals("i1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("insight_id")
					.getAsString());
		}
	}

	@Test
	void escapesSingleQuotesInTheIdsOfTheRawInsightAccessQuery() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			new AdminInsightAuthorizationResource().getAllProjectInsightUsers(f.request,
					form("projectId", "p1' OR '1'='1", "userId", "u1"));

			verify(f.utils).getAllUserInsightAccess("p1'' OR ''1''=''1", "u1");
		}
	}

	@Test
	void deletesInsightsOfAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().deleteProjectInsights(f.request,
					form("projectId", "p1", "insightId", "[\"i1\",\"i2\"]"));

			assertSuccess(response);
			verify(f.utils).deleteProjectInsights("p1", List.of("i1", "i2"));
		}
	}

	@Test
	void pagesTheUsersOfAnInsightWithTheirTotal() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getInsightUsers("p1", "i1", "u1", "EDIT", 10, 0)).thenReturn(List.of(Map.of("id", "u1")));
			when(f.utils.getInsightUsersCount("p1", "i1", "u1", "EDIT")).thenReturn(3L);

			Response response = new AdminInsightAuthorizationResource().getInsightUsers(f.request, "p1", "i1", "u1",
					"EDIT", 10, 0);

			JsonObject json = body(response).getAsJsonObject();
			assertEquals(3, json.get("totalMembers").getAsLong());
			assertEquals("u1", json.getAsJsonArray("members").get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void addsAUserToAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().addInsightUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "insightId", "i1", "permission", "EDIT"));

			assertSuccess(response);
			verify(f.utils).addInsightUser("u1", "p1", "i1", "EDIT", f.user, null);
		}
	}

	@Test
	void answersAFailedAddWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			doThrow(new IllegalArgumentException("This user already has access to this insight.")).when(f.utils)
					.addInsightUser(any(), any(), any(), any(), any(), any());

			Response response = new AdminInsightAuthorizationResource().addInsightUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "insightId", "i1", "permission", "EDIT"));

			assertEquals(400, response.getStatus());
			assertEquals("This user already has access to this insight.", errorMessage(response));
		}
	}

	@Test
	void grantsAUserEveryInsightOfAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().grantAllProjectInsights(f.request,
					form("projectId", "p1", "userId", "u1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).grantAllProjectInsights("p1", "u1", "READ_ONLY", f.user);
		}
	}

	@Test
	void grantsAnInsightToEveryoneWithoutAccess() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().grantNewUsersInsightAccess(f.request,
					form("projectId", "p1", "insightId", "i1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).grantNewUsersInsightAccess("p1", "i1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void addsEveryUserToAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().addAllUsers(f.request,
					form("projectId", "p1", "insightId", "i1", "permission", "EDIT"));

			assertSuccess(response);
			verify(f.utils).addAllInsightUsers("p1", "i1", "EDIT", f.user, null);
		}
	}

	@Test
	void editsAUserOfAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().editInsightUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "insightId", "i1", "permission", "OWNER"));

			assertSuccess(response);
			verify(f.utils).editInsightUserPermission("u1", "p1", "i1", "OWNER", f.user, null);
		}
	}

	@Test
	void editsManyUsersOfAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().editInsightUserPermissions(f.request,
					form("projectId", "p1", "insightId", "i1", "userpermissions",
							"[{\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			verify(f.utils).editInsightUserPermissions("p1", "i1", List.of(Map.of("userid", "u1", "permission", "EDIT")),
					f.user, null);
		}
	}

	@Test
	void changesEveryUserOfAnInsightToOneLevel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().updateAppUserPermissions(f.request,
					form("projectId", "p1", "insightId", "i1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).updateInsightUserPermissions("p1", "i1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void removesAUserFromAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().removeInsightUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "insightId", "i1"));

			assertSuccess(response);
			verify(f.utils).removeInsightUser("u1", "p1", "i1");
		}
	}

	@Test
	void removesManyUsersFromAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().removeInsightUserPermissions(f.request,
					form("projectId", "p1", "insightId", "i1", "ids", "[\"u1\",\"u2\"]"));

			assertSuccess(response);
			verify(f.utils).removeInsightUsers(List.of("u1", "u2"), "p1", "i1");
		}
	}

	@Test
	void setsAnInsightGlobalInTheSecurityAndInsightsDatabases() throws Exception {
		IRDBMSEngine insightDb = Mockito.mock(IRDBMSEngine.class);
		IProject project = Mockito.mock(IProject.class);
		when(project.getInsightDatabase()).thenReturn(insightDb);
		try (Fixture f = Fixture.asAdmin();
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class);
				MockedConstruction<InsightAdministrator> administrators = Mockito
						.mockConstruction(InsightAdministrator.class)) {
			utility.when(() -> Utility.getProject("p1")).thenReturn(project);

			Response response = new AdminInsightAuthorizationResource().setInsightGlobal(f.request,
					form("projectId", "p1", "insightId", "i1", "isPublic", "true"));

			assertSuccess(response);
			verify(f.utils).setInsightGlobalWithinProject("p1", "i1", true);
			verify(administrators.constructed().get(0)).updateInsightGlobal("i1", true);
		}
	}

	@Test
	void listsThePeopleWithoutAccessToAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getInsightUsersNoCredentials("p1", "i1", "smith", 10, 0))
					.thenReturn(List.of(Map.of("id", "u9")));

			Response response = new AdminInsightAuthorizationResource().getInsightUsersNoCredentials(f.request, "p1",
					"i1", "smith", 10, 0);

			assertEquals("u9", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void addsManyUsersToAnInsight() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().addInsightUserPermissions(f.request,
					form("projectId", "p1", "insightId", "i1", "userpermissions",
							"[{\"userid\":\"u1\",\"permission\":\"READ_ONLY\"}]"));

			assertSuccess(response);
			verify(f.utils).addInsightUserPermissions("p1", "i1",
					List.of(Map.of("userid", "u1", "permission", "READ_ONLY")), f.user, null);
		}
	}

	@Test
	void approvesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().approveInsightUserAccessRequest(f.request,
					form("projectId", "p1", "insightId", "i1", "requests",
							"[{\"requestid\":\"r1\",\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			verify(f.utils).approveInsightUserAccessRequests("admin1", "NATIVE", "p1", "i1",
					List.of(Map.of("requestid", "r1", "userid", "u1", "permission", "EDIT")), null);
		}
	}

	@Test
	void deniesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminInsightAuthorizationResource().denyInsightUserAccessRequest(f.request,
					form("projectId", "p1", "insightId", "i1", "requestids", "[\"r1\"]"));

			assertSuccess(response);
			verify(f.utils).denyInsightUserAccessRequests("admin1", "NATIVE", "p1", "i1", List.of("r1"));
		}
	}
}
