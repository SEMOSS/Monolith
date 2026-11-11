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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.sun.identity.saml.common.SAMLUtils;
import com.sun.identity.saml2.assertion.Assertion;
import com.sun.identity.saml2.assertion.AssertionFactory;
import com.sun.identity.saml2.assertion.Attribute;
import com.sun.identity.saml2.assertion.AttributeStatement;
import com.sun.identity.saml2.assertion.NameID;
import com.sun.identity.saml2.common.SAML2Constants;
import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.profile.SPACSUtils;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.conf.util.SSOUtil;

class SamlVerifierServletTest {

	private static final String ISSUER = "https://idp.example.com/openam";
	private static final String LOGIN_REDIRECT = "https://semoss.example.com/SemossWeb/";

	@TempDir
	Path temp;

	@Test
	void signsInTheUserTheAssertionNamesAndReturnsThemWhereTheyStarted() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			statics.respond(assertion(attribute("uid", "u-1"), attribute("mail", "ada@example.com"),
					attribute("givenName", "Ada"), attribute("sn", "Lovelace")), null);
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(SSOUtil.SAML_REDIRECT_KEY)).thenReturn("https://semoss.example.com/SemossWeb/");
			HttpServletRequest req = request(session);
			HttpServletResponse resp = mock(HttpServletResponse.class);

			servlet(Map.of()).verifySamlOutput(req, resp);

			AccessToken token = statics.signedIn();
			assertEquals(AuthProvider.SAML, token.getProvider());
			assertEquals("u-1", token.getId());
			assertEquals("ada@example.com", token.getEmail());
			assertEquals("Ada Lovelace", token.getName());
			assertNull(token.getUsername());
			statics.users.verify(() -> UserResource.addAccessToken(any(AccessToken.class), eq(req), eq(true)));
			verify(statics.util).configureSSO(req, resp);
			verify(resp).sendRedirect("https://semoss.example.com/SemossWeb/");
			statics.startup.verify(() -> AdminStartupFilter.setSuccessfulRedirectUrl("https://semoss.example.com/SemossWeb/"));
		}
	}

	@Test
	void fallsBackToTheNameIdAndTheConfiguredLoginPage() throws Exception {
		Map<String, String[]> mapping = new HashMap<>(mapping());
		mapping.remove("id");
		try (Statics statics = new Statics(temp, mapping)) {
			statics.respond(assertion(attribute("mail", "ada@example.com")), null);
			HttpServletResponse resp = mock(HttpServletResponse.class);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), resp);

			assertEquals("transient-1", statics.signedIn().getId());
			verify(resp).sendRedirect(LOGIN_REDIRECT);
		}
	}

	@Test
	void readsTheWholeSignedValuePastAComment() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			// signature checks skip comments, so the signed value is the whole text
			statics.respond(assertion(attribute("uid", "admin@corp.com<!---->.evil.com")), null);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), mock(HttpServletResponse.class));

			assertEquals("admin@corp.com.evil.com", statics.signedIn().getId());
		}
	}

	@Test
	void decodesAnEscapedCharacterInAValue() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			statics.respond(assertion(attribute("uid", "u-1"), attribute("givenName", "Smith &amp; Co")), null);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), mock(HttpServletResponse.class));

			assertEquals("Smith & Co", statics.signedIn().getName());
		}
	}

	@Test
	void skipsAnEmptyValue() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			statics.respond(assertion(attribute("uid", "u-1"), attribute("givenName", "Ada"), attribute("sn", "")),
					null);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), mock(HttpServletResponse.class));

			assertEquals("Ada", statics.signedIn().getName());
		}
	}

	@Test
	void handsARelayStateValidationBackWithoutSigningAnyoneIn() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			String relay = "https://semoss.example.com/SemossWeb/?sendRedirectForValidationNow=true";
			statics.respond(assertion(attribute("uid", "u-1")), relay);
			HttpServletResponse resp = mock(HttpServletResponse.class);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), resp);

			verify(resp).sendRedirect(relay);
			statics.users.verifyNoInteractions();
		}
	}

	@Test
	void anOrdinaryRelayStateDoesNotRedirect() throws Exception {
		try (Statics statics = new Statics(temp, mapping())) {
			statics.respond(assertion(attribute("uid", "u-1")), "https://elsewhere.example.org/");
			HttpServletResponse resp = mock(HttpServletResponse.class);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), resp);

			verify(resp, never()).sendRedirect("https://elsewhere.example.org/");
			verify(resp).sendRedirect(LOGIN_REDIRECT);
		}
	}

	@Test
	void turnsAwayAUserWithNoAllowedGroupWhenGroupsAreRequired() throws Exception {
		try (Statics statics = new Statics(temp, mapping());
				MockedStatic<AdminSecurityGroupUtils> groups = mockStatic(AdminSecurityGroupUtils.class)) {
			groups.when(() -> AdminSecurityGroupUtils.getMatchingGroupsByType(any(), anyString())).thenReturn(Set.of());
			Map<String, String> whitelist = Map.of("useSAMLGroupWhitelist", "true");

			statics.respond(assertion(attribute("uid", "u-1")), null);
			HttpServletResponse noGroups = mock(HttpServletResponse.class);
			servlet(whitelist).verifySamlOutput(request(mock(HttpSession.class)), noGroups);
			verify(noGroups).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
			groups.verifyNoInteractions();

			statics.respond(assertion(attribute("uid", "u-1"), attribute("memberOf", "strangers")), null);
			HttpServletResponse unknownGroup = mock(HttpServletResponse.class);
			servlet(whitelist).verifySamlOutput(request(mock(HttpSession.class)), unknownGroup);
			verify(unknownGroup).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
			groups.verify(() -> AdminSecurityGroupUtils.getMatchingGroupsByType(Set.of("strangers"), ISSUER));

			statics.users.verifyNoInteractions();
		}
	}

	@Test
	void putsTheAllowedGroupsOnTheToken() throws Exception {
		try (Statics statics = new Statics(temp, mapping());
				MockedStatic<AdminSecurityGroupUtils> groups = mockStatic(AdminSecurityGroupUtils.class)) {
			groups.when(() -> AdminSecurityGroupUtils.getMatchingGroupsByType(any(), eq(ISSUER)))
					.thenReturn(Set.of("analysts"));
			statics.respond(assertion(attribute("uid", "u-1"), attribute("memberOf", "analysts", "strangers")), null);

			servlet(Map.of("useSAMLGroupWhitelist", "true")).verifySamlOutput(request(mock(HttpSession.class)),
					mock(HttpServletResponse.class));

			AccessToken token = statics.signedIn();
			assertEquals(Set.of("analysts"), Set.copyOf(token.getUserGroups()));
			assertEquals(ISSUER, token.getUserGroupType());
		}
	}

	@Test
	void ignoresGroupsWhileTheWhitelistIsOff() throws Exception {
		try (Statics statics = new Statics(temp, mapping());
				MockedStatic<AdminSecurityGroupUtils> groups = mockStatic(AdminSecurityGroupUtils.class)) {
			statics.respond(assertion(attribute("uid", "u-1"), attribute("memberOf", "analysts")), null);

			servlet(Map.of()).verifySamlOutput(request(mock(HttpSession.class)), mock(HttpServletResponse.class));

			assertTrue(statics.signedIn().getUserGroups().isEmpty());
			groups.verifyNoInteractions();
		}
	}

	@Test
	void reportsAResponseTheFederationLibraryRejects() throws Exception {
		try (Statics statics = new Statics(temp, mapping());
				MockedStatic<SAMLUtils> saml = mockStatic(SAMLUtils.class)) {
			statics.acs.when(() -> SPACSUtils.processResponseForFedlet(any(), any(), any()))
					.thenThrow(new SAML2Exception("Invalid signature"));
			HttpServletRequest req = request(mock(HttpSession.class));
			HttpServletResponse resp = mock(HttpServletResponse.class);

			servlet(Map.of()).verifySamlOutput(req, resp);

			saml.verify(() -> SAMLUtils.sendError(req, resp, 500, "failedToProcessSSOResponse", "Invalid signature"));
			statics.users.verifyNoInteractions();
			verify(resp, never()).sendRedirect(anyString());
		}
	}

	private static Map<String, String[]> mapping() {
		Map<String, String[]> mapping = new HashMap<>();
		mapping.put("id", new String[] { "uid" });
		mapping.put("email", new String[] { "mail" });
		mapping.put("name", new String[] { "givenname", "sn" });
		mapping.put("groups", new String[] { "memberof" });
		return mapping;
	}

	/**
	 * An attribute as the federation library parses it out of the assertion's
	 * XML, so each value is the raw AttributeValue element.
	 */
	private static Attribute attribute(String name, String... values) throws SAML2Exception {
		StringBuilder xml = new StringBuilder(
				"<saml:Attribute xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" Name=\"" + name + "\">");
		for (String value : values) {
			xml.append("<saml:AttributeValue>").append(value).append("</saml:AttributeValue>");
		}
		xml.append("</saml:Attribute>");
		return AssertionFactory.getInstance().createAttribute(xml.toString());
	}

	private static Assertion assertion(Attribute... attributes) {
		AttributeStatement statement = mock(AttributeStatement.class);
		when(statement.getAttribute()).thenReturn(new ArrayList<>(List.of(attributes)));
		Assertion assertion = mock(Assertion.class);
		when(assertion.getAttributeStatements()).thenReturn(new ArrayList<>(List.of(statement)));
		return assertion;
	}

	private static SamlVerifierServlet servlet(Map<String, String> initParameters) throws Exception {
		ServletConfig config = mock(ServletConfig.class);
		when(config.getInitParameter(anyString())).thenAnswer(inv -> initParameters.get(inv.getArgument(0)));
		SamlVerifierServlet servlet = new SamlVerifierServlet();
		servlet.init(config);
		return servlet;
	}

	private static HttpServletRequest request(HttpSession session) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getSession(true)).thenReturn(session);
		when(req.getSession()).thenReturn(session);
		return req;
	}

	/**
	 * The federation library, the settings and the sign in the servlet hands the
	 * verified user to.
	 */
	private static final class Statics implements AutoCloseable {

		private final SSOUtil util = mock(SSOUtil.class);
		private final MockedStatic<Utility> utility;
		private final MockedStatic<SSOUtil> sso = mockStatic(SSOUtil.class);
		private final MockedStatic<SPACSUtils> acs = mockStatic(SPACSUtils.class);
		private final MockedStatic<SocialPropertiesUtil> social = mockStatic(SocialPropertiesUtil.class);
		private final MockedStatic<UserResource> users = mockStatic(UserResource.class);
		private final MockedStatic<AdminStartupFilter> startup = mockStatic(AdminStartupFilter.class);

		private Statics(Path temp, Map<String, String[]> mapping) {
			String federationLog = temp.resolve("Federation").toString();
			utility = mockStatic(Utility.class,
					invocation -> invocation.getMethod().getName().equals("getDIHelperProperty")
							? (Constants.SAML_FEDERATION_LOG_PATH.equals(invocation.getArgument(0)) ? federationLog : null)
							: invocation.callRealMethod());
			sso.when(SSOUtil::getInstance).thenReturn(util);
			SocialPropertiesUtil properties = mock(SocialPropertiesUtil.class);
			when(properties.getSamlAttributeNames()).thenReturn(mapping);
			when(properties.getLoginRedirect()).thenReturn(LOGIN_REDIRECT);
			social.when(SocialPropertiesUtil::getInstance).thenReturn(properties);
		}

		private void respond(Assertion assertion, String relayState) {
			NameID nameId = mock(NameID.class);
			when(nameId.getValue()).thenReturn("transient-1");
			Map<String, Object> verified = new HashMap<>();
			verified.put(SAML2Constants.ASSERTION, assertion);
			verified.put(SAML2Constants.NAMEID, nameId);
			verified.put(SAML2Constants.IDPENTITYID, ISSUER);
			verified.put(SAML2Constants.RELAY_STATE, relayState);
			acs.when(() -> SPACSUtils.processResponseForFedlet(any(), any(), any())).thenReturn(verified);
		}

		private AccessToken signedIn() {
			ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
			users.verify(() -> UserResource.addAccessToken(token.capture(), any(), anyBoolean()));
			return token.getValue();
		}

		@Override
		public void close() {
			startup.close();
			users.close();
			social.close();
			acs.close();
			sso.close();
			utility.close();
		}
	}
}
