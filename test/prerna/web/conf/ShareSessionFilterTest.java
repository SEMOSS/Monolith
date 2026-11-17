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
package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityShareSessionUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.Utility;

class ShareSessionFilterTest {

	private static final String URL = "http://localhost:8080/Monolith/api/engine/runPixel";

	private HttpServletResponse response;
	private FilterChain chain;
	private AccessToken shareToken;

	@BeforeEach
	void setUp() {
		assumeTrue(System.getenv(Constants.MONOLITH_PREFIX) == null);
		response = mock(HttpServletResponse.class);
		chain = mock(FilterChain.class);
		shareToken = new AccessToken();
		shareToken.setProvider(AuthProvider.NATIVE);
		shareToken.setId("jdoe");
	}

	private static Object[] details(boolean shareSession, boolean shareAuth) {
		return new Object[] { "tok1", "SESSION123", "route1", null, null, null, shareSession, shareAuth, "jdoe",
				"NATIVE" };
	}

	private static HttpServletRequest request(String method, String query, User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getRequestURL()).thenReturn(new StringBuffer(URL));
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getQueryString()).thenReturn(query);
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private void run(HttpServletRequest request, Object[] details, RuntimeException validationError)
			throws Exception {
		try (MockedStatic<SecurityShareSessionUtils> shares = mockStatic(SecurityShareSessionUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			shares.when(() -> SecurityShareSessionUtils.getShareSessionDetails("tok1")).thenReturn(details);
			if (validationError != null) {
				shares.when(() -> SecurityShareSessionUtils.validateShareSessionDetails(details))
						.thenThrow(validationError);
			} else {
				shares.when(() -> SecurityShareSessionUtils.validateShareSessionDetails(details)).thenReturn(true);
			}
			shares.when(() -> SecurityShareSessionUtils.generateAccessTokenForShareAuth(details))
					.thenReturn(shareToken);
			utility.when(() -> Utility.getDIHelperProperty(Constants.LOAD_BALANCER_COOKIE_NAME)).thenReturn("ROUTEID");
			new ShareSessionFilter().doFilter(request, response, chain);
			if (details != null && (boolean) details[7] && !(boolean) details[6] && validationError == null) {
				users.verify(() -> UserResource.addAccessToken(shareToken, request, false));
			} else {
				users.verify(() -> UserResource.addAccessToken(any(), any(), anyBoolean()), never());
			}
		}
	}

	private ServletRequest passed() throws Exception {
		ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(passed.capture(), any(ServletResponse.class));
		return passed.getValue();
	}

	private List<Cookie> cookies(int count) {
		ArgumentCaptor<Cookie> cookies = ArgumentCaptor.forClass(Cookie.class);
		verify(response, times(count)).addCookie(cookies.capture());
		return cookies.getAllValues();
	}

	@Test
	void passesARequestWithoutAShareToken() throws Exception {
		HttpServletRequest request = request("GET", "insight=abc", null);

		run(request, details(true, false), null);

		assertTrue(passed() instanceof HttpServletRequestWrapper);
		verify(response, never()).addCookie(any());
	}

	@Test
	void passesASignedInUserWhoStillCarriesAShareToken() throws Exception {
		User user = mock(User.class);
		when(user.getLogins()).thenReturn(List.of(AuthProvider.NATIVE));
		HttpServletRequest request = request("GET", "sessionToken=tok1", user);

		try (MockedStatic<SecurityShareSessionUtils> shares = mockStatic(SecurityShareSessionUtils.class)) {
			new ShareSessionFilter().doFilter(request, response, chain);
			shares.verify(() -> SecurityShareSessionUtils.getShareSessionDetails(anyString()), never());
		}

		assertSame(request, passed());
	}

	@Test
	void movesAGetOntoTheSharedSessionAndDropsTheToken() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1&insight=abc", null);
		when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("JSESSIONID", "MINE"),
				new Cookie("other", "x") });

		run(request, details(true, false), null);

		List<Cookie> cookies = cookies(3);
		Cookie session = cookies.get(0);
		assertEquals("JSESSIONID", session.getName());
		assertEquals("SESSION123", session.getValue());
		assertEquals("/Monolith", session.getPath());
		assertTrue(session.isHttpOnly());
		assertEquals("SESSION123", cookies.get(1).getValue());
		assertEquals("ROUTEID", cookies.get(2).getName());
		assertEquals("route1", cookies.get(2).getValue());
		verify(response).setStatus(302);
		verify(response).sendRedirect(URL + "?insight=abc");
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void redirectsAGetThatOnlyCarriedTheTokenToTheBareUrl() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1", null);

		run(request, details(true, false), null);

		verify(response).sendRedirect(URL);
	}

	@Test
	void answersAPostWithATemporaryRedirect() throws Exception {
		HttpServletRequest request = request("POST", "sessionToken=tok1", null);

		run(request, details(true, false), null);

		verify(response).setStatus(307);
		verify(response).setHeader("Location", URL);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void signsInWithAnAuthShareToken() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1", null);

		run(request, details(false, true), null);

		assertTrue(passed() instanceof HttpServletRequestWrapper);
		verify(response, never()).addCookie(any());
		verify(response, never()).sendRedirect(anyString());
	}

	@Test
	void passesAnUnknownToken() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1", null);

		run(request, null, null);

		assertSame(request, passed());
		verify(response, never()).addCookie(any());
	}

	@Test
	void passesATokenThatFailsValidation() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1", null);

		run(request, details(true, false), new IllegalArgumentException("The share key has already expired"));

		assertSame(request, passed());
		verify(response, never()).addCookie(any());
	}

	@Test
	void redirectsWithoutCookiesForATokenThatSharesNothing() throws Exception {
		HttpServletRequest request = request("GET", "sessionToken=tok1", null);

		run(request, details(false, false), null);

		verify(response, never()).addCookie(any());
		verify(response).sendRedirect(URL);
	}
}
