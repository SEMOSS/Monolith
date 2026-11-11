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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.reactor.mgmt.MgmtUtil;
import prerna.util.Constants;
import prerna.util.Settings;
import prerna.util.Utility;

class MemoryCheckFilterTest {

	private static final Map<String, String> CHECKING = Map.of(Settings.CHECK_MEM, "true", Settings.USER_MEM_LIMIT,
			" 4 ", Settings.MEM_PROFILE_SETTINGS, "constant");

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		setStatic("checkMem", null);
		setStatic("memProfileSettings", null);
		setStatic("memLimit", -1);
	}

	private static void setStatic(String name, Object value) throws ReflectiveOperationException {
		Field field = MemoryCheckFilter.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	private static HttpServletRequest request(String url, User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private static void run(Map<String, String> settings, long freeGigs, HttpServletRequest request,
			HttpServletResponse response, FilterChain chain) throws Exception {
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<MgmtUtil> mgmt = mockStatic(MgmtUtil.class)) {
			settings.forEach((k, v) -> utility.when(() -> Utility.getDIHelperProperty(k)).thenReturn(v));
			mgmt.when(MgmtUtil::getFreeMemory).thenReturn(freeGigs);
			new MemoryCheckFilter().doFilter(request, response, chain);
		}
	}

	@Test
	void passesEverythingWhenTheCheckIsOff() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/api/auth/login", null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(Map.of(), 0, request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void passesASignedOutRequestWhileThereIsMemoryLeft() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/api/auth/login", null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(CHECKING, 5, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void redirectsASignedOutRequestWhenMemoryRunsOut() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/api/auth/login", null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(CHECKING, 4, request, response, chain);

		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/sessionCounterFail/");
		verify(response).sendError(302, "Redirect required");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void passesASignedInUserWhenMemoryRunsOut() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/api/engine/runPixel", mock(User.class));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(CHECKING, 0, request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void passesTheFailurePageWhenMemoryRunsOut() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/sessionCounterFail/", null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(CHECKING, 0, request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void readsTheSettingsOnlyOnce() throws Exception {
		run(Map.of(), 0, request("http://localhost:8080/Monolith/api/a", null), mock(HttpServletResponse.class),
				mock(FilterChain.class));
		HttpServletRequest request = request("http://localhost:8080/Monolith/api/b", null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(CHECKING, 0, request, response, chain);

		verify(chain).doFilter(request, response);
	}
}
