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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.web.conf.util.SSOUtil;

class SSOFilterTest {

	private static final String API_URL = "http://localhost:8080/Monolith/api/engine/runPixel";
	private static final String PORTAL_URL = "http://localhost:8080/Monolith/public_home/p1/portals/index.html";
	private static final String UI_REFERER = "http://localhost:8080/semoss-ui/";

	private HttpSession session;

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		setStatic("init", false);
		for (String name : new String[] { "tracker", "userLogger", "customDomainForCookie", "loginPath", "loginUrl",
				"filterConfig" }) {
			setStatic(name, null);
		}
		session = mock(HttpSession.class);
		when(session.getId()).thenReturn("NEWSESSION");
	}

	private static void setStatic(String name, Object value) throws ReflectiveOperationException {
		Field field = SSOFilter.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	private static SSOFilter filter(Map<String, String> params) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		SSOFilter filter = new SSOFilter();
		filter.init(config);
		return filter;
	}

	private HttpServletRequest signedOut(String url, String referer, String fetchDest) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		when(request.getHeader("referer")).thenReturn(referer);
		when(request.getHeader("Sec-Fetch-Dest")).thenReturn(fetchDest);
		when(request.getSession(true)).thenReturn(session);
		when(request.getSession()).thenReturn(session);
		return request;
	}

	@Test
	void passesASignedInUser() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));
		when(request.getSession(false)).thenReturn(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(Map.of()).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(session, never()).setAttribute(eq(SSOUtil.SAML_REDIRECT_KEY), any());
	}

	@Test
	void sendsASignedOutApiCallToTheSamlLoginPage() throws Exception {
		HttpServletRequest request = signedOut(API_URL, UI_REFERER, "empty");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(Map.of()).doFilter(request, response, chain);

		verify(session).setAttribute(SSOUtil.SAML_REDIRECT_KEY, UI_REFERER);
		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/samlLogin/");
		verify(response, never()).setHeader(eq("location"), any());
		verify(response).sendError(302, "Redirect required");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void addsALocationForADocumentOrFrameNavigation() throws Exception {
		for (String fetchDest : new String[] { "document", "IFRAME" }) {
			HttpServletResponse response = mock(HttpServletResponse.class);

			filter(Map.of()).doFilter(signedOut(API_URL, UI_REFERER, fetchDest), response, mock(FilterChain.class));

			verify(response).setHeader("location", "http://localhost:8080/Monolith/samlLogin/");
		}
	}

	@Test
	void returnsAPortalVisitToThePortalItself() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter(Map.of()).doFilter(signedOut(PORTAL_URL, UI_REFERER, null), response, mock(FilterChain.class));

		verify(session).setAttribute(SSOUtil.SAML_REDIRECT_KEY, PORTAL_URL);
		verify(response).setHeader("location", "http://localhost:8080/Monolith/samlLogin/");
	}

	@Test
	void leavesOutTheLocationForAScriptCallFromAPortal() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter(Map.of()).doFilter(signedOut(PORTAL_URL, null, "script"), response, mock(FilterChain.class));

		verify(response, never()).setHeader(eq("location"), any());
	}

	@Test
	void storesNoRedirectWithoutAReferer() throws Exception {
		filter(Map.of()).doFilter(signedOut(API_URL, null, null), mock(HttpServletResponse.class),
				mock(FilterChain.class));

		verify(session).setAttribute(SSOUtil.SAML_REDIRECT_KEY, null);
	}

	@Test
	void sendsTheBrowserToTheConfiguredLoginUrl() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter(Map.of("loginUrl", " https://idp.example/login ")).doFilter(signedOut(API_URL, UI_REFERER, null),
				response, mock(FilterChain.class));

		verify(response).setHeader("redirect", "https://idp.example/login");
		verify(response).setHeader("location", "https://idp.example/login");
		verify(response).sendError(302, "Need to redirect to https://idp.example/login");
	}

	@Test
	void usesTheConfiguredLoginPath() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter(Map.of("loginPath", "/samlSPLogin/")).doFilter(signedOut(API_URL, UI_REFERER, null), response,
				mock(FilterChain.class));

		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/samlSPLogin/");
	}

	@Test
	void issuesTheSessionCookieOnTheCustomDomain() throws Exception {
		HttpServletRequest request = signedOut(API_URL, UI_REFERER, null);
		when(request.isSecure()).thenReturn(true);
		when(request.getCookies()).thenReturn(
				new Cookie[] { new Cookie("JSESSIONID", "STALE"), new Cookie("route", "r1") });
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter(Map.of("customDomain", " health.mil ")).doFilter(request, response, mock(FilterChain.class));

		ArgumentCaptor<Cookie> cookies = ArgumentCaptor.forClass(Cookie.class);
		verify(response, times(2)).addCookie(cookies.capture());
		List<Cookie> added = cookies.getAllValues();
		Cookie issued = added.get(0);
		assertEquals(DBLoader.getSessionIdKey(), issued.getName());
		assertEquals("NEWSESSION", issued.getValue());
		assertEquals("health.mil", issued.getDomain());
		assertEquals("/Monolith", issued.getPath());
		assertTrue(issued.isHttpOnly());
		assertTrue(issued.getSecure());
		assertEquals("NEWSESSION", added.get(1).getValue());
		assertEquals("health.mil", added.get(1).getDomain());
	}
}
