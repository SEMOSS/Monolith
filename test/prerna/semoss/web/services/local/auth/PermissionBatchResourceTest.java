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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import prerna.auth.access.PermissionBatch;
import prerna.auth.access.PermissionTargetResource;
import prerna.auth.access.PermissionTargetResource.TargetResourceType;
import prerna.auth.utils.EffectivePermissionUtils;
import prerna.auth.utils.SecurityAccessPermissionBatchUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class PermissionBatchResourceTest {

	private static final String ONE_BATCH = "[{\"target\":{\"resourceType\":\"PROJECT\",\"id\":\"p1\"},\"changes\":[]}]";

	private final PermissionBatchResource resource = new PermissionBatchResource();

	@Test
	void validatesTheBatchesItReadsWithTheCallersOwnPermissions() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.validateBatches(eq(user), anyList()))
					.thenReturn(List.of(Map.of("valid", true)));

			Response response = resource.validatePermissionBatches(postWith(user, Map.of("batches", ONE_BATCH)));

			assertEquals(200, response.getStatus());
			assertEquals(List.of(Map.of("valid", true)), body(response));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.validateBatches(eq(user),
					argThat(PermissionBatchResourceTest::isTheOneProjectBatch)));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.validateBatches(any(SecurityAdminUtils.class),
					any(), anyList()), never());
		}
	}

	@Test
	void savesTheBatchesItReadsWithTheCallersOwnPermissions() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.saveBatches(eq(user), anyList()))
					.thenReturn(Map.of("targets", 1));

			Response response = resource.savePermissionBatches(postWith(user, Map.of("batches", ONE_BATCH)));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("targets", 1.0), body(response));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(eq(user),
					argThat(PermissionBatchResourceTest::isTheOneProjectBatch)));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(any(SecurityAdminUtils.class),
					any(), anyList()), never());
		}
	}

	@Test
	void refusesMissingEmptyOrUnreadableBatchesWith400() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response missing = resource.savePermissionBatches(postWith(user, Map.of()));
			Response empty = resource.savePermissionBatches(postWith(user, Map.of("batches", "[]")));
			Response unreadable = resource.validatePermissionBatches(postWith(user, Map.of("batches", "{\"x\":")));

			assertEquals(400, missing.getStatus());
			assertEquals("Must define the permission batches", errorOf(missing));
			assertEquals(400, empty.getStatus());
			assertEquals("Must define at least one permission batch", errorOf(empty));
			assertEquals(400, unreadable.getStatus());
			assertTrue(errorOf(unreadable).startsWith("The permission batches are not valid"), errorOf(unreadable));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(any(User.class), anyList()),
					never());
			statics.batches.verify(
					() -> SecurityAccessPermissionBatchUtils.validateBatches(any(User.class), anyList()), never());
		}
	}

	@Test
	void refusesASaveTheCallerMayNotMakeWith403() throws Exception {
		User user = signedIn("eddie");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.saveBatches(eq(user), anyList()))
					.thenThrow(new IllegalAccessException("Only owners can give Author access"));

			Response response = resource.savePermissionBatches(postWith(user, Map.of("batches", ONE_BATCH)));

			assertEquals(403, response.getStatus());
			assertEquals("Only owners can give Author access", errorOf(response));
		}
	}

	@Test
	void readsThePermissionsOfATargetNamedInAnyCase() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getPermissions(eq(user), any()))
					.thenReturn(Map.of("people", List.of()));

			Response response = resource.getPermissions(requestFor(user), " engine ", "e1");

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("people", List.of()), body(response));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.getPermissions(eq(user),
					argThat(target -> target.getResourceType() == TargetResourceType.ENGINE
							&& "e1".equals(target.getId()) && target.getProjectId() == null)));
		}
	}

	@Test
	void refusesAnUnknownOrMissingResourceTypeWith400() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response unknown = resource.getPermissions(requestFor(user), "widget", "w1");
			Response missing = resource.getEffectivePermissions(requestFor(user), null, "p1");

			assertEquals(400, unknown.getStatus());
			assertEquals("widget is not a type of resource Bulk Access Management knows", errorOf(unknown));
			assertEquals(400, missing.getStatus());
			assertEquals("Must define the type of resource", errorOf(missing));
			statics.batches.verifyNoInteractions();
			statics.effective.verifyNoInteractions();
		}
	}

	@Test
	void refusesReadingPermissionsTheCallerMayNotSeeWith403() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getPermissions(eq(user), any()))
					.thenThrow(new IllegalAccessException("The user does not have access to view this project"));

			Response response = resource.getPermissions(requestFor(user), "PROJECT", "p1");

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void readsThePeopleToCopyBetweenTwoTargets() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(eq(user), any(), any()))
					.thenReturn(List.of(Map.of("id", "bob")));

			Response response = resource.getPermissionsToCopy(requestFor(user), "TEAM", "Sales", "team_managers",
					"Sales");

			assertEquals(List.of(Map.of("id", "bob")), body(response));
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(eq(user),
					argThat(source -> source.getResourceType() == TargetResourceType.TEAM
							&& "Sales".equals(source.getId())),
					argThat(target -> target.getResourceType() == TargetResourceType.TEAM_MANAGERS
							&& "Sales".equals(target.getId()))));
		}
	}

	@Test
	void refusesCopyingWithAnUnknownTargetType() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			Response response = resource.getPermissionsToCopy(requestFor(user), "PROJECT", "p1", "FOLDER", "f1");

			assertEquals(400, response.getStatus());
			assertEquals("FOLDER is not a type of resource Bulk Access Management knows", errorOf(response));
			statics.batches.verifyNoInteractions();
		}
	}

	@Test
	void readsTheEffectivePermissionsOfATarget() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.effective.when(() -> EffectivePermissionUtils.getEffectivePermissions(eq(user), any()))
					.thenReturn(Map.of("rows", List.of()));

			Response response = resource.getEffectivePermissions(requestFor(user), "project", "p1");

			assertEquals(Map.of("rows", List.of()), body(response));
			statics.effective.verify(() -> EffectivePermissionUtils.getEffectivePermissions(eq(user),
					argThat(target -> target.getResourceType() == TargetResourceType.PROJECT
							&& "p1".equals(target.getId()))));
		}
	}

	@Test
	void readsAPersonsUsageLimitPathWithTheirLoginType() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getUsageLimitPath(user, "m1", "bob", "LDAP"))
					.thenReturn(Map.of("steps", List.of()));

			Response response = resource.getUsageLimitPath(requestFor(user), "m1", "bob", "LDAP");

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("steps", List.of()), body(response));
		}
	}

	@Test
	void refusesAUsageLimitPathToSomeoneWhoCannotEditTheModel() throws Exception {
		User user = signedIn("mallory");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getUsageLimitPath(user, "m1", "bob", "NATIVE"))
					.thenThrow(new IllegalAccessException("Only the model's editors and owners can see this"));

			Response response = resource.getUsageLimitPath(requestFor(user), "m1", "bob", "NATIVE");

			assertEquals(403, response.getStatus());
		}
	}

	@Test
	void readsTheCallersOwnUsageLimitPaths() throws Exception {
		User user = signedIn("alice");
		try (Statics statics = new Statics()) {
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getMyUsageLimitPath(user, "m1"))
					.thenReturn(Map.of("steps", List.of()));
			statics.batches.when(() -> SecurityAccessPermissionBatchUtils.getMyUsageLimitPaths(user))
					.thenReturn(Map.of("models", List.of()));

			Response one = resource.getMyUsageLimitPath(requestFor(user), "m1");
			Response all = resource.getMyUsageLimitPaths(requestFor(user));

			assertEquals(Map.of("steps", List.of()), body(one));
			assertEquals(Map.of("models", List.of()), body(all));
		}
	}

	@Test
	void answers401WithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			HttpServletRequest request = signedOut();
			when(request.getParameter("batches")).thenReturn(ONE_BATCH);

			Response save = resource.savePermissionBatches(request);
			Response mine = resource.getMyUsageLimitPaths(request);

			assertEquals(401, save.getStatus());
			assertEquals(401, mine.getStatus());
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(any(User.class), anyList()),
					never());
			statics.batches.verify(() -> SecurityAccessPermissionBatchUtils.getMyUsageLimitPaths(any()), never());
		}
	}

	@Test
	void buildsATargetWithoutAProject() {
		PermissionTargetResource target = PermissionBatchResource.toTarget("team_managers", "Sales");

		assertEquals(TargetResourceType.TEAM_MANAGERS, target.getResourceType());
		assertEquals("Sales", target.getId());
		assertNull(target.getProjectId());
		assertThrows(IllegalArgumentException.class, () -> PermissionBatchResource.toTarget(" ", "Sales"));
	}

	private static boolean isTheOneProjectBatch(List<PermissionBatch> batches) {
		return batches.size() == 1 && batches.get(0).getTarget().getResourceType() == TargetResourceType.PROJECT
				&& "p1".equals(batches.get(0).getTarget().getId()) && batches.get(0).getChanges().isEmpty();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/permissionBatch");
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
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/permissionBatch");
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
	 * The Semoss statics these endpoints call, mocked for one test. Reading
	 * batches stays real, so the endpoints are tested with what they parse.
	 */
	private static final class Statics implements AutoCloseable {

		private final MockedStatic<UserAuditTrailUtils> audit = Mockito.mockStatic(UserAuditTrailUtils.class);
		private final MockedStatic<SecurityAccessPermissionBatchUtils> batches = Mockito
				.mockStatic(SecurityAccessPermissionBatchUtils.class, Mockito.CALLS_REAL_METHODS);
		private final MockedStatic<EffectivePermissionUtils> effective = Mockito
				.mockStatic(EffectivePermissionUtils.class);

		@Override
		public void close() {
			effective.close();
			batches.close();
			audit.close();
		}
	}
}
