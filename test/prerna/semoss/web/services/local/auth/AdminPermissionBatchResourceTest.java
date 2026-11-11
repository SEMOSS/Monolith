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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatcher;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import prerna.util.Constants;

class AdminPermissionBatchResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
	private static final String BATCHES_JSON = "[{\"target\":{\"resourceType\":\"PROJECT\",\"id\":\"p1\"}}]";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminPermissionBatchResource resource, HttpServletRequest request) throws Exception;
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
		final MockedStatic<SecurityAccessPermissionBatchUtils> batches = Mockito
				.mockStatic(SecurityAccessPermissionBatchUtils.class);
		final MockedStatic<EffectivePermissionUtils> effective = Mockito.mockStatic(EffectivePermissionUtils.class);

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
			effective.close();
			batches.close();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/permissionBatch");
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

	private static ArgumentMatcher<PermissionTargetResource> target(TargetResourceType type, String id) {
		return target -> target != null && target.getResourceType() == type && id.equals(target.getId())
				&& target.getProjectId() == null;
	}

	private static Named<Endpoint> endpoint(String name, Endpoint call) {
		return Named.of(name, call);
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(endpoint("validatePermissionBatches", (r, q) -> r.validatePermissionBatches(q)),
				endpoint("savePermissionBatches", (r, q) -> r.savePermissionBatches(q)),
				endpoint("getPermissions", (r, q) -> r.getPermissions(q, "PROJECT", "p1")),
				endpoint("getPermissionsToCopy", (r, q) -> r.getPermissionsToCopy(q, "PROJECT", "p1", "PROJECT", "p2")),
				endpoint("getEffectivePermissions", (r, q) -> r.getEffectivePermissions(q, "ENGINE", "e1")),
				endpoint("getUsageLimitPath", (r, q) -> r.getUsageLimitPath(q, "e1", "u1", "NATIVE")));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith403BeforeReadingAnyBatch(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			when(f.request.getParameter("batches")).thenReturn(BATCHES_JSON);

			Response response = endpoint.call(new AdminPermissionBatchResource(), f.request);

			assertEquals(403, response.getStatus());
			assertEquals(ADMINS_ONLY, errorMessage(response));
			f.batches.verifyNoInteractions();
			f.effective.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false)) {
			Response response = endpoint.call(new AdminPermissionBatchResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verifyNoInteractions();
			f.batches.verifyNoInteractions();
		}
	}

	@Test
	void validatesTheBatchesInAdminModeWithoutSavingThem() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			List<PermissionBatch> read = List.of(new PermissionBatch());
			when(f.request.getParameter("batches")).thenReturn(BATCHES_JSON);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.readBatches(BATCHES_JSON)).thenReturn(read);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.validateBatches(f.utils, f.user, read))
					.thenReturn(List.of(Map.of("valid", true)));

			Response response = new AdminPermissionBatchResource().validatePermissionBatches(f.request);

			assertEquals(200, response.getStatus());
			assertEquals(true, body(response).getAsJsonArray().get(0).getAsJsonObject().get("valid").getAsBoolean());
			f.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(any(SecurityAdminUtils.class),
					any(), any()), never());
		}
	}

	@Test
	void savesTheBatchesInAdminMode() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			List<PermissionBatch> read = List.of(new PermissionBatch());
			when(f.request.getParameter("batches")).thenReturn(BATCHES_JSON);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.readBatches(BATCHES_JSON)).thenReturn(read);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.saveBatches(f.utils, f.user, read))
					.thenReturn(Map.of("saved", 1));

			Response response = new AdminPermissionBatchResource().savePermissionBatches(f.request);

			assertEquals(200, response.getStatus());
			assertEquals(1, body(response).getAsJsonObject().get("saved").getAsInt());
		}
	}

	@Test
	void answersMissingBatchesWith400WithoutSaving() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.readBatches(null))
					.thenThrow(new IllegalArgumentException("Must define the permission batches"));

			Response response = new AdminPermissionBatchResource().savePermissionBatches(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("Must define the permission batches", errorMessage(response));
			f.batches.verify(() -> SecurityAccessPermissionBatchUtils.saveBatches(any(SecurityAdminUtils.class),
					any(), any()), never());
		}
	}

	@Test
	void answersAFailedSaveWith500AndTheTechnicalMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			List<PermissionBatch> read = List.of(new PermissionBatch());
			when(f.request.getParameter("batches")).thenReturn(BATCHES_JSON);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.readBatches(BATCHES_JSON)).thenReturn(read);
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.saveBatches(f.utils, f.user, read))
					.thenThrow(new Exception("The transaction was rolled back"));

			Response response = new AdminPermissionBatchResource().savePermissionBatches(f.request);

			assertEquals(500, response.getStatus());
			JsonObject json = body(response).getAsJsonObject();
			assertEquals("An unexpected error happened. Please reach out to an admin.",
					json.get(Constants.ERROR_MESSAGE).getAsString());
			assertEquals("The transaction was rolled back", json.get(Constants.TECH_ERROR_MESSAGE).getAsString());
		}
	}

	@ParameterizedTest
	@CsvSource({ "project, PROJECT", "ENGINE, ENGINE", " team , TEAM", "Team_Managers, TEAM_MANAGERS" })
	void readsThePermissionsOnATargetOfAnyKnownType(String resourceType, TargetResourceType expected)
			throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.getPermissions(Mockito.eq(f.utils),
					Mockito.eq(f.user), argThat(target(expected, "x1")))).thenReturn(Map.of("people", List.of()));

			Response response = new AdminPermissionBatchResource().getPermissions(f.request, resourceType, "x1");

			assertEquals(200, response.getStatus());
			assertEquals(0, body(response).getAsJsonObject().getAsJsonArray("people").size());
		}
	}

	@Test
	void rejectsAnUnknownOrMissingResourceType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminPermissionBatchResource resource = new AdminPermissionBatchResource();

			Response unknown = resource.getPermissions(f.request, "FOLDER", "x1");
			Response missing = resource.getEffectivePermissions(f.request, " ", "x1");

			assertEquals(400, unknown.getStatus());
			assertEquals("FOLDER is not a type of resource Bulk Access Management knows", errorMessage(unknown));
			assertEquals(400, missing.getStatus());
			assertEquals("Must define the type of resource", errorMessage(missing));
			f.batches.verifyNoInteractions();
			f.effective.verifyNoInteractions();
		}
	}

	@Test
	void readsThePeopleToCopyFromOneTargetToAnother() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(Mockito.eq(f.utils),
					Mockito.eq(f.user), argThat(target(TargetResourceType.ENGINE, "e1")),
					argThat(target(TargetResourceType.TEAM, "g1")))).thenReturn(List.of(Map.of("id", "u1")));

			Response response = new AdminPermissionBatchResource().getPermissionsToCopy(f.request, "ENGINE", "e1",
					"TEAM", "g1");

			assertEquals(200, response.getStatus());
			assertEquals("u1", body(response).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	@Test
	void readsTheEffectivePermissionsOnAProject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.effective.when(() -> EffectivePermissionUtils.getEffectivePermissions(Mockito.eq(f.utils),
					Mockito.eq(f.user), argThat(target(TargetResourceType.PROJECT, "p1"))))
					.thenReturn(Map.of("total", 2));

			Response response = new AdminPermissionBatchResource().getEffectivePermissions(f.request, "PROJECT", "p1");

			assertEquals(2, body(response).getAsJsonObject().get("total").getAsInt());
		}
	}

	@Test
	void readsHowUsageLimitsApplyToAPersonByIdAndLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.getUsageLimitPath(f.utils, f.user, "e1", "u1",
					"MICROSOFT")).thenReturn(Map.of("steps", List.of()));

			Response response = new AdminPermissionBatchResource().getUsageLimitPath(f.request, "e1", "u1",
					"MICROSOFT");

			assertEquals(200, response.getStatus());
			assertEquals(0, body(response).getAsJsonObject().getAsJsonArray("steps").size());
		}
	}

	@Test
	void passesAMissingLoginTypeOnForTheUsageLimitPathToReject() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.batches.when(() -> SecurityAccessPermissionBatchUtils.getUsageLimitPath(f.utils, f.user, "e1", "u1",
					null)).thenThrow(new IllegalArgumentException("Must define the user's login type"));

			Response response = new AdminPermissionBatchResource().getUsageLimitPath(f.request, "e1", "u1", null);

			assertEquals(400, response.getStatus());
			assertEquals("Must define the user's login type", errorMessage(response));
			assertNull(body(response).getAsJsonObject().get(Constants.TECH_ERROR_MESSAGE));
		}
	}
}
