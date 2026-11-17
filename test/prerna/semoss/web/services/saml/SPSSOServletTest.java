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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.sun.identity.saml.common.SAMLUtils;
import com.sun.identity.saml2.common.SAML2Constants;
import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.common.SAML2SDKUtils;
import com.sun.identity.saml2.common.SAML2Utils;
import com.sun.identity.saml2.meta.SAML2MetaManager;
import com.sun.identity.saml2.profile.SPCache;
import com.sun.identity.saml2.profile.SPSSOFederate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.web.conf.util.SSOUtil;

class SPSSOServletTest {

	private static final String SP = "/sp";
	private static final String IDP = "https://idp.example.com/openam";

	@Test
	void asksTheOnlyIdpForAnAssertionOnBehalfOfTheOnlyHostedSp() throws Exception {
		try (Statics statics = new Statics(List.of(SP), List.of(IDP))) {
			HttpServletRequest req = request(Map.of());
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().doGet(req, resp);

			verify(statics.util).configureSSO(req, resp);
			Map<String, List<String>> params = statics.authnRequest(req, resp, SP, IDP);
			assertEquals(List.of(SAML2Constants.NAMEID_TRANSIENT_FORMAT), params.get(SAML2Constants.NAMEID_POLICY_FORMAT));
			assertEquals(List.of(SAML2Constants.HTTP_POST), params.get(SAML2Constants.BINDING));
		}
	}

	@Test
	void keepsTheBindingTheCallerAskedForButAlwaysAsksForATransientNameId() throws Exception {
		try (Statics statics = new Statics(List.of(SP), List.of(IDP))) {
			Map<String, List<String>> asked = new HashMap<>();
			asked.put(SAML2Constants.BINDING, new ArrayList<>(List.of(SAML2Constants.HTTP_REDIRECT)));
			asked.put(SAML2Constants.NAMEID_POLICY_FORMAT, new ArrayList<>(List.of(SAML2Constants.PERSISTENT)));
			statics.utils.when(() -> SAML2Utils.getParamsMap(any())).thenReturn(asked);
			HttpServletRequest req = request(Map.of());
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(req, resp);

			Map<String, List<String>> params = statics.authnRequest(req, resp, SP, IDP);
			assertEquals(List.of(SAML2Constants.HTTP_REDIRECT), params.get(SAML2Constants.BINDING));
			assertEquals(List.of(SAML2Constants.NAMEID_TRANSIENT_FORMAT), params.get(SAML2Constants.NAMEID_POLICY_FORMAT));
		}
	}

	@Test
	void usesTheSpAndIdpTheCallerNamed() throws Exception {
		try (Statics statics = new Statics(List.of(), List.of())) {
			HttpServletRequest req = request(Map.of("metaAlias", "/other-sp", "idpEntityID", "https://idp2.example.com"));
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(req, resp);

			statics.authnRequest(req, resp, "/other-sp", "https://idp2.example.com");
			assertEquals(0, statics.managers.constructed().size(), "no metadata is looked up");
		}
	}

	@Test
	void refusesWithoutAHostedSp() throws Exception {
		try (Statics statics = new Statics(List.of(), List.of(IDP))) {
			HttpServletRequest req = request(Map.of());
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(req, resp);

			statics.saml.verify(() -> SAMLUtils.sendError(eq(req), eq(resp), eq(400), eq("nullSPEntityID"), anyString()));
			statics.federate.verifyNoInteractions();
		}
	}

	@Test
	void refusesWithoutAnIdpAndWillNotGuessBetweenSeveral() throws Exception {
		for (List<String> idps : List.of(List.<String>of(), List.of(IDP, "https://idp2.example.com"))) {
			try (Statics statics = new Statics(List.of(SP), idps)) {
				HttpServletRequest req = request(Map.of());
				HttpServletResponse resp = mock(HttpServletResponse.class);

				new SPSSOServlet().initiateSSO(req, resp);

				String reason = idps.isEmpty() ? "idpNotFound" : "nullIDPEntityID";
				statics.saml.verify(() -> SAMLUtils.sendError(eq(req), eq(resp), eq(400), eq(reason), anyString()));
				statics.federate.verifyNoInteractions();
			}
		}
	}

	@Test
	void sendsTheBrowserToIdpDiscoveryAndResumesWithThePreferredIdp() throws Exception {
		try (Statics statics = new Statics(List.of(SP), List.of());
				MockedStatic<SAML2SDKUtils> ids = mockStatic(SAML2SDKUtils.class)) {
			statics.utils.when(() -> SAML2Utils.getReaderURL(SP)).thenReturn("https://reader.example.com/read");
			ids.when(SAML2SDKUtils::generateID).thenReturn("req-1");
			statics.utils.when(() -> SAML2Utils.getRedirectURL(eq("https://reader.example.com/read"), eq("req-1"), any()))
					.thenReturn("https://reader.example.com/read?requestID=req-1");
			HttpServletResponse discovery = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(request(Map.of()), discovery);

			verify(discovery).sendRedirect("https://reader.example.com/read?requestID=req-1");
			statics.federate.verifyNoInteractions();

			HttpServletRequest resumed = request(Map.of("requestID", "req-1"));
			statics.utils.when(() -> SAML2Utils.getPreferredIDP(resumed)).thenReturn(IDP);
			HttpServletResponse resp = mock(HttpServletResponse.class);
			new SPSSOServlet().initiateSSO(resumed, resp);

			Map<String, List<String>> params = statics.authnRequest(resumed, resp, SP, IDP);
			assertEquals(SP, ((Map<?, ?>) params).get("metaAlias"));
			assertFalse(SPCache.reqParamHash.containsKey("req-1"), "a discovery request is resumed once");
		}
	}

	@Test
	void answersAnUnknownDiscoveryRequestAsABadRequest() throws Exception {
		try (Statics statics = new Statics(List.of(SP), List.of(IDP))) {
			HttpServletRequest req = request(Map.of("requestID", "never-issued"));
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(req, resp);

			statics.saml.verify(
					() -> SAMLUtils.sendError(eq(req), eq(resp), eq(400), eq("requestProcessingError"), anyString()));
			statics.federate.verifyNoInteractions();
		}
	}

	@Test
	void reportsAnAuthnRequestTheLibraryCannotBuild() throws Exception {
		try (Statics statics = new Statics(List.of(SP), List.of(IDP))) {
			statics.federate.when(() -> SPSSOFederate.initiateAuthnRequest(any(), any(), anyString(), anyString(), any(),
					any())).thenThrow(new SAML2Exception("no signing key"));
			HttpServletRequest req = request(Map.of());
			HttpServletResponse resp = mock(HttpServletResponse.class);

			new SPSSOServlet().initiateSSO(req, resp);

			ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
			statics.saml.verify(() -> SAMLUtils.sendError(eq(req), eq(resp), eq(400), eq("requestProcessingError"),
					message.capture()));
			assertEquals(SAML2Utils.bundle.getString("requestProcessingError") + " no signing key", message.getValue());
		}
	}

	private static HttpServletRequest request(Map<String, String> params) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter(anyString())).thenAnswer(inv -> params.get(inv.getArgument(0)));
		return req;
	}

	/**
	 * The federation library: the hosted and remote metadata, the parameter and
	 * discovery helpers, the request it sends and the errors it answers with.
	 */
	private static final class Statics implements AutoCloseable {

		private final SSOUtil util = mock(SSOUtil.class);
		private final MockedStatic<SSOUtil> sso = mockStatic(SSOUtil.class);
		private final MockedStatic<SAML2Utils> utils = mockStatic(SAML2Utils.class);
		private final MockedStatic<SPSSOFederate> federate = mockStatic(SPSSOFederate.class);
		private final MockedStatic<SAMLUtils> saml = mockStatic(SAMLUtils.class);
		private final MockedConstruction<SAML2MetaManager> managers;

		private Statics(List<String> hostedSps, List<String> remoteIdps) {
			sso.when(SSOUtil::getInstance).thenReturn(util);
			utils.when(() -> SAML2Utils.getParamsMap(any())).thenAnswer(inv -> new HashMap<>());
			managers = mockConstruction(SAML2MetaManager.class, (manager, context) -> {
				when(manager.getAllHostedServiceProviderMetaAliases("/")).thenReturn(new ArrayList<>(hostedSps));
				when(manager.getAllRemoteIdentityProviderEntities("/")).thenReturn(new ArrayList<>(remoteIdps));
			});
		}

		@SuppressWarnings("unchecked")
		private Map<String, List<String>> authnRequest(HttpServletRequest req, HttpServletResponse resp,
				String metaAlias, String idp) throws IOException {
			ArgumentCaptor<Map<String, List<String>>> params = ArgumentCaptor.forClass(Map.class);
			federate.verify(() -> SPSSOFederate.initiateAuthnRequest(eq(req), eq(resp), eq(metaAlias), eq(idp),
					params.capture(), isNull()));
			verify(resp, never()).sendRedirect(anyString());
			return params.getValue();
		}

		@Override
		public void close() {
			managers.close();
			saml.close();
			federate.close();
			utils.close();
			sso.close();
		}
	}
}
