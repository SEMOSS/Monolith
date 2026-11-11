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
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.reactor.project.MyProjectsReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class ProjectAuthorizationResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";

	private final ProjectAuthorizationResource resource = new ProjectAuthorizationResource();

	@Test
	void listsTheCallersProjectsWithEveryFilterTheyGive() throws Exception {
		User user = signedIn("alice");
		NounStore store = new NounStore("all");
		try (Statics statics = new Statics();
				MockedConstruction<MyProjectsReactor> reactors = Mockito.mockConstruction(MyProjectsReactor.class,
						(reactor, context) -> {
							when(reactor.getNounStore()).thenReturn(store);
							when(reactor.execute()).thenReturn(
									new NounMetadata(List.of(Map.of("project_id", "p1")), PixelDataType.VECTOR));
						})) {
			Response response = resource.getProjectsGET(requestFor(user), List.of("p1", "p2"), "fin", 10, 20, true,
					List.of("tag"), false, true);

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("project_id", "p1")), body(response));
			MyProjectsReactor reactor = reactors.constructed().get(0);
			verify(reactor).setInsight(argThat(insight -> insight.getUser() == user));
			assertEquals(List.of("fin"), values(store, ReactorKeysEnum.FILTER_WORD));
			assertEquals(List.of(10), values(store, ReactorKeysEnum.LIMIT));
			assertEquals(List.of(20), values(store, ReactorKeysEnum.OFFSET));
			assertEquals(List.of(true), values(store, ReactorKeysEnum.ONLY_FAVORITES));
			assertEquals(List.of("p1", "p2"), values(store, ReactorKeysEnum.PROJECT));
			assertEquals(List.of("tag"), values(store, ReactorKeysEnum.META_KEYS));
			assertEquals(List.of(false), values(store, ReactorKeysEnum.NO_META));
			assertEquals(List.of(true), values(store, ReactorKeysEnum.INCLUDE_USERTRACKING_KEY));
		}
	}

	@Test
	void listsTheCallersProjectsWithoutFiltersTheyLeaveOut() throws Exception {
		User user = signedIn("alice");
		NounStore store = new NounStore("all");
		try (Statics statics = new Statics();
				MockedConstruction<MyProjectsReactor> reactors = Mockito.mockConstruction(MyProjectsReactor.class,
						(reactor, context) -> {
							when(reactor.getNounStore()).thenReturn(store);
							when(reactor.execute()).thenReturn(new NounMetadata(List.of(), PixelDataType.VECTOR));
						})) {
			Response response = resource.getProjectsGET(requestFor(user), List.of(), null, null, null, null, null,
					null, null);

			assertEquals(200, response.getStatus());
			assertNull(store.getNoun(ReactorKeysEnum.FILTER_WORD.getKey()));
			assertNull(store.getNoun(ReactorKeysEnum.PROJECT.getKey()));
			assertNull(store.getNoun(ReactorKeysEnum.LIMIT.getKey()));
		}
	}

	@Test
	void listsTheCallersProjectsFromAPostedForm() throws Exception {
		User user = signedIn("alice");
		NounStore store = new NounStore("all");
		HttpServletRequest request = requestFor(user);
		Map<String, String[]> params = new LinkedHashMap<>();
		params.put("filterWord", new String[] { "fin" });
		params.put("projectId", new String[] { "p1", "p2" });
		params.put("metaFilters", new String[] { "{\"tag\":[\"a\"]}" });
		params.put("noMeta", new String[] { "true" });
		when(request.getParameterMap()).thenReturn(params);
		try (Statics statics = new Statics();
				MockedConstruction<MyProjectsReactor> reactors = Mockito.mockConstruction(MyProjectsReactor.class,
						(reactor, context) -> {
							when(reactor.getNounStore()).thenReturn(store);
							when(reactor.execute()).thenReturn(new NounMetadata(List.of(), PixelDataType.VECTOR));
						})) {
			Response response = resource.getProjectsPOST(request);

			assertEquals(200, response.getStatus());
			assertEquals(List.of("fin"), values(store, ReactorKeysEnum.FILTER_WORD));
			assertEquals(List.of("p1", "p2"), values(store, ReactorKeysEnum.PROJECT));
			assertEquals(List.of(Map.of("tag", List.of("a"))), values(store, ReactorKeysEnum.META_FILTERS));
			assertEquals(List.of("true"), values(store, ReactorKeysEnum.NO_META));
		}
	}

	@Test
	void readsTheCallersOwnPermissionOnAProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.getActualUserProjectPermission(user, "p1"))
					.thenReturn("EDIT");

			Response response = resource.getUserProjectPermission(requestFor(user), "p1", null);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("permission", "EDIT"), body(response));
		}
	}

	@Test
	void readsDiscoverableForAProjectTheCallerCanOnlyFind() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.projectIsDiscoverable("p1")).thenReturn(true);

			Response response = resource.getUserProjectPermission(requestFor(user), "p1", null);

			assertEquals(Map.of("permission", "DISCOVERABLE"), body(response));
		}
	}

	@Test
	void refusesReadingAPermissionOnAHiddenProjectWith403() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.getUserProjectPermission(requestFor(user), "p1", null);

			assertEquals(403, response.getStatus());
			assertEquals("User does not have access to this project", errorOf(response));
		}
	}

	@Test
	void listsTheProjectsUsersSearchingByTheTermOverTheUserId() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.getProjectUsers(user, "p1", "bo", "EDIT", 10, 5))
					.thenReturn(List.of(Map.of("id", "bob")));
			statics.project.when(() -> SecurityProjectUtils.getProjectUsersCount(user, "p1", "bo", "EDIT"))
					.thenReturn(1L);

			Response response = resource.getProjectUsers(requestFor(user), "p1", "carol", "bo", "EDIT", 10, 5);

			assertEquals(200, response.getStatus());
			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(1.0, payload.get("totalMembers"));
			assertEquals(List.of(Map.of("id", "bob")), payload.get("members"));
		}
	}

	@Test
	void searchesTheProjectsUsersByTheUserIdWithoutATerm() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.getProjectUsers(requestFor(user), "p1", "carol", null, null, 10, 0);

			statics.project.verify(() -> SecurityProjectUtils.getProjectUsers(user, "p1", "carol", null, 10, 0));
		}
	}

	@Test
	void refusesListingTheUsersOfAProjectTheCallerCannotSee() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.getProjectUsers(user, "p1", null, null, 10, 0))
					.thenThrow(new IllegalAccessException("The user does not have access to view this project"));

			Response response = resource.getProjectUsers(requestFor(user), "p1", null, null, null, 10, 0);

			assertEquals(403, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.getProjectUsersCount(any(), any(), any(), any()), never());
		}
	}

	@Test
	void addsAUserToAProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.addProjectUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "permission", "EDIT", "endDate", "2027-01-01"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
			statics.project.verify(
					() -> SecurityProjectUtils.addProjectUser(user, "bob", "p1", "EDIT", "2027-01-01"));
		}
	}

	@Test
	void refusesAnEditorGrantingOwnerWith403() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.addProjectUser(user, "bob", "p1", "OWNER", null))
					.thenThrow(new IllegalAccessException(
							"Cannot give owner level access to this project since you are not currently an owner."));

			Response response = resource.addProjectUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1", "permission", "OWNER"));

			assertEquals(403, response.getStatus());
			assertEquals("Cannot give owner level access to this project since you are not currently an owner.",
					errorOf(response));
		}
	}

	@Test
	void keepsEveryAccessChangeToAdminsWhenTheProjectSaysSo() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectAddAccess("p1")).thenReturn(true);

			List<Response> responses = List.of(
					resource.addProjectUserPermission(requestFor(user),
							form("id", "bob", "projectId", "p1", "permission", "EDIT")),
					resource.addProjectUserPermissions(requestFor(user),
							form("projectId", "p1", "userpermissions", "[]")),
					resource.editProjectUserPermission(requestFor(user),
							form("id", "bob", "type", "NATIVE", "projectId", "p1", "permission", "EDIT")),
					resource.editProjectUserPermissions(requestFor(user),
							form("projectId", "p1", "userpermissions", "[]")),
					resource.removeProjectUserPermission(requestFor(user), form("id", "bob", "projectId", "p1")),
					resource.removeProjectUserPermissions(requestFor(user), form("projectId", "p1", "ids", "[]")),
					resource.propagateProjectDependencyPermission(requestFor(user),
							form("id", "bob", "type", "NATIVE", "projectId", "p1", "permission", "EDIT")),
					resource.propagateProjectDependencyPermissions(requestFor(user),
							form("projectId", "p1", "userpermissions", "[]")),
					resource.approveProjectUserAccessRequest(requestFor(user),
							form("projectId", "p1", "requests", "[]")),
					resource.denyProjectUserAccessRequest(requestFor(user),
							form("projectId", "p1", "requestids", "[]")));

			for (Response response : responses) {
				assertEquals(403, response.getStatus());
				assertEquals(ADMINS_ONLY, errorOf(response));
			}
			statics.project.verifyNoInteractions();
		}
	}

	@Test
	void letsAnAdminChangeAccessWhenOnlyAdminsCan() throws Exception {
		User user = signedIn("root");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectAddAccess("p1")).thenReturn(true);
			statics.admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);

			Response response = resource.removeProjectUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.removeProjectUser(user, "bob", "p1"));
		}
	}

	@Test
	void addsManyUsersToAProject() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.addProjectUserPermissions(requestFor(user), form("projectId", "p1",
					"endDate", "2027-01-01", "userpermissions", "[{\"userid\":\"bob\",\"permission\":\"EDIT\"}]"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.addProjectUserPermissions(user, "p1",
					List.of(Map.of("userid", "bob", "permission", "EDIT")), "2027-01-01"));
			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), anyList()), never());
		}
	}

	@Test
	void addsDirectoryPeopleToTheSecurityDatabaseBeforeGivingThemAccess() throws Exception {
		User user = signedIn("alice");
		List<Map<String, String>> rows = List.of(Map.of("userid", "bob", "permission", "EDIT"));
		try (Statics statics = new Statics()) {
			statics.directory.when(MicrosoftGraphUserLookup::isEnabled).thenReturn(true);

			Response response = resource.addProjectUserPermissions(requestFor(user),
					form("projectId", "p1", "userpermissions", "[{\"userid\":\"bob\",\"permission\":\"EDIT\"}]"));

			assertEquals(200, response.getStatus());
			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(user, rows));
			statics.project.verify(() -> SecurityProjectUtils.addProjectUserPermissions(user, "p1", rows, null));
		}
	}

	@Test
	void editsAUserByIdAndLoginType() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editProjectUserPermission(requestFor(user), form("id", "bob", "type", "LDAP",
					"projectId", "p1", "permission", "READ_ONLY", "endDate", "2027-01-01"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.editProjectUserPermission(user, "bob", "LDAP", "p1",
					"READ_ONLY", "2027-01-01"));
		}
	}

	@Test
	void refusesAnEditorChangingAnOwnerWith403() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.editProjectUserPermission(user, "olga", "NATIVE", "p1",
					"READ_ONLY", null)).thenThrow(new IllegalAccessException("Not high enough"));

			Response response = resource.editProjectUserPermission(requestFor(user),
					form("id", "olga", "type", "NATIVE", "projectId", "p1", "permission", "READ_ONLY"));

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void editsManyUsersWithTheirRows() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.editProjectUserPermissions(requestFor(user), form("projectId", "p1",
					"userpermissions", "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"OWNER\"}]"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.editProjectUserPermissions(user, "p1",
					List.of(Map.of("userid", "bob", "type", "NATIVE", "permission", "OWNER")), null));
		}
	}

	@Test
	void removesManyUsersById() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.removeProjectUserPermissions(requestFor(user),
					form("projectId", "p1", "ids", "[\"bob\",\"carol\"]"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.removeProjectUsers(user, List.of("bob", "carol"), "p1"));
		}
	}

	@Test
	void refusesToPropagateTheAccessOfAProjectTheUserCannotEdit() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(false);

			Response one = resource.propagateProjectDependencyPermission(requestFor(user), form("id", "bob", "type",
					"NATIVE", "projectId", "p1", "permission", "OWNER"));
			Response many = resource.propagateProjectDependencyPermissions(requestFor(user), form("projectId", "p1",
					"userpermissions", "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"OWNER\"}]"));

			for (Response response : List.of(one, many)) {
				assertEquals(403, response.getStatus());
				assertEquals("Project p1 does not exist or user does not have access to edit it", errorOf(response));
			}
			statics.project.verify(() -> SecurityProjectUtils.propagateProjectPermission(any(), any(), any(), any(),
					any(), any()), never());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "OWNR", "7", "" })
	void refusesAnUnknownLevelWithoutSavingIt(String level) throws Exception {
		User user = signedIn("alice");
		String rows = "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"EDIT\"},"
				+ "{\"userid\":\"carol\",\"type\":\"NATIVE\",\"permission\":\"" + level + "\"}]";
		try (Statics statics = new Statics()) {
			List<Response> responses = List.of( resource.addProjectUserPermission(requestFor(user), form("id", "bob",
					"projectId", "p1", "permission", level)), resource.editProjectUserPermission(requestFor(user),
					form("id", "bob", "type", "NATIVE", "projectId", "p1", "permission", level)),
					resource.propagateProjectDependencyPermission(requestFor(user), form("id", "bob", "type", "NATIVE",
					"projectId", "p1", "permission", level)),
					resource.propagateProjectDependencyPermissions(requestFor(user), form("projectId", "p1",
					"userpermissions", rows)), resource.addProjectUserPermissions(requestFor(user), form("projectId",
					"p1", "userpermissions", rows)), resource.editProjectUserPermissions(requestFor(user),
					form("projectId", "p1", "userpermissions", rows)),
					resource.approveProjectUserAccessRequest(requestFor(user), form("projectId", "p1", "requests",
					rows)));

			for (Response response : responses) {
				assertEquals(400, response.getStatus());
				assertEquals("The permission must be OWNER, EDIT or READ_ONLY. Received value = " + level,
						errorOf(response));
			}
			statics.project.verify(() -> SecurityProjectUtils.propagateProjectPermission(any(), any(), any(), any(),
					any(), any()), never());
			statics.project.verify(() -> SecurityProjectUtils.addProjectUser(any(), any(), any(), any(), any()),
					never());
			statics.project.verify(() -> SecurityProjectUtils.addProjectUserPermissions(any(), any(), any(), any()),
					never());
			statics.project.verify(() -> SecurityProjectUtils.editProjectUserPermissions(any(), any(), any(), any()),
					never());
			statics.project.verify(() -> SecurityProjectUtils.approveProjectUserAccessRequests(any(), any(), any(),
					any()), never());
			statics.directory.verify(() -> MicrosoftGraphUserLookup.addMissingUsers(any(), any()), never());
		}
	}

	@Test
	void savesALevelSentInAnyCaseUnderItsName() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			resource.addProjectUserPermission(requestFor(user), form("id", "bob", "projectId", "p1", "permission",
					" edit "));

			statics.project.verify(() -> SecurityProjectUtils.addProjectUser(user, "bob", "p1", "EDIT", null));
		}
	}

	@Test
	void propagatesAccessToTheProjectsDependenciesWithTheLoginType() throws Exception {
		User user = signedIn("alice");
		Map<String, Object> outcome = Map.of("accessGranted", List.of("m1"));
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.propagateProjectPermission(user, "p1", "bob", "NATIVE",
					"READ_ONLY", "2027-01-01")).thenReturn(outcome);

			Response response = resource.propagateProjectDependencyPermission(requestFor(user), form("id", "bob",
					"type", "NATIVE", "projectId", "p1", "permission", "READ_ONLY", "endDate", "2027-01-01"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("accessGranted", List.of("m1")), body(response));
			statics.limits.verifyNoInteractions();
		}
	}

	@Test
	void propagatesTheUsageLimitItIsSent() throws Exception {
		User user = signedIn("alice");
		ModelUsageLimit limit = new ModelUsageLimit();
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{}")).thenReturn(limit);

			Response response = resource.propagateProjectDependencyPermission(requestFor(user), form("id", "bob",
					"type", "NATIVE", "projectId", "p1", "permission", "READ_ONLY", "usageLimit", "{}"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.propagateProjectPermission(user, "p1", "bob", "NATIVE",
					"READ_ONLY", null, limit));
		}
	}

	@Test
	void propagatesEveryRowWithItsOwnLimitAndKeepsLimitsForRowsWithout() throws Exception {
		User user = signedIn("alice");
		ModelUsageLimit limit = new ModelUsageLimit();
		String rows = "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"EDIT\",\"usageLimit\":{\"unlimited\":true}},"
				+ "{\"userid\":\"carol\",\"type\":\"LDAP\",\"permission\":\"READ_ONLY\"}]";
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{\"unlimited\":true}")).thenReturn(limit);
			statics.project.when(() -> SecurityProjectUtils.propagateProjectPermission(user, "p1", "bob", "NATIVE",
					"EDIT", null, limit)).thenReturn(Map.of("accessGranted", List.of("m1")));
			statics.project.when(() -> SecurityProjectUtils.propagateProjectPermission(user, "p1", "carol", "LDAP",
					"READ_ONLY", null)).thenReturn(Map.of("newRequestAdded", List.of("m1")));

			Response response = resource.propagateProjectDependencyPermissions(requestFor(user),
					form("projectId", "p1", "userpermissions", rows));

			assertEquals(200, response.getStatus());
			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(true, payload.get("success"));
			assertEquals(Map.of("bob", Map.of("accessGranted", List.of("m1")), "carol",
					Map.of("newRequestAdded", List.of("m1"))), payload.get("users"));
		}
	}

	@Test
	void readsARowLimitSentAsJsonText() throws Exception {
		User user = signedIn("alice");
		ModelUsageLimit limit = new ModelUsageLimit();
		String rows = "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"EDIT\",\"usageLimit\":\"{}\"}]";
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{}")).thenReturn(limit);

			resource.propagateProjectDependencyPermissions(requestFor(user),
					form("projectId", "p1", "userpermissions", rows));

			statics.project.verify(() -> SecurityProjectUtils.propagateProjectPermission(user, "p1", "bob", "NATIVE",
					"EDIT", null, limit));
		}
	}

	@Test
	void refusesAnUnreadableRowLimitWith400() throws Exception {
		User user = signedIn("alice");
		String rows = "[{\"userid\":\"bob\",\"type\":\"NATIVE\",\"permission\":\"EDIT\",\"usageLimit\":{\"rules\":5}}]";
		try (Statics statics = new Statics()) {
			statics.limits.when(() -> SecurityModelUsageLimitUtils.readJson(anyString())).thenCallRealMethod();

			Response response = resource.propagateProjectDependencyPermissions(requestFor(user),
					form("projectId", "p1", "userpermissions", rows));

			assertEquals(400, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.propagateProjectPermission(any(), any(), any(), any(),
					any(), any(), any()), never());
		}
	}

	@Test
	void requiresATrueOrFalseTemplateValue() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response noProject = resource.setProjectTemplate(requestFor(user), form("template", "true"));
			Response badValue = resource.setProjectTemplate(requestFor(user), form("projectId", "p1", "template", "yes"));

			assertEquals(400, noProject.getStatus());
			assertEquals("projectId and a true or false template value are required", errorOf(noProject));
			assertEquals(400, badValue.getStatus());
			statics.project.verifyNoInteractions();
		}
	}

	@Test
	void setsWhetherTheProjectIsATemplate() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.setProjectTemplate(requestFor(user),
					form("projectId", "p1", "template", "TRUE"));

			assertEquals(200, response.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.setProjectTemplate(user, "p1", true));
		}
	}

	@Test
	void refusesANonOwnerMakingAProjectATemplate() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.setProjectTemplate(user, "p1", false))
					.thenThrow(new IllegalAccessException("Only the owner"));

			Response response = resource.setProjectTemplate(requestFor(user),
					form("projectId", "p1", "template", "false"));

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void makesAProjectPublicUnlessOnlyAdminsCan() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response allowed = resource.setProjectGlobal(requestFor(user), form("projectId", "p1", "public", "true"));
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectSetPublic("p2")).thenReturn(true);
			Response refused = resource.setProjectGlobal(requestFor(user), form("projectId", "p2", "public", "false"));

			assertEquals(200, allowed.getStatus());
			assertEquals(403, refused.getStatus());
			assertEquals(ADMINS_ONLY, errorOf(refused));
			statics.project.verify(() -> SecurityProjectUtils.setProjectGlobal(user, "p1", true));
			statics.project.verify(() -> SecurityProjectUtils.setProjectGlobal(any(), eq("p2"), anyBoolean()), never());
		}
	}

	@Test
	void makesAProjectDiscoverableUnderItsOwnAdminSetting() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectSetPublic("p1")).thenReturn(true);
			Response allowed = resource.setProjectDiscoverable(requestFor(user),
					form("projectId", "p1", "discoverable", "true"));
			statics.security.when(() -> AbstractSecurityUtils.adminOnlyProjectSetDiscoverable("p1")).thenReturn(true);
			Response refused = resource.setProjectDiscoverable(requestFor(user),
					form("projectId", "p1", "discoverable", "false"));

			assertEquals(200, allowed.getStatus());
			assertEquals(403, refused.getStatus());
			assertEquals(ADMINS_ONLY, errorOf(refused));
			statics.project.verify(() -> SecurityProjectUtils.setProjectDiscoverable(user, "p1", true));
			statics.project.verify(() -> SecurityProjectUtils.setProjectDiscoverable(user, "p1", false), never());
		}
	}

	@Test
	void setsTheCallersOwnVisibilityAndFavorite() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response visibility = resource.setProjectVisibility(requestFor(user),
					form("projectId", "p1", "visibility", "false"));
			Response favorite = resource.setProjectFavorite(requestFor(user),
					form("projectId", "p1", "isFavorite", "true"));

			assertEquals(true, body(visibility));
			assertEquals(true, body(favorite));
			statics.project.verify(() -> SecurityProjectUtils.setProjectVisibility(user, "p1", false));
			statics.project.verify(() -> SecurityProjectUtils.setProjectFavorite(user, "p1", true));
		}
	}

	@Test
	void findsPeopleWithoutAccessInTheSecurityDatabaseOrTheDirectory() throws Exception {
		User user = signedIn("alice");
		HttpServletRequest request = requestFor(user);
		try (Statics statics = new Statics()) {
			statics.directory.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			statics.project.when(() -> SecurityProjectUtils.getProjectUsersNoCredentials(user, "p1", "bo", 10, 0))
					.thenReturn(List.of(Map.of("id", "bob")));
			statics.graph.when(() -> MsGraphUtility.getProjectUsers(request, user, "p1", "bo", 10, 0, false))
					.thenReturn(List.of(Map.of("id", "dir-bob")));

			Response fromDb = resource.getProjectUsersNoCredentials(request, "p1", "bo", 10, 0, null);
			Response fromDirectory = resource.getProjectUsersNoCredentials(request, "p1", "bo", 10, 0, "true");

			assertEquals(List.of(Map.of("id", "bob")), body(fromDb));
			assertEquals(List.of(Map.of("id", "dir-bob")), body(fromDirectory));
		}
	}

	@Test
	void approvesAndDeniesAccessRequests() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response approved = resource.approveProjectUserAccessRequest(requestFor(user), form("projectId", "p1",
					"endDate", "2027-01-01", "requests", "[{\"requestid\":\"r1\",\"permission\":\"EDIT\"}]"));
			Response denied = resource.denyProjectUserAccessRequest(requestFor(user),
					form("projectId", "p1", "requestids", "[\"r2\"]"));

			assertEquals(200, approved.getStatus());
			assertEquals(200, denied.getStatus());
			statics.project.verify(() -> SecurityProjectUtils.approveProjectUserAccessRequests(user, "p1",
					List.of(Map.of("requestid", "r1", "permission", "EDIT")), "2027-01-01"));
			statics.project.verify(() -> SecurityProjectUtils.denyProjectUserAccessRequests(user, "p1", List.of("r2")));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			Response response = resource.addProjectUserPermission(signedOut(),
					form("id", "bob", "projectId", "p1", "permission", "OWNER"));

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorOf(response));
			statics.project.verifyNoInteractions();
			statics.security.verifyNoInteractions();
		}
	}

	@Test
	void reportsAnUnexpectedFailureAs500() throws Exception {
		User user = signedIn("alice");
		IllegalStateException failure = new IllegalStateException("db down");
		try (Statics statics = new Statics()) {
			statics.project.when(() -> SecurityProjectUtils.removeProjectUser(user, "bob", "p1")).thenThrow(failure);

			Response response = resource.removeProjectUserPermission(requestFor(user),
					form("id", "bob", "projectId", "p1"));

			assertEquals(500, response.getStatus());
			assertEquals("db down", ((Map<?, ?>) body(response)).get(Constants.TECH_ERROR_MESSAGE));
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/project");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/project");
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
		private final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);
		private final MockedStatic<MicrosoftGraphUserLookup> directory = Mockito
				.mockStatic(MicrosoftGraphUserLookup.class);
		private final MockedStatic<MsGraphUtility> graph = Mockito.mockStatic(MsGraphUtility.class);

		private Statics() {
			// propagation needs edit access to the project; yes unless a test says no
			project.when(() -> SecurityProjectUtils.userCanEditProject(any(), anyString())).thenReturn(true);
		}

		@Override
		public void close() {
			graph.close();
			directory.close();
			limits.close();
			project.close();
			admin.close();
			security.close();
			audit.close();
		}
	}
}
