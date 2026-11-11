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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class InsightAuthorizationResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
	private static final String INSUFFICIENT_PRIVILEGES = "Insufficient privileges to modify this insight's permissions.";
	private static final String PROJECT_INSUFFICIENT_PRIVILEGES = "Insufficient privileges to modify this project's permissions.";

	private final InsightAuthorizationResource resource = new InsightAuthorizationResource();

	@Test
	void searchesTheCallersInsightsInOneProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.searchUserInsights(user, List.of("p1"), "sales", false,
					null, null, "10", "20")).thenReturn(List.of(Map.of("app_insight_id", "i1")));

			Response response = resource.getInsights(requestFor(user), " p1 ", "sales", "10", "20");

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("app_insight_id", "i1")), body(response));
		}
	}

	@Test
	void searchesTheCallersInsightsInEveryProjectWithoutOne() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.getInsights(requestFor(user), "  ", null, null, null);

			statics.insight.verify(
					() -> SecurityInsightUtils.searchUserInsights(user, null, null, false, null, null, null, null));
		}
	}

	@Test
	void listsTheInsightsTheCallerCanEdit() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.getUserEditableInsights(user, "p1"))
					.thenReturn(List.of(Map.of("id", "i1")));

			Response response = resource.getProjectInsights(requestFor(user), "p1", null);

			assertEquals(List.of(Map.of("id", "i1")), body(response));
		}
	}

	@Test
	void readsTheCallersOwnPermissionOnAnInsightOrRefuses() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.getActualUserInsightPermission(user, "p1", "i1"))
					.thenReturn("EDIT");

			Response allowed = resource.getUserInsightPermission(requestFor(user), "p1", "i1");
			Response refused = resource.getUserInsightPermission(requestFor(user), "p1", "i2");

			assertEquals(Map.of("permission", "EDIT"), body(allowed));
			assertEquals(403, refused.getStatus());
			assertEquals("User does not have access to this insight", errorOf(refused));
		}
	}

	@Test
	void listsTheInsightsUsersWithTheirCount() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.getInsightUsers(user, "p1", "i1", "bob", "EDIT", 10, 0))
					.thenReturn(List.of(Map.of("id", "bob")));
			statics.insight.when(() -> SecurityInsightUtils.getInsightUsersCount(user, "p1", "i1", "bob", "EDIT"))
					.thenReturn(1L);

			Response response = resource.getInsightUsers(requestFor(user), "p1", "i1", "bob", "EDIT", 10, 0);

			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(1.0, payload.get("totalMembers"));
			assertEquals(List.of(Map.of("id", "bob")), payload.get("members"));
		}
	}

	@Test
	void refusesListingTheUsersOfAnInsightTheCallerCannotSee() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.getInsightUsers(user, "p1", "i1", null, null, 10, 0))
					.thenThrow(new IllegalAccessException("The user does not have access to view this insight"));

			Response response = resource.getInsightUsers(requestFor(user), "p1", "i1", null, null, 10, 0);

			assertEquals(403, response.getStatus());
			statics.insight.verify(
					() -> SecurityInsightUtils.getInsightUsersCount(any(), any(), any(), any(), any()), never());
		}
	}

	@Test
	void addsAPersonToTheInsightAndItsProjectInOneSave() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.addInsightUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "insightId", "i1", "permission", "EDIT"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
			statics.insight.verify( () -> SecurityInsightUtils.addInsightUserWithProjectAccess(user, "bob", "p1", "i1",
					"EDIT", null));
			statics.project.verify(() -> SecurityProjectUtils.addProjectUser(any(), any(), any(), any(), any()),
					never());
		}
	}

	@Test
	void addsSomeoneWithProjectAccessWhileProjectAddsAreAdminOnly() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectAddAccess("p1")).thenReturn(true);
			statics.project.when(() -> SecurityProjectUtils.getUserProjectPermission("bob", "p1"))
					.thenReturn(AccessPermissionEnum.EDIT.getId());

			Response response = resource.addInsightUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "insightId", "i1", "permission", "READ_ONLY"));

			assertEquals(200, response.getStatus());
			statics.insight.verify(() -> SecurityInsightUtils.addInsightUserWithProjectAccess(user, "bob", "p1", "i1",
					"READ_ONLY", null));
		}
	}

	@Test
	void refusesToGiveProjectAccessWithAnInsightWhenProjectAddsAreAdminOnly() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectAddAccess("p1")).thenReturn(true);
			statics.project.when(() -> SecurityProjectUtils.getUserProjectPermission("bob", "p1")).thenReturn(null);

			Response response = resource.addInsightUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "insightId", "i1", "permission", "EDIT"));

			assertEquals(403, response.getStatus());
			assertEquals("This functionality is limited to only admins", errorOf(response));
			statics.insight.verifyNoInteractions();
		}
	}

	@Test
	void letsAnAdminGiveProjectAccessWithAnInsightWhenProjectAddsAreAdminOnly() throws Exception {
		User user = signedIn("admin1");
		try (Statics statics = new Statics()) {
			statics.admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectAddAccess("p1")).thenReturn(true);
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightAddAccess).thenReturn(true);

			Response response = resource.addInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "EDIT"));

			assertEquals(200, response.getStatus());
			statics.insight.verify( () -> SecurityInsightUtils.addInsightUserWithProjectAccess(user, "bob", "p1", "i1",
					"EDIT", null));
		}
	}

	@Test
	void answersWith403WhenTheCallerCannotGiveTheProjectAccess() throws Exception {
		User user = signedIn("reader");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.addInsightUserWithProjectAccess(eq(user), eq("bob"),
					eq("p1"), eq("i1"), anyString(), any()))
					.thenThrow(new IllegalAccessException(PROJECT_INSUFFICIENT_PRIVILEGES));

			Response response = resource.addInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "EDIT"));

			assertEquals(403, response.getStatus());
			assertEquals(PROJECT_INSUFFICIENT_PRIVILEGES, errorOf(response));
		}
	}

	@Test
	void answersARefusedAddWith400AndItsMessage() throws Exception {
		User user = signedIn("reader");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.addInsightUserWithProjectAccess(eq(user), eq("bob"),
					eq("p1"), eq("i1"), anyString(), any()))
					.thenThrow(new IllegalArgumentException(INSUFFICIENT_PRIVILEGES));

			Response response = resource.addInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "EDIT"));

			assertEquals(400, response.getStatus());
			assertEquals(INSUFFICIENT_PRIVILEGES, errorOf(response));
		}
	}

	@Test
	void refusesTheSingleAddEditAndRemoveWhenInsightAddsAreAdminOnly() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightAddAccess).thenReturn(true);

			Response added = resource.addInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "EDIT"));
			Response edited = resource.editInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "OWNER"));
			Response removed = resource.removeInsightUserPermission(requestFor(user), form("id", "bob", "projectId",
					"p1", "insightId", "i1"));

			for (Response response : List.of(added, edited, removed)) {
				assertEquals(403, response.getStatus());
				assertEquals("This functionality is limited to only admins", errorOf(response));
			}
			statics.insight.verifyNoInteractions();
			statics.project.verify(() -> SecurityProjectUtils.addProjectUser(any(), any(), any(), any(), any()),
					never());
		}
	}

	@Test
	void letsAnAdminEditAndRemoveWhenInsightAddsAreAdminOnly() throws Exception {
		User user = signedIn("admin1");
		try (Statics statics = new Statics()) {
			statics.admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightAddAccess).thenReturn(true);

			Response edited = resource.editInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", "OWNER"));
			Response removed = resource.removeInsightUserPermission(requestFor(user), form("id", "bob", "projectId",
					"p1", "insightId", "i1"));

			assertEquals(200, edited.getStatus());
			assertEquals(200, removed.getStatus());
			statics.insight.verify( () -> SecurityInsightUtils.editInsightUserPermission(user, "bob", "p1", "i1",
					"OWNER", null));
			statics.insight.verify(() -> SecurityInsightUtils.removeInsightUser(user, "bob", "p1", "i1"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "OWNR", "7", "" })
	void refusesAnUnknownLevelWithoutSavingIt(String level) throws Exception {
		User user = signedIn("alice");
		String rows = "[{\"userid\":\"bob\",\"permission\":\"" + level + "\"}]";
		try (Statics statics = new Statics()) {
			List<Response> responses = List.of( resource.addInsightUserPermission(requestFor(user), form("id", "bob",
					"projectId", "p1", "insightId", "i1", "permission", level)),
					resource.editInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1",
					"insightId", "i1", "permission", level)), resource.addInsightUserPermissions(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "userpermissions", rows)),
					resource.editInsightUserPermissions(requestFor(user), form("projectId", "p1", "insightId", "i1",
					"userpermissions", rows)), resource.approveInsightUserAccessRequest(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "requests", rows)));

			for (Response response : responses) {
				assertEquals(400, response.getStatus());
				assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
						errorOf(response));
			}
			statics.insight.verifyNoInteractions();
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.editInsightUserPermission(requestFor(user), form("id", "bob", "projectId", "p1", "insightId", "i1",
					"permission", " edit "));
			resource.addInsightUserPermissions(requestFor(user), form("projectId", "p1", "insightId", "i1",
					"userpermissions", "[{\"userid\":\"bob\",\"permission\":\"read_only\"}]"));

			statics.insight.verify( () -> SecurityInsightUtils.editInsightUserPermission(user, "bob", "p1", "i1",
					"EDIT", null));
			statics.insight.verify(() -> SecurityInsightUtils.addInsightUserPermissions(eq(user), eq("p1"), eq("i1"),
					argThat(rows -> "READ_ONLY".equals(rows.get(0).get("permission"))), isNull()));
		}
	}

	@Test
	void editsAndRemovesOneUser() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response edited = resource.editInsightUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "insightId", "i1", "permission", "OWNER"));
			Response removed = resource.removeInsightUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "insightId", "i1"));

			assertEquals(200, edited.getStatus());
			assertEquals(200, removed.getStatus());
			statics.insight.verify(
					() -> SecurityInsightUtils.editInsightUserPermission(user, "bob", "p1", "i1", "OWNER", null));
			statics.insight.verify(() -> SecurityInsightUtils.removeInsightUser(user, "bob", "p1", "i1"));
		}
	}

	@Test
	void refusesAnEditorChangingAnOwnerWith403() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.insight.when(() -> SecurityInsightUtils.removeInsightUser(user, "olga", "p1", "i1"))
					.thenThrow(new IllegalAccessException("Not high enough"));

			Response response = resource.removeInsightUserPermission(requestFor(user),
					form("id", "olga", "projectId", "p1", "insightId", "i1"));

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void keepsBulkAccessChangesToAdminsWhenTheSettingSaysSo() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightAddAccess).thenReturn(true);

			List<Response> responses = List.of(
					resource.addInsightUserPermissions(requestFor(user),
							form("projectId", "p1", "insightId", "i1", "userpermissions", "[]")),
					resource.editInsightUserPermissions(requestFor(user),
							form("projectId", "p1", "insightId", "i1", "userpermissions", "[]")),
					resource.removeInsightUserPermissions(requestFor(user),
							form("projectId", "p1", "insightId", "i1", "ids", "[]")),
					resource.approveInsightUserAccessRequest(requestFor(user),
							form("projectId", "p1", "insightId", "i1", "requests", "[]")),
					resource.denyInsightUserAccessRequest(requestFor(user),
							form("projectId", "p1", "insightId", "i1", "requestIds", "[]")));

			for (Response response : responses) {
				assertEquals(403, response.getStatus());
				assertEquals(ADMINS_ONLY, errorOf(response));
			}
			statics.insight.verifyNoInteractions();
		}
	}

	@Test
	void letsAnAdminChangeAccessInBulkWhenOnlyAdminsCan() throws Exception {
		User user = signedIn("root");
		try (Statics statics = new Statics()) {
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightAddAccess).thenReturn(true);
			statics.admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);

			Response response = resource.removeInsightUserPermissions(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "ids", "[\"bob\"]"));

			assertEquals(200, response.getStatus());
			statics.insight.verify(() -> SecurityInsightUtils.removeInsightUsers(user, List.of("bob"), "p1", "i1"));
		}
	}

	@Test
	void addsAndEditsManyUsersWithTheirRows() throws Exception {
		User user = signedIn("alice");
		String rows = "[{\"userid\":\"bob\",\"permission\":\"EDIT\"}]";
		try (Statics statics = new Statics()) {
			Response added = resource.addInsightUserPermissions(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "userpermissions", rows));
			Response edited = resource.editInsightUserPermissions(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "userpermissions", rows));

			assertEquals(200, added.getStatus());
			assertEquals(200, edited.getStatus());
			List<Map<String, String>> expected = List.of(Map.of("userid", "bob", "permission", "EDIT"));
			statics.insight.verify(() -> SecurityInsightUtils.addInsightUserPermissions(user, "p1", "i1", expected, null));
			statics.insight.verify(() -> SecurityInsightUtils.editInsightUserPermissions(user, "p1", "i1", expected, null));
		}
	}

	@Test
	void makesAnInsightPublicUnlessOnlyAdminsCan() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response allowed = resource.setInsightGlobal(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "isPublic", "true"));
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightSetPublic).thenReturn(true);
			Response refused = resource.setInsightGlobal(requestFor(user),
					form("projectId", "p1", "insightId", "i2", "isPublic", "true"));

			assertEquals(200, allowed.getStatus());
			assertEquals(403, refused.getStatus());
			assertEquals("Only an admin can set an insight as public", errorOf(refused));
			statics.insight.verify(() -> SecurityInsightUtils.setInsightGlobalWithinProject(user, "p1", "i1", true));
			statics.insight.verify(() -> SecurityInsightUtils.setInsightGlobalWithinProject(any(), any(), eq("i2"),
					anyBoolean()), never());
		}
	}

	@Test
	void letsANonAdminMakeAnInsightPrivateWhenOnlyAdminsMakeOnesPublic() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(AbstractSecurityUtils::adminOnlyInsightSetPublic).thenReturn(true);

			Response response = resource.setInsightGlobal(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "isPublic", "false"));

			assertEquals(200, response.getStatus());
			statics.insight.verify(() -> SecurityInsightUtils.setInsightGlobalWithinProject(user, "p1", "i1", false));
		}
	}

	@Test
	void setsTheCallersOwnFavoriteAndVisibility() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response favorite = resource.setInsightFavorite(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "isFavorite", "true"), null);
			Response visibility = resource.setInsightVisibility(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "visibility", "false"));

			assertEquals(Map.of("success", true), body(favorite));
			assertEquals(true, body(visibility));
			statics.insight.verify(() -> SecurityInsightUtils.setInsightFavorite(user, "p1", "i1", true));
			statics.insight.verify(() -> SecurityInsightUtils.setInsightVisibility(user, "p1", "i1", false));
		}
	}

	@Test
	void listsThePeopleWithoutAccessToAnInsight() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.insight.when(
					() -> SecurityInsightUtils.getInsightUsersNoCredentials(user, "p1", "i1", "bo", 10, 0))
					.thenReturn(List.of(Map.of("id", "bob")));

			Response response = resource.getInsightUsersNoCredentials(requestFor(user), "p1", "i1", "bo", 10, 0);

			assertEquals(List.of(Map.of("id", "bob")), body(response));
		}
	}

	@Test
	void approvesAndDeniesAccessRequests() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.approveInsightUserAccessRequest(requestFor(user), form("projectId", "p1", "insightId", "i1",
					"requests", "[{\"requestid\":\"r1\",\"permission\":\"READ_ONLY\"}]"));
			resource.denyInsightUserAccessRequest(requestFor(user),
					form("projectId", "p1", "insightId", "i1", "requestIds", "[\"r2\"]"));

			statics.insight.verify(() -> SecurityInsightUtils.approveInsightUserAccessRequests(user, "p1", "i1",
					List.of(Map.of("requestid", "r1", "permission", "READ_ONLY")), null));
			statics.insight.verify(
					() -> SecurityInsightUtils.denyInsightUserAccessRequests(user, "p1", "i1", List.of("r2")));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.addInsightUserPermission(signedOut(),
					form("id", "bob", "projectId", "p1", "insightId", "i1", "permission", "OWNER"));

			assertEquals(401, response.getStatus());
			statics.project.verifyNoInteractions();
			statics.insight.verifyNoInteractions();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/insight");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/insight");
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
		private final MockedStatic<AbstractSecurityUtils> security = Mockito.mockStatic(AbstractSecurityUtils.class);
		private final MockedStatic<SecurityAdminUtils> admin = Mockito.mockStatic(SecurityAdminUtils.class);
		private final MockedStatic<SecurityProjectUtils> project = Mockito.mockStatic(SecurityProjectUtils.class);
		private final MockedStatic<SecurityInsightUtils> insight = Mockito.mockStatic(SecurityInsightUtils.class);

		@Override
		public void close() {
			insight.close();
			project.close();
			admin.close();
			security.close();
			audit.close();
		}
	}
}
