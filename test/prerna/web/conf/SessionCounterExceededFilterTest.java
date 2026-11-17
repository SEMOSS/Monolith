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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.apache.catalina.session.StandardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.semoss.web.services.local.SessionResource;
import prerna.util.Constants;

class SessionCounterExceededFilterTest {

	private static final String API_URL = "http://localhost:8080/Monolith/api/auth/logins";
	private static final String FAIL_URL = "http://localhost:8080/Monolith/sessionCounterFail/";

	private HttpSession session;

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		for (String name : new String[] { "sessionLimit", "filterConfig" }) {
			Field field = SessionCounterExceededFilter.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, null);
		}
		session = mock(HttpSession.class);
	}

	private static SessionCounterExceededFilter filter(String limit) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		when(config.getInitParameter("sessionLimit")).thenReturn(limit);
		SessionCounterExceededFilter filter = new SessionCounterExceededFilter();
		filter.init(config);
		return filter;
	}

	private HttpServletRequest request(String url, User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		when(request.getSession()).thenReturn(session);
		if (user != null) {
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private void run(String limit, int activeSessions, HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws Exception {
		StandardManager manager = mock(StandardManager.class);
		when(manager.getActiveSessions()).thenReturn(activeSessions);
		try (MockedStatic<SessionResource> sessions = mockStatic(SessionResource.class)) {
			sessions.when(() -> SessionResource.getManager(session)).thenReturn(manager);
			filter(limit).doFilter(request, response, chain);
		}
	}

	@Test
	void passesEveryoneWhenTheLimitIsOff() throws Exception {
		for (String limit : new String[] { "-1", "0", "not a number", null }) {
			resetStaticConfig();
			HttpServletRequest request = request(API_URL, null);
			HttpServletResponse response = mock(HttpServletResponse.class);
			FilterChain chain = mock(FilterChain.class);

			run(limit, 100, request, response, chain);

			verify(chain).doFilter(request, response);
			verify(request, never()).getSession();
		}
	}

	@Test
	void turnsAwayANewVisitorOverTheLimit() throws Exception {
		HttpServletRequest request = request(API_URL, null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run("2", 3, request, response, chain);

		verify(session).invalidate();
		verify(response).setHeader("redirect", FAIL_URL);
		verify(response).sendError(302, "Redirect required");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void acceptsADecimalLimit() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		run("2.0", 3, request(API_URL, null), response, mock(FilterChain.class));

		verify(response).sendError(302, "Redirect required");
	}

	@Test
	void letsANewVisitorInAtTheLimit() throws Exception {
		HttpServletRequest request = request(API_URL, null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run("2", 2, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(session, never()).invalidate();
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void passesASignedInUserOverTheLimit() throws Exception {
		HttpServletRequest request = request(API_URL, mock(User.class));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run("2", 50, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(session, never()).invalidate();
	}

	@Test
	void showsTheFailurePageOverTheLimit() throws Exception {
		HttpServletRequest request = request(FAIL_URL, null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run("2", 3, request, response, chain);

		verify(session).invalidate();
		verify(chain).doFilter(request, response);
		verify(response, never()).setHeader(anyString(), any());
	}
}
