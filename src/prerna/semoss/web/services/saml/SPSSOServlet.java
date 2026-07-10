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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.sun.identity.saml.common.SAMLUtils;
import com.sun.identity.saml2.common.SAML2Constants;
import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.common.SAML2Utils;
import com.sun.identity.saml2.meta.SAML2MetaManager;
import com.sun.identity.saml2.profile.SPCache;
import com.sun.identity.saml2.profile.SPSSOFederate;

import prerna.web.conf.util.SSOUtil;

/**
 * Handles Service Provider (SP)-initiated SAML authentication.
 *
 * <p>
 * This servlet starts SSO from this application (the SP) toward an IdP. It
 * prepares request parameters, chooses a target IdP, and delegates request
 * construction/signing to the OpenAM federation library.
 *
 * <p>
 * How it connects to the rest of the SAML flow:
 *
 * <ol>
 * <li>SSO runtime metadata is loaded through {@link SSOUtil}.</li>
 * <li>This servlet sends the AuthnRequest to the IdP.</li>
 * <li>After IdP authentication, the browser posts back to
 * {@code SamlVerifierServlet} for assertion validation and local session
 * creation.</li>
 * </ol>
 */
@WebServlet("/SPSSOServlet")
public class SPSSOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public SPSSOServlet() {
		super();
	}

	/**
	 * Entry point for SP-initiated login.
	 *
	 * <p>
	 * Ensures SSO config exists and then starts AuthnRequest creation/dispatch.
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		SSOUtil util = SSOUtil.getInstance();
		// Reads metadata and caches core values needed by the OpenAM flow.
		util.configureSSO(request, response);
		initiateSSO(request, response);
	}

	/**
	 * Builds the SAML request context and delegates the final AuthnRequest send.
	 *
	 * <p>
	 * This method supports both:
	 *
	 * <ol>
	 * <li>Fresh login requests (no {@code requestID}).</li>
	 * <li>Follow-up requests after IdP discovery (with {@code requestID}).</li>
	 * </ol>
	 */
	public void initiateSSO(HttpServletRequest request, HttpServletResponse response) {
		// Retreive the Request Query Parameters
		// metaAlias and idpEntiyID are the required query parameters
		// metaAlias - Service Provider Entity Id
		// idpEntityID - Identity Provider Identifier
		// Query parameters supported will be documented.
		String idpEntityID = null;
		String metaAlias = null;
		Map paramsMap = null;
		try {
			String reqID = request.getParameter("requestID");
			if (reqID != null) {
				// get the preferred idp
				idpEntityID = SAML2Utils.getPreferredIDP(request);
				paramsMap = (Map) SPCache.reqParamHash.get(reqID);
				metaAlias = (String) paramsMap.get("metaAlias");
				SPCache.reqParamHash.remove(reqID);
			} else {
				// this is an original request check
				// get the metaAlias ,idpEntityID
				// if idpEntityID is null redirect to IDP Discovery
				// Service to retrieve.
				metaAlias = request.getParameter("metaAlias");
				if ((metaAlias == null) || (metaAlias.length() == 0)) {
					SAML2MetaManager manager = new SAML2MetaManager();
					List spMetaAliases = manager.getAllHostedServiceProviderMetaAliases("/");
					if ((spMetaAliases != null) && !spMetaAliases.isEmpty()) {
						// get first one
						metaAlias = (String) spMetaAliases.get(0);
					}
					if ((metaAlias == null) || (metaAlias.length() == 0)) {
						SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "nullSPEntityID",
								SAML2Utils.bundle.getString("nullSPEntityID"));

						return;
					}
				}

				idpEntityID = request.getParameter("idpEntityID");
				paramsMap = SAML2Utils.getParamsMap(request);
				// always use transient
				List list = new ArrayList();
				list.add(SAML2Constants.NAMEID_TRANSIENT_FORMAT);
				paramsMap.put(SAML2Constants.NAMEID_POLICY_FORMAT, list);
				if (paramsMap.get(SAML2Constants.BINDING) == null) {
					// use POST binding
					list = new ArrayList();
					list.add(SAML2Constants.HTTP_POST);
					paramsMap.put(SAML2Constants.BINDING, list);
				}

				if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
					// get reader url
					String readerURL = SAML2Utils.getReaderURL(metaAlias);
					if (readerURL != null) {
						String rID = SAML2Utils.generateID();
						String redirectURL = SAML2Utils.getRedirectURL(readerURL, rID, request);
						if (redirectURL != null) {
							paramsMap.put("metaAlias", metaAlias);
							SPCache.reqParamHash.put(rID, paramsMap);
							response.sendRedirect(redirectURL);
							return;
						}
					}
				}
			}

			if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
				SAML2MetaManager manager = new SAML2MetaManager();
				List idpEntities = manager.getAllRemoteIdentityProviderEntities("/");
				if ((idpEntities == null) || idpEntities.isEmpty()) {
					SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "idpNotFound",
							SAML2Utils.bundle.getString("idpNotFound"));
					return;
				} else if (idpEntities.size() == 1) {
					// only one IDP, just use it
					idpEntityID = (String) idpEntities.get(0);
				} else {
					// multiple IDP configured in fedlet
					SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "nullIDPEntityID",
							SAML2Utils.bundle.getString("nullIDPEntityID"));
					return;
				}
			}
			// Final handoff to the OpenAM SAML library which creates/sends the
			// AuthnRequest.
			SPSSOFederate.initiateAuthnRequest(request, response, metaAlias, idpEntityID, paramsMap, null);
		} catch (SAML2Exception sse) {
			SAML2Utils.debug.error("Error sending AuthnRequest ", sse);
			SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "requestProcessingError",
					SAML2Utils.bundle.getString("requestProcessingError") + " " + sse.getMessage());
			return;
		} catch (Exception e) {
			SAML2Utils.debug.error("Error processing Request ", e);
			SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "requestProcessingError",
					SAML2Utils.bundle.getString("requestProcessingError") + " " + e.getMessage());
			return;
		}

	}

}
