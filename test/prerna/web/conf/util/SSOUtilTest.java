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
package prerna.web.conf.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.sun.identity.saml2.jaxb.metadata.AssertionConsumerServiceElement;
import com.sun.identity.saml2.jaxb.metadata.IDPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SingleSignOnServiceElement;
import com.sun.identity.saml2.meta.SAML2MetaManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.util.Constants;
import prerna.util.Utility;

class SSOUtilTest {

	private static final String[] FEDLET_PROPERTIES = { "com.sun.identity.fedlet.home", "com.iplanet.am.cookie.name",
			"com.sun.identity.federation.fedCookieName" };
	private static final String POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

	@TempDir
	Path fedletHome;

	private final Map<String, String> savedProperties = new HashMap<>();

	@BeforeEach
	void resetConfiguration() throws ReflectiveOperationException {
		setConfigured(false);
		for (String name : FEDLET_PROPERTIES) {
			savedProperties.put(name, System.getProperty(name));
		}
	}

	@AfterEach
	void restoreConfiguration() throws ReflectiveOperationException {
		setConfigured(false);
		savedProperties.forEach((name, value) -> {
			if (value == null) {
				System.clearProperty(name);
			} else {
				System.setProperty(name, value);
			}
		});
	}

	private static void setConfigured(boolean configured) throws ReflectiveOperationException {
		Field field = SSOUtil.class.getDeclaredField("isConfigured");
		field.setAccessible(true);
		field.set(null, configured);
	}

	private static HttpServletRequest request() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn("/Monolith/IdpSSOServlet");
		return request;
	}

	/**
	 * Stubs metadata with one hosted SP and two remote IdPs, of which only
	 * idp-trusted is in the SP's circle of trust.
	 */
	private static void metadata(SAML2MetaManager manager, boolean hasSp) throws Exception {
		when(manager.getAllHostedServiceProviderEntities("/")).thenReturn(hasSp ? List.of("sp-entity") : List.of());
		when(manager.getAllHostedServiceProviderMetaAliases("/")).thenReturn(List.of("/sp"));
		when(manager.getAllRemoteIdentityProviderEntities("/")).thenReturn(List.of("idp-untrusted", "idp-trusted"));
		when(manager.isTrustedProvider("/", "sp-entity", "idp-trusted")).thenReturn(true);

		SingleSignOnServiceElement sso = mock(SingleSignOnServiceElement.class);
		when(sso.getBinding()).thenReturn("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect");
		when(sso.getLocation()).thenReturn("https://idp.example/openam/SSORedirect/metaAlias/idp");
		IDPSSODescriptorElement idp = mock(IDPSSODescriptorElement.class);
		when(idp.getSingleSignOnService()).thenReturn(List.of(sso));
		when(manager.getIDPSSODescriptor("/", "idp-trusted")).thenReturn(idp);

		AssertionConsumerServiceElement acs = mock(AssertionConsumerServiceElement.class);
		when(acs.getBinding()).thenReturn(POST_BINDING);
		when(acs.getLocation()).thenReturn("https://sp.example/Monolith/saml/config/fedletapplication");
		SPSSODescriptorElement sp = mock(SPSSODescriptorElement.class);
		when(sp.getAssertionConsumerService()).thenReturn(List.of(acs));
		when(manager.getSPSSODescriptor("/", "sp-entity")).thenReturn(sp);
	}

	private void configure(String samlHome, HttpServletRequest request, HttpServletResponse response,
			boolean hasSp) throws Exception {
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedConstruction<SAML2MetaManager> managers = mockConstruction(SAML2MetaManager.class,
						(manager, context) -> metadata(manager, hasSp))) {
			utility.when(() -> Utility.getDIHelperProperty(Constants.SAML_PROP_LOC)).thenReturn(samlHome);
			SSOUtil.getInstance().configureSSO(request, response);
		}
	}

	@Test
	void sharesOneInstanceWithAReadOnlyMap() {
		assertSame(SSOUtil.getInstance(), SSOUtil.getInstance());
		assertThrows(UnsupportedOperationException.class, () -> SSOUtil.getInstance().getSSOMap().put("k", "v"));
	}

	@Test
	void refusesAFedletHomeThatDoesNotExist() {
		String missing = fedletHome.resolve("missing").toString();

		FileNotFoundException error = assertThrows(FileNotFoundException.class,
				() -> configure(" " + missing + " ", request(), mock(HttpServletResponse.class), true));

		assertEquals("Configuration directory does not exist.", error.getMessage());
		assertEquals(missing, System.getProperty("com.sun.identity.fedlet.home"));
		assertFalse(SSOUtil.getInstance().isConfigured());
	}

	@Test
	void refusesAFedletHomeWithoutTheFederationConfig() {
		FileNotFoundException error = assertThrows(FileNotFoundException.class,
				() -> configure(fedletHome.toString(), request(), mock(HttpServletResponse.class), true));

		assertEquals("Configuration files do not exist.", error.getMessage());
		assertFalse(SSOUtil.getInstance().isConfigured());
	}

	@Test
	void configuresFromTheTrustedIdpMetadataOnce() throws Exception {
		Files.writeString(fedletHome.resolve("FederationConfig.properties"), "");
		HttpServletResponse response = mock(HttpServletResponse.class);

		configure(fedletHome.toString(), request(), response, true);

		assertTrue(SSOUtil.getInstance().isConfigured());
		Map<String, String> sso = SSOUtil.getInstance().getSSOMap();
		assertEquals("sp-entity", sso.get("spEntityID"));
		assertEquals("/sp", sso.get("metaAlias"));
		assertEquals("idp-trusted", sso.get("idpEntityID"));
		assertEquals("https://idp.example/openam", sso.get("idpBaseUrl"));
		assertEquals("/idp", sso.get("idpMetaAlias"));
		assertEquals("https://sp.example/Monolith", sso.get("fedletBaseUrl"));
		assertEquals(POST_BINDING, sso.get("binding"));
		assertEquals("iPlanetDirectoryPro", System.getProperty("com.iplanet.am.cookie.name"));
		verify(response, never()).sendError(anyInt(), anyString());

		try (MockedConstruction<SAML2MetaManager> managers = mockConstruction(SAML2MetaManager.class)) {
			SSOUtil.getInstance().configureSSO(request(), response);
			assertTrue(managers.constructed().isEmpty());
		}
	}

	@Test
	void answersAServerErrorWhenTheFedletHasNoServiceProvider() throws Exception {
		Files.writeString(fedletHome.resolve("FederationConfig.properties"), "");
		HttpServletResponse response = mock(HttpServletResponse.class);

		configure(fedletHome.toString(), request(), response, false);

		verify(response).sendError(eq(500), anyString());
		assertFalse(SSOUtil.getInstance().isConfigured());
	}
}
