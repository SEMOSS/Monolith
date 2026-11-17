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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.util.Constants;

class SetAdminSessionTimeoutFilterTest {

	private static final String CHECKED = "adminSessionTimeout";

	private User user;
	private HttpSession session;
	private HttpServletRequest request;

	@BeforeEach
	void setUp() throws ReflectiveOperationException {
		for (String name : new String[] { "sessionTimeout", "filterConfig" }) {
			Field field = SetAdminSessionTimeoutFilter.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, null);
		}
		user = mock(User.class);
		session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
	}

	private static SetAdminSessionTimeoutFilter filter(String timeout) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		when(config.getInitParameter("timeout")).thenReturn(timeout);
		SetAdminSessionTimeoutFilter filter = new SetAdminSessionTimeoutFilter();
		filter.init(config);
		return filter;
	}

	private void run(SetAdminSessionTimeoutFilter filter, boolean admin, FilterChain chain) throws Exception {
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(admin);
			filter.doFilter(request, mock(HttpServletResponse.class), chain);
		}
	}

	@Test
	void givesAnAdminTheConfiguredTimeoutInSeconds() throws Exception {
		FilterChain chain = mock(FilterChain.class);

		run(filter("120"), true, chain);

		verify(session).setMaxInactiveInterval(7200);
		verify(session).setAttribute(CHECKED, true);
		verify(chain).doFilter(any(), any());
	}

	@Test
	void leavesAnotherUsersTimeoutAlone() throws Exception {
		run(filter("120"), false, mock(FilterChain.class));

		verify(session, never()).setMaxInactiveInterval(anyInt());
		verify(session).setAttribute(CHECKED, false);
	}

	@Test
	void checksEachSessionOnce() throws Exception {
		when(session.getAttribute(CHECKED)).thenReturn(false);

		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class)) {
			filter("120").doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
			admins.verify(() -> SecurityAdminUtils.userIsAdmin(any()), never());
		}
		verify(session, never()).setMaxInactiveInterval(anyInt());
	}

	@Test
	void doesNothingWithoutAUsableTimeout() throws Exception {
		for (String timeout : new String[] { null, "two hours" }) {
			setUp();
			FilterChain chain = mock(FilterChain.class);

			run(filter(timeout), true, chain);

			verify(session, never()).setMaxInactiveInterval(anyInt());
			verify(session, never()).setAttribute(anyString(), any());
			verify(chain).doFilter(any(), any());
		}
	}

	@Test
	void passesARequestWithoutASession() throws Exception {
		when(request.getSession(false)).thenReturn(null);
		FilterChain chain = mock(FilterChain.class);

		run(filter("120"), true, chain);

		verify(chain).doFilter(any(), any());
	}
}
