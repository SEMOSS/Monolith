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
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.SSOUtil;
import prerna.web.services.util.WebUtility;

/**
 * Handles the IdP-initiated login entry point.
 *
 * <p>
 * This servlet is typically reached from the SAML login page/filter flow when
 * we want the Identity Provider (IdP) to start authentication immediately. Its
 * responsibility is to:
 *
 * <ol>
 * <li>Validate and preserve any post-login redirect target in the HTTP
 * session.</li>
 * <li>Ask {@link SSOUtil} to load SP/IdP metadata and runtime SSO
 * parameters.</li>
 * <li>Build the IdP initiation URL and redirect the browser to the IdP.</li>
 * </ol>
 *
 * <p>
 * After the user authenticates at the IdP, the IdP posts the SAML response back
 * to {@code SamlVerifierServlet}, which completes local login/session creation.
 */
@WebServlet("/IdpSSOServlet")
public class IdpSSOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger classLogger = LogManager.getLogger(IdpSSOServlet.class);

	public IdpSSOServlet() {
		super();
	}

	/**
	 * Entry point for IdP-initiated SSO.
	 *
	 * <p>
	 * Connection to the other SAML classes:
	 *
	 * <ol>
	 * <li>{@code SSOFilter} sends unauthenticated users to the SAML login
	 * page.</li>
	 * <li>This servlet reads SSO metadata through {@link SSOUtil} and redirects to
	 * the IdP.</li>
	 * <li>{@code SamlVerifierServlet} receives and verifies the IdP callback
	 * assertion.</li>
	 * </ol>
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// if a redirect is given, validate it
		String redirect = request.getParameter("redirect");
		boolean hasRedirect = (redirect != null && !(redirect = redirect.trim()).isEmpty());
		if (hasRedirect) {
			try {
				WebUtility.checkIfValidDomain(redirect);
			} catch (IllegalArgumentException | IllegalStateException e) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, " Provided redirect is unauthorized");
				return;
			}
		}

		// if already logged in
		// then nothing to do, redirect
		if (request.getSession(false) != null) {
			HttpSession session = request.getSession();
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user != null && user.getAccessToken(AuthProvider.SAML) != null && hasRedirect) {
				response.setStatus(302);
				response.sendRedirect(redirect);
				return;
			}
		}

		// not already logged in
		// if we are specifying the redirect
		// set it here so after the login we redirect to the correct page
		if (hasRedirect) {
			HttpSession session = request.getSession();
			classLogger.info("Setting new redirect value to {}", Utility.cleanLogString(redirect));
			session.setAttribute(SSOUtil.SAML_REDIRECT_KEY, redirect);
		}

		classLogger.info("Starting IDP initiated SSO.");
		SSOUtil util = SSOUtil.getInstance();
		// Configure OpenAM/Fedlet metadata and endpoint values used to build the IdP
		// URL.
		util.configureSSO(request, response);
		Map<String, String> map = util.getSSOMap();
		String idpBaseUrl = map.get("idpBaseUrl");
		String idpMetaAlias = map.get("idpMetaAlias");
		String spEntityID = map.get("spEntityID");
		String idpRequest = new StringBuilder(idpBaseUrl)
				.append("/idpssoinit?NameIDFormat=urn:oasis:names:tc:SAML:2.0:nameid-format:transient&metaAlias=")
				.append(idpMetaAlias).append("&spEntityID=").append(spEntityID)
				.append("&binding=urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST").toString();
		// Browser leaves this app here and authenticates at the IdP.
		// The successful callback returns to SamlVerifierServlet.
		classLogger.info("Redirect request created and redirecting to IDP now. IDPRequest - {}", idpRequest);
		response.sendRedirect(idpRequest);
	}

}
