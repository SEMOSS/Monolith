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

import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
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
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.Utility;

class CACFilterTest {

	private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
	private static final String UPN_OID = "1.3.6.1.4.1.311.20.2.3";
	private static final String CAC_SUBJECT = "CN=DOE.JOHN.ALLEN.1234567890, OU=USA, OU=PKI, OU=DoD, O=U.S. Government, C=US";

	@BeforeEach
	void resetStaticConfig() throws ReflectiveOperationException {
		for (String name : new String[] { "autoAdd", "updateUser", "tracker", "userLogger", "filterConfig" }) {
			Field field = CACFilter.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	private static CACFilter filter(Map<String, String> params) throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		CACFilter filter = new CACFilter();
		filter.init(config);
		return filter;
	}

	private static X509Certificate cert(String subject, Collection<List<?>> altNames) throws Exception {
		X509Certificate cert = mock(X509Certificate.class);
		when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
		when(cert.getSubjectAlternativeNames()).thenReturn(altNames);
		return cert;
	}

	/** A subject alternative name extension with an email and an otherName of the given type. */
	private static byte[] alternativeNames(String otherNameType, String otherNameValue) throws Exception {
		ASN1EncodableVector otherName = new ASN1EncodableVector();
		otherName.add(new ASN1ObjectIdentifier(otherNameType));
		otherName.add(new DERTaggedObject(true, 0, new DERUTF8String(otherNameValue)));
		GeneralNames names = new GeneralNames(new GeneralName[] {
				new GeneralName(GeneralName.rfc822Name, "john.doe@mail.mil"),
				new GeneralName(GeneralName.otherName, new DERSequence(otherName)) });
		return new DEROctetString(names).getEncoded();
	}

	private static HttpServletRequest request(User sessionUser, X509Certificate... certs) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(sessionUser);
		when(request.getSession(true)).thenReturn(session);
		when(request.getAttribute(CERT_ATTRIBUTE)).thenReturn(certs.length == 0 ? null : certs);
		return request;
	}

	private static AccessToken signIn(CACFilter filter, HttpServletRequest request, boolean autoAdd)
			throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		ArgumentCaptor<AccessToken> token = ArgumentCaptor.forClass(AccessToken.class);
		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<SecurityUpdateUtils> updates = mockStatic(SecurityUpdateUtils.class)) {
			filter.doFilter(request, response, chain);
			users.verify(() -> UserResource.addAccessToken(token.capture(), eq(request), eq(autoAdd)));
		}
		verify(chain).doFilter(request, response);
		return token.getValue();
	}

	private static void assertNoSignIn(CACFilter filter, HttpServletRequest request) throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
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
	void signsInTheCacHolderFromTheCommonNameAndEmail() throws Exception {
		X509Certificate cert = cert(CAC_SUBJECT, List.of(List.of(0, new byte[0]), List.of(1, "John.Doe@Mail.MIL")));

		AccessToken token = signIn(filter(Map.of()), request(null, cert), true);

		assertEquals(AuthProvider.CAC, token.getProvider());
		assertEquals("1234567890", token.getId());
		assertEquals("DOE JOHN ALLEN", token.getName());
		assertEquals("john.doe@mail.mil", token.getEmail());
	}

	@Test
	void signsInACacHolderWhoseAlternativeNamesHaveNoEmail() throws Exception {
		AccessToken token = signIn(filter(Map.of()), request(null, cert(CAC_SUBJECT, List.of())), true);

		assertEquals("1234567890", token.getId());
		assertNull(token.getEmail());
	}

	@Test
	void honorsAnAutoAddOfFalse() throws Exception {
		AccessToken token = signIn(filter(Map.of("autoAdd", "false")), request(null, cert(CAC_SUBJECT, List.of())),
				false);

		assertEquals("1234567890", token.getId());
	}

	@Test
	void updatesTheStoredUserWhenConfigured() throws Exception {
		CACFilter filter = filter(Map.of("updateUserInfo", "true"));
		HttpServletRequest request = request(null, cert(CAC_SUBJECT, List.of()));

		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<SecurityUpdateUtils> updates = mockStatic(SecurityUpdateUtils.class)) {
			filter.doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
			updates.verify(() -> SecurityUpdateUtils.updateOAuthUser(any(AccessToken.class)));
		}
	}

	@Test
	void keepsAnExistingSessionUser() throws Exception {
		assertNoSignIn(filter(Map.of()), request(mock(User.class), cert(CAC_SUBJECT, List.of())));
	}

	@Test
	void ignoresACommonNameThatIsNotACacIdentity() throws Exception {
		CACFilter filter = filter(Map.of());

		assertNoSignIn(filter, request(null, cert("CN=server.example.com, O=Example", null)));
		assertNoSignIn(filter, request(null, cert("CN=DOE.JOHN.12345, O=U.S. Government", null)));
		assertNoSignIn(filter, request(null, cert("OU=DOE.JOHN.ALLEN.1234567890, O=U.S. Government", null)));
	}

	@Test
	void usesTheFirstCertificateInTheChainThatCarriesACacIdentity() throws Exception {
		X509Certificate issuer = cert("CN=DOD ID CA-59, OU=PKI, OU=DoD, O=U.S. Government, C=US", null);

		AccessToken token = signIn(filter(Map.of()), request(null, issuer, cert(CAC_SUBJECT, List.of())), true);

		assertEquals("1234567890", token.getId());
	}

	@Test
	void signsInTheTopazServiceWithItsIssuerAsTheTokenType() throws Exception {
		X509Certificate cert = cert("CN=topazbpm001.mhse2e.med.osd.mil, OU=PKI, O=U.S. Government", null);
		when(cert.getIssuerDN()).thenReturn(new X500Principal("CN=DOD SW CA-60, O=U.S. Government"));
		when(cert.getNotAfter()).thenReturn(new Date(1000L));

		AccessToken token = signIn(filter(Map.of()), request(null, cert), true);

		assertEquals("topazbpm001.mhse2e.med.osd.mil", token.getId());
		assertEquals("TOPAZ", token.getName());
		assertEquals("CN=DOD SW CA-60,O=U.S. Government", token.getToken_type());
		assertEquals(1000, token.getExpires_in());
	}

	@Test
	void movesALegacyEmailIdOntoTheCacIdWithEscapedValues() throws Exception {
		CACFilter filter = filter(Map.of());
		X509Certificate cert = cert(CAC_SUBJECT, List.of(List.of(1, "O'Brien@mail.mil")));
		HttpServletRequest request = request(null, cert);
		IRDBMSEngine securityDb = mock(IRDBMSEngine.class);
		IRawSelectWrapper wrapper = mock(IRawSelectWrapper.class);
		when(wrapper.hasNext()).thenReturn(true);
		WrapperManager manager = mock(WrapperManager.class);
		when(manager.getRawWrapper(any(IDatabaseEngine.class), anyString())).thenReturn(wrapper);

		try (MockedStatic<UserResource> users = mockStatic(UserResource.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<WrapperManager> managers = mockStatic(WrapperManager.class)) {
			utility.when(() -> Utility.getDatabase(Constants.SECURITY_DB)).thenReturn(securityDb);
			managers.when(WrapperManager::getInstance).thenReturn(manager);
			filter.doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
		}

		verify(manager).getRawWrapper(securityDb, "SELECT * FROM SMSS_USER WHERE ID='o''brien@mail.mil'");
		verify(securityDb).insertData("UPDATE SMSS_USER SET ID='1234567890' WHERE ID='o''brien@mail.mil'");
		verify(securityDb)
				.insertData("UPDATE ENGINEPERMISSION SET USERID='1234567890' WHERE USERID='o''brien@mail.mil'");
		verify(securityDb).insertData(
				"UPDATE USERINSIGHTPERMISSION SET USERID='1234567890' WHERE USERID='o''brien@mail.mil'");
		verify(wrapper).close();
	}

	@Test
	void readsTheUpnFromTheOtherNameAlternativeName() throws Exception {
		X509Certificate cert = mock(X509Certificate.class);
		when(cert.getExtensionValue("2.5.29.17")).thenReturn(alternativeNames(UPN_OID, "1234567890@mil"));

		assertEquals("1234567890@mil", new CACFilter().getUPN(cert));
	}

	@Test
	void readsNoUpnFromAnOtherNameOfAnotherType() throws Exception {
		X509Certificate cert = mock(X509Certificate.class);
		when(cert.getExtensionValue("2.5.29.17")).thenReturn(alternativeNames("1.2.3.4", "1234567890@mil"));

		assertNull(new CACFilter().getUPN(cert));
	}

	@Test
	void signsInACacHolderAndKeepsTheirUpn() throws Exception {
		X509Certificate cert = cert(CAC_SUBJECT, List.of(List.of(1, "John.Doe@Mail.MIL")));
		when(cert.getExtensionValue("2.5.29.17")).thenReturn(alternativeNames(UPN_OID, "1234567890@mil"));

		AccessToken token = signIn(filter(Map.of()), request(null, cert), true);

		assertEquals("1234567890@mil", token.getSAN().get("UPN"));
	}

	@Test
	void signsInACacHolderWithoutAlternativeNames() throws Exception {
		AccessToken token = signIn(filter(Map.of()), request(null, cert(CAC_SUBJECT, null)), true);

		assertEquals("1234567890", token.getId());
		assertNull(token.getEmail());
	}

	@Test
	void readsNoUpnFromACertificateWithoutAlternativeNames() throws Exception {
		assertNull(new CACFilter().getUPN(mock(X509Certificate.class)));
	}
}
