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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.auth.utils.reactors.admin.AdminMyEnginesReactor;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;

class AdminEngineAuthorizationResourceTest {

	private static final String USAGE_LIMIT_JSON = "{\"unlimited\":true}";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminEngineAuthorizationResource resource, HttpServletRequest request) throws Exception;
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
		final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);
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
			limits.close();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/engine");
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

	private static MockedConstruction<AdminMyEnginesReactor> reactorsReturning(Object output) {
		return Mockito.mockConstruction(AdminMyEnginesReactor.class,
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
		Response call(AdminEngineAuthorizationResource resource, HttpServletRequest request,
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
				write("grantAllEngines", (r, q, l) -> r.grantAllEngines(q, form("userId", "u1", "permission", l))),
				write("grantNewUsersEngineAccess", (r, q, l) -> r.grantNewUsersEngineAccess(q,
						form("engineId", "e1", "permission", l))),
				write("addEngineUserPermission", (r, q, l) -> r.addEngineUserPermission(q,
						form("id", "u1", "engineId", "e1", "permission", l))),
				write("addEngineUserPermissions", (r, q, l) -> r.addEngineUserPermissions(q,
						form("engineId", "e1", "userpermissions", rows(l)))),
				write("addAllUsers", (r, q, l) -> r.addAllUsers(q, form("engineId", "e1", "permission", l))),
				write("editEngineUserPermission", (r, q, l) -> r.editEngineUserPermission(q,
						form("id", "u1", "engineId", "e1", "permission", l))),
				write("editEngineUserPermissions", (r, q, l) -> r.editEngineUserPermissions(q,
						form("engineId", "e1", "userpermissions", rows(l)))),
				write("updateEngineUserPermissions", (r, q, l) -> r.updateEngineUserPermissions(q,
						form("engineId", "e1", "permission", l))),
				write("approveEngineUserAccessRequest", (r, q, l) -> r.approveEngineUserAccessRequest(q,
						form("engineId", "e1", "requests", rows(l)))));
		return writes.stream().flatMap(write -> Stream.of(null, "", "OWNR", "7", "admin")
				.map(level -> Arguments.of(write, level)));
	}

	@ParameterizedTest
	@MethodSource("unknownLevels")
	void refusesAMissingOrUnknownLevelWithoutSavingAnything(LevelWrite write, String level) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = write.call(new AdminEngineAuthorizationResource(), f.request, level);

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
			Response response = new AdminEngineAuthorizationResource().updateEngineUserPermissions(f.request,
					form("engineId", "e1", "permission", " edit "));

			assertSuccess(response);
			verify(f.utils).updateEngineUserPermissions("e1", "EDIT", f.user, null);
		}
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(
				endpoint("getEngines GET",
						(r, q) -> r.getEnginesGET(q, null, null, null, null, null, null, null, null, null, null, null,
								null)),
				endpoint("getEngines POST", (r, q) -> r.getEnginesPOST(q)),
				endpoint("getAllUserEngines", (r, q) -> r.getAllUserEngines(q, "u1", null, null, 0, 0)),
				endpoint("getUserEnginesNoCredentials",
						(r, q) -> r.getUserEnginesNoCredentials(q, "u1", null, null, 0, 0)),
				endpoint("grantAllEngines", (r, q) -> r.grantAllEngines(q, form("userId", "u1"))),
				endpoint("grantNewUsersEngineAccess", (r, q) -> r.grantNewUsersEngineAccess(q, form("engineId", "e1"))),
				endpoint("getEngineUsers", (r, q) -> r.getEngineUsers(q, "e1", null, null, null, 0, 0)),
				endpoint("addEngineUserPermission", (r, q) -> r.addEngineUserPermission(q, form("id", "u1"))),
				endpoint("addEngineUserPermissions", (r, q) -> r.addEngineUserPermissions(q, form("engineId", "e1"))),
				endpoint("addAllUsers", (r, q) -> r.addAllUsers(q, form("engineId", "e1"))),
				endpoint("editEngineUserPermission", (r, q) -> r.editEngineUserPermission(q, form("id", "u1"))),
				endpoint("editEngineUserPermissions",
						(r, q) -> r.editEngineUserPermissions(q, form("engineId", "e1"))),
				endpoint("updateEngineUserPermissions",
						(r, q) -> r.updateEngineUserPermissions(q, form("engineId", "e1"))),
				endpoint("removeEngineUserPermission", (r, q) -> r.removeEngineUserPermission(q, form("id", "u1"))),
				endpoint("removeEngineUserPermissions",
						(r, q) -> r.removeEngineUserPermissions(q, form("engineId", "e1"))),
				endpoint("setEngineGlobal", (r, q) -> r.setEngineGlobal(q, form("engineId", "e1"))),
				endpoint("setEngineDiscoverable", (r, q) -> r.setEngineDiscoverable(q, form("engineId", "e1"))),
				endpoint("getEngineUsersNoCredentials",
						(r, q) -> r.getEngineUsersNoCredentials(q, "e1", null, 0, 0, null)),
				endpoint("approveEngineUserAccessRequest",
						(r, q) -> r.approveEngineUserAccessRequest(q, form("engineId", "e1"))),
				endpoint("denyEngineUserAccessRequest",
						(r, q) -> r.denyEngineUserAccessRequest(q, form("engineId", "e1"))));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith401BeforeDoingAnything(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false);
				MockedConstruction<AdminMyEnginesReactor> reactors = reactorsReturning(List.of())) {
			Response response = endpoint.call(new AdminEngineAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User is not an admin", errorMessage(response));
			assertTrue(reactors.constructed().isEmpty());
			f.limits.verifyNoInteractions();
			f.directory.verifyNoInteractions();
			f.graph.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false);
				MockedConstruction<AdminMyEnginesReactor> reactors = reactorsReturning(List.of())) {
			Response response = endpoint.call(new AdminEngineAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verify(() -> SecurityAdminUtils.getInstance(any()), never());
			assertTrue(reactors.constructed().isEmpty());
		}
	}

	@Test
	void passesTheQueryFiltersOfAGetToTheAdminEnginesReactor() throws Exception {
		try (Fixture f = Fixture.asAdmin();
				MockedConstruction<AdminMyEnginesReactor> reactors = reactorsReturning(
						List.of(Map.of("database_id", "e1")))) {
			Response response = new AdminEngineAuthorizationResource().getEnginesGET(f.request, List.of("e1"),
					List.of("MODEL"), "chat", 10, 20, List.of("tag"), true, false, " jdoe ", List.of(1, 2),
					"[{\"id\":\"c1\",\"type\":\"NATIVE\"}]", true);

			assertEquals(200, response.getStatus());
			assertEquals("e1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("database_id")
					.getAsString());
			AdminMyEnginesReactor reactor = reactors.constructed().get(0);
			verify(reactor).setInsight(argThat(insight -> insight.getUser() == f.user));
			NounStore nouns = reactor.getNounStore();
			assertEquals(List.of("chat"), noun(nouns, ReactorKeysEnum.FILTER_WORD.getKey()));
			assertEquals(List.of(10), noun(nouns, ReactorKeysEnum.LIMIT.getKey()));
			assertEquals(List.of(20), noun(nouns, ReactorKeysEnum.OFFSET.getKey()));
			assertEquals(List.of("e1"), noun(nouns, ReactorKeysEnum.ENGINE.getKey()));
			assertEquals(List.of("MODEL"), noun(nouns, ReactorKeysEnum.ENGINE_TYPE.getKey()));
			assertEquals(List.of("tag"), noun(nouns, ReactorKeysEnum.META_KEYS.getKey()));
			assertEquals(List.of(true), noun(nouns, ReactorKeysEnum.NO_META.getKey()));
			assertEquals(List.of(false), noun(nouns, ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey()));
			assertEquals(List.of("jdoe"), noun(nouns, "permissionUser"));
			assertEquals(List.of(1, 2), noun(nouns, "effectivePermissions"));
			assertEquals(List.of(Map.of("id", "c1", "type", "NATIVE")), noun(nouns, "createdBy"));
			assertEquals(List.of(true), noun(nouns, "createdByMe"));
		}
	}

	@Test
	void leavesOutTheFiltersAGetDoesNotSend() throws Exception {
		try (Fixture f = Fixture.asAdmin(); MockedConstruction<AdminMyEnginesReactor> reactors = reactorsReturning(
				List.of())) {
			new AdminEngineAuthorizationResource().getEnginesGET(f.request, List.of(), List.of(), null, null, null,
					null, null, null, "  ", List.of(), " ", null);

			NounStore nouns = reactors.constructed().get(0).getNounStore();
			for (String key : List.of(ReactorKeysEnum.FILTER_WORD.getKey(), ReactorKeysEnum.LIMIT.getKey(),
					ReactorKeysEnum.ENGINE.getKey(), ReactorKeysEnum.ENGINE_TYPE.getKey(), "permissionUser",
					"effectivePermissions", "createdBy", "createdByMe")) {
				assertNull(nouns.getNoun(key), key);
			}
		}
	}

	@Test
	void passesTheFormFiltersOfAPostToTheAdminEnginesReactor() throws Exception {
		try (Fixture f = Fixture.asAdmin(); MockedConstruction<AdminMyEnginesReactor> reactors = reactorsReturning(
				List.of())) {
			when(f.request.getParameterMap()).thenReturn(Map.of("filterWord", new String[] { "chat" }, "limit",
					new String[] { "10" }, "engineId", new String[] { "e1", "e2" }, "engineTypes",
					new String[] { "MODEL" }, "metaFilters", new String[] { "{\"tag\":\"x\"}" }, "permissionUser",
					new String[] { " jdoe " }, "effectivePermissions", new String[] { "1", " 3" }, "createdBy",
					new String[] { "[{\"id\":\"c1\",\"type\":\"NATIVE\"}]" }, "createdByMe",
					new String[] { " TRUE " }));

			Response response = new AdminEngineAuthorizationResource().getEnginesPOST(f.request);

			assertEquals(200, response.getStatus());
			NounStore nouns = reactors.constructed().get(0).getNounStore();
			assertEquals(List.of("chat"), noun(nouns, ReactorKeysEnum.FILTER_WORD.getKey()));
			assertEquals(List.of("10"), noun(nouns, ReactorKeysEnum.LIMIT.getKey()));
			assertEquals(List.of("e1", "e2"), noun(nouns, ReactorKeysEnum.ENGINE.getKey()));
			assertEquals(List.of("MODEL"), noun(nouns, ReactorKeysEnum.ENGINE_TYPE.getKey()));
			assertEquals(List.of(Map.of("tag", "x")), noun(nouns, ReactorKeysEnum.META_FILTERS.getKey()));
			assertEquals(List.of("jdoe"), noun(nouns, "permissionUser"));
			assertEquals(List.of(1, 3), noun(nouns, "effectivePermissions"));
			assertEquals(List.of(Map.of("id", "c1", "type", "NATIVE")), noun(nouns, "createdBy"));
			assertEquals(List.of(true), noun(nouns, "createdByMe"));
		}
	}

	@Test
	void listsTheEnginesOfAUser() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllUserEngines("u1", List.of("MODEL"), "chat", 10, 5))
					.thenReturn(List.of(Map.of("engine_id", "e1")));

			Response response = new AdminEngineAuthorizationResource().getAllUserEngines(f.request, "u1",
					List.of("MODEL"), "chat", 10, 5);

			assertEquals(200, response.getStatus());
			assertEquals("e1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("engine_id")
					.getAsString());
		}
	}

	@Test
	void listsTheEnginesAUserCannotUse() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getUserEnginesNoCredentials("u1", List.of("MODEL"), "chat", 10, 5))
					.thenReturn(List.of(Map.of("engine_id", "e2")));

			Response response = new AdminEngineAuthorizationResource().getUserEnginesNoCredentials(f.request, "u1",
					List.of("MODEL"), "chat", 10, 5);

			assertEquals(200, response.getStatus());
			assertEquals("e2", body(response).getAsJsonArray().get(0).getAsJsonObject().get("engine_id")
					.getAsString());
		}
	}

	@Test
	void grantsAllEnginesOfTheGivenTypes() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().grantAllEngines(f.request, form("userId", "u1",
					"permission", "EDIT", "isAddNew", "true", "engineTypes", "[\"MODEL\",\"DATABASE\"]"));

			assertSuccess(response);
			verify(f.utils).grantAllEngines("u1", "EDIT", true, List.of("MODEL", "DATABASE"), f.user);
		}
	}

	@Test
	void grantsEnginesOfEveryTypeWhenNoTypesAreSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().grantAllEngines(f.request,
					form("userId", "u1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).grantAllEngines("u1", "READ_ONLY", false, null, f.user);
		}
	}

	@Test
	void answersAFailedGrantWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			doThrow(new IllegalArgumentException("An error occurred granting")).when(f.utils).grantAllEngines(any(),
					any(), Mockito.anyBoolean(), any(), any());

			Response response = new AdminEngineAuthorizationResource().grantAllEngines(f.request,
					form("userId", "u1", "permission", "EDIT"));

			assertEquals(400, response.getStatus());
			assertEquals("An error occurred granting", errorMessage(response));
		}
	}

	@Test
	void grantsAnEngineToEveryoneWithoutAccess() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().grantNewUsersEngineAccess(f.request,
					form("engineId", "e1", "permission", "READ_ONLY", "endDate", "2030-01-01"));

			assertSuccess(response);
			verify(f.utils).grantNewUsersEngineAccess("e1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void pagesTheUsersOfAnEngineWithTheirTotal() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getEngineUsers("e1", "u1", "OWNER", 10, 0)).thenReturn(List.of(Map.of("id", "u1")));
			when(f.utils.getEngineUsersCount("e1", "u1", "OWNER")).thenReturn(7L);

			Response response = new AdminEngineAuthorizationResource().getEngineUsers(f.request, "e1", "u1", null,
					"OWNER", 10, 0);

			assertEquals(200, response.getStatus());
			JsonObject json = body(response).getAsJsonObject();
			assertEquals(7, json.get("totalMembers").getAsLong());
			assertEquals("u1", json.getAsJsonArray("members").get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void searchesTheUsersOfAnEngineByTheSearchTermOverTheUserId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			new AdminEngineAuthorizationResource().getEngineUsers(f.request, "e1", "u1", "smith", null, 0, 0);

			verify(f.utils).getEngineUsers("e1", "smith", null, 0, 0);
			verify(f.utils).getEngineUsersCount("e1", "smith", null);
		}
	}

	@Test
	void addsAUserToAnEngineWithTheirUsageLimit() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson(USAGE_LIMIT_JSON)).thenReturn(limit);

			Response response = new AdminEngineAuthorizationResource().addEngineUserPermission(f.request,
					form("id", "u1", "engineId", "e1", "permission", "EDIT", "usageLimit", USAGE_LIMIT_JSON));

			assertSuccess(response);
			verify(f.utils).addEngineUser("u1", "e1", "EDIT", f.user, null, limit);
		}
	}

	@Test
	void answersAFailedAddWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			doThrow(new IllegalArgumentException("This user already has access to this engine.")).when(f.utils)
					.addEngineUser(any(), any(), any(), any(), any(), any());

			Response response = new AdminEngineAuthorizationResource().addEngineUserPermission(f.request,
					form("id", "u1", "engineId", "e1", "permission", "EDIT"));

			assertEquals(400, response.getStatus());
			assertEquals("This user already has access to this engine.", errorMessage(response));
		}
	}

	@Test
	void addsMissingDirectoryUsersBeforeAddingThemToAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(MicrosoftGraphUserLookup::isEnabled).thenReturn(true);
			List<Map<String, Object>> rows = List.of(Map.of("userid", "u1", "type", "MICROSOFT", "permission", "EDIT"));

			Response response = new AdminEngineAuthorizationResource().addEngineUserPermissions(f.request, form(
					"engineId", "e1", "userpermissions",
					"[{\"userid\":\"u1\",\"type\":\"MICROSOFT\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			f.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(f.user, rows));
			verify(f.utils).addEngineUserPermissions("e1", rows, f.user);
		}
	}

	@Test
	void addsUsersToAnEngineWithoutTheDirectoryWhenItIsOff() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().addEngineUserPermissions(f.request,
					form("engineId", "e1", "userpermissions", "[{\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			f.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), any()), never());
			verify(f.utils).addEngineUserPermissions("e1", List.of(Map.of("userid", "u1", "permission", "EDIT")),
					f.user);
		}
	}

	@Test
	void addsEveryUserToAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().addAllUsers(f.request,
					form("engineId", "e1", "permission", "READ_ONLY"));

			assertSuccess(response);
			verify(f.utils).addAllEngineUsers("e1", "READ_ONLY", f.user, null);
		}
	}

	@Test
	void editsAUserAndTheirUsageLimitWhenALimitIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson(USAGE_LIMIT_JSON)).thenReturn(limit);

			Response response = new AdminEngineAuthorizationResource().editEngineUserPermission(f.request,
					form("id", "u1", "engineId", "e1", "permission", "OWNER", "usageLimit", USAGE_LIMIT_JSON));

			assertSuccess(response);
			verify(f.utils).editEngineUserPermission("u1", "e1", "OWNER", f.user, null, limit);
			verify(f.utils, never()).editEngineUserPermission(any(), any(), any(), any(), any());
		}
	}

	@Test
	void editsAUserAndKeepsTheirUsageLimitWhenNoLimitIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().editEngineUserPermission(f.request,
					form("id", "u1", "engineId", "e1", "permission", "EDIT"));

			assertSuccess(response);
			verify(f.utils).editEngineUserPermission("u1", "e1", "EDIT", f.user, null);
			verify(f.utils, never()).editEngineUserPermission(any(), any(), any(), any(), any(), any());
			f.limits.verifyNoInteractions();
		}
	}

	@Test
	void editsManyUsersOfAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().editEngineUserPermissions(f.request,
					form("engineId", "e1", "userpermissions", "[{\"userid\":\"u1\",\"permission\":\"OWNER\"}]"));

			assertSuccess(response);
			verify(f.utils).editEngineUserPermissions("e1", List.of(Map.of("userid", "u1", "permission", "OWNER")),
					f.user);
		}
	}

	@Test
	void changesEveryUserOfAnEngineToOneLevel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().updateEngineUserPermissions(f.request,
					form("engineId", "e1", "permission", "EDIT"));

			assertSuccess(response);
			verify(f.utils).updateEngineUserPermissions("e1", "EDIT", f.user, null);
		}
	}

	@Test
	void removesAUserFromAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().removeEngineUserPermission(f.request,
					form("id", "u1", "engineId", "e1"));

			assertSuccess(response);
			verify(f.utils).removeEngineUser("u1", "e1");
		}
	}

	@Test
	void removesManyUsersFromAnEngine() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().removeEngineUserPermissions(f.request,
					form("engineId", "e1", "ids", "[\"u1\",\"u2\"]"));

			assertSuccess(response);
			verify(f.utils).removeEngineUsers(List.of("u1", "u2"), "e1");
		}
	}

	@Test
	void setsAnEngineGlobalAndDiscoverable() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminEngineAuthorizationResource resource = new AdminEngineAuthorizationResource();

			assertSuccess(resource.setEngineGlobal(f.request, form("engineId", "e1", "public", "true")));
			assertSuccess(resource.setEngineDiscoverable(f.request, form("engineId", "e1", "discoverable", "false")));

			verify(f.utils).setEngineGlobal("e1", true);
			verify(f.utils).setEngineDiscoverable("e1", false);
		}
	}

	@Test
	void answersAFailedGlobalChangeWith500AndAGenericMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.setEngineGlobal("e1", true)).thenThrow(new IllegalStateException("db down"));

			Response response = new AdminEngineAuthorizationResource().setEngineGlobal(f.request,
					form("engineId", "e1", "public", "true"));

			assertEquals(500, response.getStatus());
			assertEquals("An unexpected error happened. Please try again.", errorMessage(response));
		}
	}

	@Test
	void searchesTheSecurityDatabaseForPeopleWithoutAccessWhenTheDirectoryIsNotUsed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("false")).thenReturn(false);
			when(f.utils.getEngineUsersNoCredentials("e1", "smith", 10, 0)).thenReturn(List.of(Map.of("id", "u9")));

			Response response = new AdminEngineAuthorizationResource().getEngineUsersNoCredentials(f.request, "e1",
					"smith", 10, 0, "false");

			assertEquals(200, response.getStatus());
			assertEquals("u9", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			f.graph.verifyNoInteractions();
		}
	}

	@Test
	void searchesTheDirectoryForPeopleWithoutAccessWhenItIsUsed() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			f.graph.when(() -> MsGraphUtility.getEngineUsers(f.request, f.user, "e1", "smith", 10, 0, true))
					.thenReturn(List.of(Map.of("id", "ms1")));

			Response response = new AdminEngineAuthorizationResource().getEngineUsersNoCredentials(f.request, "e1",
					"smith", 10, 0, "true");

			assertEquals(200, response.getStatus());
			assertEquals("ms1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			verify(f.utils, never()).getEngineUsersNoCredentials(any(), any(), anyLong(), anyLong());
		}
	}

	@Test
	void answersADirectorySearchWithoutAMicrosoftLoginWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(() -> MicrosoftGraphUserLookup.useDirectory(null)).thenReturn(true);
			f.graph.when(() -> MsGraphUtility.getEngineUsers(any(), any(), any(), any(), anyLong(), anyLong(),
					Mockito.anyBoolean())).thenThrow(new IllegalAccessException("Sign in to Microsoft first"));

			Response response = new AdminEngineAuthorizationResource().getEngineUsersNoCredentials(f.request, "e1",
					null, 0, 0, null);

			assertEquals(400, response.getStatus());
			assertEquals("Sign in to Microsoft first", errorMessage(response));
		}
	}

	@Test
	void approvesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().approveEngineUserAccessRequest(f.request,
					form("engineId", "e1", "requests",
							"[{\"requestid\":\"r1\",\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertSuccess(response);
			verify(f.utils).approveEngineUserAccessRequests("admin1", "NATIVE", "e1",
					List.of(Map.of("requestid", "r1", "userid", "u1", "permission", "EDIT")), null);
		}
	}

	@Test
	void deniesAccessRequestsAsTheSignedInAdminWithTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminEngineAuthorizationResource().denyEngineUserAccessRequest(f.request,
					form("engineId", "e1", "requestIds", "[\"r1\",\"r2\"]"));

			assertSuccess(response);
			verify(f.utils).denyEngineUserAccessRequests("admin1", "NATIVE", "e1", List.of("r1", "r2"));
		}
	}

	@Test
	void answersAFailedDirectoryLookupWith400WithoutAddingAnyone() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.directory.when(MicrosoftGraphUserLookup::isEnabled).thenReturn(true);
			f.directory.when(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), any()))
					.thenThrow(new IllegalAccessException("Sign in to Microsoft first"));

			Response response = new AdminEngineAuthorizationResource().addEngineUserPermissions(f.request,
					form("engineId", "e1", "userpermissions", "[{\"userid\":\"u1\",\"permission\":\"EDIT\"}]"));

			assertEquals(400, response.getStatus());
			assertEquals("Sign in to Microsoft first", errorMessage(response));
			verify(f.utils, never()).addEngineUserPermissions(any(), any(), any());
		}
	}
}
