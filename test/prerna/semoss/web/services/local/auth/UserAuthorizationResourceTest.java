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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import prerna.auth.PasswordRequirements;
import prerna.auth.User;
import prerna.auth.utils.SecurityNativeUserUtils;
import prerna.auth.utils.SecurityPasswordResetUtils;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.auth.utils.UserRegistrationEmailService;
import prerna.engine.impl.function.mail.engine.SMTPFunctionEngine;
import prerna.theme.AdminThemeUtils;
import prerna.usertracking.AuditEventType;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;

class UserAuthorizationResourceTest {

	private static final String APP_URL = "https://semoss.example.com/Monolith";
	private static final String RESET_LINK = APP_URL + "/resetPassword/index.html?token=t-1";
	private static final String RESET_REQUESTED = "If an account with that email exists, a password reset link has been sent to it.";

	// the reset email runs inline so the thread-local static mocks reach it
	private final UserAuthorizationResource resource = new UserAuthorizationResource(Runnable::run);

	private static MockedStatic<Utility> applicationUrl() {
		return Mockito.mockStatic(Utility.class,
				inv -> "getApplicationUrl".equals(inv.getMethod().getName()) ? APP_URL : inv.callRealMethod());
	}

	@Test
	void editsTheCallersOwnDetailsAndRefreshesTheirSession() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest request = jsonFrom(user, "{\"name\":\"Alice B\",\"newEmail\":\"alice@example.com\"}");
		try (Statics statics = new Statics()) {
			statics.users.when(() -> SecurityUserUtils.editUser(eq(user), anyMap())).thenReturn(true);

			Response response = resource.editUser(request);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("name", "Alice B", "newEmail", "alice@example.com"), body(response));
			statics.users.verify(() -> SecurityUserUtils.editUser(user,
					Map.of("name", "Alice B", "newEmail", "alice@example.com")));
			assertEquals("Alice B", user.getPrimaryLoginToken().getName());
			assertEquals("alice@example.com", user.getPrimaryLoginToken().getEmail());
			statics.audit.verify(() -> UserAuditTrailUtils.recordUserAdmin(eq(user), isNull(),
					eq(AuditEventType.USER_UPDATE), eq("alice"), eq("NATIVE"), eq("Alice B"), isNull(), isNull(),
					argThat(details -> Boolean.TRUE.equals(((Map<?, ?>) details).get("selfService")))));
		}
	}

	@Test
	void editsTheCallersDetailsFromAFormField() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest request = requestFor(user);
		when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
		when(request.getParameter("user")).thenReturn("{\"name\":\"Alice B\"}");
		try (Statics statics = new Statics()) {
			Response response = resource.editUser(request);

			assertEquals(200, response.getStatus());
			statics.users.verify(() -> SecurityUserUtils.editUser(user, Map.of("name", "Alice B")));
		}
	}

	@Test
	void leavesTheSessionAloneWhenNothingWasUpdated() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		AccessToken before = user.getPrimaryLoginToken();
		try (Statics statics = new Statics()) {
			Response response = resource.editUser(jsonFrom(user, "{\"name\":\"Alice B\"}"));

			assertEquals(200, response.getStatus());
			assertSame(before, user.getPrimaryLoginToken());
			statics.audit.verifyNoInteractions();
		}
	}

	@Test
	void refusesAnUnreadableEditWith400() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest form = requestFor(user);
		when(form.getContentType()).thenReturn("application/x-www-form-urlencoded");
		when(form.getParameter("user")).thenReturn("not json");
		try (Statics statics = new Statics()) {
			Response json = resource.editUser(jsonFrom(user, "{\"name\":"));
			Response fromForm = resource.editUser(form);

			assertEquals(400, json.getStatus());
			assertEquals("Error reading request body.", errorOf(json));
			assertEquals(400, fromForm.getStatus());
			assertEquals("Error parsing user JSON from form data.", errorOf(fromForm));
			statics.users.verifyNoInteractions();
		}
	}

	@Test
	void answers401ToEveryEndpointWithoutASignedInUser() throws Exception {
		try (Statics statics = new Statics()) {
			assertEquals(401, resource.editUser(signedOut()).getStatus());
			assertEquals(401, resource.deleteUser(signedOut()).getStatus());
			assertEquals(401, resource.createUserAccessKey(signedOut()).getStatus());
			assertEquals(401, resource.deleteUserAccessKey(signedOut()).getStatus());
			assertEquals(401, resource.getUserAccessKeys(signedOut()).getStatus());
			assertEquals(401, resource.setUserMetadata(signedOut()).getStatus());
			assertEquals(401, resource.changePassword(null, signedOut()).getStatus());
			statics.users.verifyNoInteractions();
			statics.keys.verifyNoInteractions();
			statics.nativeUsers.verifyNoInteractions();
		}
	}

	@Test
	void createsAnAccessKeyForTheCallersPrimaryLogin() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest request = jsonFrom(user, "{\"tokenName\":\"ci\",\"tokenDescription\":\"for builds\"}");
		try (Statics statics = new Statics()) {
			when(statics.social.accessKeysAllowed(AuthProvider.NATIVE)).thenReturn(true);
			statics.keys.when(() -> SecurityUserAccessKeyUtils.createUserAccessToken(user.getPrimaryLoginToken(),
					"ci", "for builds")).thenReturn(Map.of("accessKey", "ak", "secretKey", "sk"));

			Response response = resource.createUserAccessKey(request);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("accessKey", "ak", "secretKey", "sk"), body(response));
		}
	}

	@Test
	void refusesAnAccessKeyWhenTheLoginMayNotHaveOne() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			Response response = resource.createUserAccessKey(jsonFrom(user, "{\"tokenName\":\"ci\"}"));

			assertEquals(403, response.getStatus());
			statics.keys.verifyNoInteractions();
		}
	}

	@Test
	void refusesAnAccessKeyNameOrDescriptionThatIsTooLong() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			when(statics.social.accessKeysAllowed(AuthProvider.NATIVE)).thenReturn(true);
			HttpServletRequest longName = requestFor(user);
			when(longName.getParameter("tokenName")).thenReturn("n".repeat(256));
			HttpServletRequest longDescription = requestFor(user);
			when(longDescription.getParameter("tokenDescription")).thenReturn("d".repeat(501));

			Response name = resource.createUserAccessKey(longName);
			Response description = resource.createUserAccessKey(longDescription);

			assertEquals(400, name.getStatus());
			assertEquals("Token name must be less than 255 characters long", errorOf(name));
			assertEquals(400, description.getStatus());
			assertEquals("Token description must be less than 500 characters long", errorOf(description));
			statics.keys.verifyNoInteractions();
		}
	}

	@Test
	void deletesOneOfTheCallersAccessKeys() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			statics.keys.when(() -> SecurityUserAccessKeyUtils.deleteUserAccessToken(user.getPrimaryLoginToken(), "ak"))
					.thenReturn(true);

			Response response = resource.deleteUserAccessKey(jsonFrom(user, "{\"accessKey\":\"ak\"}"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true), body(response));
		}
	}

	@Test
	void answers400ForAnAccessKeyThatIsMissingUnknownOrUnreadable() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			Response missing = resource.deleteUserAccessKey(requestFor(user));
			Response unknown = resource.deleteUserAccessKey(jsonFrom(user, "{\"accessKey\":\"other\"}"));
			Response unreadable = resource.deleteUserAccessKey(jsonFrom(user, "{\"key\":\"ak\"}"));

			assertEquals(400, missing.getStatus());
			assertEquals("accessKey parameter is not defined", errorOf(missing));
			assertEquals(400, unknown.getStatus());
			assertEquals(Map.of("success", false), body(unknown));
			assertEquals(400, unreadable.getStatus());
			assertEquals("Error parsing JSON request body.", errorOf(unreadable));
		}
	}

	@Test
	void listsTheCallersOwnAccessKeys() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			statics.keys.when(() -> SecurityUserAccessKeyUtils.getUserAccessKeyInfo(user.getPrimaryLoginToken()))
					.thenReturn(List.of(Map.of("accessKey", "ak")));

			Response response = resource.getUserAccessKeys(requestFor(user));

			assertEquals(List.of(Map.of("accessKey", "ak")), body(response));
		}
	}

	private static void allowResetFor(Statics statics, String email) {
		when(statics.passwords.isAllowUserChangePassword()).thenReturn(true);
		when(statics.social.getSmtpEngine()).thenReturn(mock(SMTPFunctionEngine.class));
		statics.resets.when(() -> SecurityPasswordResetUtils.userEmailExists(email, "NATIVE")).thenReturn(true);
		statics.resets.when(() -> SecurityPasswordResetUtils.allowUserResetPassword(email, "NATIVE")).thenReturn("t-1");
		when(statics.email.sendPasswordResetRequestEmail(anyString(), anyString(), any())).thenReturn(true);
	}

	@Test
	void emailsAResetLinkToTheApplicationsOwnResetPage() throws Exception {
		HttpServletRequest request = anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}");
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");

			Response response = resource.setupResetPassword(null, request);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true, "message", RESET_REQUESTED), body(response));
			verify(statics.email).sendPasswordResetRequestEmail("bob@example.com", RESET_LINK,
					"SEMOSS Reset Password Request");
		}
	}

	@Test
	void ignoresTheCallersResetUrlSubjectAndRequestHost() throws Exception {
		HttpServletRequest json = anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\","
				+ "\"url\":\"https://attacker.example/steal\",\"subject\":\"URGENT: verify your account\"}");
		HttpServletRequest form = mock(HttpServletRequest.class);
		when(form.getContentType()).thenReturn("application/x-www-form-urlencoded");
		when(form.getParameter("email")).thenReturn("bob@example.com");
		when(form.getParameter("type")).thenReturn("NATIVE");
		when(form.getParameter("url")).thenReturn("https://attacker.example/steal");
		when(form.getParameter("subject")).thenReturn("URGENT: verify your account");
		for (HttpServletRequest request : List.of(json, form)) {
			when(request.getRequestURL())
					.thenReturn(new StringBuffer("https://attacker.example/Monolith/api/auth/user/setupResetPassword"));
			when(request.getContextPath()).thenReturn("/Monolith");
		}
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");

			resource.setupResetPassword(null, json);
			resource.setupResetPassword(null, form);

			verify(statics.email, Mockito.times(2)).sendPasswordResetRequestEmail("bob@example.com", RESET_LINK,
					"SEMOSS Reset Password Request");
		}
	}

	@Test
	void buildsTheSubjectFromTheActiveThemeName() throws Exception {
		Gson gson = new Gson();
		String doubleEncoded = gson.toJson(gson.toJson(Map.of("name", "Acme\r\nLabs")));
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");
			statics.themes.when(AdminThemeUtils::getActiveAdminTheme).thenReturn(Map.of("THEME_MAP", doubleEncoded),
					Map.of("THEME_MAP", "{not json"));

			resource.setupResetPassword(null, anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));
			resource.setupResetPassword(null, anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));

			verify(statics.email).sendPasswordResetRequestEmail("bob@example.com", RESET_LINK,
					"Acme Labs Reset Password Request");
			verify(statics.email).sendPasswordResetRequestEmail("bob@example.com", RESET_LINK,
					"SEMOSS Reset Password Request");
		}
	}

	@Test
	void answersTheSameWhetherOrNotTheAccountExists() throws Exception {
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");

			Response known = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));
			Response unknown = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"nobody@example.com\",\"type\":\"NATIVE\"}"));

			assertEquals(known.getStatus(), unknown.getStatus());
			assertEquals(body(known), body(unknown));
			statics.resets.verify(
					() -> SecurityPasswordResetUtils.allowUserResetPassword(eq("nobody@example.com"), anyString()),
					never());
			verify(statics.email, never()).sendPasswordResetRequestEmail(eq("nobody@example.com"), anyString(), any());
		}
	}

	@Test
	void dropsTheResetTokenAndAnswersTheSameWhenTheEmailCannotBeSent() throws Exception {
		HttpServletRequest request = anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}");
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");
			when(statics.email.sendPasswordResetRequestEmail(anyString(), anyString(), any())).thenReturn(false);

			Response response = resource.setupResetPassword(null, request);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true, "message", RESET_REQUESTED), body(response));
			statics.resets.verify(() -> SecurityPasswordResetUtils.deleteToken("t-1"));
		}
	}

	@Test
	void answersBeforeTheResetTokenIsStoredOrTheEmailIsSent() throws Exception {
		List<Runnable> pending = new ArrayList<>();
		UserAuthorizationResource deferred = new UserAuthorizationResource(pending::add);
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");

			Response response = deferred.setupResetPassword(null,
					anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("success", true, "message", RESET_REQUESTED), body(response));
			statics.resets.verify(() -> SecurityPasswordResetUtils.allowUserResetPassword(anyString(), anyString()),
					never());
			verify(statics.email, never()).sendPasswordResetRequestEmail(anyString(), anyString(), any());

			assertEquals(1, pending.size());
			pending.get(0).run();

			verify(statics.email).sendPasswordResetRequestEmail("bob@example.com", RESET_LINK,
					"SEMOSS Reset Password Request");
		}
	}

	@Test
	void sendsResetEmailsOnAVirtualThread() throws Exception {
		CompletableFuture<Thread> ranOn = new CompletableFuture<>();

		new UserAuthorizationResource().passwordResetSender.execute(() -> ranOn.complete(Thread.currentThread()));

		Thread thread = ranOn.get(5, TimeUnit.SECONDS);
		assertTrue(thread.isVirtual());
		assertEquals("password-reset-email", thread.getName());
	}

	@Test
	void dropsTheResetTokenWhenSendingTheEmailThrows() throws Exception {
		try (Statics statics = new Statics(); MockedStatic<Utility> utility = applicationUrl()) {
			allowResetFor(statics, "bob@example.com");
			when(statics.email.sendPasswordResetRequestEmail(anyString(), anyString(), any()))
					.thenThrow(new IllegalStateException("smtp down"));

			Response response = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));

			assertEquals(200, response.getStatus());
			statics.resets.verify(() -> SecurityPasswordResetUtils.deleteToken("t-1"));
		}
	}

	@Test
	void rejectsAMissingEmailOrALoginTypeThatCannotResetPasswords() throws Exception {
		try (Statics statics = new Statics()) {
			when(statics.passwords.isAllowUserChangePassword()).thenReturn(true);

			Response noEmail = resource.setupResetPassword(null, anonymousJson("{\"type\":\"NATIVE\"}"));
			Response blankEmail = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"  \",\"type\":\"NATIVE\"}"));
			Response noType = resource.setupResetPassword(null, anonymousJson("{\"email\":\"bob@example.com\"}"));
			Response oauthType = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"MICROSOFT\"}"));

			for (Response response : List.of(noEmail, blankEmail)) {
				assertEquals(400, response.getStatus());
				assertEquals("An email is required to reset a password", errorOf(response));
			}
			for (Response response : List.of(noType, oauthType)) {
				assertEquals(400, response.getStatus());
				assertEquals("Passwords can only be reset for NATIVE, LDAP and LINOTP logins", errorOf(response));
			}
			statics.resets.verifyNoInteractions();
		}
	}

	@Test
	void answers500WhenNoMailServerIsConfigured() throws Exception {
		try (Statics statics = new Statics()) {
			when(statics.passwords.isAllowUserChangePassword()).thenReturn(true);

			Response response = resource.setupResetPassword(null,
					anonymousJson("{\"email\":\"bob@example.com\",\"type\":\"NATIVE\"}"));

			assertEquals(500, response.getStatus());
			assertEquals("Password reset emails are not available on this server", errorOf(response));
			statics.resets.verifyNoInteractions();
		}
	}

	@Test
	void refusesPasswordResetsWhenOnlyAdminsChangePasswords() throws Exception {
		try (Statics statics = new Statics()) {
			Response setup = resource.setupResetPassword(null, anonymousJson("{\"email\":\"bob@example.com\"}"));
			Response reset = resource.resetPassword(null, anonymousJson("{\"token\":\"t-1\",\"password\":\"x\"}"));

			assertEquals(401, setup.getStatus());
			assertEquals("Only the administrator is allowed to change the user password", errorOf(setup));
			assertEquals(401, reset.getStatus());
			statics.resets.verifyNoInteractions();
		}
	}

	@Test
	void resetsAPasswordWithAResetToken() throws Exception {
		try (Statics statics = new Statics()) {
			when(statics.passwords.isAllowUserChangePassword()).thenReturn(true);
			statics.resets.when(() -> SecurityPasswordResetUtils.userResetPassword("t-1", "N3w!pass"))
					.thenReturn(Map.of("userId", "bob", "email", "bob@example.com"));

			Response response = resource.resetPassword(null,
					anonymousJson("{\"token\":\"t-1\",\"password\":\"N3w!pass\",\"sender\":\"Team\"}"));

			assertEquals(200, response.getStatus());
			Map<?, ?> payload = (Map<?, ?>) body(response);
			assertEquals(true, payload.get("success"));
			assertEquals("bob", payload.get("userId"));
			verify(statics.email).sendPasswordResetSuccessEmail("bob@example.com", "Team");
		}
	}

	@Test
	void refusesAnInvalidResetTokenWith401() throws Exception {
		try (Statics statics = new Statics()) {
			when(statics.passwords.isAllowUserChangePassword()).thenReturn(true);
			statics.resets.when(() -> SecurityPasswordResetUtils.userResetPassword("bad", "x"))
					.thenThrow(new IllegalArgumentException("The token is not valid"));

			Response response = resource.resetPassword(null, anonymousJson("{\"token\":\"bad\",\"password\":\"x\"}"));

			assertEquals(401, response.getStatus());
			assertEquals("The token is not valid", errorOf(response));
			verify(statics.email, never()).sendPasswordResetSuccessEmail(any(), any());
		}
	}

	@Test
	void setsTheCallersOwnMetadataFromJson() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			Response single = resource.setUserMetadata(jsonFrom(user, "{\"metaKey\":\"team\",\"metaValue\":\"hr\"}"));
			Response list = resource
					.setUserMetadata(jsonFrom(user, "{\"metaKey\":\"tags\",\"metaValue\":[\"a\",\"b\"]}"));

			assertEquals(200, single.getStatus());
			assertEquals(Map.of("userMetadata", "Metadata team updated to hr"), body(single));
			assertEquals(200, list.getStatus());
			statics.users.verify(() -> SecurityUserUtils.updateUserMetadata(user, "team", "hr"));
			statics.users.verify(() -> SecurityUserUtils.updateUserMetadata(user, "tags", List.of("a", "b")));
		}
	}

	@Test
	void setsTheCallersOwnMetadataFromAForm() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest request = requestFor(user);
		when(request.getParameterMap())
				.thenReturn(Map.of("metaKey", new String[] { "tags" }, "metaValue", new String[] { "a", "b" }));
		try (Statics statics = new Statics()) {
			Response response = resource.setUserMetadata(request);

			assertEquals(200, response.getStatus());
			statics.users.verify(() -> SecurityUserUtils.updateUserMetadata(user, "tags", List.of("a", "b")));
		}
	}

	@Test
	void requiresAMetadataKeyAndValue() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			Response noKey = resource.setUserMetadata(jsonFrom(user, "{\"metaValue\":\"hr\"}"));
			Response noValue = resource.setUserMetadata(jsonFrom(user, "{\"metaKey\":\"team\"}"));

			assertEquals(400, noKey.getStatus());
			assertEquals("Parameter metaKey cannot be null", errorOf(noKey));
			assertEquals(400, noValue.getStatus());
			assertEquals("Parameter metaValue cannot be null", errorOf(noValue));
			statics.users.verifyNoInteractions();
		}
	}

	@Test
	void changesTheCallersOwnNativePassword() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		HttpServletRequest request = jsonFrom(user, "{\"currentPassword\":\"old\",\"newPassword\":\"N3w!pass\"}");
		try (Statics statics = new Statics()) {
			statics.nativeUsers.when(() -> SecurityNativeUserUtils.isCurrentPassword("alice", AuthProvider.NATIVE,
					"old")).thenReturn(true);
			when(statics.passwords.validatePassword("N3w!pass")).thenReturn(true);

			Response response = resource.changePassword(null, request);

			assertEquals(200, response.getStatus());
			assertEquals("alice", ((Map<?, ?>) body(response)).get("userId"));
			statics.nativeUsers.verify(() -> SecurityNativeUserUtils.performResetPassword("alice", "N3w!pass"));
		}
	}

	@Test
	void refusesAWrongCurrentPasswordOrAWeakNewOne() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			statics.nativeUsers.when(() -> SecurityNativeUserUtils.isCurrentPassword("alice", AuthProvider.NATIVE,
					"right")).thenReturn(true);

			Response wrong = resource.changePassword(null,
					jsonFrom(user, "{\"currentPassword\":\"wrong\",\"newPassword\":\"N3w!pass\"}"));
			Response weak = resource.changePassword(null,
					jsonFrom(user, "{\"currentPassword\":\"right\",\"newPassword\":\"a\"}"));

			assertEquals(401, wrong.getStatus());
			assertEquals("The current password for the user is wrong. Try again.",
					((Map<?, ?>) body(wrong)).get("message"));
			assertEquals(401, weak.getStatus());
			assertEquals("The new password is not valid", ((Map<?, ?>) body(weak)).get("message"));
			statics.nativeUsers.verify(() -> SecurityNativeUserUtils.performResetPassword(anyString(), anyString()),
					never());
		}
	}

	@Test
	void requiresBothPasswordsToChangeOne() throws Exception {
		User user = signedIn("alice", AuthProvider.NATIVE);
		try (Statics statics = new Statics()) {
			Response response = resource.changePassword(null, jsonFrom(user, "{\"newPassword\":\"N3w!pass\"}"));

			assertEquals(400, response.getStatus());
			statics.nativeUsers.verifyNoInteractions();
		}
	}

	@Test
	void changesNoPasswordForALoginThatIsNotNative() throws Exception {
		User user = signedIn("alice", AuthProvider.MICROSOFT);
		try (Statics statics = new Statics()) {
			Response response = resource.changePassword(null,
					jsonFrom(user, "{\"currentPassword\":\"old\",\"newPassword\":\"N3w!pass\"}"));

			assertEquals("Can only change password for NATIVE logins", errorOf(response));
			statics.nativeUsers.verifyNoInteractions();
		}
	}

	private static User signedIn(String id, AuthProvider provider) {
		AccessToken token = new AccessToken();
		token.setProvider(provider);
		token.setId(id);
		token.setEmail(id + "@old.example.com");
		User user = new User();
		user.setAccessToken(token);
		return user;
	}

	private static HttpServletRequest requestFor(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/user");
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return request;
	}

	private static HttpServletRequest jsonFrom(User user, String json) throws Exception {
		HttpServletRequest request = requestFor(user);
		when(request.getContentType()).thenReturn("application/json");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
		return request;
	}

	private static HttpServletRequest anonymousJson(String json) throws Exception {
		HttpServletRequest request = signedOut();
		when(request.getContentType()).thenReturn("application/json");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
		return request;
	}

	private static HttpServletRequest signedOut() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/user");
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
	 * The Semoss statics and singletons these endpoints call, mocked for one test.
	 */
	private static final class Statics implements AutoCloseable {

		private final PasswordRequirements passwords = mock(PasswordRequirements.class);
		private final SocialPropertiesUtil social = mock(SocialPropertiesUtil.class);
		private final UserRegistrationEmailService email = mock(UserRegistrationEmailService.class);
		private final MockedStatic<UserAuditTrailUtils> audit = Mockito.mockStatic(UserAuditTrailUtils.class);
		private final MockedStatic<SecurityUserUtils> users = Mockito.mockStatic(SecurityUserUtils.class);
		private final MockedStatic<SecurityUserAccessKeyUtils> keys = Mockito
				.mockStatic(SecurityUserAccessKeyUtils.class);
		private final MockedStatic<SecurityPasswordResetUtils> resets = Mockito
				.mockStatic(SecurityPasswordResetUtils.class);
		private final MockedStatic<SecurityNativeUserUtils> nativeUsers = Mockito
				.mockStatic(SecurityNativeUserUtils.class);
		private final MockedStatic<PasswordRequirements> passwordRequirements = Mockito
				.mockStatic(PasswordRequirements.class);
		private final MockedStatic<SocialPropertiesUtil> socialProperties = Mockito
				.mockStatic(SocialPropertiesUtil.class);
		private final MockedStatic<UserRegistrationEmailService> emailService = Mockito
				.mockStatic(UserRegistrationEmailService.class);
		private final MockedStatic<AdminThemeUtils> themes = Mockito.mockStatic(AdminThemeUtils.class);

		private Statics() {
			passwordRequirements.when(PasswordRequirements::getInstance).thenReturn(passwords);
			socialProperties.when(SocialPropertiesUtil::getInstance).thenReturn(social);
			emailService.when(UserRegistrationEmailService::getInstance).thenReturn(email);
		}

		@Override
		public void close() {
			themes.close();
			emailService.close();
			socialProperties.close();
			passwordRequirements.close();
			nativeUsers.close();
			resets.close();
			keys.close();
			users.close();
			audit.close();
		}
	}
}
