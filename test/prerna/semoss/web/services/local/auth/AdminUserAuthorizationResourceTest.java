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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonElement;
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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.util.Constants;

class AdminUserAuthorizationResourceTest {

	private static final String LAST_ADMIN = "You cannot remove the last admin from having admin level permissions. "
			+ "Please assign a new admin before removing admin access.";

	@FunctionalInterface
	interface Endpoint {
		Response call(AdminUserAuthorizationResource resource, HttpServletRequest request) throws Exception;
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
		final MockedStatic<SecurityUpdateUtils> updates = Mockito.mockStatic(SecurityUpdateUtils.class);
		final MockedStatic<SecurityModelUsageLimitUtils> limits = Mockito
				.mockStatic(SecurityModelUsageLimitUtils.class);
		final MockedStatic<AbstractSecurityUtils> validation = Mockito.mockStatic(AbstractSecurityUtils.class);

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
			validation.close();
			limits.close();
			updates.close();
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
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/admin/user");
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

	static Stream<Named<Endpoint>> adminCheckedEndpoints() {
		return Stream.of(endpoint("setUserPublisher", (r, q) -> r.setUserPublisher(q, form("userId", "u1"))),
				endpoint("setUserLocked", (r, q) -> r.setUserLocked(q, form("userId", "u1"))),
				endpoint("editUser", (r, q) -> r.editUser(q, form("user", "{\"id\":\"u1\",\"admin\":true}"))),
				endpoint("deleteUser", (r, q) -> r.deleteUser(q, form("userId", "u1", "type", "NATIVE"))),
				endpoint("getAllUsers", (r, q) -> r.getAllUsers(q, null, 0, 0)),
				endpoint("getNumUsers", (r, q) -> r.getNumUsers(q, null)),
				endpoint("getAllAPIUsers", (r, q) -> r.getAllAPIUsers(q, null, 0, 0)),
				endpoint("getNumAPIUsers", (r, q) -> r.getNumAPIUsers(q, null)));
	}

	static Stream<Named<Endpoint>> allEndpoints() {
		return Stream.concat(Stream.of(endpoint("isAdminUser", (r, q) -> r.isAdminUser(q)),
				endpoint("registerUser", (r, q) -> r.registerUser(q, form("userId", "u1")))),
				adminCheckedEndpoints());
	}

	@ParameterizedTest
	@MethodSource("adminCheckedEndpoints")
	void answersANonAdminWith401BeforeDoingAnything(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			Response response = endpoint.call(new AdminUserAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User is not an admin", errorMessage(response));
			f.updates.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@MethodSource("allEndpoints")
	void answersASignedOutCallerWith401WithoutAnAdminCheck(Endpoint endpoint) throws Exception {
		try (Fixture f = new Fixture(null, false)) {
			Response response = endpoint.call(new AdminUserAuthorizationResource(), f.request);

			assertEquals(401, response.getStatus());
			assertEquals("User session is invalid", errorMessage(response));
			f.admin.verifyNoInteractions();
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void tellsAnAdminTheyAreAnAdmin() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminUserAuthorizationResource().isAdminUser(f.request);

			assertEquals(200, response.getStatus());
			assertTrue(body(response).getAsBoolean());
		}
	}

	@Test
	void tellsANonAdminTheyAreNotAnAdmin() throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			Response response = new AdminUserAuthorizationResource().isAdminUser(f.request);

			assertEquals(200, response.getStatus());
			assertFalse(body(response).getAsBoolean());
		}
	}

	@Test
	void registersAUserWithTheirDetailsRolesAndUsageLimit() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			ModelUsageLimit limit = ModelUsageLimit.unlimited();
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{\"unlimited\":true}")).thenReturn(limit);
			f.validation.when(() -> AbstractSecurityUtils.formatPhone("(555) 123-4567")).thenReturn("5551234567");
			when(f.request.getParameter("phone")).thenReturn("(555) 123-4567");
			when(f.request.getParameter("phoneextension")).thenReturn("12");
			when(f.request.getParameter("countrycode")).thenReturn("1");
			f.updates.when(() -> SecurityUpdateUtils.registerUser(any(), any(), any(), any(), any(), any(), any(),
					any(), anyBoolean(), anyBoolean(), anyBoolean(), any())).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "jdoe", "type", "NATIVE", "name", "Jane Doe", "email", "jane@example.com",
							"password", "Secret1!", "admin", "true", "publisher", "false", "exporter", "true",
							"modelUsageLimit", "{\"unlimited\":true}"));

			assertEquals(200, response.getStatus());
			assertTrue(body(response).getAsBoolean());
			f.validation.verify(() -> AbstractSecurityUtils.validEmail("jane@example.com", true));
			f.validation.verify(() -> AbstractSecurityUtils.validPassword("jdoe", AuthProvider.NATIVE, "Secret1!"));
			f.updates.verify(() -> SecurityUpdateUtils.registerUser("jdoe", "Jane Doe", "jane@example.com",
					"Secret1!", "NATIVE", "5551234567", "12", "1", true, false, true, limit));
		}
	}

	@Test
	void answersFalseWhenTheUserIsAlreadyRegistered() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "jdoe", "type", "NATIVE"));

			assertEquals(200, response.getStatus());
			assertFalse(body(response).getAsBoolean());
			f.validation.verifyNoInteractions();
		}
	}

	@Test
	void rejectsARegistrationWithoutAUserId() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminUserAuthorizationResource resource = new AdminUserAuthorizationResource();

			Response blank = resource.registerUser(f.request, form("userId", "", "type", "NATIVE"));
			Response missing = resource.registerUser(f.request, form("type", "NATIVE"));

			for (Response response : List.of(blank, missing)) {
				assertEquals(400, response.getStatus());
				assertEquals("The user id cannot be null or empty", errorMessage(response));
			}
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void answersAnInvalidUsageLimitWith400WithoutRegistering() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.limits.when(() -> SecurityModelUsageLimitUtils.readJson("{oops"))
					.thenThrow(new IllegalArgumentException("The usage limit is not valid: oops"));

			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "jdoe", "type", "NATIVE", "modelUsageLimit", "{oops"));

			assertEquals(400, response.getStatus());
			assertEquals("The usage limit is not valid: oops", errorMessage(response));
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void answersAnUnexpectedRegistrationFailureWith500AndAGenericMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.updates.when(() -> SecurityUpdateUtils.registerUser(any(), any(), any(), any(), any(), any(), any(),
					any(), anyBoolean(), anyBoolean(), anyBoolean(), any())).thenThrow(new IllegalStateException("db"));

			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "jdoe", "type", "NATIVE"));

			assertEquals(500, response.getStatus());
			assertEquals("An unexpected error happened. Please reach out to an admin.", errorMessage(response));
		}
	}

	@Test
	void refusesANonAdminRegistrationWith403WithoutRegisteringAnyone() throws Exception {
		try (Fixture f = new Fixture(signedIn("jdoe"), false)) {
			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "intruder", "type", "NATIVE", "admin", "true"));

			assertEquals(403, response.getStatus());
			assertEquals("This functionality is limited to only admins", errorMessage(response));
			f.updates.verifyNoInteractions();
			f.validation.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	@NullSource
	void refusesARegistrationWithoutALoginType(String type) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			MultivaluedMap<String, String> form = form("userId", "jdoe", "name", "Jane Doe");
			if (type != null) {
				form.putSingle("type", type);
			}

			Response response = new AdminUserAuthorizationResource().registerUser(f.request, form);

			assertEquals(400, response.getStatus());
			assertEquals("The login type ('type') cannot be null or empty", errorMessage(response));
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void registersTheLoginTypeAsItsLabel() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			AdminUserAuthorizationResource resource = new AdminUserAuthorizationResource();
			Response nativeLogin = resource.registerUser(f.request, form("userId", "jdoe", "type", "native"));
			// no login is turned on in these tests, so a provider passes whether or not it is enabled
			Response microsoftLogin = resource.registerUser(f.request, form("userId", "jdoe", "type", "ms"));

			assertEquals(200, nativeLogin.getStatus());
			assertEquals(200, microsoftLogin.getStatus());
			f.updates.verify(() -> SecurityUpdateUtils.registerUser(eq("jdoe"), any(), any(), any(), eq("NATIVE"),
					any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()));
			f.updates.verify(() -> SecurityUpdateUtils.registerUser(eq("jdoe"), any(), any(), any(), eq("MICROSOFT"),
					any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "foo", "NATIVE_X", "group:sales" })
	void refusesALoginTypeThatNamesNoProvider(String type) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminUserAuthorizationResource().registerUser(f.request,
					form("userId", "jdoe", "type", type));

			assertEquals(400, response.getStatus());
			assertEquals("The login type ('type') is not a known login type. Received value = " + type,
					errorMessage(response));
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void answersAnInvalidEmailPhoneOrPasswordWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			f.validation.when(() -> AbstractSecurityUtils.validEmail("bad", true))
					.thenThrow(new IllegalArgumentException("bad is not a valid email address. "));
			f.validation.when(() -> AbstractSecurityUtils.formatPhone("x1"))
					.thenThrow(new IllegalArgumentException("Phone number x1 contains invalid characters. "));
			f.validation.when(() -> AbstractSecurityUtils.validPassword("jdoe", AuthProvider.NATIVE, "short"))
					.thenThrow(new IllegalArgumentException("Password is too short. "));
			when(f.request.getParameter("phone")).thenReturn(null, "x1", null);
			AdminUserAuthorizationResource resource = new AdminUserAuthorizationResource();

			Response email = resource.registerUser(f.request, form("userId", "jdoe", "type", "NATIVE", "email", "bad"));
			Response phone = resource.registerUser(f.request, form("userId", "jdoe", "type", "NATIVE"));
			Response password = resource.registerUser(f.request, form("userId", "jdoe", "type", "NATIVE", "password",
					"short"));

			assertEquals(400, email.getStatus());
			assertEquals("bad is not a valid email address. ", errorMessage(email));
			assertEquals(400, phone.getStatus());
			assertEquals("Phone number x1 contains invalid characters. ", errorMessage(phone));
			assertEquals(400, password.getStatus());
			assertEquals("Password is too short. ", errorMessage(password));
			f.updates.verifyNoInteractions();
		}
	}

	@Test
	void setsWhetherAUserIsAPublisher() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminUserAuthorizationResource().setUserPublisher(f.request,
					form("userId", "u1", "type", "native", "isPublisher", "true"));

			assertSuccess(response);
			verify(f.utils).setUserPublisher("u1", "NATIVE", true);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	@NullSource
	void refusesAPublisherChangeWithoutALoginType(String type) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			MultivaluedMap<String, String> form = form("userId", "u1", "isPublisher", "true");
			if (type != null) {
				form.putSingle("type", type);
			}

			Response response = new AdminUserAuthorizationResource().setUserPublisher(f.request, form);

			assertEquals(400, response.getStatus());
			assertEquals("The login type ('type') cannot be null or empty", errorMessage(response));
			verify(f.utils, never()).setUserPublisher(any(), any(), anyBoolean());
		}
	}

	@Test
	void answersAFailedPublisherChangeWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Mockito.doThrow(new IllegalArgumentException("An error occurred setting this user as a publisher"))
					.when(f.utils).setUserPublisher(any(), any(), anyBoolean());

			Response response = new AdminUserAuthorizationResource().setUserPublisher(f.request,
					form("userId", "u1", "type", "NATIVE", "isPublisher", "false"));

			assertEquals(400, response.getStatus());
			assertEquals("An error occurred setting this user as a publisher", errorMessage(response));
		}
	}

	@Test
	void locksAUserByTheirIdAndLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			Response response = new AdminUserAuthorizationResource().setUserLocked(f.request,
					form("userId", "u1", "type", "MICROSOFT", "isLocked", "true"));

			assertSuccess(response);
			verify(f.utils).setUserLock("u1", "MICROSOFT", true);
		}
	}

	@Test
	void editsAUser() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.editUser(any())).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"u1\",\"type\":\"NATIVE\",\"name\":\"Jane\",\"admin\":true}"));

			assertEquals(200, response.getStatus());
			assertTrue(body(response).getAsBoolean());
			verify(f.utils).editUser(argThat(info -> "u1".equals(info.get("id")) && "Jane".equals(info.get("name"))
					&& Boolean.TRUE.equals(info.get("admin"))));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "false", "0", "\"false\"" })
	void refusesToTakeAdminAwayFromTheLastAdmin(String adminValue) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getNumAdmins()).thenReturn(1);
			when(f.utils.getAdminUserIdAndType()).thenReturn(new Object[] { "admin1", "NATIVE" });

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"admin1\",\"type\":\"NATIVE\",\"admin\":" + adminValue + "}"));

			assertEquals(400, response.getStatus());
			assertEquals(LAST_ADMIN, errorMessage(response));
			verify(f.utils, never()).editUser(any());
		}
	}

	@Test
	void takesAdminAwayFromAnAdminWhenAnotherAdminRemains() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getNumAdmins()).thenReturn(2);
			when(f.utils.editUser(any())).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"admin1\",\"type\":\"NATIVE\",\"admin\":false}"));

			assertEquals(200, response.getStatus());
			verify(f.utils).editUser(any());
		}
	}

	@Test
	void takesAdminAwayFromAnotherLoginOfTheSameIdWhenOneAdminRemains() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getNumAdmins()).thenReturn(1);
			when(f.utils.getAdminUserIdAndType()).thenReturn(new Object[] { "admin1", "NATIVE" });
			when(f.utils.editUser(any())).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"admin1\",\"type\":\"MICROSOFT\",\"admin\":false}"));

			assertEquals(200, response.getStatus());
			verify(f.utils).editUser(any());
		}
	}

	@Test
	void answersAnEditThatSavesNothingWith400() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.editUser(any())).thenReturn(false);

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"u1\",\"type\":\"NATIVE\"}"));

			assertEquals(400, response.getStatus());
			assertEquals("Unknown error occurred with updating user. Please try again.", errorMessage(response));
		}
	}

	@Test
	void answersAnInvalidEditWith400AndItsMessage() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.editUser(any())).thenThrow(new IllegalArgumentException("bad is not a valid email address. "));

			Response response = new AdminUserAuthorizationResource().editUser(f.request,
					form("user", "{\"id\":\"u1\",\"type\":\"NATIVE\",\"email\":\"bad\"}"));

			assertEquals(400, response.getStatus());
			assertEquals("bad is not a valid email address. ", errorMessage(response));
		}
	}

	@Test
	void deletesAUserByTheirIdAndLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.deleteUser("u1", "NATIVE")).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().deleteUser(f.request,
					form("userId", "u1", "type", "NATIVE"));

			assertEquals(200, response.getStatus());
			assertTrue(body(response).getAsBoolean());
			verify(f.utils).deleteUser("u1", "NATIVE");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	@NullSource
	void refusesADeleteWithoutALoginTypeBeforeTheLastAdminCheck(String type) throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			MultivaluedMap<String, String> form = form("userId", "admin1");
			if (type != null) {
				form.putSingle("type", type);
			}

			Response response = new AdminUserAuthorizationResource().deleteUser(f.request, form);

			assertEquals(400, response.getStatus());
			assertEquals("The user id ('userId') and login type ('type') are required", errorMessage(response));
			verify(f.utils, never()).userIsAdmin(any(), any());
			verify(f.utils, never()).deleteUser(any(), any());
		}
	}

	@Test
	void deletesAUserUnderTheLabelOfTheirLoginType() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.deleteUser("u1", "NATIVE")).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().deleteUser(f.request, form("userId", "u1", "type",
					"native"));

			assertEquals(200, response.getStatus());
			verify(f.utils).userIsAdmin("u1", "NATIVE");
			verify(f.utils).deleteUser("u1", "NATIVE");
		}
	}

	@Test
	void refusesToDeleteTheLastAdmin() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.userIsAdmin("admin1", "NATIVE")).thenReturn(true);
			when(f.utils.otherAdminsExist("admin1", "NATIVE")).thenReturn(false);

			Response response = new AdminUserAuthorizationResource().deleteUser(f.request,
					form("userId", "admin1", "type", "NATIVE"));

			assertEquals(400, response.getStatus());
			assertEquals("You cannot delete this user as it is the last admin. Please assign a new admin before "
					+ "deleting this user.", errorMessage(response));
			verify(f.utils, never()).deleteUser(anyString(), anyString());
		}
	}

	@Test
	void deletesAnAdminWhenAnotherAdminRemains() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.userIsAdmin("admin2", "NATIVE")).thenReturn(true);
			when(f.utils.otherAdminsExist("admin2", "NATIVE")).thenReturn(true);
			when(f.utils.deleteUser("admin2", "NATIVE")).thenReturn(true);

			Response response = new AdminUserAuthorizationResource().deleteUser(f.request,
					form("userId", "admin2", "type", "NATIVE"));

			assertEquals(200, response.getStatus());
			verify(f.utils).deleteUser("admin2", "NATIVE");
		}
	}

	@Test
	void pagesAndCountsTheUsers() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllUsers("o''neil", 10, 5)).thenReturn(List.of(Map.of("id", "u1")));
			when(f.utils.getNumUsers("smith")).thenReturn(42L);
			AdminUserAuthorizationResource resource = new AdminUserAuthorizationResource();

			Response users = resource.getAllUsers(f.request, "o'neil", 10, 5);
			Response count = resource.getNumUsers(f.request, "smith");

			assertEquals("u1", body(users).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			assertEquals(42, body(count).getAsLong());
		}
	}

	@Test
	void pagesAndCountsTheApiUsers() throws Exception {
		try (Fixture f = Fixture.asAdmin()) {
			when(f.utils.getAllAPIUsers("bot", 10, 0)).thenReturn(List.of(Map.of("id", "api1")));
			when(f.utils.getNumAPIUsers("bot")).thenReturn(3L);
			AdminUserAuthorizationResource resource = new AdminUserAuthorizationResource();

			Response users = resource.getAllAPIUsers(f.request, "bot", 10, 0);
			Response count = resource.getNumAPIUsers(f.request, "bot");

			assertEquals("api1", body(users).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
			assertEquals(3, body(count).getAsLong());
		}
	}
}
