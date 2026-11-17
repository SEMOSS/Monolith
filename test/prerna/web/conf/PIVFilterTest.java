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
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

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
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;

class PIVFilterTest {

	private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
	private static final String PIV_SUBJECT = "UID=Jane.Doe@Agency.gov, CN=Jane Doe, OU=People, O=Agency, C=US";

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		for (String name : new String[] { "autoAdd", "updateUser", "tracker", "userLogger", "filterConfig" }) {
			Field field = PIVFilter.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	private static PIVFilter filter(Map<String, String> params) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		PIVFilter filter = new PIVFilter();
		filter.init(config);
		return filter;
	}

	private static X509Certificate cert(String subject, Collection<List<?>> altNames) throws Exception {
		X509Certificate cert = mock(X509Certificate.class);
		when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
		when(cert.getSubjectAlternativeNames()).thenReturn(altNames);
		return cert;
	}

	private static HttpServletRequest request(User sessionUser, X509Certificate... certs) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(sessionUser);
		when(request.getSession(true)).thenReturn(session);
		when(request.getAttribute(CERT_ATTRIBUTE)).thenReturn(certs.length == 0 ? null : certs);
		return request;
	}

	private static AccessToken signIn(PIVFilter filter, HttpServletRequest request, boolean autoAdd)
			throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<SecurityUpdateUtils> updates = mockStatic(SecurityUpdateUtils.class)) {
			filter.doFilter(request, response, chain);
			users.verify(() -> UserResource.addAccessToken(token.capture(), eq(request), eq(autoAdd)));
		}
		verify(chain).doFilter(request, response);
		return token.getValue();
	}

	private static void assertNoSignIn(PIVFilter filter, HttpServletRequest request) throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<UserResource> users = mockStatic(UserResource.class)) {
			filter.doFilter(request, response, chain);
			users.verify(() -> UserResource.addAccessToken(any(), any(), anyBoolean()), never());
		}
		verify(chain).doFilter(request, response);
	}

	@Test
	void passesARequestWithoutCertificatesWithoutSigningIn() throws Exception {
		assertNoSignIn(filter(Map.of()), request(null));
	}

	@Test
	void signsInFromTheUidEmailAndCommonName() throws Exception {
		AccessToken token = signIn(filter(Map.of()), request(null, cert(PIV_SUBJECT, List.of())), true);

		assertEquals(AuthProvider.CAC, token.getProvider());
		assertEquals("jane.doe@agency.gov", token.getId());
		assertEquals("jane.doe@agency.gov", token.getEmail());
		assertEquals("Jane Doe", token.getName());
	}

	@Test
	void fallsBackToTheEmailAlternativeName() throws Exception {
		X509Certificate cert = cert("CN=Jane Doe, O=Agency, C=US",
				List.of(List.of(2, "agency.gov"), List.of(1, "Jane.Doe@Agency.gov")));

		AccessToken token = signIn(filter(Map.of()), request(null, cert), true);

		assertEquals("jane.doe@agency.gov", token.getId());
		assertEquals("Jane Doe", token.getName());
	}

	@Test
	void prefersTheAlternativeNameEmailOverAUidThatIsNotAnEmail() throws Exception {
		X509Certificate cert = cert("UID=jdoe, CN=Jane Doe, O=Agency", List.of(List.of(1, "jane@agency.gov")));

		AccessToken token = signIn(filter(Map.of()), request(null, cert), true);

		assertEquals("jane@agency.gov", token.getId());
	}

	@Test
	void ignoresACertificateWithoutAnEmail() throws Exception {
		assertNoSignIn(filter(Map.of()), request(null, cert("CN=Jane Doe, O=Agency, C=US", List.of())));
	}

	@Test
	void ignoresACertificateWithoutAlternativeNamesOrAnEmail() throws Exception {
		assertNoSignIn(filter(Map.of()), request(null, cert("UID=jdoe, CN=Jane Doe, O=Agency", null)));
	}

	@Test
	void ignoresACertificateWithoutACommonName() throws Exception {
		assertNoSignIn(filter(Map.of()), request(null, cert("UID=jane@agency.gov, O=Agency", List.of())));
	}

	@Test
	void keepsAnExistingSessionUser() throws Exception {
		assertNoSignIn(filter(Map.of()), request(mock(User.class), cert(PIV_SUBJECT, List.of())));
	}

	@Test
	void honorsAnAutoAddOfFalse() throws Exception {
		AccessToken token = signIn(filter(Map.of("autoAdd", "false")), request(null, cert(PIV_SUBJECT, List.of())),
				false);

		assertEquals("jane.doe@agency.gov", token.getId());
	}

	@Test
	void updatesTheStoredUserWhenConfigured() throws Exception {
		PIVFilter filter = filter(Map.of("updateUserInfo", "true"));
		HttpServletRequest request = request(null, cert(PIV_SUBJECT, List.of()));

		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<SecurityUpdateUtils> updates = mockStatic(SecurityUpdateUtils.class)) {
			filter.doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
			updates.verify(() -> SecurityUpdateUtils.updateOAuthUser(any(AccessToken.class)));
		}
	}
}
