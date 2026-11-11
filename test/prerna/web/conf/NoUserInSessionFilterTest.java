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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

class NoUserInSessionFilterTest {

	private static final String BASE = "http://localhost:8080/Monolith";

	private static HttpServletRequest request(String path, HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(BASE + path));
		when(request.getPathInfo()).thenReturn(path.substring("/api".length()));
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static HttpSession session(User user) {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return session;
	}

	private static User signedIn() {
		User user = mock(User.class);
		when(user.getLogins()).thenReturn(List.of(AuthProvider.NATIVE));
		return user;
	}

	private static void run(String loginRedirect, HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws Exception {
		SocialPropertiesUtil social = mock(SocialPropertiesUtil.class);
		when(social.getLoginRedirect()).thenReturn(loginRedirect);
		try (MockedStatic<SocialPropertiesUtil> socials = mockStatic(SocialPropertiesUtil.class)) {
			socials.when(SocialPropertiesUtil::getInstance).thenReturn(social);
			new NoUserInSessionFilter().doFilter(request, response, chain);
		}
	}

	@Test
	void passesASignedInUser() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", session(signedIn()));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(null, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).setStatus(anyInt());
	}

	@Test
	void passesTheEndpointsThatWorkWithoutALogin() throws Exception {
		for (String path : new String[] { "/api/config", "/api/config/fetchCsrf", "/api/auth/logins",
				"/api/auth/login", "/api/auth/login/google", "/api/auth/userinfo/ms", "/api/auth/login/producthunt",
				"/api/session/active", "/api/auth/whoami" }) {
			HttpServletRequest request = request(path, null);
			HttpServletResponse response = mock(HttpServletResponse.class);
			FilterChain chain = mock(FilterChain.class);

			run(null, request, response, chain);

			verify(chain).doFilter(request, response);
			verify(response, never()).setStatus(anyInt());
		}
	}

	@Test
	void answersAnApiClientWithoutAValidKeyWithA401InItsApisShape() throws Exception {
		Map<String, String> bodies = new LinkedHashMap<>();
		bodies.put("/api/model/openai/v1/chat/completions", "\"code\":\"invalid_api_key\"");
		bodies.put("/api/model/anthropic/v1/messages", "\"type\":\"authentication_error\"");
		bodies.put("/api/model/ollama/api/chat", "{\"error\":\"Invalid or missing API key.");
		bodies.put("/api/ext/mcp/toolbox-1/comms", "\"code\":\"invalid_api_key\"");
		for (Map.Entry<String, String> api : bodies.entrySet()) {
			HttpServletRequest request = request(api.getKey(), null);
			HttpServletResponse response = mock(HttpServletResponse.class);
			StringWriter body = new StringWriter();
			when(response.getWriter()).thenReturn(new PrintWriter(body));
			FilterChain chain = mock(FilterChain.class);

			run(null, request, response, chain);

			verify(response).setStatus(401);
			verify(response).setContentType("application/json");
			verify(response, never()).sendRedirect(anyString());
			verify(request, never()).getSession(true);
			verify(chain, never()).doFilter(any(), any());
			assertTrue(body.toString().contains(api.getValue()), api.getKey() + ": " + body);
		}
	}

	@Test
	void redirectsAPathThatOnlyStartsLikeAnApi() throws Exception {
		HttpServletRequest request = request("/api/model/openaiproxy/v1/models", null);
		when(request.getSession(true)).thenReturn(mock(HttpSession.class));
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(response, never()).setStatus(401);
		verify(response).sendRedirect(anyString());
	}

	@Test
	void stopsASignedOutRequestAfterRedirectingIt() throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletRequest directHit = request("/api/engine/runPixel", null);
		when(directHit.getSession(true)).thenReturn(mock(HttpSession.class));
		HttpServletRequest fromTheApp = request("/api/engine/runPixel", null);
		when(fromTheApp.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/");

		run(null, directHit, mock(HttpServletResponse.class), chain);
		run(null, fromTheApp, mock(HttpServletResponse.class), chain);

		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void redirectsAPathParameterThatEndsInAnOpenEndpoint() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		when(request.getRequestURL()).thenReturn(new StringBuffer(BASE + "/api/engine/runPixel;config"));
		when(request.getSession(true)).thenReturn(mock(HttpSession.class));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(null, request, response, chain);

		verify(response).sendRedirect(BASE + "/noUserFail/");
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void sendsADirectHitWithoutASessionToTheNoUserPage() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		HttpSession created = mock(HttpSession.class);
		when(request.getSession(true)).thenReturn(created);
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(created).setAttribute(Constants.ENDPOINT_REDIRECT_KEY, BASE + "/api/engine/runPixel");
		verify(response).sendRedirect(BASE + "/noUserFail/");
	}

	@Test
	void sendsADirectHitToTheConfiguredLoginPage() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		when(request.getSession(true)).thenReturn(mock(HttpSession.class));
		HttpServletResponse response = mock(HttpServletResponse.class);

		run("https://login.example/semoss", request, response, mock(FilterChain.class));

		verify(response).sendRedirect("https://login.example/semoss");
	}

	@Test
	void answersACallFromTheAppWithTheAppLoginRoute() throws Exception {
		HttpSession session = session(null);
		when(session.isNew()).thenReturn(false);
		HttpServletRequest request = request("/api/engine/runPixel", session);
		when(request.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/");
		when(request.isRequestedSessionIdValid()).thenReturn(true);
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(response).setHeader("redirect", "http://localhost:8080/semoss-ui/#!/login");
		verify(response).sendError(302, "Need to redirect to http://localhost:8080/semoss-ui/#!/login");
		verify(response, never()).sendRedirect(anyString());
		verify(session).invalidate();
	}

	@Test
	void answersACallFromADeepPageWithTheHashLoginRoute() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		when(request.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/packages/client/dist/");
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(response).setHeader("redirect", "http://localhost:8080/semoss-ui/packages/client/dist/#/login");
	}

	@Test
	void encodesTheRefererItEchoes() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		when(request.getHeader("referer")).thenReturn("http://localhost:8080/x\"><script>");
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(response).setHeader("redirect", "http://localhost:8080/x&#34;&gt;&lt;script&gt;#/login");
	}

	@Test
	void keepsAnUnvalidatedSessionOfARejectedCall() throws Exception {
		HttpSession session = session(null);
		HttpServletRequest request = request("/api/engine/runPixel", session);
		when(request.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/");
		when(request.isRequestedSessionIdValid()).thenReturn(false);
		when(session.isNew()).thenReturn(false);

		run(null, request, mock(HttpServletResponse.class), mock(FilterChain.class));

		verify(session, never()).invalidate();
	}

	@Test
	void treatsAUserWithoutLoginsAsSignedOut() throws Exception {
		User user = mock(User.class);
		when(user.getLogins()).thenReturn(List.of());
		HttpServletRequest request = request("/api/engine/runPixel", session(user));
		when(request.getHeader("referer")).thenReturn("http://localhost:8080/semoss-ui/");
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(null, request, response, mock(FilterChain.class));

		verify(response).sendError(302, "Need to redirect to http://localhost:8080/semoss-ui/#!/login");
	}
}
