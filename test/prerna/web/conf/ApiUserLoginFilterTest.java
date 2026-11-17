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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

class ApiUserLoginFilterTest {

	private static final String API_URI = "/Monolith/api/engine/runPixel";

	private static String basic(String credentials) {
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	private static HttpServletRequest request(String uri, String authorization) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		when(request.getHeader("Authorization")).thenReturn(authorization);
		return request;
	}

	/**
	 * Runs the filter and returns the token it signed in with, or null when it
	 * signed nobody in. The API user {@code client} has the secret {@code secret}.
	 */
	private static AccessToken run(HttpServletRequest request, Map<String, Boolean> loginsAllowed) throws Exception {
		SocialPropertiesUtil social = mock(SocialPropertiesUtil.class);
		when(social.getLoginsAllowed()).thenReturn(loginsAllowed);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
		boolean[] signedIn = { false };
		try (MockedStatic<SocialPropertiesUtil> socials = mockStatic(SocialPropertiesUtil.class);
				MockedStatic<SecurityAPIUserUtils> apiUsers = mockStatic(SecurityAPIUserUtils.class);
				MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			socials.when(SocialPropertiesUtil::getInstance).thenReturn(social);
			apiUsers.when(() -> SecurityAPIUserUtils.validCredentials("client", "secret")).thenReturn(true);
			apiUsers.when(() -> SecurityAPIUserUtils.validCredentials("client", "sec:ret")).thenReturn(true);
			users.when(() -> UserResource.addAccessToken(token.capture(), eq(request), eq(false)))
					.then(i -> signedIn[0] = true);
			new ApiUserLoginFilter().doFilter(request, response, chain);
		}
		verify(chain).doFilter(request, response);
		return signedIn[0] ? token.getValue() : null;
	}

	private static AccessToken run(HttpServletRequest request) throws Exception {
		return run(request, Map.of("api_user", true));
	}

	@Test
	void passesASignedInUserWithoutReadingHeaders() throws Exception {
		HttpServletRequest request = request(API_URI, basic("client:secret"));
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));
		when(request.getSession(false)).thenReturn(session);
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		new ApiUserLoginFilter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(request, never()).getHeader(anyString());
	}

	@Test
	void leavesTheModelAndExtensionRoutesToTheirOwnFilters() throws Exception {
		for (String uri : new String[] { "/Monolith/api/model/openai/v1/chat", "/Monolith/api/ext/mcp/tools" }) {
			HttpServletRequest request = request(uri, basic("client:secret"));
			FilterChain chain = mock(FilterChain.class);
			HttpServletResponse response = mock(HttpServletResponse.class);

			try (MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
				new ApiUserLoginFilter().doFilter(request, response, chain);
				users.verify(() -> UserResource.addAccessToken(any(), any(), anyBoolean()), never());
			}
			verify(chain).doFilter(request, response);
		}
	}

	@Test
	void passesWhenApiUserLoginIsOff() throws Exception {
		assertNull(run(request(API_URI, basic("client:secret")), Map.of()));
		assertNull(run(request(API_URI, basic("client:secret")), Map.of("api_user", false)));
	}

	@Test
	void passesARequestWithoutAnAuthorizationHeader() throws Exception {
		assertNull(run(request(API_URI, null)));
	}

	@Test
	void signsInAnApiUserWithItsClientIdAndSecret() throws Exception {
		AccessToken token = run(request(API_URI, basic("client:secret")));

		assertEquals("client", token.getId());
		assertEquals(AuthProvider.API_USER, token.getProvider());
	}

	@Test
	void keepsAColonThatIsPartOfTheSecret() throws Exception {
		assertEquals("client", run(request(API_URI, basic("client:sec:ret"))).getId());
	}

	@Test
	void refusesCredentialsThatDoNotValidate() throws Exception {
		assertNull(run(request(API_URI, basic("client:wrong"))));
		assertNull(run(request(API_URI, basic("client"))));
	}

	@Test
	void leavesABearerTokenToTheFiltersAfterIt() throws Exception {
		assertNull(run(request(API_URI, "Bearer tok-1")));
	}

	@Test
	void passesAMalformedBasicHeaderOnWithoutAnError() throws Exception {
		assertNull(run(request(API_URI, "Basic %%%not-base64%%%")));
	}
}
