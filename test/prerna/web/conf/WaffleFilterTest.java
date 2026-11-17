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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;

class WaffleFilterTest {

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		for (String name : new String[] { "autoAdd", "tracker", "userLogger", "filterConfig" }) {
			Field field = WaffleFilter.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	private static WaffleFilter filter(Map<String, String> params) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		WaffleFilter filter = new WaffleFilter();
		filter.init(config);
		return filter;
	}

	private static HttpServletRequest request(User sessionUser, String principal) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(sessionUser);
		when(request.getSession(true)).thenReturn(session);
		Principal windowsUser = mock(Principal.class);
		when(windowsUser.getName()).thenReturn(principal);
		when(request.getUserPrincipal()).thenReturn(windowsUser);
		return request;
	}

	private static AccessToken signIn(WaffleFilter filter, HttpServletRequest request, boolean autoAdd)
			throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
		try (MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			filter.doFilter(request, response, chain);
			users.verify(() -> UserResource.addAccessToken(token.capture(), eq(request), eq(autoAdd)));
		}
		verify(chain).doFilter(request, response);
		return token.getValue();
	}

	@Test
	void signsInTheWindowsPrincipalWithItsShortName() throws Exception {
		AccessToken token = signIn(filter(Map.of()), request(null, "CORP\\jdoe"), true);

		assertEquals(AuthProvider.WINDOWS_USER, token.getProvider());
		assertEquals("CORP\\jdoe", token.getId());
		assertEquals("jdoe", token.getName());
	}

	@Test
	void usesTheWholePrincipalWhenItHasNoDomain() throws Exception {
		AccessToken token = signIn(filter(Map.of()), request(null, "jdoe"), true);

		assertEquals("jdoe", token.getName());
	}

	@Test
	void honorsAnAutoAddOfFalse() throws Exception {
		AccessToken token = signIn(filter(Map.of("autoAdd", "false")), request(null, "CORP\\jdoe"), false);

		assertEquals("CORP\\jdoe", token.getId());
	}

	@Test
	void keepsAnExistingSessionUser() throws Exception {
		HttpServletRequest request = request(mock(User.class), "CORP\\jdoe");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		try (MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			filter(Map.of()).doFilter(request, response, chain);
			users.verify(() -> UserResource.addAccessToken(any(), any(), anyBoolean()), never());
		}
		verify(chain).doFilter(request, response);
	}
}
