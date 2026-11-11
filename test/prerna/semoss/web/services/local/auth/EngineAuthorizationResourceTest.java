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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.Gson;

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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.reactor.security.MyEnginesReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class EngineAuthorizationResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";

	private final EngineAuthorizationResource resource = new EngineAuthorizationResource();

	@Test
	void listsTheCallersEnginesWithEveryFilterTheyGive() throws Exception {
		User user = signedIn("alice");
		NounStore store = new NounStore("all");
		try (Statics statics = new Statics();
				MockedConstruction<MyEnginesReactor> reactors = Mockito.mockConstruction(MyEnginesReactor.class,
						(reactor, context) -> {
							when(reactor.getNounStore()).thenReturn(store);
							when(reactor.execute()).thenReturn(
									new NounMetadata(List.of(Map.of("engine_id", "e1")), PixelDataType.VECTOR));
						})) {
			Response response = resource.getEngines(requestFor(user), List.of("e1"), List.of("MODEL", "DATABASE"),
					"gpt", 10, 0, false, List.of("tag"), true, false);

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("engine_id", "e1")), body(response));
			verify(reactors.constructed().get(0)).setInsight(argThat(insight -> insight.getUser() == user));
			assertEquals(List.of("gpt"), values(store, ReactorKeysEnum.FILTER_WORD));
			assertEquals(List.of("e1"), values(store, ReactorKeysEnum.ENGINE));
			assertEquals(List.of("MODEL", "DATABASE"), values(store, ReactorKeysEnum.ENGINE_TYPE));
			assertEquals(List.of(10), values(store, ReactorKeysEnum.LIMIT));
			assertEquals(List.of(0), values(store, ReactorKeysEnum.OFFSET));
			assertEquals(List.of(false), values(store, ReactorKeysEnum.ONLY_FAVORITES));
			assertEquals(List.of("tag"), values(store, ReactorKeysEnum.META_KEYS));
			assertEquals(List.of(true), values(store, ReactorKeysEnum.NO_META));
			assertEquals(List.of(false), values(store, ReactorKeysEnum.INCLUDE_USERTRACKING_KEY));
		}
	}

	@Test
	void listsTheCallersEnginesFromAPostedForm() throws Exception {
		User user = signedIn("alice");
		NounStore store = new NounStore("all");
		HttpServletRequest request = requestFor(user);
		Map<String, String[]> params = new LinkedHashMap<>();
		params.put("engineTypes", new String[] { "MODEL" });
		params.put("engineId", new String[] { "e1", "e2" });
		params.put("limit", new String[] { "5" });
		params.put("metaFilters", new String[] { "{\"domain\":\"hr\"}" });
		when(request.getParameterMap()).thenReturn(params);
		try (Statics statics = new Statics();
				MockedConstruction<MyEnginesReactor> reactors = Mockito.mockConstruction(MyEnginesReactor.class,
						(reactor, context) -> {
							when(reactor.getNounStore()).thenReturn(store);
							when(reactor.execute()).thenReturn(new NounMetadata(List.of(), PixelDataType.VECTOR));
						})) {
			Response response = resource.getEnginesPOST(request);

			assertEquals(200, response.getStatus());
			assertEquals(List.of("MODEL"), values(store, ReactorKeysEnum.ENGINE_TYPE));
			assertEquals(List.of("e1", "e2"), values(store, ReactorKeysEnum.ENGINE));
			assertEquals(List.of("5"), values(store, ReactorKeysEnum.LIMIT));
			assertEquals(List.of(Map.of("domain", "hr")), values(store, ReactorKeysEnum.META_FILTERS));
			assertNull(store.getNoun(ReactorKeysEnum.FILTER_WORD.getKey()));
		}
	}

	@Test
	void readsTheCallersOwnPermissionOnAnEngine() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.getActualUserEnginePermission(user, "e1"))
					.thenReturn("OWNER");

			Response response = resource.getUserEnginePermission(requestFor(user), "e1");

			assertEquals(Map.of("permission", "OWNER"), body(response));
		}
	}

	@Test
	void readsDiscoverableOrRefusesForAnEngineTheCallerHasNoAccessTo() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.engineIsDiscoverable("e1")).thenReturn(true);

			Response discoverable = resource.getUserEnginePermission(requestFor(user), "e1");
			Response hidden = resource.getUserEnginePermission(requestFor(user), "e2");

			assertEquals(Map.of("permission", "DISCOVERABLE"), body(discoverable));
			assertEquals(403, hidden.getStatus());
			assertEquals("User does not have access to this engine", errorOf(hidden));
		}
	}

	@Test
	void listsTheEnginesUsersWithTheirCount() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.getEngineUsers(user, "e1", "carol", "OWNER", 10, 0))
					.thenReturn(List.of(Map.of("id", "carol")));
			statics.engine.when(() -> SecurityEngineUtils.getEngineUsersCount(user, "e1", "carol", "OWNER"))
					.thenReturn(1L);

			Response response = resource.getEngineUsers(requestFor(user), "e1", "carol", null, "OWNER", 10, 0);

			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(1.0, payload.get("totalMembers"));
			assertEquals(List.of(Map.of("id", "carol")), payload.get("members"));
		}
	}

	@Test
	void refusesListingTheUsersOfAnEngineTheCallerCannotSee() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.getEngineUsers(user, "e1", null, null, 10, 0))
					.thenThrow(new IllegalAccessException("The user does not have access to view this engine"));

			Response response = resource.getEngineUsers(requestFor(user), "e1", null, null, null, 10, 0);

			assertEquals(403, response.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.getEngineUsersCount(any(), any(), any(), any()), never());
		}
	}

	@Test
	void addsAUserWithNoLimitWhenNoneIsSent() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.addEngineUserPermission(requestFor(user),
					form("id", "bob", "engineId", "e1", "permission", "EDIT", "endDate", "2027-01-01"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
			statics.engine.verify(() -> SecurityEngineUtils.addEngineUser(user, "bob", "e1", "EDIT", null, null));
		}
	}

	@Test
	void addsAUserWithTheLimitItIsSent() throws Exception {
		User user = signedIn("alice");
		ModelUsageLimit limit = new ModelUsageLimit();
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{}")).thenReturn(limit);

			resource.addEngineUserPermission(requestFor(user),
					form("id", "bob", "engineId", "e1", "permission", "READ_ONLY", "usageLimit", "{}"));

			statics.engine.verify(() -> SecurityEngineUtils.addEngineUser(user, "bob", "e1", "READ_ONLY", null, limit));
		}
	}

	@Test
	void refusesAnEditorGrantingOwnerWith403() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.addEngineUser(user, "bob", "e1", "OWNER", null, null))
					.thenThrow(new IllegalAccessException("Only owners can give Author access"));

			Response response = resource.addEngineUserPermission(requestFor(user),
					form("id", "bob", "engineId", "e1", "permission", "OWNER"));

			assertEquals(403, response.getStatus());
			assertEquals("Only owners can give Author access", errorOf(response));
		}
	}

	@Test
	void refusesAnUnreadableLimitWith400() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson(anyString())).thenCallRealMethod();

			Response response = resource.addEngineUserPermission(requestFor(user),
					form("id", "bob", "engineId", "e1", "permission", "EDIT", "usageLimit", "{\"rules\":5}"));

			assertEquals(400, response.getStatus());
			statics.engine.verifyNoInteractions();
		}
	}

	@Test
	void keepsEveryAccessChangeToAdminsWhenTheEngineSaysSo() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyEngineAddAccess("e1")).thenReturn(true);

			List<Response> responses = List.of(
					resource.addEngineUserPermission(requestFor(user),
							form("id", "bob", "engineId", "e1", "permission", "EDIT")),
					resource.addEngineUserPermissions(requestFor(user),
							form("engineId", "e1", "userpermissions", "[]")),
					resource.editEngineUserPermission(requestFor(user),
							form("id", "bob", "type", "NATIVE", "engineId", "e1", "permission", "EDIT")),
					resource.editEngineUserPermissions(requestFor(user),
							form("engineId", "e1", "userpermissions", "[]")),
					resource.removeEngineUserPermission(requestFor(user), form("id", "bob", "engineId", "e1")),
					resource.removeEngineUserPermissions(requestFor(user), form("engineId", "e1", "ids", "[]")),
					resource.approveEngineUserAccessRequest(requestFor(user),
							form("engineId", "e1", "requests", "[]")),
					resource.denyEngineUserAccessRequest(requestFor(user),
							form("engineId", "e1", "requestIds", "[]")));

			for (Response response : responses) {
				assertEquals(403, response.getStatus());
				assertEquals(ADMINS_ONLY, errorOf(response));
			}
			statics.engine.verifyNoInteractions();
		}
	}

	@Test
	void letsAnAdminChangeAccessWhenOnlyAdminsCan() throws Exception {
		User user = signedIn("root");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyEngineAddAccess("e1")).thenReturn(true);
			statics.admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);

			Response response = resource.removeEngineUserPermission(requestFor(user), form("id", "bob", "engineId", "e1"));

			assertEquals(200, response.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.removeEngineUser(user, "bob", "e1"));
		}
	}

	@Test
	void addsManyUsersAndTheirDirectoryAccounts() throws Exception {
		User user = signedIn("alice");
		List<Map<String, Object>> rows = List.of(Map.of("userid", "bob", "permission", "EDIT"));
		try (Statics statics = new Statics()) {
			statics.directory.when(MicrosoftGraphUserLookup::isEnabled).thenReturn(true);

			Response response = resource.addEngineUserPermissions(requestFor(user),
					form("engineId", "e1", "userpermissions", "[{\"userid\":\"bob\",\"permission\":\"EDIT\"}]"));

			assertEquals(200, response.getStatus());
			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(user, rows));
			statics.engine.verify(() -> SecurityEngineUtils.addEngineUserPermissions(user, "e1", rows));
		}
	}

	@Test
	void addsManyUsersWithoutTheDirectoryWhenItIsOff() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.addEngineUserPermissions(requestFor(user), form("engineId", "e1", "userpermissions", "[]"));

			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), anyList()), never());
			statics.engine.verify(() -> SecurityEngineUtils.addEngineUserPermissions(user, "e1", List.of()));
		}
	}

	@Test
	void editsAUserKeepingTheirLimitWhenNoneIsSent() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editEngineUserPermission(requestFor(user),
					form("id", "bob", "type", "LDAP", "engineId", "e1", "permission", "READ_ONLY"));

			assertEquals(200, response.getStatus());
			statics.engine.verify(
					() -> SecurityEngineUtils.editEngineUserPermission(user, "bob", "LDAP", "e1", "READ_ONLY", null));
			statics.limits.verifyNoInteractions();
		}
	}

	@Test
	void editsAUserRemovingTheirLimitForAnEmptyOne() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson(anyString())).thenCallRealMethod();

			resource.editEngineUserPermission(requestFor(user),
					form("id", "bob", "type", "NATIVE", "engineId", "e1", "permission", "EDIT", "usageLimit", ""));

			statics.engine.verify(() -> SecurityEngineUtils.editEngineUserPermission(user, "bob", "NATIVE", "e1",
					"EDIT", null, null));
		}
	}

	@Test
	void editsManyUsersKeepingARowsNullLimitSoItIsRemoved() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.editEngineUserPermissions(requestFor(user), form("engineId", "e1", "userpermissions",
					"[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"EDIT\",\"usageLimit\":null}]"));

			statics.engine.verify(() -> SecurityEngineUtils.editEngineUserPermissions(eq(user), eq("e1"),
					argThat(rows -> rows.size() == 1 && "bob".equals(rows.get(0).get("userid"))
							&& rows.get(0).containsKey("usageLimit") && rows.get(0).get("usageLimit") == null)));
		}
	}

	@Test
	void removesManyUsersById() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.removeEngineUserPermissions(requestFor(user),
					form("engineId", "e1", "ids", "[\"bob\",\"carol\"]"));

			assertEquals(200, response.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.removeEngineUsers(user, List.of("bob", "carol"), "e1"));
		}
	}

	@Test
	void makesAnEnginePublicUnlessOnlyAdminsCan() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response allowed = resource.setEngineGlobal(requestFor(user), form("engineId", "e1", "public", "true"));
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyEngineSetPublic("e2")).thenReturn(true);
			Response refused = resource.setEngineGlobal(requestFor(user), form("engineId", "e2", "public", "true"));

			assertEquals(200, allowed.getStatus());
			assertEquals(403, refused.getStatus());
			assertEquals(ADMINS_ONLY, errorOf(refused));
			statics.engine.verify(() -> SecurityEngineUtils.setEngineGlobal(user, "e1", true));
			statics.engine.verify(() -> SecurityEngineUtils.setEngineGlobal(any(), eq("e2"), anyBoolean()), never());
		}
	}

	@Test
	void makesAnEngineDiscoverableForItsOwner() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.setEngineDiscoverable(requestFor(user),
					form("engineId", "e1", "discoverable", "true"));

			assertEquals(200, response.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.setEngineDiscoverable(user, "e1", true));
		}
	}

	@Test
	void keepsDiscoverabilityToAdminsWhenOnlyTheDiscoverableSettingSaysSo() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyEngineSetDiscoverable("e1")).thenReturn(true);

			Response response = resource.setEngineDiscoverable(requestFor(user), form("engineId", "e1", "discoverable",
					"true"));

			assertEquals(403, response.getStatus());
			assertEquals(ADMINS_ONLY, errorOf(response));
			statics.engine.verify(() -> SecurityEngineUtils.setEngineDiscoverable(any(), any(), anyBoolean()), never());
		}
	}

	@Test
	void letsAnOwnerMakeAnEngineDiscoverableWhenOnlyPublicIsAdminOnly() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyEngineSetPublic("e1")).thenReturn(true);

			Response response = resource.setEngineDiscoverable(requestFor(user), form("engineId", "e1", "discoverable",
					"true"));

			assertEquals(200, response.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.setEngineDiscoverable(user, "e1", true));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "OWNR", "7", "" })
	void refusesAnUnknownLevelWithoutSavingIt(String level) throws Exception {
		User user = signedIn("alice");
		String rows = "[{\"userid\":\"bob\",\"permission\":\"EDIT\"},{\"userid\":\"carol\",\"permission\":\"" + level
				+ "\"}]";
		try (Statics statics = new Statics()) {
			List<Response> responses = List.of( resource.addEngineUserPermission(requestFor(user), form("engineId",
					"e1", "id", "bob", "permission", level)), resource.editEngineUserPermission(requestFor(user),
					form("engineId", "e1", "id", "bob", "type", "NATIVE", "permission", level)),
					resource.addEngineUserPermissions(requestFor(user), form("engineId", "e1", "userpermissions",
					rows)), resource.editEngineUserPermissions(requestFor(user), form("engineId", "e1",
					"userpermissions", rows)), resource.approveEngineUserAccessRequest(requestFor(user),
					form("engineId", "e1", "requests", rows)));

			for (Response response : responses) {
				assertEquals(400, response.getStatus());
				assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
						errorOf(response));
			}
			statics.engine.verifyNoInteractions();
			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), any()), never());
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.editEngineUserPermission(requestFor(user), form("engineId", "e1", "id", "bob", "type", "NATIVE",
					"permission", " read_only "));

			statics.engine.verify(() -> SecurityEngineUtils.editEngineUserPermission(user, "bob", "NATIVE", "e1",
					"READ_ONLY", null));
		}
	}

	@Test
	void refusesANonOwnerMakingAnEngineDiscoverable() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.engine.when(() -> SecurityEngineUtils.setEngineDiscoverable(user, "e1", false))
					.thenThrow(new IllegalAccessException("Only the owner"));

			Response response = resource.setEngineDiscoverable(requestFor(user),
					form("engineId", "e1", "discoverable", "false"));

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void setsTheCallersOwnVisibilityAndFavorite() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response visibility = resource.setEngineVisibility(requestFor(user),
					form("engineId", "e1", "visibility", "true"));
			Response favorite = resource.setEngineFavorite(requestFor(user),
					form("engineId", "e1", "isFavorite", "false"));

			assertEquals(true, body(visibility));
			assertEquals(true, body(favorite));
			statics.engine.verify(() -> SecurityEngineUtils.setEngineVisibility(user, "e1", true));
			statics.engine.verify(() -> SecurityEngineUtils.setEngineFavorite(user, "e1", false));
		}
	}

	@Test
	void findsPeopleWithoutAccessInTheSecurityDatabaseOrTheDirectory() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = requestFor(user);
		try (Statics statics = new Statics()) {
			statics.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			statics.engine.when(() -> SecurityEngineUtils.getEngineUsersNoCredentials(user, "e1", "bo", 10, 0))
					.thenReturn(List.of(Map.of("id", "bob")));
			statics.graph.when(() -> MsGraphUtility.getEngineUsers(request, user, "e1", "bo", 10, 0, false))
					.thenReturn(List.of(Map.of("id", "dir-bob")));

			Response fromDb = resource.getEngineUsersNoCredentials(request, "e1", "bo", 10, 0, "false");
			Response fromDirectory = resource.getEngineUsersNoCredentials(request, "e1", "bo", 10, 0, "true");

			assertEquals(List.of(Map.of("id", "bob")), body(fromDb));
			assertEquals(List.of(Map.of("id", "dir-bob")), body(fromDirectory));
		}
	}

	@Test
	void approvesAndDeniesAccessRequests() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response approved = resource.approveEngineUserAccessRequest(requestFor(user),
					form("engineId", "e1", "requests", "[{\"requestid\":\"r1\",\"permission\":\"EDIT\"}]"));
			Response denied = resource.denyEngineUserAccessRequest(requestFor(user),
					form("engineId", "e1", "requestIds", "[\"r2\"]"));

			assertEquals(200, approved.getStatus());
			assertEquals(200, denied.getStatus());
			statics.engine.verify(() -> SecurityEngineUtils.approveEngineUserAccessRequests(user, "e1",
					List.of(Map.of("requestid", "r1", "permission", "EDIT")), null));
			statics.engine.verify(() -> SecurityEngineUtils.denyEngineUserAccessRequests(user, "e1", List.of("r2")));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.editEngineUserPermission(signedOut(),
					form("id", "bob", "type", "NATIVE", "engineId", "e1", "permission", "OWNER"));

			assertEquals(401, response.getStatus());
			statics.engine.verifyNoInteractions();
			statics.security.verifyNoInteractions();
		}
	}

	private static List<Object> values(NounStore store, ReactorKeysEnum key) {
		GenRowStruct struct = store.getNoun(key.getKey());
		return struct == null ? null : struct.getAllValues();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/engine");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/engine");
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
		private final MockedStatic<SecurityEngineUtils> engine = Mockito.mockStatic(SecurityEngineUtils.class);
		private final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);
		private final MockedStatic<MicrosoftGraphUserLookup> directory = Mockito
				.mockStatic(MicrosoftGraphUserLookup.class);
		private final MockedStatic<MsGraphUtility> graph = Mockito.mockStatic(MsGraphUtility.class);

		@Override
		public void close() {
			graph.close();
			directory.close();
			limits.close();
			engine.close();
			admin.close();
			security.close();
			audit.close();
		}
	}
}
