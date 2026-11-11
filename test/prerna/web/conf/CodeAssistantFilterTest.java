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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import prerna.om.LocalUserStore;
import prerna.util.Constants;

class CodeAssistantFilterTest {

	private static final String SECRET = "local-secret";

	private String accessKey;
	private User keyUser;

	@BeforeEach
	void storeLocalKey() {
		accessKey = "local-" + UUID.randomUUID();
		LocalUserStore.getInstance().store(accessKey, new Object[] { SECRET, null, null });
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		keyUser = mock(User.class);
		when(keyUser.getPrimaryLoginToken()).thenReturn(token);
	}

	@AfterEach
	void removeLocalKey() {
		LocalUserStore.getInstance().remove(accessKey);
	}

	private static HttpServletRequest request(String remoteAddr) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn(remoteAddr);
		return request;
	}

	private HttpSession run(HttpServletRequest request, FilterChain chain) throws Exception {
		HttpSession session = mock(HttpSession.class);
		when(request.getSession(true)).thenReturn(session);
		try (MockedStatic<SecurityUserAccessKeyUtils> keys = mockStatic(SecurityUserAccessKeyUtils.class)) {
			keys.when(() -> SecurityUserAccessKeyUtils.validateLocalUserStore(accessKey, SECRET)).thenReturn(keyUser);
			new CodeAssistantFilter().doFilter(request, mock(HttpServletResponse.class), chain);
		}
		return session;
	}

	private void assertSignedIn(HttpServletRequest request) throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpSession session = run(request, chain);

		verify(session).setAttribute(Constants.SESSION_USER, keyUser);
		verify(session).setAttribute(Constants.SESSION_USER_ID_LOG, "jdoe");
		ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(passed.capture(), any(ServletResponse.class));
		assertTrue(passed.getValue() instanceof HttpServletRequestWrapper);
	}

	private void assertNotSignedIn(HttpServletRequest request) throws Exception {
		FilterChain chain = mock(FilterChain.class);

		run(request, chain);

		verify(request, never()).getSession(true);
		verify(chain).doFilter(any(ServletRequest.class), any(ServletResponse.class));
	}

	@Test
	void passesASignedInUserWithoutReadingCredentials() throws Exception {
		HttpServletRequest request = request("10.0.0.5");
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(keyUser);
		when(request.getSession(false)).thenReturn(session);
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		new CodeAssistantFilter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(request, never()).getHeader(anyString());
	}

	@Test
	void signsInALoopbackCallerWithABearerKeyPair() throws Exception {
		HttpServletRequest request = request("127.0.0.1");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey + ":" + SECRET);

		assertSignedIn(request);
	}

	@Test
	void signsInAnIpv6LoopbackCallerWithAnApiKeyHeader() throws Exception {
		HttpServletRequest request = request("0:0:0:0:0:0:0:1");
		when(request.getHeader("x-api-key")).thenReturn(" " + accessKey + ":" + SECRET + " ");

		assertSignedIn(request);
	}

	@Test
	void linksTheRoomNamedInTheThirdSegment() throws Exception {
		HttpServletRequest request = request("::1");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey + ":" + SECRET + ":room-abc123");

		assertSignedIn(request);
		verify(request).setAttribute("roomId", "abc123");
	}

	@Test
	void ignoresAThirdSegmentThatIsNotARoom() throws Exception {
		HttpServletRequest request = request("127.0.0.1");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey + ":" + SECRET + ":other");

		assertSignedIn(request);
		verify(request, never()).setAttribute(eq("roomId"), any());
	}

	@Test
	void ignoresCredentialsFromARemoteAddress() throws Exception {
		HttpServletRequest request = request("10.0.0.5");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey + ":" + SECRET);
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		try (MockedStatic<SecurityUserAccessKeyUtils> keys = mockStatic(SecurityUserAccessKeyUtils.class)) {
			new CodeAssistantFilter().doFilter(request, response, chain);
			keys.verify(() -> SecurityUserAccessKeyUtils.validateLocalUserStore(anyString(), anyString()), never());
		}

		verify(chain).doFilter(request, response);
		verify(request, never()).getSession(true);
	}

	@Test
	void refusesAWrongSecret() throws Exception {
		HttpServletRequest request = request("127.0.0.1");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey + ":wrong");

		assertNotSignedIn(request);
	}

	@Test
	void ignoresATokenWithoutAKeyPair() throws Exception {
		HttpServletRequest request = request("127.0.0.1");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + accessKey);

		assertNotSignedIn(request);
	}

	@Test
	void passesALoopbackCallerWithoutCredentialsUnchanged() throws Exception {
		HttpServletRequest request = request("127.0.0.1");
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		new CodeAssistantFilter().doFilter(request, response, chain);

		ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(passed.capture(), eq(response));
		assertSame(request, passed.getValue());
	}
}
