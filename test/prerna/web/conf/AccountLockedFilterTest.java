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

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.usertracking.AuditErrorCode;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class AccountLockedFilterTest {

	private static final String URL = "http://localhost:8080/Monolith/api/engine/runPixel";

	private static HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(URL));
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(request.getSession(false)).thenReturn(session);
		}
		return request;
	}

	private static User user(boolean locked) {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		token.setLocked(locked);
		User user = mock(User.class);
		when(user.getPrimaryLogin()).thenReturn(AuthProvider.NATIVE);
		when(user.getAccessToken(AuthProvider.NATIVE)).thenReturn(token);
		return user;
	}

	@Test
	void passesARequestWithoutASession() throws Exception {
		HttpServletRequest request = request(null);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new AccountLockedFilter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void passesAnUnlockedUser() throws Exception {
		HttpServletRequest request = request(user(false));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new AccountLockedFilter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void redirectsALockedUserToTheAccountLockedPage() throws Exception {
		HttpServletRequest request = request(user(true));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		try (MockedStatic<UserAuditTrailUtils> audit = mockStatic(UserAuditTrailUtils.class)) {
			new AccountLockedFilter().doFilter(request, response, chain);

			audit.verify(() -> UserAuditTrailUtils.recordHttpDenial(AuditErrorCode.ACCOUNT_LOCKED,
					"User account is locked", 302));
		}

		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/accountLocked/");
		verify(response).sendError(302, "Redirect required");
		verify(chain, never()).doFilter(request, response);
	}
}
