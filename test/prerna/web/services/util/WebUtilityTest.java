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
package prerna.web.services.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.logging.SemossLogUtils;
import prerna.om.ThreadStore;
import prerna.util.Constants;
import prerna.util.Utility;

class WebUtilityTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void clearThreadState() {
		ThreadContext.clearMap();
		ThreadStore.remove();
	}

	/**
	 * Mocks {@link Utility} so the named methods return the given values and every
	 * other method runs for real.
	 */
	private static MockedStatic<Utility> utility(Map<String, Object> values) {
		return Mockito.mockStatic(Utility.class, invocation -> {
			String name = invocation.getMethod().getName();
			if (values.containsKey(name)) {
				return values.get(name);
			}
			return invocation.callRealMethod();
		});
	}

	private static String body(StreamingOutput output) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		output.write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static NewCookie cookie(String name, String value, String path, String domain, int maxAge, boolean secure,
			boolean httpOnly) {
		return new NewCookie.Builder(name).value(value).path(path).domain(domain).maxAge(maxAge).secure(secure)
				.httpOnly(httpOnly).build();
	}

	private static HttpSession sessionWithAttributes() {
		Map<String, Object> attributes = new HashMap<>();
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
		doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1))).when(session).setAttribute(anyString(),
				any());
		return session;
	}

	private static HttpServletRequest joinedRequest(HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		when(request.isRequestedSessionIdValid()).thenReturn(true);
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("JSESSIONID", "abc") });
		when(request.getContextPath()).thenReturn("/Monolith");
		return request;
	}

	@Test
	void buildsAJsonResponseWithItsLength() throws IOException {
		Response response = WebUtility.getResponse(Map.of("success", true), 201);

		assertEquals(201, response.getStatus());
		assertEquals("{\"success\":true}", body((StreamingOutput) response.getEntity()));
		assertEquals("16", response.getHeaderString("Content-Length"));
		assertNull(WebUtility.getResponse(null, 200));
	}

	@Test
	void addsExtraHeadersAndNoCache() {
		List<String[]> headers = new ArrayList<>();
		headers.add(new String[] { "Retry-After", "30" });

		assertEquals("30", WebUtility.getResponse("x", 429, headers).getHeaderString("Retry-After"));
		assertEquals("private", WebUtility.getResponseNoCache("x", 200).getHeaderString("Cache-Control"));
	}

	@Test
	void writesCookiesAsSameSiteHeaders() {
		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "Lax"))) {
			NewCookie[] cookies = { cookie("A", "1", "/", null, -1, false, false),
					cookie("B", "2", "/", null, -1, true, true) };

			Response response = WebUtility.getResponseNoCache("x", 200, cookies);

			List<Object> setCookies = response.getHeaders().get("Set-Cookie");
			assertEquals(List.of("A=1; SameSite=Lax; path=/", "B=2; SameSite=Lax; path=/; secure; HttpOnly"),
					setCookies);
		}
	}

	@Test
	void rendersEveryCookieAttribute() {
		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "Strict"))) {
			String header = WebUtility
					.convertCookieToHeader(cookie("SID", "abc", "/Monolith", "semoss.org", 60, true, true));

			assertTrue(header.startsWith(
					"SID=abc; SameSite=Strict; domain=semoss.org; path=/Monolith; secure; HttpOnly; Expires="), header);
			assertTrue(header.endsWith(" GMT"), header);
			assertEquals("SID=; SameSite=Strict",
					WebUtility.convertCookieToHeader(cookie("SID", "", null, null, -1, false, false)));
		}
	}

	@Test
	void streamsAnObjectAsJson() throws IOException {
		assertEquals("[\"a\",1]", body(WebUtility.getSO(Arrays.asList("a", 1))));
		assertEquals("abc", body(WebUtility.getSO("abc".getBytes(StandardCharsets.UTF_8))));
		assertNull(WebUtility.getSO((Object) null));
	}

	@Test
	void streamsAFileAndDeletesItAfterwards() throws IOException {
		Path file = Files.writeString(tempDir.resolve("export.csv"), "a,b\n1,2\n");

		String streamed = body(WebUtility.getSOFile(file.toString()));

		assertEquals("a,b" + System.lineSeparator() + "1,2" + System.lineSeparator(), streamed);
		assertFalse(Files.exists(file));
		assertNull(WebUtility.getSOFile(null));
	}

	@Test
	void streamsNothingForANullBinaryPayload() {
		assertNull(WebUtility.getBinarySO(null));
	}

	@Test
	void replacesLineBreaksAndEncodesHtmlInResponseStrings() {
		assertEquals("a_b_c_d_e_f_&lt;x&gt;", WebUtility.cleanHttpResponse("a\nb\rc\td%0ae%0df%09<x>"));
		assertNull(WebUtility.cleanHttpResponse(null));
	}

	@Test
	void encodesAUriComponent() {
		assertEquals("a%20b%2Fc%3Fd%3De", WebUtility.encodeHTTPUri("a b/c?d=e"));
		assertNull(WebUtility.encodeHTTPUri(null));
	}

	@Test
	void sanitizesMarkupAndQuotesButKeepsPlainIds() {
		assertEquals("6b9d0c7e-1f2a-4c3b-9d8e-abcdef012345",
				WebUtility.inputSanitizer("6b9d0c7e-1f2a-4c3b-9d8e-abcdef012345"));
		assertEquals("hi", WebUtility.inputSanitizer("<script>alert(1)</script>hi"));
		String quoted = WebUtility.inputSanitizer("a\"b'c");
		assertFalse(quoted.contains("\""), quoted);
		assertFalse(quoted.contains("'"), quoted);
		assertNull(WebUtility.inputSanitizer((String) null));
	}

	@Test
	void sanitizesEveryElementOfACollection() {
		assertEquals(List.of("a", "hi"), WebUtility.inputSanitizer(List.of("a", "<script>x</script>hi")));
		assertEquals(new HashSet<>(List.of("a", "hi")),
				WebUtility.inputSanitizer(new HashSet<>(List.of("a", "<script>x</script>hi"))));
		LinkedHashSet<String> ordered = WebUtility.inputSanitizer(new LinkedHashSet<>(List.of("z", "a", "m")));
		assertEquals(List.of("z", "a", "m"), new ArrayList<>(ordered));
		assertNull(WebUtility.inputSanitizer((List<String>) null));
		assertNull(WebUtility.inputSanitizer((HashSet<String>) null));
		assertNull(WebUtility.inputSanitizer((LinkedHashSet<String>) null));
	}

	@Test
	void escapesSqlQuotes() {
		assertEquals("O''Brien", WebUtility.inputSQLSanitizer("O'Brien"));
		assertEquals(List.of("a''b", "c"), WebUtility.inputSQLSanitizer(List.of("a'b", "c")));
		assertNull(WebUtility.inputSQLSanitizer((String) null));
		assertNull(WebUtility.inputSQLSanitizer((List<String>) null));
	}

	@Test
	void normalizesSeparatorsAndDotSegments() {
		assertEquals("/base/db/app/assets", WebUtility.normalizePath("\\base\\\\db//app/./x/../assets"));
		assertNull(WebUtility.normalizePath(null));
	}

	@Test
	void rejectsAPathThatClimbsAboveItsRoot() {
		assertThrows(IllegalArgumentException.class, () -> WebUtility.normalizePath("/base/../../etc/passwd"));
	}

	@Test
	void acceptsOnlySinglePathSegments() {
		for (String safe : new String[] { "abc", "6b9d0c7e-1f2a", "a.b", "...", "name with space" }) {
			assertTrue(WebUtility.isSafePathSegment(safe), safe);
			assertEquals(safe, WebUtility.safePathSegment(safe));
		}
		for (String unsafe : new String[] { null, "", ".", "..", "a/b", "a\\b", "../x", "a\0b" }) {
			assertFalse(WebUtility.isSafePathSegment(unsafe), String.valueOf(unsafe));
			assertNull(WebUtility.safePathSegment(unsafe));
		}
	}

	@Test
	void resolvesAPathInsideTheBase() throws IOException {
		Path base = Files.createDirectories(tempDir.resolve("assets"));
		Files.createDirectories(base.resolve("css"));

		assertEquals(base.toRealPath(), WebUtility.resolveWithin(base, ""));
		assertEquals(base.toRealPath().resolve("css/site.css"), WebUtility.resolveWithin(base, "css/site.css"));
		assertEquals(base.toRealPath().resolve("css/site.css"), WebUtility.resolveWithin(base, "css\\site.css"));
		assertEquals(base.toRealPath().resolve("new/file.txt"), WebUtility.resolveWithin(base, "new/./file.txt"));
	}

	@Test
	void rejectsPathsThatEscapeTheBase() throws IOException {
		Path base = Files.createDirectories(tempDir.resolve("assets"));
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.createSymbolicLink(base.resolve("link"), outside);

		assertThrows(SecurityException.class, () -> WebUtility.resolveWithin(base, "../outside/secret.txt"));
		assertThrows(SecurityException.class, () -> WebUtility.resolveWithin(base, outside.toString()));
		assertThrows(SecurityException.class, () -> WebUtility.resolveWithin(base, "link/secret.txt"));
		assertThrows(IllegalArgumentException.class, () -> WebUtility.resolveWithin(base, "a\0b"));
		assertThrows(IllegalArgumentException.class, () -> WebUtility.resolveWithin(base, null));
		assertThrows(IllegalArgumentException.class, () -> WebUtility.resolveWithin(null, "a"));
	}

	@Test
	void expiresTheSessionCookieAtTheContextAndRootPaths() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		Cookie session = new Cookie("JSESSIONID", "abc");
		session.setDomain("semoss.org");
		when(request.getCookies()).thenReturn(new Cookie[] { session, new Cookie("other", "x") });
		when(request.getContextPath()).thenReturn("/Monolith");
		List<NewCookie> expired = new ArrayList<>();

		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "Lax"))) {
			WebUtility.expireSessionCookies(request, expired);
		}

		assertEquals(2, expired.size());
		assertEquals("/Monolith", expired.get(0).getPath());
		assertEquals("/", expired.get(1).getPath());
		for (NewCookie cookie : expired) {
			assertEquals("JSESSIONID", cookie.getName());
			assertEquals("", cookie.getValue());
			assertEquals(0, cookie.getMaxAge());
			assertEquals("semoss.org", cookie.getDomain());
			assertFalse(cookie.isSecure());
		}
	}

	@Test
	void marksExpiredCookiesSecureWhenSameSiteIsNone() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("JSESSIONID", "abc") });
		when(request.getContextPath()).thenReturn("");
		List<NewCookie> expired = new ArrayList<>();

		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "none"))) {
			WebUtility.expireSessionCookies(request, expired);
		}

		assertEquals(1, expired.size());
		assertEquals("/", expired.get(0).getPath());
		assertTrue(expired.get(0).isSecure());
	}

	@Test
	void expiresNothingWithoutCookies() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		List<NewCookie> expired = new ArrayList<>();

		WebUtility.expireSessionCookies(request, expired);
		WebUtility.expireSessionCookies(null, expired);
		WebUtility.expireSessionCookies(request, null);

		assertTrue(expired.isEmpty());
	}

	@Test
	void dropsASessionWithoutAUserAndExpiresItsCookie() {
		HttpSession session = sessionWithAttributes();
		HttpServletRequest request = joinedRequest(session);
		List<NewCookie> expired = new ArrayList<>();

		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "Lax"))) {
			WebUtility.dropSessionWithoutUser(request, expired);
		}

		verify(session).invalidate();
		assertEquals(2, expired.size());
		assertEquals(0, expired.get(0).getMaxAge());
	}

	@Test
	void keepsTheSessionAndCookieOfAnOAuthSignInInProgress() {
		HttpSession session = sessionWithAttributes();
		WebUtility.markOAuthLoginStarted(session);
		HttpServletRequest request = joinedRequest(session);
		List<NewCookie> expired = new ArrayList<>();

		WebUtility.dropSessionWithoutUser(request, expired);

		assertTrue(WebUtility.isOAuthLoginPending(session));
		verify(session, never()).invalidate();
		assertTrue(expired.isEmpty());
	}

	@Test
	void dropsASessionWhoseOAuthSignInStartedOverTenMinutesAgo() {
		HttpSession session = sessionWithAttributes();
		session.setAttribute("oauth_login_started", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));
		HttpServletRequest request = joinedRequest(session);
		List<NewCookie> expired = new ArrayList<>();

		try (MockedStatic<Utility> u = utility(Map.of("getSameSiteCookieValue", "Lax"))) {
			WebUtility.dropSessionWithoutUser(request, expired);
		}

		assertFalse(WebUtility.isOAuthLoginPending(session));
		verify(session).invalidate();
		assertEquals(2, expired.size());
	}

	@Test
	void dropsOnlyTheSessionWhenNotGivenTheResponseCookies() {
		HttpSession session = sessionWithAttributes();
		HttpServletRequest request = joinedRequest(session);

		WebUtility.dropSessionWithoutUser(request, null);

		verify(session).invalidate();
	}

	@Test
	void keepsASessionTheRequestNeitherMadeNorJoined() {
		HttpSession session = sessionWithAttributes();
		HttpServletRequest request = joinedRequest(session);
		when(request.isRequestedSessionIdValid()).thenReturn(false);

		WebUtility.dropSessionWithoutUser(request, null);

		verify(session, never()).invalidate();
	}

	@Test
	void leavesTheCookieOfASessionThatNoLongerExists() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("JSESSIONID", "renamed") });
		when(request.getContextPath()).thenReturn("/Monolith");
		List<NewCookie> expired = new ArrayList<>();

		WebUtility.dropSessionWithoutUser(request, expired);

		assertTrue(expired.isEmpty());
	}

	@Test
	void seesNoOAuthSignInWithoutASessionOrAStart() {
		assertFalse(WebUtility.isOAuthLoginPending(null));
		assertFalse(WebUtility.isOAuthLoginPending(sessionWithAttributes()));
	}

	@Test
	void allowsEveryDomainWhenNoWhitelistIsSet() {
		Map<String, Object> values = new HashMap<>();
		values.put("getDIHelperProperty", null);
		try (MockedStatic<Utility> u = utility(values)) {
			WebUtility.checkIfValidDomain("https://anything.example.net/path");
		}
		try (MockedStatic<Utility> u = utility(Map.of("getDIHelperProperty", "   "))) {
			WebUtility.checkIfValidDomain("https://anything.example.net/path");
		}
	}

	@Test
	void allowsOnlyWhitelistedTopPrivateDomains() {
		try (MockedStatic<Utility> u = utility(Map.of("getDIHelperProperty", "semoss.org,example.com"))) {
			WebUtility.checkIfValidDomain("https://semoss.org/login");
			WebUtility.checkIfValidDomain("https://app.sub.example.com:8443/x?y=z");

			assertThrows(IllegalArgumentException.class,
					() -> WebUtility.checkIfValidDomain("https://semoss.org.evil.net/login"));
			assertThrows(IllegalArgumentException.class,
					() -> WebUtility.checkIfValidDomain("https://evil.net/?r=semoss.org"));
			assertThrows(IllegalArgumentException.class, () -> WebUtility.checkIfValidDomain("//semoss.org/login"));
			assertThrows(IllegalArgumentException.class,
					() -> WebUtility.checkIfValidDomain("javascript:alert(1)"));
		}
	}

	@Test
	void picksTheLoginFragmentFromTheReferer() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		assertEquals("#/login", WebUtility.determineLoginExtension(request));

		when(request.getHeader("referer")).thenReturn("https://host/semoss-ui/");
		assertEquals("#!/login", WebUtility.determineLoginExtension(request));
		when(request.getHeader("referer")).thenReturn("https://host/SemossWeb");
		assertEquals("#!/login", WebUtility.determineLoginExtension(request));
		when(request.getHeader("referer")).thenReturn("https://host/public_home/semoss-ui/");
		assertEquals("#/login", WebUtility.determineLoginExtension(request));
		when(request.getHeader("referer")).thenReturn("https://host/semoss-ui/packages/client/dist/");
		assertEquals("#/login", WebUtility.determineLoginExtension(request));
	}

	@Test
	void buildsTheSpaBaseUrlFromTheReferer() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		assertNull(WebUtility.getRefererURL(request));
		assertNull(WebUtility.getRefererURL(null));

		when(request.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/packages/client/dist/");
		assertEquals("http://localhost:8080/semoss-ui/packages/client/dist/#!/", WebUtility.getRefererURL(request));
	}

	@Test
	void usesTheRemoteAddressWithoutAForwardedHeader() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.5");
		assertEquals("10.0.0.5", WebUtility.getClientIp(request));

		when(request.getHeader("X-FORWARDED-FOR")).thenReturn("");
		assertEquals("10.0.0.5", WebUtility.getClientIp(request));
		assertEquals("", WebUtility.getClientIp(null));
	}

	@Test
	void putsTheSignedInUserIntoTheLoggingContext() {
		AccessToken token = mock(AccessToken.class);
		when(token.getId()).thenReturn("jdoe");
		when(token.getProvider()).thenReturn(AuthProvider.NATIVE);
		User user = mock(User.class);
		when(user.getPrimaryLoginToken()).thenReturn(token);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("s1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		WebUtility.loggingContextLoginEvent(session);

		assertEquals("s1", ThreadContext.get(SemossLogUtils.SESSION_ID));
		assertEquals("jdoe", ThreadContext.get(SemossLogUtils.USER_ID));
		assertEquals(AuthProvider.NATIVE.getLabel(), ThreadContext.get(SemossLogUtils.USER_TYPE));
		assertEquals("UNKNOWN", ThreadContext.get(SemossLogUtils.USER_NAME));
	}

	@Test
	void logsAnUnknownUserWithoutASignedInUser() {
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("s2");

		WebUtility.loggingContextLoginEvent(session);
		assertEquals("s2", ThreadContext.get(SemossLogUtils.SESSION_ID));
		assertEquals("UNKNOWN", ThreadContext.get(SemossLogUtils.USER_ID));

		WebUtility.loggingContextLoginEvent(null);
		assertEquals("UNKNOWN", ThreadContext.get(SemossLogUtils.SESSION_ID));
		assertEquals("UNKNOWN", ThreadContext.get(SemossLogUtils.USER_NAME));
	}

	@Test
	void fillsTheLoggingContextFromTheRequest() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.5");
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/Monolith/api/engine/runPixel");
		when(request.getHeader("Host")).thenReturn("semoss.org");
		when(request.getHeader("User-Agent")).thenReturn("x".repeat(1500));
		when(request.getServerName()).thenReturn("node-1");
		when(request.getLocalPort()).thenReturn(8080);
		when(request.getScheme()).thenReturn("http");

		WebUtility.loggingContext(request);

		assertTrue(ThreadContext.get(SemossLogUtils.REQUEST_ID).matches("[0-9a-f-]{36}"));
		assertEquals("10.0.0.5", ThreadContext.get(SemossLogUtils.CLIENT_IP));
		assertEquals("POST", ThreadContext.get(SemossLogUtils.METHOD));
		assertEquals("/Monolith/api/engine/runPixel", ThreadContext.get(SemossLogUtils.ENDPOINT));
		assertEquals(1000, ThreadContext.get(SemossLogUtils.USER_AGENT).length());
		assertEquals("node-1", ThreadStore.getLocalHostname());
		assertEquals(8080, ThreadStore.getLocalPort());
		assertEquals("http", ThreadStore.getLocalProtocol());
	}

	@Test
	void usesTheRequestUrlForTheCallbackWithoutAnApplicationUrl() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/Monolith/api/auth/login2/ms"));
		Map<String, Object> values = new HashMap<>();
		values.put("getApplicationUrl", null);

		try (MockedStatic<Utility> u = utility(values)) {
			assertEquals("http://localhost:8080/Monolith/api/auth/login2/ms",
					WebUtility.getCurrentCallbackUrl(request));
		}
	}

	@Test
	void keepsTheProxyRouteOfTheApplicationUrlInTheCallback() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL())
				.thenReturn(new StringBuffer("http://10.0.0.5:8080/Monolith/api/auth/login2/salesforce"));
		when(request.getRequestURI()).thenReturn("/Monolith/api/auth/login2/salesforce");
		when(request.getContextPath()).thenReturn("/Monolith");

		try (MockedStatic<Utility> u = utility(Map.of("getApplicationUrl", "https://semoss.org/route/Monolith/"))) {
			assertEquals("https://semoss.org/route/Monolith/api/auth/login2/salesforce",
					WebUtility.getCurrentCallbackUrl(request));
		}
		try (MockedStatic<Utility> u = utility(Map.of("getApplicationUrl", "https://semoss.org/route"))) {
			assertEquals("https://semoss.org/route/Monolith/api/auth/login2/salesforce",
					WebUtility.getCurrentCallbackUrl(request));
		}
	}

	@Test
	void readsTheClientFacingProtocolHostAndPort() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getScheme()).thenReturn("http");
		when(request.getServerName()).thenReturn("internal");
		when(request.getServerPort()).thenReturn(8080);
		assertEquals("http", WebUtility.getProtocol(request));
		assertEquals("internal", WebUtility.getHostname(request));
		assertEquals(8080, WebUtility.getPort(request));

		when(request.getHeader("Host")).thenReturn("semoss.org:9443");
		assertEquals("semoss.org", WebUtility.getHostname(request));
		assertEquals(9443, WebUtility.getPort(request));

		when(request.getHeader("X-Forwarded-Proto")).thenReturn("HTTPS");
		when(request.getHeader("X-Forwarded-Host")).thenReturn("public.semoss.org:443");
		when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
		assertEquals("https", WebUtility.getProtocol(request));
		assertEquals("public.semoss.org", WebUtility.getHostname(request));
		assertEquals(443, WebUtility.getPort(request));
	}

	@Test
	void fallsBackToTheDefaultPortForTheProtocol() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader("X-Forwarded-Port")).thenReturn("not-a-port");
		when(request.getHeader("Host")).thenReturn("semoss.org:bad");
		assertEquals(80, WebUtility.getPort(request));

		when(request.isSecure()).thenReturn(true);
		assertEquals(443, WebUtility.getPort(request));
		assertEquals("https", WebUtility.getProtocol(request));
	}

	@Test
	void readsTheContainerLocalProtocolHostAndPort() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
		when(request.getScheme()).thenReturn("http");
		assertEquals("http", WebUtility.getLocalProtocol(request));
		assertEquals("localhost", WebUtility.getLocalHostname(request));
		assertEquals(80, WebUtility.getLocalPort(request));

		when(request.getServerName()).thenReturn("node-1");
		when(request.getLocalPort()).thenReturn(8080);
		assertEquals("node-1", WebUtility.getLocalHostname(request));
		assertEquals(8080, WebUtility.getLocalPort(request));

		when(request.isSecure()).thenReturn(true);
		assertEquals("https", WebUtility.getLocalProtocol(request));
	}

	@Test
	void recognizesAClientDisconnectAnywhereInTheCauseChain() {
		assertTrue(WebUtility.isClientDisconnect(new IOException("Broken pipe")));
		assertTrue(WebUtility.isClientDisconnect(
				new RuntimeException("write failed", new IOException("Connection reset by peer"))));
		assertFalse(WebUtility.isClientDisconnect(new IOException("disk full")));
		assertFalse(WebUtility.isClientDisconnect(new RuntimeException((String) null)));
		assertFalse(WebUtility.isClientDisconnect(null));
	}

	@Test
	void runsTheDisconnectHookOnlyForADisconnect() {
		Logger logger = mock(Logger.class);
		Runnable hook = mock(Runnable.class);

		assertFalse(WebUtility.handleStreamingException(new IOException("disk full"), logger, "e1", "j1", hook));
		verify(hook, never()).run();

		assertTrue(WebUtility.handleStreamingException(new IOException("Broken pipe"), logger, "e1", "j1", hook));
		verify(hook).run();
	}

	@Test
	void survivesAFailingDisconnectHook() {
		Logger logger = mock(Logger.class);

		assertTrue(WebUtility.handleStreamingException(new IOException("broken pipe"), logger, null, null, () -> {
			throw new IllegalStateException("cleanup failed");
		}));
		assertTrue(WebUtility.handleStreamingException(new IOException("broken pipe"), logger, null, null, null));
	}

	@Test
	void sanitizesJsonForEmbedding() {
		assertEquals("{\"a\":1}", WebUtility.jsonSanitizer("{a:1}"));
	}
}
