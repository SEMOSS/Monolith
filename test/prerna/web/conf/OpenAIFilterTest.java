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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

class OpenAIFilterTest {

	private static final String OPENAI = "/Monolith/api/model/openai/v1/chat/completions";
	private static final String ANTHROPIC = "/Monolith/api/model/anthropic/v1/messages";

	private static HttpServletRequest request(String uri) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		return request;
	}

	private static AccessToken token() {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		return token;
	}

	/**
	 * Runs the filter with access key validation stubbed to accept ak:sk and
	 * returns whether a login happened.
	 */
	private static boolean run(HttpServletRequest request, boolean accessKeysAllowed, FilterChain chain)
			throws Exception {
		AccessToken token = token();
		SocialPropertiesUtil social = mock(SocialPropertiesUtil.class);
		when(social.accessKeysAllowed(AuthProvider.NATIVE)).thenReturn(accessKeysAllowed);
		boolean[] signedIn = { false };
		try (MockedStatic<SecurityUserAccessKeyUtils> keys = mockStatic(SecurityUserAccessKeyUtils.class);
				MockedStatic<SocialPropertiesUtil> socials = mockStatic(SocialPropertiesUtil.class);
				MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			keys.when(() -> SecurityUserAccessKeyUtils.validateKeysAndReturnToken("ak", "sk")).thenReturn(token);
			socials.when(SocialPropertiesUtil::getInstance).thenReturn(social);
			users.when(() -> UserResource.addAccessToken(token, request, false)).then(i -> signedIn[0] = true);
			new OpenAIFilter().doFilter(request, mock(HttpServletResponse.class), chain);
			if (signedIn[0]) {
				keys.verify(() -> SecurityUserAccessKeyUtils.updateAccessTokenLastUsed("ak"));
			} else {
				keys.verify(() -> SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(anyString()), never());
			}
		}
		return signedIn[0];
	}

	private static ServletRequest passed(FilterChain chain) throws Exception {
		ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(passed.capture(), any(ServletResponse.class));
		return passed.getValue();
	}

	@Test
	void passesASignedInUserWithoutReadingHeaders() throws Exception {
		HttpServletRequest request = request(OPENAI);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));
		when(request.getSession(false)).thenReturn(session);
		FilterChain chain = mock(FilterChain.class);

		new OpenAIFilter().doFilter(request, mock(HttpServletResponse.class), chain);

		assertSame(request, passed(chain));
		verify(request, never()).getHeader(anyString());
	}

	@Test
	void signsInWithABearerAccessKeyPair() throws Exception {
		HttpServletRequest request = request(OPENAI);
		when(request.getHeader("Authorization")).thenReturn("Bearer ak:sk");
		FilterChain chain = mock(FilterChain.class);

		assertTrue(run(request, true, chain));
		assertTrue(passed(chain) instanceof HttpServletRequestWrapper);
	}

	@Test
	void signsInWithAnApiKeyHeaderOnTheAnthropicRoutes() throws Exception {
		HttpServletRequest request = request(ANTHROPIC);
		when(request.getHeader("x-api-key")).thenReturn(" ak:sk ");

		assertTrue(run(request, true, mock(FilterChain.class)));
	}

	@Test
	void ignoresAnApiKeyHeaderOffTheAnthropicRoutes() throws Exception {
		HttpServletRequest request = request(OPENAI);
		when(request.getHeader("x-api-key")).thenReturn("ak:sk");
		FilterChain chain = mock(FilterChain.class);

		assertFalse(run(request, true, chain));
		assertSame(request, passed(chain));
	}

	@Test
	void refusesAccessKeysTheAdministratorDisabledForTheProvider() throws Exception {
		HttpServletRequest request = request(OPENAI);
		when(request.getHeader("Authorization")).thenReturn("Bearer ak:sk");

		assertFalse(run(request, false, mock(FilterChain.class)));
	}

	@Test
	void refusesAnUnknownKeyPair() throws Exception {
		HttpServletRequest request = request(OPENAI);
		when(request.getHeader("Authorization")).thenReturn("Bearer ak:wrong");
		FilterChain chain = mock(FilterChain.class);

		assertFalse(run(request, true, chain));
		assertTrue(passed(chain) instanceof HttpServletRequestWrapper);
	}

	@Test
	void ignoresABearerValueThatIsNotAKeyPair() throws Exception {
		for (String header : new String[] { "Bearer ak", "Bearer ak:sk:extra", "Bearer ", "Basic YWs6c2s=" }) {
			HttpServletRequest request = request(OPENAI);
			when(request.getHeader("Authorization")).thenReturn(header);
			try (MockedStatic<SecurityUserAccessKeyUtils> keys = mockStatic(SecurityUserAccessKeyUtils.class);
					MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
				new OpenAIFilter().doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
				keys.verify(() -> SecurityUserAccessKeyUtils.validateKeysAndReturnToken(anyString(), anyString()),
						never());
				users.verify(() -> UserResource.addAccessToken(any(), any(), anyBoolean()), never());
			}
		}
	}
}
