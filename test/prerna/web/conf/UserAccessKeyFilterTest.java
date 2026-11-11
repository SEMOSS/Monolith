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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
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
import prerna.io.connector.google.GoogleTokenFiller;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

class UserAccessKeyFilterTest {

	private static final String API_URI = "/Monolith/api/engine/runPixel";

	private Boolean autoAdd;

	private static String basic(String credentials) {
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	private static HttpServletRequest request(String uri, String authorization) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		when(request.getHeader("Authorization")).thenReturn(authorization);
		return request;
	}

	private static Map<String, Object> provider(String label, boolean oauth) {
		return Map.of("label", label, "isOauth", oauth);
	}

	private static AccessToken keyToken() {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		return token;
	}

	/**
	 * Runs the filter with ak:sk accepted as a NATIVE access key pair and returns
	 * the token it signed in with, or null when it signed nobody in.
	 */
	private AccessToken run(HttpServletRequest request, SocialPropertiesUtil social, FilterChain chain)
			throws Exception {
		AccessToken keyToken = keyToken();
		ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
		ArgumentCaptor<Boolean> addUser = ArgumentCaptor.forClass(Boolean.class);
		boolean[] signedIn = { false };
		try (MockedStatic<SecurityUserAccessKeyUtils> keys = mockStatic(SecurityUserAccessKeyUtils.class);
				MockedStatic<SocialPropertiesUtil> socials = mockStatic(SocialPropertiesUtil.class);
				MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			keys.when(() -> SecurityUserAccessKeyUtils.validateKeysAndReturnToken("ak", "sk")).thenReturn(keyToken);
			socials.when(SocialPropertiesUtil::getInstance).thenReturn(social);
			users.when(() -> UserResource.addAccessToken(token.capture(), eq(request), addUser.capture()))
					.then(i -> signedIn[0] = true);
			new UserAccessKeyFilter().doFilter(request, mock(HttpServletResponse.class), chain);
			if (signedIn[0] && token.getValue() == keyToken) {
				keys.verify(() -> SecurityUserAccessKeyUtils.updateAccessTokenLastUsed("ak"));
				users.verify(() -> UserResource.addAccessToken(keyToken, request, false));
			} else {
				keys.verify(() -> SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(anyString()), never());
			}
		}
		autoAdd = signedIn[0] ? addUser.getValue() : null;
		return signedIn[0] ? token.getValue() : null;
	}

	private static SocialPropertiesUtil social(boolean accessKeysAllowed, List<Map<String, Object>> providers) {
		SocialPropertiesUtil social = mock(SocialPropertiesUtil.class);
		when(social.accessKeysAllowed(AuthProvider.NATIVE)).thenReturn(accessKeysAllowed);
		when(social.getAvailableProviders()).thenReturn(providers);
		return social;
	}

	private static ServletRequest passed(FilterChain chain) throws Exception {
		ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(passed.capture(), any(ServletResponse.class));
		return passed.getValue();
	}

	@Test
	void passesASignedInUserWithoutReadingHeaders() throws Exception {
		HttpServletRequest request = request(API_URI, basic("ak:sk"));
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));
		when(request.getSession(false)).thenReturn(session);
		FilterChain chain = mock(FilterChain.class);

		new UserAccessKeyFilter().doFilter(request, mock(HttpServletResponse.class), chain);

		assertSame(request, passed(chain));
		verify(request, never()).getHeader(anyString());
	}

	@Test
	void leavesTheModelRoutesToTheirOwnFilters() throws Exception {
		for (String uri : new String[] { "/Monolith/api/model/openai/v1/chat", "/Monolith/api/model/anthropic/v1",
				"/Monolith/api/ext/mcp/tools" }) {
			FilterChain chain = mock(FilterChain.class);
			HttpServletRequest request = request(uri, basic("ak:sk"));

			assertEquals(null, run(request, social(true, List.of()), chain));
			assertSame(request, passed(chain));
		}
	}

	@Test
	void passesARequestWithoutAnAuthorizationHeader() throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletRequest request = request(API_URI, null);

		assertEquals(null, run(request, social(true, List.of()), chain));
		assertSame(request, passed(chain));
	}

	@Test
	void signsInWithBasicAccessKeys() throws Exception {
		for (String header : new String[] { basic("ak:sk"), basic("ak:sk").replace("Basic", "basic") }) {
			FilterChain chain = mock(FilterChain.class);

			AccessToken token = run(request(API_URI, header), social(true, List.of()), chain);

			assertEquals("jdoe", token.getId());
			assertTrue(passed(chain) instanceof HttpServletRequestWrapper);
		}
	}

	@Test
	void refusesAccessKeysTheAdministratorDisabledForTheProvider() throws Exception {
		assertEquals(null, run(request(API_URI, basic("ak:sk")), social(false, List.of()), mock(FilterChain.class)));
	}

	@Test
	void refusesAnInvalidKeyPair() throws Exception {
		assertEquals(null, run(request(API_URI, basic("ak:wrong")), social(true, List.of()), mock(FilterChain.class)));
		assertEquals(null, run(request(API_URI, basic("ak")), social(true, List.of()), mock(FilterChain.class)));
	}

	@Test
	void passesABearerTokenForAProviderThatDoesNotExist() throws Exception {
		HttpServletRequest request = request(API_URI, "Bearer abc");
		when(request.getHeader("Bearer-Provider")).thenReturn("NOPE");

		assertEquals(null, run(request, social(true, List.of(provider("GOOGLE", true))), mock(FilterChain.class)));
	}

	@Test
	void passesABearerTokenWhenTheProviderCannotBeGuessed() throws Exception {
		List<Map<String, Object>> providers = List.of(provider("GOOGLE", true), provider("MICROSOFT", true),
				provider("NATIVE", false));

		assertEquals(null,
				run(request(API_URI, "Bearer abc"), social(true, providers), mock(FilterChain.class)));
	}

	@Test
	void passesABearerTokenForAProviderThatIsNotEnabled() throws Exception {
		HttpServletRequest request = request(API_URI, "Bearer abc");
		when(request.getHeader("Bearer-Provider")).thenReturn("GOOGLE");

		assertEquals(null, run(request, social(true, List.of(provider("MICROSOFT", true))), mock(FilterChain.class)));
	}

	@Test
	void passesABearerTokenForAProviderWithoutATokenFiller() throws Exception {
		HttpServletRequest request = request(API_URI, "Bearer abc");
		when(request.getHeader("Bearer-Provider")).thenReturn("NATIVE");

		assertEquals(null, run(request, social(true, List.of(provider("NATIVE", false))), mock(FilterChain.class)));
	}

	@Test
	void fillsABearerTokenFromTheOnlyOauthProviderAndSignsItIn() throws Exception {
		SocialPropertiesUtil social = social(true, List.of(provider("GOOGLE", true), provider("NATIVE", false)));
		when(social.getProperty("google_userinfo_url")).thenReturn("https://idp.example/userinfo");
		when(social.getProperty("google_jsonPattern")).thenReturn("[name, sub, email]");
		when(social.getProperty("google_auto_add", "true")).thenReturn("true");

		try (MockedConstruction<GoogleTokenFiller> fillers = mockConstruction(GoogleTokenFiller.class)) {
			AccessToken token = run(request(API_URI, "Bearer the-idp-token"), social, mock(FilterChain.class));

			assertEquals(AuthProvider.GOOGLE, token.getProvider());
			assertEquals("the-idp-token", token.getAccess_token());
			assertEquals(Boolean.TRUE, autoAdd);
			verify(fillers.constructed().get(0)).fillAccessToken(eq(token), eq("https://idp.example/userinfo"),
					eq("[name, sub, email]"), any(), isNull(), eq(false));
		}
	}
}
