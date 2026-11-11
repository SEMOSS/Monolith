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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
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
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.util.Constants;

class AdminModelUsageResourceTest {

	private static final String ADMINS_ONLY = "This functionality is limited to only admins";
	private static final String USAGE_LIMIT_JSON = "{\"unlimited\":true}";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminModelUsageResource resource, HttpServletRequest request) throws Exception;
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

		Fixture(User user, boolean isAdmin) {
			this.user = user;
			this.request = requestFor(user);
			admin.when(() -> SecurityAdminUtils.getInstance(user)).thenReturn(isAdmin ? utils : null);
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(isAdmin);
		}

		static Fixture asAdmin() {
			return new Fixture(signedIn("admin1"), true);
		}

		Fixture params(String... pairs) {
			for (int i = 0; i < pairs.length; i += 2) {
				when(request.getParameter(pairs[i])).thenReturn(pairs[i + 1]);
			}
			return this;
		}

		@Override
		public void close() {
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/modelUsage");
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

	private static boolean holdsNoLimit(Response response) throws IOException {
		JsonObject json = body(response).getAsJsonObject();
		return !json.has("usageLimit") || json.get("usageLimit").isJsonNull();
	}

	private static boolean holdsAnUnlimitedLimit(Response response) throws IOException {
		return body(response).getAsJsonObject().getAsJsonObject("usageLimit").get("unlimited").getAsBoolean();
	}

	private static Named<Endpoint> endpoint(String name, Endpoint call) {
		return Named.of(name, call);
	}

	static Stream<Named<Endpoint>> endpoints() {
		return Stream.of(endpoint("getPlatformDefault", (r, q) -> r.getPlatformDefault(q)),
				endpoint("setPlatformDefault", (r, q) -> r.setPlatformDefault(q)),
				endpoint("getTeamTotal", (r, q) -> r.getTeamTotal(q, "g1", "CUSTOM")),
				endpoint("setTeamTotal", (r, q) -> r.setTeamTotal(q)));
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersANonAdminWith403BeforeReadingOrWritingALimit(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			f.params("groupId", "g1", "type", "CUSTOM", "usageLimit", USAGE_LIMIT_JSON);

			Response response = endpoint.call(new AdminModelUsageResource(), f.request);

			assertEquals(403, response.getStatus());
			assertEquals(ADMINS_ONLY, errorMessage(response));
			f.limits.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("endpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false)) {
			Response response = endpoint.call(new AdminModelUsageResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verifyNoInteractions();
			f.limits.verifyNoInteractions();
		}
	}

	@Test
	void readsTheGeneralPlatformLimit() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.limits.when(SecurityModelUsageLimitUtils::getPlatformDefault).thenReturn(ModelUsageLimit.unlimited());

			Response response = new AdminModelUsageResource().getPlatformDefault(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(holdsAnUnlimitedLimit(response));
		}
	}

	@Test
	void answersNoLimitWhenNoGeneralPlatformLimitIsDefined() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminModelUsageResource().getPlatformDefault(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(holdsNoLimit(response));
		}
	}

	@Test
	void setsTheGeneralPlatformLimitAndAnswersWithTheSavedLimit() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.params("usageLimit", USAGE_LIMIT_JSON);
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson(USAGE_LIMIT_JSON)).thenReturn(limit);
			f.limits.when(SecurityModelUsageLimitUtils::getPlatformDefault).thenReturn(limit);

			Response response = new AdminModelUsageResource().setPlatformDefault(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(holdsAnUnlimitedLimit(response));
			f.limits.verify(() -> SecurityModelUsageLimitUtils.setPlatformDefault(f.utils, f.user, limit));
		}
	}

	@Test
	void removesTheGeneralPlatformLimitWhenAnEmptyLimitIsSent() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("usageLimit", "");
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson("")).thenReturn(null);

			Response response = new AdminModelUsageResource().setPlatformDefault(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(holdsNoLimit(response));
			f.limits.verify(() -> SecurityModelUsageLimitUtils.setPlatformDefault(f.utils, f.user, null));
		}
	}

	@Test
	void answersAnInvalidLimitWith400WithoutSavingIt() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("usageLimit", "{oops");
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{oops"))
					.thenThrow(new IllegalArgumentException("The usage limit is not valid: oops"));

			Response response = new AdminModelUsageResource().setPlatformDefault(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("The usage limit is not valid: oops", errorMessage(response));
			f.limits.verify(() -> SecurityModelUsageLimitUtils.setPlatformDefault(any(), any(), any()), never());
		}
	}

	@Test
	void readsTheTeamTotalOfAGroupByItsTypeLabel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.limits.when(() -> SecurityModelUsageLimitUtils.getTeamTotal("g1", "MICROSOFT"))
					.thenReturn(ModelUsageLimit.unlimited());

			Response response = new AdminModelUsageResource().getTeamTotal(f.request, " g1 ", "ms");

			assertEquals(200, response.getStatus());
			assertTrue(holdsAnUnlimitedLimit(response));
		}
	}

	@Test
	void rejectsATeamTotalReadWithoutAGroupIdOrType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminModelUsageResource resource = new AdminModelUsageResource();

			Response noId = resource.getTeamTotal(f.request, null, "CUSTOM");
			Response noType = resource.getTeamTotal(f.request, "g1", " ");

			assertEquals(400, noId.getStatus());
			assertEquals("The group id ('groupId') cannot be null or empty", errorMessage(noId));
			assertEquals(400, noType.getStatus());
			assertEquals("The group type ('type') cannot be null or empty", errorMessage(noType));
			f.limits.verifyNoInteractions();
		}
	}

	@Test
	void setsTheTeamTotalOfAGroupAndAnswersWithTheSavedTotal() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.params("groupId", "g1", "type", "CUSTOM", "usageLimit", USAGE_LIMIT_JSON);
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson(USAGE_LIMIT_JSON)).thenReturn(limit);
			f.limits.when(() -> SecurityModelUsageLimitUtils.getTeamTotal("g1", "CUSTOM")).thenReturn(limit);

			Response response = new AdminModelUsageResource().setTeamTotal(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(holdsAnUnlimitedLimit(response));
			InOrder order = Mockito.inOrder(SecurityModelUsageLimitUtils.class);
			order.verify(f.limits,
					() -> SecurityModelUsageLimitUtils.setTeamTotal(f.utils, f.user, "g1", "CUSTOM", limit));
			order.verify(f.limits, () -> SecurityModelUsageLimitUtils.getTeamTotal("g1", "CUSTOM"));
		}
	}

	@Test
	void rejectsATeamTotalChangeWithoutAGroupIdOrType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminModelUsageResource resource = new AdminModelUsageResource();
			f.params("type", "CUSTOM", "usageLimit", USAGE_LIMIT_JSON);
			Response noId = resource.setTeamTotal(f.request);

			f.params("groupId", "g1", "type", "");
			Response noType = resource.setTeamTotal(f.request);

			assertEquals(400, noId.getStatus());
			assertEquals("The group id ('groupId') cannot be null or empty", errorMessage(noId));
			assertEquals(400, noType.getStatus());
			assertEquals("The group type ('type') cannot be null or empty", errorMessage(noType));
			f.limits.verify(() -> SecurityModelUsageLimitUtils.setTeamTotal(any(), any(), any(), any(), any()),
					never());
		}
	}

	@Test
	void answersATeamTotalForAMissingTeamWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.params("groupId", "ghost", "type", "CUSTOM", "usageLimit", "");
			f.limits.when(() -> SecurityModelUsageLimitUtils.setTeamTotal(f.utils, f.user, "ghost", "CUSTOM", null))
					.thenThrow(new IllegalArgumentException("Team ghost does not exist"));

			Response response = new AdminModelUsageResource().setTeamTotal(f.request);

			assertEquals(400, response.getStatus());
			assertEquals("Team ghost does not exist", errorMessage(response));
		}
	}
}
