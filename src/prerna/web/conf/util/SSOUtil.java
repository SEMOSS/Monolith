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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.identity.saml2.common.SAML2Utils;
import com.sun.identity.saml2.jaxb.metadata.AssertionConsumerServiceElement;
import com.sun.identity.saml2.jaxb.metadata.IDPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SingleSignOnServiceElement;
import com.sun.identity.saml2.meta.SAML2MetaException;
import com.sun.identity.saml2.meta.SAML2MetaManager;

import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * Central SAML/OpenAM Fedlet configuration utility used by the SSO servlet
 * flow.
 *
 * <p>
 * How this class connects to the other SAML classes:
 *
 * <ol>
 * <li>{@code IdpSSOServlet} calls this class to resolve IdP metadata and build
 * the IdP redirect URL.</li>
 * <li>{@code SPSSOServlet} calls this class before creating an SP-initiated
 * AuthnRequest.</li>
 * <li>{@code SamlVerifierServlet} calls this class before validating an IdP
 * callback assertion.</li>
 * </ol>
 *
 * <p>
 * The implementation is specific to OpenAM metadata APIs and Fedlet
 * conventions.
 */

public class SSOUtil {

	private static final Logger classLogger = LogManager.getLogger(SSOUtil.class);

	// Fedlet/OpenAM runtime values reused by SAML servlets.
	private static final Map<String, String> SSO_MAP = new HashMap<String, String>();
	private static final Map<String, String> READ_ONLY_SSO_MAP = Collections.unmodifiableMap(SSO_MAP);
	private static final String HTTP_POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

	private static String fedletHomeDir = "";
	private static volatile boolean isConfigured = false;

	public static final String SAML_REDIRECT_KEY = "SAML_REDIRECT_KEY";

	/**
	 * Singleton instance.
	 *
	 * <p>
	 * SSO metadata is initialized lazily in
	 * {@link #configureSSO(HttpServletRequest, HttpServletResponse)}.
	 */
	private static final SSOUtil INSTANCE = new SSOUtil();

	private SSOUtil() {

	}

	/**
	 * Singleton accessor for servlet callers.
	 */
	public static SSOUtil getInstance() {
		return INSTANCE;
	}

	/**
	 * Returns the shared SSO runtime map populated during
	 * {@link #configureSSO(HttpServletRequest, HttpServletResponse)}.
	 *
	 * <p>
	 * Primarily consumed by {@code IdpSSOServlet} to build the redirect to the IdP.
	 *
	 * <p>
	 * The returned map is read-only.
	 */
	public Map<String, String> getSSOMap() {
		return READ_ONLY_SSO_MAP;
	}

	/**
	 * Public entry point used by SAML servlets to initialize metadata-driven SSO
	 * state.
	 */
	public void configureSSO(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (isConfigured) {
			return;
		}
		synchronized (SSOUtil.class) {
			if (isConfigured) {
				return;
			}

			// First set the SAML home dir.
			setFedletHomeDir();
			String deployUri = deriveDeployUri(request);

			String spEntityID = null;
			String spMetaAlias = null;
			String idpEntityID = null;
			String idpMetaAlias = null;
			try {
				File dir = new File(WebUtility.normalizePath(fedletHomeDir));
				File file = new File(
						WebUtility.normalizePath(fedletHomeDir + File.separator + "FederationConfig.properties"));
				classLogger.info("Fedlet config being used " + file);
				if (!dir.exists() || !dir.isDirectory()) {
					throw new FileNotFoundException("Configuration directory does not exist.");
				} else if (!file.exists()) {
					throw new FileNotFoundException("Configuration files do not exist.");
				}

				SAML2MetaManager manager = new SAML2MetaManager();
				List spEntities = manager.getAllHostedServiceProviderEntities("/");
				if ((spEntities != null) && !spEntities.isEmpty()) {
					// get first one
					spEntityID = (String) spEntities.get(0);
				}

				List spMetaAliases = manager.getAllHostedServiceProviderMetaAliases("/");
				if ((spMetaAliases != null) && !spMetaAliases.isEmpty()) {
					// get first one
					spMetaAlias = (String) spMetaAliases.get(0);
				}

				List trustedIDPs = new ArrayList();
				idpEntityID = request.getParameter("idpEntityID");
				if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
					// find out all trusted IDPs
					List idpEntities = manager.getAllRemoteIdentityProviderEntities("/");
					if ((idpEntities != null) && !idpEntities.isEmpty()) {
						int numOfIDP = idpEntities.size();
						for (int j = 0; j < numOfIDP; j++) {
							String idpID = (String) idpEntities.get(j);
							if (manager.isTrustedProvider("/", spEntityID, idpID)) {
								trustedIDPs.add(idpID);
							}
						}
					}
				}

				// get the single IDP entity ID
				if (!trustedIDPs.isEmpty()) {
					idpEntityID = (String) trustedIDPs.get(0);
				}
				if ((spEntityID == null) || (idpEntityID == null)) {
					throw new SAML2MetaException(
							"Fedlet or remote Identity Provider metadata is not configured. Please configure SP/IDP first.");
				}

				// IDP base URL
				Map<String, String> idpMap = getIDPBaseUrlAndMetaAlias(idpEntityID, deployUri);
				String idpBaseUrl = idpMap.get("idpBaseUrl");
				idpMetaAlias = idpMap.get("idpMetaAlias");
				String fedletBaseUrl = getFedletBaseUrl(spEntityID, deployUri);

				// Store resolved runtime values for servlet consumers.
				// IdpSSOServlet reads these keys to build the outbound IdP redirect.
				SSO_MAP.clear();
				SSO_MAP.put("idpBaseUrl", idpBaseUrl);
				SSO_MAP.put("fedletBaseUrl", fedletBaseUrl);
				SSO_MAP.put("idpMetaAlias", idpMetaAlias);
				SSO_MAP.put("spEntityID", spEntityID);
				SSO_MAP.put("metaAlias", spMetaAlias);
				SSO_MAP.put("idpEntityID", idpEntityID);
				SSO_MAP.put("binding", HTTP_POST_BINDING);

				classLogger.info(Utility.cleanLogString("Fedlet (SP) Entity ID:" + spEntityID));
				classLogger.info(Utility.cleanLogString("IDP Entity ID:" + idpEntityID));
				isConfigured = true;
			} catch (SAML2MetaException se) {
				classLogger.error("Failed to configure SSO metadata from Fedlet/OpenAM.", se);
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, se.getMessage());
			}
		}
	}

	/**
	 * Initializes Fedlet runtime properties used in endpoint calculations.
	 *
	 * <p>
	 * This must be called before metadata reads because OpenAM APIs depend on these
	 * system properties being present.
	 */
	private void setFedletHomeDir() {
		classLogger.info("SSO directory setup starting");

		// Nice to have these setup in the env.
		System.setProperty("com.iplanet.am.cookie.name", "iPlanetDirectoryPro");
		System.setProperty("com.sun.identity.federation.fedCookieName", "fedCookie");

		// this is where we set the SAML home dir by getting the loc from RDF props.
		String confLocation = Utility.getDIHelperProperty(Constants.SAML_PROP_LOC).trim();
		classLogger.info("Directory is set to.. " + confLocation);
		System.getProperties().setProperty("com.sun.identity.fedlet.home", confLocation);

		// Set the saml config location
		fedletHomeDir = confLocation;
		if ((fedletHomeDir == null) || (fedletHomeDir.trim().length() == 0)) {
			if (System.getProperty("user.home").equals(File.separator)) {
				fedletHomeDir = File.separator + "fedlet";
			} else {
				fedletHomeDir = System.getProperty("user.home") + File.separator + "fedlet";
			}
		}
		classLogger.info("SSO directory setup complete.");
	}

	/**
	 * Derives the application deployment root from the incoming request URI.
	 *
	 * <p>
	 * For example, {@code /Monolith_Dev/IdpSSOServlet} becomes
	 * {@code /Monolith_Dev}. This normalized value is used when matching ACS/SSO
	 * metadata URLs that are rooted at the app context path.
	 *
	 * @param request current HTTP request
	 * @return deployment root segment (context-style path)
	 * @throws IllegalArgumentException when the request URI is missing
	 */
	private String deriveDeployUri(HttpServletRequest request) {
		String requestUri = request.getRequestURI();
		if (requestUri == null || requestUri.isEmpty()) {
			throw new IllegalArgumentException("Deploy uri is null or empty. Cannot create SSO config.");
		}
		int slashLoc = requestUri.indexOf("/", 1);
		if (slashLoc != -1) {
			return requestUri.substring(0, slashLoc);
		}
		return requestUri;
	}

	/**
	 * Finds the IdP base URL and IdP meta alias from OpenAM metadata for a specific
	 * IdP entity.
	 *
	 * @param idpEntityID IdP entity ID selected for this authentication flow
	 * @param deployuri   current app deployment URI root (used by surrounding flow)
	 * @return map containing {@code idpBaseUrl} and {@code idpMetaAlias} when found
	 */
	private Map<String, String> getIDPBaseUrlAndMetaAlias(String idpEntityID, String deployuri) {
		Map<String, String> returnMap = new HashMap<>();
		if (idpEntityID == null) {
			return returnMap;
		}
		try {
			// find out IDP meta alias
			SAML2MetaManager manager = new SAML2MetaManager();
			IDPSSODescriptorElement idp = manager.getIDPSSODescriptor("/", idpEntityID);
			List ssoServiceList = idp.getSingleSignOnService();
			if ((ssoServiceList != null) && (!ssoServiceList.isEmpty())) {
				Iterator i = ssoServiceList.iterator();
				while (i.hasNext()) {
					SingleSignOnServiceElement sso = (SingleSignOnServiceElement) i.next();
					if ((sso != null) && (sso.getBinding() != null)) {
						String ssoURL = sso.getLocation();
						int loc = ssoURL.indexOf("/metaAlias/");
						if (loc == -1) {
							continue;
						} else {
							returnMap.put("idpMetaAlias", ssoURL.substring(loc + 10));
							String tmp = ssoURL.substring(0, loc);
							loc = tmp.lastIndexOf("/");
							returnMap.put("idpBaseUrl", tmp.substring(0, loc));
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			SAML2Utils.debug.error("Couldn't get IDP base url:", e);
		}
		return returnMap;
	}

	/**
	 * Derives the SP (fedlet) base URL from assertion consumer service metadata.
	 *
	 * @param spEntityID hosted SP entity ID
	 * @param deployuri  deployment URI root used to trim the ACS URL
	 * @return computed fedlet base URL, or {@code null} when not derivable
	 */
	private String getFedletBaseUrl(String spEntityID, String deployuri) {
		if (spEntityID == null) {
			return null;
		}
		String fedletBaseUrl = null;
		try {
			SAML2MetaManager manager = new SAML2MetaManager();
			SPSSODescriptorElement sp = manager.getSPSSODescriptor("/", spEntityID);
			List acsList = sp.getAssertionConsumerService();
			if ((acsList != null) && (!acsList.isEmpty())) {
				Iterator iterator = acsList.iterator();
				while (iterator.hasNext()) {
					AssertionConsumerServiceElement acs = (AssertionConsumerServiceElement) iterator.next();
					if ((acs != null) && (acs.getBinding() != null)) {
						String acsURL = acs.getLocation();
						int loc = acsURL.indexOf(deployuri + "/");
						if (loc == -1) {
							continue;
						} else {
							fedletBaseUrl = acsURL.substring(0, loc + deployuri.length());
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			SAML2Utils.debug.error("Couldn't get fedlet base url:", e);
		}
		return fedletBaseUrl;
	}

	/**
	 * Indicates whether this singleton has completed SSO configuration at least
	 * once.
	 */
	public boolean isConfigured() {
		return isConfigured;
	}

}
