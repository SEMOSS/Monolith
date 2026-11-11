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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import prerna.auth.utils.reactors.admin.AdminMyProjectsReactor;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;

class AdminProjectAuthorizationResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminProjectAuthorizationResource resource, HttpServletRequest request) throws Exception;
	}

	/**
	 * The static Semoss calls the resource makes, mocked for one test, with the
	 * signed in user an admin or not.
	 */
	private static final class Fixture implements AutoCloseable {
		final User user;
		final HttpServletRequest request;
		final SecurityAdminUtils utils = Mockito.mock(SecurityAdminUtils.class);
		final MockedStatic<SecurityAdminUtils> admin = Mockito.mockStatic(SecurityAdminUtils.class);
		final MockedStatic<MicrosoftGraphUserLookup> directory = Mockito.mockStatic(MicrosoftGraphUserLookup.class);
		final MockedStatic<MsGraphUtility> graph = Mockito.mockStatic(MsGraphUtility.class);

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
			graph.close();
			directory.close();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/project");
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

	private static MockedConstruction<AdminMyProjectsReactor> reactorsReturning(Object output) {
		return Mockito.mockConstruction(AdminMyProjectsReactor.class,
				Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS),
				(reactor, context) -> Mockito
						.doReturn(new NounMetadata(output, PixelDataType.CUSTOM_DATA_STRUCTURE)).when(reactor)
						.execute());
	}

	private static List<Object> noun(NounStore nouns, String key) {
		return nouns.getNoun(key).getAllValues();
	}

	private static Named<Endpoint> endpoint(String name, Endpoint call) {
		return Named.of(name, call);
	}

	@FunctionalInterface
	interface LevelWrite {
		Response call(AdminProjectAuthorizationResource resource, HttpServletRequest request,
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
				write("grantAllProjects", (r, q, l) -> r.grantAllProjects(q, form("userId", "u1", "permission", l))),
				write("grantNewUsersProjectAccess", (r, q, l) -> r.grantNewUsersProjectAccess(q,
						form("projectId", "p1", "permission", l))),
				write("addProjectUserPermission", (r, q, l) -> r.addProjectUserPermission(q,
						form("id", "u1", "projectId", "p1", "permission", l))),
				write("addAllUsers", (r, q, l) -> r.addAllUsers(q, form("projectId", "p1", "permission", l))),
				write("editProjectUserPermission", (r, q, l) -> r.editProjectUserPermission(q,
						form("id", "u1", "projectId", "p1", "permission", l))),
				write("editProjectUserPermissions", (r, q, l) -> r.editProjectUserPermissions(q,
						form("projectId", "p1", "userpermissions", rows(l)))),
				write("updateProjectUserPermissions", (r, q, l) -> r.updateProjectUserPermissions(q,
						form("projectId", "p1", "permission", l))),
				write("approveProjectUserAccessRequest", (r, q, l) -> r.approveProjectUserAccessRequest(q,
						form("projectId", "p1", "requests", rows(l)))),
				write("addProjectUserPermissions", (r, q, l) -> r.addProjectUserPermissions(q,
						form("projectId", "p1", "userpermissions", rows(l)))));
		return writes.stream().flatMap(write -> Stream.of(null, "", "OWNR", "7", "admin")
				.map(level -> Arguments.of(write, level)));
	}

	@ParameterizedTest
	@MethodSource("unknownLevels")
	void refusesAMissingOrUnknownLevelWithoutSavingAnything(LevelWrite write, String level) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = write.call(new AdminProjectAuthorizationResource(), f.request, level);

			assertEquals(400, response.getStatus());
			assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
					errorMessage(response));
			Mockito.verifyNoInteractions(f.utils);
			f.directory.verifyNoInteractions();
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().updateProjectUserPermissions(f.request,
					form("projectId", "p1", "permission", " read_only "));

			assertEquals(200, response.getStatus());
			verify(f.utils).updateProjectUserPermissions("p1", "READ_ONLY", f.user, null);
		}
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(
				endpoint("getProjects GET",
						(r, q) -> r.getProjectsGET(q, null, null, null, null, null, null, null, null, null, null,
								null)),
				endpoint("getProjects POST", (r, q) -> r.getProjectsPOST(q)),
				endpoint("getAllUserProjects", (r, q) -> r.getAllUserProjects(q, "u1", null, null, 0, 0)),
				endpoint("getUserProjectsNoCredentials",
						(r, q) -> r.getUserProjectsNoCredentials(q, "u1", null, null, 0, 0)),
				endpoint("grantAllProjects", (r, q) -> r.grantAllProjects(q, form("userId", "u1"))),
				endpoint("grantNewUsersProjectAccess",
						(r, q) -> r.grantNewUsersProjectAccess(q, form("projectId", "p1"))),
				endpoint("getProjectUsers", (r, q) -> r.getProjectUsers(q, "p1", null, null, null, 0, 0)),
				endpoint("addProjectUserPermission", (r, q) -> r.addProjectUserPermission(q, form("id", "u1"))),
				endpoint("addAllUsers", (r, q) -> r.addAllUsers(q, form("projectId", "p1"))),
				endpoint("editProjectUserPermission", (r, q) -> r.editProjectUserPermission(q, form("id", "u1"))),
				endpoint("editProjectUserPermissions",
						(r, q) -> r.editProjectUserPermissions(q, form("projectId", "p1"))),
				endpoint("updateProjectUserPermissions",
						(r, q) -> r.updateProjectUserPermissions(q, form("projectId", "p1"))),
				endpoint("removeProjectUserPermission",
						(r, q) -> r.removeProjectUserPermission(q, form("id", "u1"))),
				endpoint("setProjectTemplate",
						(r, q) -> r.setProjectTemplate(q, form("projectId", "p1", "template", "true"))),
				endpoint("setProjectGlobal", (r, q) -> r.setProjectGlobal(q, form("projectId", "p1"))),
				endpoint("setProjectDiscoverable", (r, q) -> r.setProjectDiscoverable(q, form("projectId", "p1"))),
				endpoint("getProjectUsersNoCredentials",
						(r, q) -> r.getProjectUsersNoCredentials(q, "p1", null, 0, 0, null)),
				endpoint("approveProjectUserAccessRequest",
						(r, q) -> r.approveProjectUserAccessRequest(q, form("projectId", "p1"))),
				endpoint("denyProjectUserAccessRequest",
						(r, q) -> r.denyProjectUserAccessRequest(q, form("projectId", "p1"))),
				endpoint("addProjectUserPermissions",
						(r, q) -> r.addProjectUserPermissions(q, form("projectId", "p1"))),
				endpoint("removeProjectUserPermissions",
						(r, q) -> r.removeProjectUserPermissions(q, form("projectId", "p1"))));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith403BeforeDoingAnything(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false);
				MockedConstruction<AdminMyProjectsReactor> reactors = reactorsReturning(List.of())) {
			Response response = endpoint.call(new AdminProjectAuthorizationResource(), f.request);

			assertEquals(403, response.getStatus());
			assertEquals(ADMINS_ONLY, errorMessage(response));
			assertTrue(reactors.constructed().isEmpty());
			f.directory.verifyNoInteractions();
			f.graph.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false);
				MockedConstruction<AdminMyProjectsReactor> reactors = reactorsReturning(List.of())) {
			Response response = endpoint.call(new AdminProjectAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verifyNoInteractions();
			assertTrue(reactors.constructed().isEmpty());
		}
	}

	@Test
	void passesTheQueryFiltersOfAGetToTheAdminProjectsReactor() throws Exception {
		try (Fixture f = Fixture.asAdmin();
				MockedConstruction<AdminMyProjectsReactor> reactors = reactorsReturning(
						List.of(Map.of("project_id", "p1")))) {
			Response response = new AdminProjectAuthorizationResource().getProjectsGET(f.request, List.of("p1"),
					"o'neil", 10, 20, List.of("tag"), true, false, " jdoe ", List.of(2),
					"[{\"id\":\"c1\",\"type\":\"NATIVE\"}]", false);

			assertEquals(200, response.getStatus());
			assertEquals("p1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("project_id")
					.getAsString());
			AdminMyProjectsReactor reactor = reactors.constructed().get(0);
			verify(reactor).setInsight(argThat(insight -> insight.getUser() == f.user));
			NounStore nouns = reactor.getNounStore();
			assertEquals(List.of("o''neil"), noun(nouns, ReactorKeysEnum.FILTER_WORD.getKey()));
			assertEquals(List.of(10), noun(nouns, ReactorKeysEnum.LIMIT.getKey()));
			assertEquals(List.of(20), noun(nouns, ReactorKeysEnum.OFFSET.getKey()));
			assertEquals(List.of("p1"), noun(nouns, ReactorKeysEnum.PROJECT.getKey()));
			assertEquals(List.of("tag"), noun(nouns, ReactorKeysEnum.META_KEYS.getKey()));
			assertEquals(List.of(true), noun(nouns, ReactorKeysEnum.NO_META.getKey()));
			assertEquals(List.of(false), noun(nouns, ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey()));
			assertEquals(List.of("jdoe"), noun(nouns, "permissionUser"));
			assertEquals(List.of(2), noun(nouns, "effectivePermissions"));
			assertEquals(List.of(Map.of("id", "c1", "type", "NATIVE")), noun(nouns, "createdBy"));
			assertEquals(List.of(false), noun(nouns, "createdByMe"));
		}
	}

	@Test
	void passesTheFormFiltersOfAPostToTheAdminProjectsReactor() throws Exception {
		try (Fixture f = Fixture.asAdmin(); MockedConstruction<AdminMyProjectsReactor> reactors = reactorsReturning(
				List.of())) {
			when(f.request.getParameterMap()).thenReturn(Map.of("filterWord", new String[] { "sales" }, "offset",
					new String[] { "5" }, "projectId", new String[] { "p1", "p2" }, "metaFilters",
					new String[] { "{\"tag\":\"x\"}" }, "effectivePermissions", new String[] { "1" }, "createdByMe",
					new String[] { "true" }));

			Response response = new AdminProjectAuthorizationResource().getProjectsPOST(f.request);

			assertEquals(200, response.getStatus());
			NounStore nouns = reactors.constructed().get(0).getNounStore();
			assertEquals(List.of("sales"), noun(nouns, ReactorKeysEnum.FILTER_WORD.getKey()));
			assertEquals(List.of("5"), noun(nouns, ReactorKeysEnum.OFFSET.getKey()));
			assertEquals(List.of("p1", "p2"), noun(nouns, ReactorKeysEnum.PROJECT.getKey()));
			assertEquals(List.of(Map.of("tag", "x")), noun(nouns, ReactorKeysEnum.META_FILTERS.getKey()));
			assertEquals(List.of(1), noun(nouns, "effectivePermissions"));
			assertEquals(List.of(true), noun(nouns, "createdByMe"));
		}
	}

	@Test
	void answersAMalformedEffectivePermissionWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin(); MockedConstruction<AdminMyProjectsReactor> reactors = reactorsReturning(
				List.of())) {
			when(f.request.getParameterMap())
					.thenReturn(Map.of("effectivePermissions", new String[] { "owner" }));

			Response response = new AdminProjectAuthorizationResource().getProjectsPOST(f.request);

			assertEquals(400, response.getStatus());
			Mockito.verify(reactors.constructed().get(0), never()).execute();
		}
	}

	@Test
	void listsTheProjectsOfAUser() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllUserProjects("u1", List.of("APP"), "sales", 10, 5))
					.thenReturn(List.of(Map.of("project_id", "p1")));

			Response response = new AdminProjectAuthorizationResource().getAllUserProjects(f.request, "u1",
					List.of("APP"), "sales", 10, 5);

			assertEquals(200, response.getStatus());
			assertEquals("p1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("project_id")
					.getAsString());
		}
	}

	@Test
	void listsTheProjectsAUserCannotUse() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getUserProjectsNoCredentials("u1", null, null, 0, 0))
					.thenReturn(List.of(Map.of("project_id", "p2")));

			Response response = new AdminProjectAuthorizationResource().getUserProjectsNoCredentials(f.request, "u1",
					null, null, 0, 0);

			assertEquals("p2", body(response).getAsJsonArray().get(0).getAsJsonObject().get("project_id")
					.getAsString());
		}
	}

	@Test
	void grantsAUserEveryProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().grantAllProjects(f.request,
					form("userId", "u1", "permission", "EDIT", "isAddNew", "true"));

			assertSuccess(response);
			verify(f.utils).grantAllProjects("u1", "EDIT", true, f.user);
		}
	}

	@Test
	void grantsAProjectToEveryoneWithoutAccess() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().grantNewUsersProjectAccess(f.request,
					form("projectId", "p1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).grantNewUsersProjectAccess("p1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void pagesTheUsersOfAProjectWithTheirTotal() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getProjectUsers("p1", "u1", "EDIT", 10, 0)).thenReturn(List.of(Map.of("id", "u1")));
			when(f.utils.getProjectUsersCount("p1", "u1", "EDIT")).thenReturn(4L);

			Response response = new AdminProjectAuthorizationResource().getProjectUsers(f.request, "p1", "u1", null,
					"EDIT", 10, 0);

			JsonObject json = body(response).getAsJsonObject();
			assertEquals(4, json.get("totalMembers").getAsLong());
			assertEquals("u1", json.getAsJsonArray("members").get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void searchesTheUsersOfAProjectByTheSearchTermOverTheUserId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			new AdminProjectAuthorizationResource().getProjectUsers(f.request, "p1", "u1", "smith", null, 0, 0);

			verify(f.utils).getProjectUsers("p1", "smith", null, 0, 0);
			verify(f.utils).getProjectUsersCount("p1", "smith", null);
		}
	}

	@Test
	void addsAUserToAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().addProjectUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "permission", "EDIT", "endDate", "2030-01-01"));

			assertSuccess(response);
			verify(f.utils).addProjectUser("u1", "p1", "EDIT", f.user, null);
		}
	}

	@Test
	void answersAFailedAddWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			doThrow(new IllegalArgumentException("This user already has access to this project.")).when(f.utils)
					.addProjectUser(any(), any(), any(), any(), any());

			Response response = new AdminProjectAuthorizationResource().addProjectUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "permission", "EDIT"));

			assertEquals(400, response.getStatus());
			assertEquals("This user already has access to this project.", errorMessage(response));
		}
	}

	@Test
	void answersAnUnexpectedFailureWith500AndTheTechnicalMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			doThrow(new IllegalStateException("db down")).when(f.utils).removeProjectUser(any(), any());

			Response response = new AdminProjectAuthorizationResource().removeProjectUserPermission(f.request,
					form("id", "u1", "projectId", "p1"));

			assertEquals(500, response.getStatus());
			JsonObject json = body(response).getAsJsonObject();
			assertEquals("An unexpected error happened. Please reach out to an admin.",
					json.get(Constants.ERROR_MESSAGE).getAsString());
			assertEquals("db down", json.get(Constants.TECH_ERROR_MESSAGE).getAsString());
		}
	}

	@Test
	void addsEveryUserToAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().addAllUsers(f.request,
					form("projectId", "p1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).addAllProjectUsers("p1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void editsAUserOfAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().editProjectUserPermission(f.request,
					form("id", "u1", "projectId", "p1", "permission", "OWNER"));

			assertSuccess(response);
			verify(f.utils).editProjectUserPermission("u1", "p1", "OWNER", f.user, null);
		}
	}

	@Test
	void editsManyUsersOfAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().editProjectUserPermissions(f.request,
					form("projectId", "p1", "userpermissions", "[{\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			verify(f.utils).editProjectUserPermissions("p1", List.of(Map.of("userid", "u1", "permission", "EDIT")),
					f.user, null);
		}
	}

	@Test
	void changesEveryUserOfAProjectToOneLevel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().updateProjectUserPermissions(f.request,
					form("projectId", "p1", "permission", "EDIT"));

			assertSuccess(response);
			verify(f.utils).updateProjectUserPermissions("p1", "EDIT", f.user, null);
		}
	}

	@Test
	void removesAUserFromAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().removeProjectUserPermission(f.request,
					form("id", "u1", "projectId", "p1"));

			assertSuccess(response);
			verify(f.utils).removeProjectUser("u1", "p1");
		}
	}

	@Test
	void setsAProjectAsATemplate() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().setProjectTemplate(f.request,
					form("projectId", "p1", "template", "TRUE"));

			assertSuccess(response);
			verify(f.utils).setProjectTemplate("p1", true);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "yes", "", "1" })
	void rejectsATemplateValueThatIsNotTrueOrFalse(String template) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().setProjectTemplate(f.request,
					form("projectId", "p1", "template", template));

			assertEquals(400, response.getStatus());
			assertEquals("projectId and a true or false template value are required", errorMessage(response));
			verify(f.utils, never()).setProjectTemplate(any(), anyBoolean());
		}
	}

	@Test
	void rejectsATemplateChangeWithoutAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().setProjectTemplate(f.request,
					form("projectId", " ", "template", "false"));

			assertEquals(400, response.getStatus());
			verify(f.utils, never()).setProjectTemplate(any(), anyBoolean());
		}
	}

	@Test
	void setsAProjectGlobalAndDiscoverable() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminProjectAuthorizationResource resource = new AdminProjectAuthorizationResource();

			assertSuccess(resource.setProjectGlobal(f.request, form("projectId", "p1", "public", "true")));
			assertSuccess(resource.setProjectDiscoverable(f.request, form("projectId", "p1")));

			verify(f.utils).setProjectGlobal("p1", true);
			verify(f.utils).setProjectDiscoverable("p1", false);
		}
	}

	@Test
	void searchesTheSecurityDatabaseForPeopleWithoutAccessWhenTheDirectoryIsNotUsed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getProjectUsersNoCredentials("p1", "smith", 10, 0)).thenReturn(List.of(Map.of("id", "u9")));

			Response response = new AdminProjectAuthorizationResource().getProjectUsersNoCredentials(f.request, "p1",
					"smith", 10, 0, "false");

			assertEquals("u9", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			f.graph.verifyNoInteractions();
		}
	}

	@Test
	void searchesTheDirectoryForPeopleWithoutAccessWhenItIsUsed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory(null)).thenReturn(true);
			f.graph.when(() -> MsGraphUtility.getProjectUsers(f.request, f.user, "p1", "smith", 10, 0, true))
					.thenReturn(List.of(Map.of("id", "ms1")));

			Response response = new AdminProjectAuthorizationResource().getProjectUsersNoCredentials(f.request, "p1",
					"smith", 10, 0, null);

			assertEquals("ms1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			verify(f.utils, never()).getProjectUsersNoCredentials(any(), any(), anyLong(), anyLong());
		}
	}

	@Test
	void answersADirectorySearchWithoutAMicrosoftLoginWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory(null)).thenReturn(true);
			f.graph.when(() -> MsGraphUtility.getProjectUsers(any(), any(), any(), any(), anyLong(), anyLong(),
					anyBoolean())).thenThrow(new IllegalAccessException("Sign in to Microsoft first"));

			Response response = new AdminProjectAuthorizationResource().getProjectUsersNoCredentials(f.request, "p1",
					null, 0, 0, null);

			assertEquals(400, response.getStatus());
			assertEquals("Sign in to Microsoft first", errorMessage(response));
		}
	}

	@Test
	void approvesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().approveProjectUserAccessRequest(f.request,
					form("projectId", "p1", "requests",
							"[{\"requestid\":\"r1\",\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			verify(f.utils).approveProjectUserAccessRequests("admin1", "NATIVE", "p1",
					List.of(Map.of("requestid", "r1", "userid", "u1", "permission", "EDIT")), null);
		}
	}

	@Test
	void deniesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().denyProjectUserAccessRequest(f.request,
					form("projectId", "p1", "requestids", "[\"r1\"]"));

			assertSuccess(response);
			verify(f.utils).denyProjectUserAccessRequests("admin1", "NATIVE", "p1", List.of("r1"));
		}
	}

	@Test
	void addsMissingDirectoryUsersBeforeAddingThemToAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(MicrosoftGraphUserLookup::isEnabled).thenReturn(true);
			List<Map<String, Object>> rows = List.of(Map.of("userid", "u1", "type", "MICROSOFT", "permission", "EDIT"));

			Response response = new AdminProjectAuthorizationResource().addProjectUserPermissions(f.request,
					form("projectId", "p1", "userpermissions",
							"[{\"userid\":\"u1\",\"type\":\"MICROSOFT\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			f.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(f.user, rows));
			verify(f.utils).addProjectUserPermissions("p1", List.of(Map.of("userid", "u1", "type", "MICROSOFT",
					"permission", "EDIT")), f.user);
		}
	}

	@Test
	void addsUsersToAProjectWithoutTheDirectoryWhenItIsOff() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().addProjectUserPermissions(f.request,
					form("projectId", "p1", "userpermissions", "[{\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			f.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), any()), never());
		}
	}

	@Test
	void removesManyUsersFromAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminProjectAuthorizationResource().removeProjectUserPermissions(f.request,
					form("projectId", "p1", "ids", "[\"u1\",\"u2\"]"));

			assertSuccess(response);
			verify(f.utils).removeProjectUsers(List.of("u1", "u2"), "p1");
		}
	}
}
