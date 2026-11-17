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
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.SyncUserAssetsThread;
import prerna.auth.User;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityNativeUserUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.io.connector.GenericTokenFiller;
import prerna.usertracking.AuditErrorCode;
import prerna.usertracking.AuditEventType;
import prerna.usertracking.AuditTargetType;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.util.ldap.ILdapAuthenticator;
import prerna.util.ldap.LDAPPasswordChangeRequiredException;
import prerna.util.linotp.LinOTPResponse;
import prerna.util.linotp.LinOTPUtil;
import prerna.web.conf.DBLoader;
import prerna.web.conf.UserSessionLoader;

class UserResourceTest {

	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();

	private SocialPropertiesUtil social;
	private SocialPropertiesUtil originalSocial;
	private final Map<String, Boolean> loginsAllowed = new HashMap<>();
	private Statics statics;

	@BeforeEach
	void useMockSocialProperties() throws Exception {
		social = mock(SocialPropertiesUtil.class);
		when(social.getLoginsAllowed()).thenReturn(loginsAllowed);
		when(social.getProperty(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
		when(social.getProperty("redirect")).thenReturn("https://app.example.com/semoss-ui/");
		originalSocial = (SocialPropertiesUtil) socialField().get(null);
		socialField().set(null, social);
		statics = new Statics(social);
	}

	@AfterEach
	void restoreSocialProperties() throws Exception {
		statics.close();
		socialField().set(null, originalSocial);
	}

	private static Field socialField() throws Exception {
		Field field = UserResource.class.getDeclaredField("socialData");
		field.setAccessible(true);
		return field;
	}

	private static final class Statics implements AutoCloseable {
		final MockedStatic<SecurityUpdateUtils> update = mockStatic(SecurityUpdateUtils.class);
		final MockedStatic<SecurityUserUtils> users = mockStatic(SecurityUserUtils.class);
		final MockedStatic<UserTrackingUtils> tracking = mockStatic(UserTrackingUtils.class);
		final MockedStatic<UserAuditTrailUtils> audit = mockStatic(UserAuditTrailUtils.class);
		final MockedStatic<SocialPropertiesUtil> socialStatic = mockStatic(SocialPropertiesUtil.class);

		Statics(SocialPropertiesUtil social) {
			socialStatic.when(SocialPropertiesUtil::getInstance).thenReturn(social);
		}

		@Override
		public void close() {
			socialStatic.close();
			audit.close();
			tracking.close();
			users.close();
			update.close();
		}
	}

	private static User user(AuthProvider provider, String id, String name) {
		User user = new User();
		user.setAccessToken(token(provider, id, name));
		return user;
	}

	private static AccessToken token(AuthProvider provider, String id, String name) {
		AccessToken token = new AccessToken();
		token.setProvider(provider);
		token.setId(id);
		token.setName(name);
		return token;
	}

	private static HttpSession session(boolean isNew) {
		Map<String, Object> attributes = new HashMap<>();
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(UUID.randomUUID().toString());
		when(session.isNew()).thenReturn(isNew);
		when(session.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
		doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1))).when(session).setAttribute(anyString(),
				any());
		doAnswer(i -> attributes.remove(i.getArgument(0))).when(session).removeAttribute(anyString());
		return session;
	}

	private static HttpServletRequest request(HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		HttpSession created = session == null ? session(true) : session;
		when(request.getSession()).thenReturn(created);
		when(request.getSession(true)).thenReturn(created);
		when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/Monolith/api/auth/login"));
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/login");
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getScheme()).thenReturn("http");
		return request;
	}

	private static HttpServletRequest signedIn(User user) {
		HttpSession session = session(false);
		session.setAttribute(Constants.SESSION_USER, user);
		return request(session);
	}

	private static HttpServletRequest withParams(HttpServletRequest request, String... params) {
		for (int i = 0; i < params.length; i += 2) {
			when(request.getParameter(params[i])).thenReturn(params[i + 1]);
		}
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> json(Response response) throws IOException {
		return new Gson().fromJson(body(response), MAP_TYPE);
	}

	private static User sessionUser(HttpServletRequest request) {
		return (User) request.getSession().getAttribute(Constants.SESSION_USER);
	}

	@Test
	void getAllLoginsListsTheProviderNamesOfTheSignedInUser() throws Exception {
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane Doe");
		Response response = new UserResource().getAllLogins(signedIn(user));

		assertEquals(200, response.getStatus());
		assertEquals(Map.of("NATIVE", "Jane Doe"), json(response));
	}

	@Test
	void getAllLoginsExpiresTheSessionCookieAndDropsTheSessionWhenNoOneIsSignedIn() throws Exception {
		HttpSession session = session(true);
		HttpServletRequest request = request(session);
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("JSESSIONID", "abc") });

		Response response = new UserResource().getAllLogins(request);

		assertEquals(Map.of(), json(response));
		verify(session).invalidate();
		List<Object> cookies = response.getHeaders().get("Set-Cookie");
		assertNotNull(cookies);
		assertTrue(cookies.stream().allMatch(c -> c.toString().startsWith("JSESSIONID=;")), cookies.toString());
	}

	@Test
	void logoutRejectsASessionWithoutAUserWith400() throws Exception {
		Response response = new UserResource().logout("ALL", true, request(null), mock(HttpServletResponse.class));

		assertEquals(400, response.getStatus());
		assertEquals(false, json(response).get("success"));
	}

	@Test
	void logoutOfAllProvidersEndsTheSessionWithoutARedirectWhenAsked() throws Exception {
		HttpServletRequest request = signedIn(user(AuthProvider.NATIVE, "jdoe", "Jane"));
		HttpSession session = request.getSession(false);
		HttpServletResponse response = mock(HttpServletResponse.class);

		Response result = new UserResource().logout("ALL", true, request, response);

		assertEquals(200, result.getStatus());
		assertEquals(true, json(result).get("success"));
		assertEquals(true, session.getAttribute(UserSessionLoader.IS_USER_LOGOUT));
		verify(session).invalidate();
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void logoutOfTheLastProviderRedirectsToTheCustomLogoutUrl() throws Exception {
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane");
		HttpServletRequest request = signedIn(user);
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::getCustomLogoutUrl).thenReturn("https://sso.example.com/out");
			loader.when(DBLoader::getSessionIdKey).thenReturn("JSESSIONID");
			Response result = new UserResource().logout("native", false, request, response);

			assertEquals(true, json(result).get("success"));
		}
		assertTrue(user.getLogins().isEmpty());
		verify(response).setHeader("redirect", "https://sso.example.com/out");
		verify(response).sendError(302, "Need to redirect to https://sso.example.com/out");
		verify(request.getSession(false)).invalidate();
	}

	@Test
	void logoutOfOneOfTwoProvidersKeepsTheSession() throws Exception {
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane");
		user.setAccessToken(token(AuthProvider.GITHUB, "gh-1", "Jane"));
		HttpServletRequest request = signedIn(user);
		HttpSession session = request.getSession(false);
		try (MockedConstruction<SyncUserAssetsThread> sync = mockConstruction(SyncUserAssetsThread.class)) {
			Response result = new UserResource().logout("github", true, request, mock(HttpServletResponse.class));

			assertEquals(true, json(result).get("success"));
			assertEquals(List.of(AuthProvider.NATIVE), user.getLogins());
			assertSame(user, session.getAttribute(Constants.SESSION_USER));
			assertNotNull(session.getAttribute(Constants.USER_ASSET_IDS));
			verify(session, never()).invalidate();
		}
	}

	@Test
	void userinfoPromptsForALoginWhenNoOneIsSignedIn() throws Exception {
		HttpSession session = session(true);
		Response response = new UserResource().userinfoGeneric("github", request(session));

		assertEquals(200, response.getStatus());
		assertEquals("Log into your " + AuthProvider.GITHUB.getDisplayName() + " account",
				json(response).get(Constants.ERROR_MESSAGE));
		verify(session).invalidate();
	}

	@Test
	void userinfoPromptsForALoginWhenTheUserLacksThatProvider() throws Exception {
		Response response = new UserResource().userinfoGeneric("github",
				signedIn(user(AuthProvider.NATIVE, "jdoe", "Jane")));
		assertEquals("Log into your " + AuthProvider.GITHUB.getDisplayName() + " account",
				json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void loginGenericRejectsAProviderThatIsNotAllowedWith400() throws Exception {
		Response response = new UserResource().loginGeneric("acme", request(null), mock(HttpServletResponse.class));

		assertEquals(400, response.getStatus());
		assertEquals("acme login is not allowed", json(response).get(Constants.ERROR_MESSAGE));
	}

	private static final class Provider implements AutoCloseable {
		final ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
		final ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
		final MockedConstruction<GenericTokenFiller> fillers;
		AccessToken exchanged;
		boolean pkce;

		Provider() {
			fillers = mockConstruction(GenericTokenFiller.class, (filler, context) -> {
				when(filler.usesPKCE()).thenAnswer(i -> pkce);
				when(filler.buildAuthorizeRedirect(eq("acme_"), state.capture(), challenge.capture()))
						.thenReturn("https://idp.example.com/authorize");
				when(filler.exchangeCodeForToken(eq("acme_"), anyString(), any())).thenAnswer(i -> exchanged);
			});
		}

		GenericTokenFiller last() {
			List<GenericTokenFiller> made = fillers.constructed();
			return made.get(made.size() - 1);
		}

		@Override
		public void close() {
			fillers.close();
		}
	}

	private HttpServletRequest callback(HttpSession session, String code, String state) {
		HttpServletRequest request = withParams(request(session), "code", code, "state", state);
		when(request.getQueryString()).thenReturn("code=" + code + "&state=" + state);
		return request;
	}

	@Test
	void loginGenericSendsANewUserToTheProviderAndRemembersTheRedirectChoices() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(true);
		HttpServletRequest request = withParams(request(session), "redirect", "https://app.example.com/x",
				"disableRedirect", "true");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (Provider provider = new Provider()) {
			assertNull(new UserResource().loginGeneric("acme", request, response));

			verify(response).setStatus(302);
			verify(response).sendRedirect("https://idp.example.com/authorize");
			verify(provider.last()).setDefaultRedirectUri(argThat(uri -> uri.endsWith("/Monolith/api/auth/login")));
			assertNull(provider.challenge.getValue());
			assertNotNull(provider.state.getValue());
			assertEquals("https://app.example.com/x", session.getAttribute("custom_redirect"));
			assertEquals(Boolean.TRUE, session.getAttribute("oauth_disable_redirect"));
		}
	}

	@Test
	void loginGenericExchangesTheCodeSignsTheUserInAndRedirectsToTheApp() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			provider.exchanged = token(AuthProvider.GENERIC, "acme-1", "Ann");

			HttpServletRequest callback = callback(session, "abc", provider.state.getValue());
			HttpServletResponse response = mock(HttpServletResponse.class);
			assertNull(new UserResource().loginGeneric("acme", callback, response));

			GenericTokenFiller filler = provider.last();
			verify(filler).exchangeCodeForToken("acme_", "abc", null);
			verify(filler).fillAccessToken(provider.exchanged, "acme_");
			verify(filler).fillUserGroups(provider.exchanged, "acme_");
			statics.update.verify(() -> SecurityUpdateUtils.addOAuthUser(provider.exchanged));
			statics.update.verify(() -> SecurityUpdateUtils.validateUserLogin(provider.exchanged));
			User user = sessionUser(callback);
			assertEquals("acme-1", user.getAccessToken(AuthProvider.GENERIC).getId());
			verify(callback).changeSessionId();
			verify(response).sendRedirect("https://app.example.com/semoss-ui/");
			verify(response).addHeader(eq("Set-Cookie"),
					argThat(c -> c.startsWith("JSESSIONID=" + session.getId() + "; Path=/Monolith; HttpOnly")));
		}
	}

	@Test
	void loginGenericReplaysThePkceVerifierItIssued() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			provider.pkce = true;
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			String verifier = (String) session.getAttribute("acme_pkce_code_verifier");
			assertNotNull(verifier);
			assertNotNull(provider.challenge.getValue());
			assertFalse(verifier.equals(provider.challenge.getValue()));

			provider.exchanged = token(AuthProvider.GENERIC, "acme-1", "Ann");
			new UserResource().loginGeneric("acme", callback(session, "abc", provider.state.getValue()),
					mock(HttpServletResponse.class));

			verify(provider.last()).exchangeCodeForToken("acme_", "abc", verifier);
		}
	}

	@Test
	void loginGenericSendsTheUserBackToTheProviderWhenTheExchangeFails() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			HttpServletResponse response = mock(HttpServletResponse.class);
			new UserResource().loginGeneric("acme", callback(session, "abc", provider.state.getValue()), response);

			verify(response).sendRedirect("https://idp.example.com/authorize");
			assertNull(session.getAttribute(Constants.SESSION_USER));
		}
	}

	@Test
	void loginGenericNeverExchangesACodeThatIsNotPrintableAscii() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			HttpServletResponse response = mock(HttpServletResponse.class);
			new UserResource().loginGeneric("acme", callback(session, "%C3%A9", provider.state.getValue()),
					response);

			verify(provider.last(), never()).exchangeCodeForToken(anyString(), anyString(), any());
			verify(response).sendRedirect("https://idp.example.com/authorize");
		}
	}

	@Test
	void loginGenericEndsAPopupLoginOnAPageThatClosesItself() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", withParams(request(session), "disableRedirect", "true"),
					mock(HttpServletResponse.class));
			provider.exchanged = token(AuthProvider.GENERIC, "acme-1", "Ann");
			HttpServletResponse response = mock(HttpServletResponse.class);
			StringWriter page = new StringWriter();
			when(response.getWriter()).thenReturn(new PrintWriter(page));

			new UserResource().loginGeneric("acme", callback(session, "abc", provider.state.getValue()), response);

			verify(response).setStatus(200);
			verify(response).setContentType("text/html;charset=UTF-8");
			verify(response).flushBuffer();
			verify(response, never()).sendRedirect(anyString());
			ArgumentCaptor<String> csp = ArgumentCaptor.forClass(String.class);
			verify(response).setHeader(eq("Content-Security-Policy"), csp.capture());
			Matcher nonce = Pattern.compile("'nonce-([^']+)'").matcher(csp.getValue());
			assertTrue(nonce.find());
			assertTrue(page.toString().contains("<script nonce=\"" + nonce.group(1) + "\">window.close();</script>"));
			assertNull(session.getAttribute("oauth_disable_redirect"));
		}
	}

	private void assertStateRefused(Response response) throws IOException {
		assertEquals(400, response.getStatus());
		assertEquals("The login could not be verified. Please start the login again.",
				json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void loginGenericKeepsTheStateItSendsInTheSession() throws Exception {
		loginsAllowed.put("acme", true);
		HttpServletRequest request = request(null);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request, mock(HttpServletResponse.class));

			assertEquals(provider.state.getValue(), request.getSession().getAttribute("acme_oauth_state"));
		}
	}

	@Test
	void loginGenericRefusesACallbackWithAStateItDidNotIssue() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			provider.exchanged = token(AuthProvider.GENERIC, "attacker", "Mallory");

			Response response = new UserResource().loginGeneric("acme", callback(session, "abc", "forged"),
					mock(HttpServletResponse.class));

			assertStateRefused(response);
			verify(provider.last(), never()).exchangeCodeForToken(anyString(), anyString(), any());
			assertNull(session.getAttribute(Constants.SESSION_USER));
			statics.audit.verify(() -> UserAuditTrailUtils.recordLoginFailed(isNull(),
					eq(AuthProvider.GENERIC.getLabel()), eq(AuditErrorCode.VALIDATION_FAILED), anyString(), any(),
					eq(400)));
		}
	}

	@Test
	void loginGenericRefusesACallbackForALoginTheSessionNeverStarted() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			provider.exchanged = token(AuthProvider.GENERIC, "attacker", "Mallory");

			Response response = new UserResource().loginGeneric("acme", callback(session, "abc", "any"),
					mock(HttpServletResponse.class));

			assertStateRefused(response);
			verify(provider.last(), never()).exchangeCodeForToken(anyString(), anyString(), any());
			assertNull(session.getAttribute(Constants.SESSION_USER));
		}
	}

	@Test
	void loginGenericNeverLinksAnUnrequestedLoginToASignedInUser() throws Exception {
		loginsAllowed.put("acme", true);
		User victim = user(AuthProvider.NATIVE, "jdoe", "Jane");
		HttpServletRequest signedIn = signedIn(victim);
		HttpSession session = signedIn.getSession();
		try (Provider provider = new Provider()) {
			provider.exchanged = token(AuthProvider.GENERIC, "attacker", "Mallory");

			Response response = new UserResource().loginGeneric("acme", callback(session, "abc", "any"),
					mock(HttpServletResponse.class));

			assertStateRefused(response);
			assertNull(victim.getAccessToken(AuthProvider.GENERIC));
			assertSame(victim, session.getAttribute(Constants.SESSION_USER));
		}
	}

	@Test
	void loginGenericAcceptsEachStateOnce() throws Exception {
		loginsAllowed.put("acme", true);
		HttpSession session = session(false);
		try (Provider provider = new Provider()) {
			new UserResource().loginGeneric("acme", request(session), mock(HttpServletResponse.class));
			String first = provider.state.getValue();
			// the exchange fails, so the login is sent back to the provider with a new state
			new UserResource().loginGeneric("acme", callback(session, "abc", first), mock(HttpServletResponse.class));
			provider.exchanged = token(AuthProvider.GENERIC, "attacker", "Mallory");

			Response replay = new UserResource().loginGeneric("acme", callback(session, "def", first),
					mock(HttpServletResponse.class));

			assertStateRefused(replay);
			assertNull(session.getAttribute(Constants.SESSION_USER));
		}
	}

	@Test
	void loginNativeRejectsALoginWhenNativeLoginIsOffWith400() throws Exception {
		Response response = new UserResource().loginNative(withParams(request(null), "username", "jdoe"),
				mock(HttpServletResponse.class));

		assertEquals(400, response.getStatus());
		assertEquals("Native login is not allowed", json(response).get(Constants.ERROR_MESSAGE));
		statics.audit.verify(() -> UserAuditTrailUtils.recordLoginFailed(eq("jdoe"), anyString(),
				eq(AuditErrorCode.LOGIN_METHOD_DISABLED), anyString(), any(), eq(400)));
	}

	@Test
	void loginNativeRejectsEmptyCredentialsWith401() throws Exception {
		loginsAllowed.put("native", true);
		for (String password : new String[] { null, "" }) {
			Response response = new UserResource().loginNative(
					withParams(request(null), "username", "jdoe", "password", password), mock(HttpServletResponse.class));
			assertEquals(401, response.getStatus());
			assertEquals("The user name or password are empty", json(response).get(Constants.ERROR_MESSAGE));
		}
		statics.audit.verify(() -> UserAuditTrailUtils.recordLoginFailed(eq("jdoe"), anyString(),
				eq(AuditErrorCode.MISSING_CREDENTIALS), anyString(), any(), eq(401)), times(2));
	}

	@Test
	void loginNativeRejectsWrongCredentialsWith401AndDropsAnEmptySession() throws Exception {
		loginsAllowed.put("native", true);
		HttpServletRequest request = signedIn(new User());
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class)) {
			nativeUsers.when(() -> SecurityNativeUserUtils.logIn("jdoe", "wrong")).thenReturn(false);
			Response response = new UserResource().loginNative(
					withParams(request, "username", "jdoe", "password", "wrong"), mock(HttpServletResponse.class));

			assertEquals(401, response.getStatus());
			assertEquals("The user name or password are invalid.", json(response).get(Constants.ERROR_MESSAGE));
		}
		verify(request.getSession(false)).invalidate();
		statics.audit.verify(() -> UserAuditTrailUtils.recordLoginFailed(eq("jdoe"), anyString(),
				eq(AuditErrorCode.INVALID_CREDENTIALS), anyString(), any(), eq(401)));
	}

	@Test
	void loginNativeKeepsAnExistingLoginWhenANativeLoginFails() throws Exception {
		loginsAllowed.put("native", true);
		HttpServletRequest request = signedIn(user(AuthProvider.GITHUB, "gh-1", "Jane"));
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class)) {
			new UserResource().loginNative(withParams(request, "username", "jdoe", "password", "wrong"),
					mock(HttpServletResponse.class));
		}
		verify(request.getSession(false), never()).invalidate();
	}

	private static MockedStatic<SecurityNativeUserUtils> validNativeUser() {
		MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class);
		nativeUsers.when(() -> SecurityNativeUserUtils.logIn("jdoe", "secret")).thenReturn(true);
		nativeUsers.when(() -> SecurityNativeUserUtils.getNameUser("jdoe")).thenReturn("Jane Doe");
		nativeUsers.when(() -> SecurityNativeUserUtils.getUserEmail("jdoe")).thenReturn("jane@example.com");
		nativeUsers.when(() -> SecurityNativeUserUtils.getUserId("jdoe")).thenReturn("jdoe-id");
		return nativeUsers;
	}

	@Test
	void loginNativeSignsTheUserInWithANewSessionId() throws Exception {
		loginsAllowed.put("native", true);
		HttpServletRequest request = withParams(request(session(false)), "username", "jdoe", "password", "secret",
				"disableRedirect", "true");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = validNativeUser()) {
			Response result = new UserResource().loginNative(request, response);

			assertEquals(200, result.getStatus());
			assertEquals(Map.of("success", "true", "username", "jdoe", "name", "Jane Doe", "email",
					"jane@example.com"), json(result));
		}
		AccessToken token = sessionUser(request).getAccessToken(AuthProvider.NATIVE);
		assertEquals("jdoe-id", token.getId());
		assertEquals("jdoe", token.getUsername());
		verify(request).changeSessionId();
		statics.update.verify(() -> SecurityUpdateUtils.addOAuthUser(any()), never());
		statics.update.verify(() -> SecurityUpdateUtils.validateUserLogin(any()));
		statics.tracking.verify(() -> UserTrackingUtils.registerLogin(anyString(), any(), any(), eq(AuthProvider.NATIVE)));
		verify(response, never()).sendRedirect(any());
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void loginNativeAddsTheLoginToAnExistingUserWithoutChangingTheSessionId() throws Exception {
		loginsAllowed.put("native", true);
		User user = user(AuthProvider.GITHUB, "gh-1", "Jane");
		HttpServletRequest request = withParams(signedIn(user), "username", "jdoe", "password", "secret",
				"disableRedirect", "true");
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = validNativeUser()) {
			new UserResource().loginNative(request, mock(HttpServletResponse.class));
		}
		assertSame(user, sessionUser(request));
		assertEquals(List.of(AuthProvider.GITHUB, AuthProvider.NATIVE), user.getLogins());
		verify(request, never()).changeSessionId();
	}

	@Test
	void loginNativeRedirectsToTheRequestedPageThroughTheRedirectHeader() throws Exception {
		loginsAllowed.put("native", true);
		HttpServletRequest request = withParams(request(session(true)), "username", "jdoe", "password", "secret",
				"redirect", "https://app.example.com/semoss-ui/#!/home");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = validNativeUser()) {
			new UserResource().loginNative(request, response);
		}
		verify(response).setStatus(302);
		verify(response).addHeader("redirect", "https://app.example.com/semoss-ui/#!/home");
		verify(response).sendError(302, "Need to redirect to https://app.example.com/semoss-ui/#!/home");
		verify(request, never()).changeSessionId();
	}

	@Test
	void loginNativeRedirectsToTheEndpointTheUserFirstAskedFor() throws Exception {
		loginsAllowed.put("native", true);
		HttpSession session = session(false);
		session.setAttribute(Constants.ENDPOINT_REDIRECT_KEY, "http://localhost:8080/Monolith/api/engine/all");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = validNativeUser()) {
			new UserResource().loginNative(
					withParams(request(session), "username", "jdoe", "password", "secret"), response);
		}
		verify(response).addHeader("redirect", "http://localhost:8080/Monolith/api/engine/all");
	}

	@Test
	void loginNativeOtherwiseRedirectsToTheConfiguredAppAndMarksTheCookieSecureOverHttps() throws Exception {
		loginsAllowed.put("native", true);
		HttpServletRequest request = withParams(request(session(false)), "username", "jdoe", "password", "secret");
		when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = validNativeUser()) {
			new UserResource().loginNative(request, response);
		}
		verify(response).sendRedirect("https://app.example.com/semoss-ui/");
		verify(response).addHeader(eq("Set-Cookie"), argThat(c -> c.contains("; HttpOnly; Secure; SameSite=")));
	}

	@Test
	void loginNativeAnswers500WithAGenericMessageWhenTheLoginFails() throws Exception {
		loginsAllowed.put("native", true);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class)) {
			nativeUsers.when(() -> SecurityNativeUserUtils.logIn(anyString(), anyString()))
					.thenThrow(new IllegalStateException("db down"));
			Response response = new UserResource().loginNative(
					withParams(request(null), "username", "jdoe", "password", "secret"), mock(HttpServletResponse.class));

			assertEquals(500, response.getStatus());
			assertEquals("An unexpected error happened. Please try again.", json(response).get(Constants.ERROR_MESSAGE));
		}
		statics.audit.verify(() -> UserAuditTrailUtils.recordLoginFailed(eq("jdoe"), anyString(),
				eq(AuditErrorCode.INTERNAL_ERROR), eq("db down"), any(), eq(500)));
	}

	@Test
	void loginLdapRejectsALoginWhenLdapIsOffOrTheCredentialsAreEmpty() throws Exception {
		Response off = new UserResource().loginLDAP(request(null), mock(HttpServletResponse.class));
		assertEquals(400, off.getStatus());
		assertEquals("LDAP login is not allowed", json(off).get(Constants.ERROR_MESSAGE));

		loginsAllowed.put("ldap", true);
		Response empty = new UserResource().loginLDAP(withParams(request(null), "username", "jdoe"),
				mock(HttpServletResponse.class));
		assertEquals(401, empty.getStatus());
		verify(social, never()).getLdapAuthenticator();
	}

	@Test
	void loginLdapSignsTheUserInAndClosesTheAuthenticator() throws Exception {
		loginsAllowed.put("ldap", true);
		ILdapAuthenticator authenticator = mock(ILdapAuthenticator.class);
		when(social.getLdapAuthenticator()).thenReturn(authenticator);
		when(social.getProperty(ILdapAuthenticator.LDAP + "auto_add", "true")).thenReturn("false");
		AccessToken token = token(AuthProvider.LDAP, "jdoe", "Jane");
		when(authenticator.authenticate("jdoe", "secret")).thenReturn(token);
		HttpServletRequest request = withParams(request(session(true)), "username", "jdoe", "password", "secret",
				"disableRedirect", "true");

		Response response = new UserResource().loginLDAP(request, mock(HttpServletResponse.class));

		assertEquals(200, response.getStatus());
		assertEquals(Map.of("success", "true", "username", "jdoe"), json(response));
		assertEquals("jdoe", sessionUser(request).getAccessToken(AuthProvider.LDAP).getId());
		statics.update.verify(() -> SecurityUpdateUtils.addOAuthUser(any()), never());
		verify(authenticator).close();
	}

	@Test
	void loginLdapAsksForAPasswordChangeWith401() throws Exception {
		loginsAllowed.put("ldap", true);
		ILdapAuthenticator authenticator = mock(ILdapAuthenticator.class);
		when(social.getLdapAuthenticator()).thenReturn(authenticator);
		when(authenticator.authenticate("jdoe", "old")).thenThrow(new LDAPPasswordChangeRequiredException("expired"));

		Response response = new UserResource().loginLDAP(
				withParams(request(null), "username", "jdoe", "password", "old"), mock(HttpServletResponse.class));

		assertEquals(401, response.getStatus());
		Map<String, Object> body = json(response);
		assertEquals(true, body.get(ILdapAuthenticator.LDAP_PASSWORD_CHANGE_RETURN_KEY));
		assertEquals("User must change their password before login", body.get(Constants.ERROR_MESSAGE));
		verify(authenticator).close();
	}

	@Test
	void loginLdapReportsAnAuthenticatorFailureAndClosesIt() throws Exception {
		loginsAllowed.put("ldap", true);
		ILdapAuthenticator authenticator = mock(ILdapAuthenticator.class);
		when(social.getLdapAuthenticator()).thenReturn(authenticator);

		Response response = new UserResource().loginLDAP(
				withParams(request(null), "username", "jdoe", "password", "x"), mock(HttpServletResponse.class));

		assertEquals("Unable to parse any user attributes", json(response).get(Constants.ERROR_MESSAGE));
		verify(authenticator).close();
	}

	@Test
	void changeAdPasswordIsOffUnlessLdapOrLinOtpAdChecksAreOn() throws Exception {
		Response response = new UserResource().changeADPassword(request(null), mock(HttpServletResponse.class));
		assertEquals(400, response.getStatus());
		assertEquals("LDAP change password is not allowed/configured", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void changeAdPasswordNeedsEveryField() throws Exception {
		when(social.getProperty("linotp_check_ad", "false")).thenReturn("true");
		Response response = new UserResource().changeADPassword(
				withParams(request(null), "username", "jdoe", "curPassword", "old"), mock(HttpServletResponse.class));
		assertEquals(401, response.getStatus());
	}

	@Test
	void changeAdPasswordUpdatesThePasswordAndClosesTheAuthenticator() throws Exception {
		loginsAllowed.put("ldap", true);
		ILdapAuthenticator authenticator = mock(ILdapAuthenticator.class);
		when(social.getLdapAuthenticator()).thenReturn(authenticator);
		HttpServletRequest request = withParams(request(null), "username", "jdoe", "curPassword", "old",
				"newPassword", "new");

		Response response = new UserResource().changeADPassword(request, mock(HttpServletResponse.class));

		assertEquals(200, response.getStatus());
		verify(authenticator).updateUserPassword("jdoe", "old", "new");
		verify(authenticator).close();
	}

	@Test
	void changeAdPasswordAnswers500WhenTheDirectoryRefuses() throws Exception {
		loginsAllowed.put("ldap", true);
		ILdapAuthenticator authenticator = mock(ILdapAuthenticator.class);
		when(social.getLdapAuthenticator()).thenReturn(authenticator);
		doThrow(new IllegalStateException("too short")).when(authenticator).updateUserPassword("jdoe", "old", "new");

		Response response = new UserResource().changeADPassword(
				withParams(request(null), "username", "jdoe", "curPassword", "old", "newPassword", "new"),
				mock(HttpServletResponse.class));

		assertEquals(500, response.getStatus());
		assertEquals("too short", json(response).get(Constants.ERROR_MESSAGE));
		verify(authenticator).close();
	}

	private static LinOTPResponse linOtp(int code, AccessToken token) {
		LinOTPResponse response = new LinOTPResponse();
		Map<String, Object> map = new HashMap<>();
		map.put("step", code);
		response.setReturnMap(map);
		response.setResponseCode(code);
		response.setToken(token);
		return response;
	}

	@Test
	void loginLinOtpRejectsALoginWhenLinOtpIsOffWith400() throws Exception {
		Response response = new UserResource().loginLinOTP(request(null), mock(HttpServletResponse.class));
		assertEquals(400, response.getStatus());
		assertEquals("LinOTP login is not allowed", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void loginLinOtpPassesThroughAFailureAndAPendingSecondStep() throws Exception {
		loginsAllowed.put("linotp", true);
		HttpServletRequest request = request(null);
		try (MockedStatic<LinOTPUtil> linotp = mockStatic(LinOTPUtil.class)) {
			linotp.when(() -> LinOTPUtil.login(request)).thenReturn(linOtp(403, null), linOtp(200, null));

			assertEquals(403, new UserResource().loginLinOTP(request, mock(HttpServletResponse.class)).getStatus());
			Response pending = new UserResource().loginLinOTP(request, mock(HttpServletResponse.class));
			assertEquals(200, pending.getStatus());
			assertEquals(200.0, json(pending).get("step"));
		}
		assertNull(request.getSession(false));
		statics.users.verifyNoInteractions();
	}

	@Test
	void loginLinOtpSignsTheUserInOnceTheTokenArrives() throws Exception {
		loginsAllowed.put("linotp", true);
		HttpServletRequest request = withParams(request(session(true)), "disableRedirect", "true");
		AccessToken token = token(AuthProvider.LINOTP, "jdoe", "Jane");
		try (MockedStatic<LinOTPUtil> linotp = mockStatic(LinOTPUtil.class)) {
			linotp.when(() -> LinOTPUtil.login(request)).thenReturn(linOtp(200, token));
			assertEquals(200, new UserResource().loginLinOTP(request, mock(HttpServletResponse.class)).getStatus());
		}
		assertEquals("jdoe", sessionUser(request).getAccessToken(AuthProvider.LINOTP).getId());
		statics.update.verify(() -> SecurityUpdateUtils.addOAuthUser(token));
	}

	@Test
	void resetLinOtpFailCounterPassesThroughTheResultAndAnswers500OnFailure() throws Exception {
		HttpServletRequest request = request(null);
		try (MockedStatic<LinOTPUtil> linotp = mockStatic(LinOTPUtil.class)) {
			linotp.when(() -> LinOTPUtil.resetCounter(request)).thenReturn(linOtp(202, null))
					.thenThrow(new IOException("unreachable"));

			assertEquals(202, new UserResource().resetLinOTPFailCounter(request, null).getStatus());
			Response failed = new UserResource().resetLinOTPFailCounter(request, null);
			assertEquals(500, failed.getStatus());
			assertEquals("Error occurred resetting the pin. Error message = unreachable",
					json(failed).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void createUserNeedsNativeLoginAndRegistrationTurnedOn() throws Exception {
		Response off = new UserResource().createNativeUser(request(null));
		assertEquals(400, off.getStatus());
		assertEquals("Native login is not allowed", json(off).get(Constants.ERROR_MESSAGE));

		loginsAllowed.put("native", true);
		Response closed = new UserResource().createNativeUser(request(null));
		assertEquals(400, closed.getStatus());
		assertEquals("Native registration is not allowed", json(closed).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void createUserRegistersTheNativeUserWithTheUsernameAsId() throws Exception {
		loginsAllowed.put("native", true);
		loginsAllowed.put("registration", true);
		HttpServletRequest request = withParams(request(null), "username", "jdoe", "name", "Jane", "password",
				"secret", "email", "jane@example.com", "phone", "555");
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class)) {
			ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
			nativeUsers.when(() -> SecurityNativeUserUtils.addNativeUser(token.capture(), eq("secret")))
					.thenReturn(true, false);

			Response created = new UserResource().createNativeUser(request);
			assertEquals(200, created.getStatus());
			assertEquals(Map.of("success", "true", "username", "jdoe"), json(created));
			assertEquals(AuthProvider.NATIVE, token.getValue().getProvider());
			assertEquals("jdoe", token.getValue().getId());
			assertEquals("jane@example.com", token.getValue().getEmail());

			Response exists = new UserResource().createNativeUser(request);
			assertEquals(400, exists.getStatus());
		}
	}

	@Test
	void createUserReportsAValidationMessageButHidesUnexpectedFailures() throws Exception {
		loginsAllowed.put("native", true);
		loginsAllowed.put("registration", true);
		try (MockedStatic<SecurityNativeUserUtils> nativeUsers = mockStatic(SecurityNativeUserUtils.class)) {
			nativeUsers.when(() -> SecurityNativeUserUtils.addNativeUser(any(), any()))
					.thenThrow(new IllegalArgumentException("Password is too short"))
					.thenThrow(new IllegalStateException("db down"));

			HttpServletRequest request = withParams(request(null), "username", "jdoe", "password", "x");
			assertEquals("Password is too short",
					json(new UserResource().createNativeUser(request)).get(Constants.ERROR_MESSAGE));
			Response unexpected = new UserResource().createNativeUser(request);
			assertEquals(500, unexpected.getStatus());
			assertEquals("An unexpected error happened. Please try again.",
					json(unexpected).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void createApiUserNeedsApiUsersTurnedOn() throws Exception {
		Response response = new UserResource().createAPIUser(request(null));
		assertEquals(400, response.getStatus());
		assertEquals("API User is not allowed for login", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void createApiUserIsForAdminsWhenOnlyAdminsMayCreateThem() throws Exception {
		loginsAllowed.put("api_user", true);
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane");
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityAPIUserUtils> api = mockStatic(SecurityAPIUserUtils.class)) {
			utility.when(Utility::getApplicationAdminOnlyCreateAPIUser).thenReturn(true);
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false);

			Response anonymous = new UserResource().createAPIUser(request(null));
			assertEquals(401, anonymous.getStatus());
			assertEquals("No active session. Please login as an admin", json(anonymous).get(Constants.ERROR_MESSAGE));
			Response nonAdmin = new UserResource().createAPIUser(signedIn(user));
			assertEquals("User is not an admin and does not have access. Please login as an admin",
					json(nonAdmin).get(Constants.ERROR_MESSAGE));
			api.verifyNoInteractions();

			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			api.when(() -> SecurityAPIUserUtils.createAPIUser("bot")).thenReturn(Map.of("key", "k", "secret", "s"));
			Response created = new UserResource().createAPIUser(withParams(signedIn(user), "name", "bot"));
			assertEquals(200, created.getStatus());
			assertEquals(Map.of("key", "k", "secret", "s"), json(created));
		}
	}

	@Test
	void loginsAllowedReturnsTheConfiguredLogins() throws Exception {
		loginsAllowed.put("native", true);
		loginsAllowed.put("google", false);
		assertEquals(Map.of("native", true, "google", false), json(new UserResource().loginsAllowed(request(null))));
	}

	@Test
	void loginPropertiesAreOnlyForAdmins() throws Exception {
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false);

			Response anonymous = new UserResource().loginProperties(request(null));
			assertEquals(401, anonymous.getStatus());
			Response nonAdmin = new UserResource().loginProperties(signedIn(user));
			assertEquals("User is not an admin and does not have access. Please login as an admin",
					json(nonAdmin).get(Constants.ERROR_MESSAGE));
			verify(social, never()).stringPropertyNames();
		}
	}

	@Test
	void loginPropertiesGroupsThePropertiesByProvider() throws Exception {
		User user = user(AuthProvider.NATIVE, "admin", "Admin");
		when(social.stringPropertyNames()).thenReturn(Set.of("google_client_id", "google_auto_add", "ms_tenant",
				"redirect"));
		when(social.getProperty("google_client_id")).thenReturn("gid");
		when(social.getProperty("google_auto_add")).thenReturn("true");
		when(social.getProperty("ms_tenant")).thenReturn("t1");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			Response response = new UserResource().loginProperties(signedIn(user));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("google", Map.of("client_id", "gid", "auto_add", "true"), "ms",
					Map.of("tenant", "t1")), json(response));
		}
	}

	@Test
	void modifyLoginPropertiesRejectsAnyoneButAnAdmin() throws Exception {
		User user = user(AuthProvider.NATIVE, "jdoe", "Jane");
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		form.add("modifications", "{\"client_id\":\"x\"}");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false);
			assertEquals("\"No user defined to access properties. Please login as an admin\"",
					body(new UserResource().modifyLoginProperties("google", form, request(null))));
			assertEquals("\"User is not an admin and does not have access. Please login as an admin\"",
					body(new UserResource().modifyLoginProperties("google", form, signedIn(user))));
		}
		verify(social, never()).updateSocialProperties(anyString(), any());
	}

	@Test
	void modifyLoginPropertiesSavesTheChangesAndAuditsOnlyTheKeys() throws Exception {
		User user = user(AuthProvider.NATIVE, "admin", "Admin");
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		form.add("modifications", "{\"client_secret\":\"s3cret\"}");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			Response response = new UserResource().modifyLoginProperties("google", form, signedIn(user));

			assertEquals(200, response.getStatus());
			assertEquals("true", body(response));
		}
		verify(social).updateSocialProperties("google", Map.of("client_secret", "s3cret"));
		statics.audit.verify(() -> UserAuditTrailUtils.recordResourceEvent(eq(user), eq(AuditEventType.CONFIG_UPDATE),
				eq(AuditTargetType.PLATFORM_CONFIG), eq("social.properties"), eq("google"), isNull(), isNull(),
				isNull(), eq(Map.of("provider", "google", "changedKeys", List.of("client_secret")))));
	}

	@Test
	void modifyLoginPropertiesAnswers500WhenTheSaveFails() throws Exception {
		User user = user(AuthProvider.NATIVE, "admin", "Admin");
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		form.add("modifications", "{}");
		doThrow(new IOException("read only")).when(social).updateSocialProperties(anyString(), any());
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(true);
			Response response = new UserResource().modifyLoginProperties("google", form, signedIn(user));

			assertEquals(500, response.getStatus());
			assertEquals("read only", json(response).get(Constants.ERROR_MESSAGE));
		}
		statics.audit.verify(() -> UserAuditTrailUtils.recordResourceEvent(any(), any(), any(), any(), any(), any(),
				any(), any(), any()), never());
	}

	@Test
	void modifyAllLoginPropertiesReplacesTheFileForAnAdminOnly() throws Exception {
		User user = user(AuthProvider.NATIVE, "admin", "Admin");
		HttpServletRequest request = withParams(signedIn(user), "socialProperties", "native_login=true");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false, true);

			new UserResource().modifyAllLoginProperties(request);
			verify(social, never()).updateAllProperties(anyString());

			Response response = new UserResource().modifyAllLoginProperties(request);
			assertEquals(200, response.getStatus());
		}
		verify(social).updateAllProperties("native_login=true");
	}

	@Test
	void getAllLoginPropertiesReturnsTheRawFileForAnAdminOnly() throws Exception {
		User user = user(AuthProvider.NATIVE, "admin", "Admin");
		when(social.getFileContents()).thenReturn("native_login=true");
		try (MockedStatic<SecurityAdminUtils> admin = mockStatic(SecurityAdminUtils.class)) {
			admin.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(false, true);

			assertEquals("\"User is not an admin and does not have access. Please login as an admin\"",
					body(new UserResource().getAllLoginProperties(signedIn(user))));
			Response response = new UserResource().getAllLoginProperties(signedIn(user));
			assertEquals(Map.of("content", "native_login=true"), json(response));
		}
	}

	@Test
	void whoamiReportsTheContainerPrincipal() throws Exception {
		HttpServletRequest request = request(null);
		Principal principal = () -> "DOMAIN\\jdoe";
		when(request.getUserPrincipal()).thenReturn(principal);
		assertEquals(Map.of("name", "DOMAIN\\jdoe"), json(new UserResource().show(request, null)));
	}

	@Test
	void whoamiFallsBackToThePrimaryLoginAndOtherwiseReportsNoPrincipal() throws Exception {
		Response fromUser = new UserResource().show(signedIn(user(AuthProvider.NATIVE, "jdoe", "Jane")), null);
		assertEquals(Map.of("name", "jdoe", "warning", "null principal - grab from user"), json(fromUser));

		Response nobody = new UserResource().show(request(null), null);
		assertEquals(Map.of(Constants.ERROR_MESSAGE, "null principal"), json(nobody));
	}
}
