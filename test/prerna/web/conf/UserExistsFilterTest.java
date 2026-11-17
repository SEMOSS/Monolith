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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;

class UserExistsFilterTest {

	private static final String BASE = "http://localhost:8080/Monolith";
	private static final String UI_REFERER = "http://localhost:8080/semoss-ui/";

	private AccessToken token;
	private User user;
	private HttpSession session;
	private HttpServletResponse response;
	private FilterChain chain;

	@BeforeEach
	void setUp() {
		token = new AccessToken();
		token.setProvider(AuthProvider.SAML);
		token.setId("jdoe");
		token.setUserGroups(Set.of("engineering"));
		token.setUserGroupType("SAML");
		user = mock(User.class);
		when(user.getLogins()).thenReturn(List.of(AuthProvider.SAML));
		when(user.getAccessToken(AuthProvider.SAML)).thenReturn(token);
		session = mock(HttpSession.class);
		response = mock(HttpServletResponse.class);
		chain = mock(FilterChain.class);
	}

	private static UserExistsFilter filter(Map<String, String> params) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		UserExistsFilter filter = new UserExistsFilter();
		filter.init(config);
		return filter;
	}

	private HttpServletRequest request(String path, User sessionUser) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL()).thenReturn(new StringBuffer(BASE + path));
		when(request.getPathInfo()).thenReturn(path.substring("/api".length()));
		when(request.getHeader("referer")).thenReturn(UI_REFERER);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(sessionUser);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private void run(Map<String, String> params, HttpServletRequest request, boolean registered,
			Set<String> matchingGroups) throws Exception {
		UserExistsFilter filter = filter(params);
		try (MockedStatic<SecurityQueryUtils> queries = mockStatic(SecurityQueryUtils.class);
				MockedStatic<AdminSecurityGroupUtils> groups = mockStatic(AdminSecurityGroupUtils.class);
				MockedStatic<UserAuditTrailUtils> audit = mockStatic(UserAuditTrailUtils.class)) {
			queries.when(() -> SecurityQueryUtils.checkUserExist("jdoe")).thenReturn(registered);
			groups.when(() -> AdminSecurityGroupUtils.getMatchingGroupsByType(Set.of("engineering"), "SAML"))
					.thenReturn(matchingGroups);
			filter.doFilter(request, response, chain);
		}
	}

	private void assertSentToLogin(HttpServletRequest request) throws Exception {
		verify(response).setStatus(302);
		verify(response).setHeader("redirect", UI_REFERER + "#!/login");
		verify(response).sendError(302, "Need to redirect to " + UI_REFERER + "#!/login");
		verify(chain, never()).doFilter(request, response);
	}

	private void assertForbidden(HttpServletRequest request) throws Exception {
		verify(session).removeAttribute(Constants.SESSION_USER);
		verify(response).sendError(403, "User lacks permissions for this resource");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void sendsASignedOutRequestBackToTheLogin() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);

		run(Map.of(), request, true, Set.of());

		assertSentToLogin(request);
	}

	@Test
	void sendsASignedOutPathParameterThatEndsInAnOpenEndpointBackToTheLogin() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", null);
		when(request.getRequestURL()).thenReturn(new StringBuffer(BASE + "/api/engine/runPixel;auth/logins"));

		run(Map.of(), request, true, Set.of());

		assertSentToLogin(request);
	}

	@Test
	void passesAnOpenEndpointWithoutAUser() throws Exception {
		HttpServletRequest request = request("/api/auth/logins", null);

		run(Map.of(), request, false, Set.of());

		verify(chain).doFilter(request, response);
	}

	@Test
	void passesARegisteredUser() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of(), request, true, Set.of());

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
		verify(session, never()).removeAttribute(anyString());
	}

	@Test
	void signsOutAUserTheAdminHasNotRegistered() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of(), request, false, Set.of());

		verify(session).removeAttribute(Constants.SESSION_USER);
		assertSentToLogin(request);
	}

	@Test
	void forbidsAUserOutsideTheAllowedGroups() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of("useGroupWhitelist", "true"), request, true, Set.of());

		assertForbidden(request);
	}

	@Test
	void forbidsAUserWithoutGroupsWhenTheWhitelistIsOn() throws Exception {
		token.setUserGroups(Set.of());
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of("useSAMLGroupWhitelist", "true"), request, true, Set.of("engineering"));

		assertForbidden(request);
	}

	@Test
	void passesAUserInAnAllowedGroup() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of("useSAMLGroupWhitelist", "true"), request, true, Set.of("engineering"));

		verify(chain).doFilter(request, response);
	}

	@Test
	void skipsTheRegistrationCheckWhenOnlyGroupsMatter() throws Exception {
		HttpServletRequest request = request("/api/engine/runPixel", user);

		run(Map.of("useGroupWhitelist", "true", "onlyGroupCheck", "true"), request, false, Set.of("engineering"));

		verify(chain).doFilter(request, response);
	}

	@Test
	void checksASingleLoginOnAnOpenEndpointToo() throws Exception {
		HttpServletRequest request = request("/api/auth/whoami", user);

		run(Map.of(), request, false, Set.of());

		assertForbidden(request);
	}

	@Test
	void failsClosedWhenTheGroupLookupFails() throws Exception {
		UserExistsFilter filter = filter(Map.of("useGroupWhitelist", "true"));
		HttpServletRequest request = request("/api/engine/runPixel", user);

		try (MockedStatic<AdminSecurityGroupUtils> groups = mockStatic(AdminSecurityGroupUtils.class)) {
			groups.when(() -> AdminSecurityGroupUtils.getMatchingGroupsByType(any(), eq("SAML")))
					.thenThrow(new IllegalStateException("security db is down"));

			assertThrows(IllegalArgumentException.class, () -> filter.doFilter(request, response, chain));
		}
		verify(chain, never()).doFilter(request, response);
	}
}
