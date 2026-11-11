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
package prerna.semoss.web.services.saml;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.SSOUtil;

class IdpSSOServletTest {

	private static final String IDP_REQUEST = "https://idp.example.com/openam/idpssoinit"
			+ "?NameIDFormat=urn:oasis:names:tc:SAML:2.0:nameid-format:transient&metaAlias=/idp"
			+ "&spEntityID=https://semoss.example.com/sp&binding=urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

	@Test
	void refusesARedirectOutsideTheAllowedDomains() throws Exception {
		try (Statics statics = new Statics("example.com")) {
			for (String redirect : new String[] { "https://evil.org/home", "//evil.org/home", "javascript:alert(1)",
					"https://example.com.evil.org/" }) {
				HttpServletRequest req = request(null, redirect);
				HttpServletResponse resp = mock(HttpServletResponse.class);

				new IdpSSOServlet().doGet(req, resp);

				verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
				verify(resp, never()).sendRedirect(anyString());
				verify(req, never()).getSession();
			}
			statics.sso.verifyNoInteractions();
		}
	}

	@Test
	void sendsASignedInSamlUserStraightToAnAllowedRedirect() throws Exception {
		User user = mock(User.class);
		when(user.getAccessToken(AuthProvider.SAML)).thenReturn(new AccessToken());
		try (Statics statics = new Statics("example.com")) {
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new IdpSSOServlet().doGet(request(session(user), " https://app.example.com/home "), resp);

			verify(resp).sendRedirect("https://app.example.com/home");
			statics.sso.verifyNoInteractions();
		}
	}

	@Test
	void remembersTheRedirectAndSendsEveryoneElseToTheIdp() throws Exception {
		User nativeUser = mock(User.class);
		for (HttpSession existing : new HttpSession[] { null, session(nativeUser) }) {
			try (Statics statics = new Statics("example.com")) {
				HttpServletRequest req = request(existing, "https://app.example.com/home");
				HttpServletResponse resp = mock(HttpServletResponse.class);

				new IdpSSOServlet().doGet(req, resp);

				verify(req.getSession()).setAttribute(SSOUtil.SAML_REDIRECT_KEY, "https://app.example.com/home");
				verify(statics.util).configureSSO(req, resp);
				verify(resp).sendRedirect(IDP_REQUEST);
			}
		}
	}

	@Test
	void storesNothingWithoutARedirect() throws Exception {
		try (Statics statics = new Statics(null)) {
			HttpServletRequest req = request(null, "  ");
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new IdpSSOServlet().doGet(req, resp);

			verify(req.getSession(), never()).setAttribute(eq(SSOUtil.SAML_REDIRECT_KEY), any());
			verify(resp).sendRedirect(IDP_REQUEST);
		}
	}

	/**
	 * The redirect whitelist and the SSO metadata the servlet reads.
	 */
	private static final class Statics implements AutoCloseable {

		private final SSOUtil util = mock(SSOUtil.class);
		private final MockedStatic<Utility> utility;
		private final MockedStatic<SSOUtil> sso = mockStatic(SSOUtil.class);

		private Statics(String whiteListDomains) {
			utility = mockStatic(Utility.class,
					invocation -> invocation.getMethod().getName().equals("getDIHelperProperty")
							? (Constants.WHITE_LIST_DOMAINS.equals(invocation.getArgument(0)) ? whiteListDomains : null)
							: invocation.callRealMethod());
			Map<String, String> metadata = new HashMap<>();
			metadata.put("idpBaseUrl", "https://idp.example.com/openam");
			metadata.put("idpMetaAlias", "/idp");
			metadata.put("spEntityID", "https://semoss.example.com/sp");
			when(util.getSSOMap()).thenReturn(metadata);
			sso.when(SSOUtil::getInstance).thenReturn(util);
		}

		@Override
		public void close() {
			sso.close();
			utility.close();
		}
	}

	private static HttpSession session(User user) {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return session;
	}

	private static HttpServletRequest request(HttpSession session, String redirect) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter("redirect")).thenReturn(redirect);
		HttpSession created = session == null ? mock(HttpSession.class) : session;
		when(req.getSession(false)).thenReturn(session);
		when(req.getSession()).thenReturn(created);
		when(req.getSession(true)).thenReturn(created);
		return req;
	}
}
