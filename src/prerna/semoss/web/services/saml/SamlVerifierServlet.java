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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import com.sun.identity.plugin.session.SessionException;
import com.sun.identity.saml.common.SAMLUtils;
import com.sun.identity.saml2.assertion.Assertion;
import com.sun.identity.saml2.assertion.Attribute;
import com.sun.identity.saml2.assertion.AttributeStatement;
import com.sun.identity.saml2.assertion.NameID;
import com.sun.identity.saml2.assertion.Subject;
import com.sun.identity.saml2.common.SAML2Constants;
import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.profile.SPACSUtils;
import com.sun.identity.saml2.protocol.Response;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.conf.util.SSOUtil;

/**
 * Receives the SAML assertion callback from the IdP and finalizes login in this
 * application.
 *
 * <p>
 * This servlet is the convergence point for both login entry paths:
 *
 * <ol>
 * <li>{@code IdpSSOServlet}: IdP-initiated redirect flow.</li>
 * <li>{@code SPSSOServlet}: SP-initiated AuthnRequest flow.</li>
 * </ol>
 *
 * <p>
 * High-level responsibilities:
 *
 * <ol>
 * <li>Use OpenAM Fedlet APIs to validate/process the SAML response.</li>
 * <li>Map SAML attributes into the local user/token model.</li>
 * <li>Create/update the application session and redirect the user back.</li>
 * </ol>
 */
public class SamlVerifierServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger classLogger = LogManager.getLogger(SamlVerifierServlet.class);

	public SamlVerifierServlet() {
		super();
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		classLogger.info("Starting saml verification doPost.");
		verifySamlOutput(request, response);
		classLogger.info("Ending saml verification doPost.");
	}

	/**
	 * Validates the IdP callback and converts it into an authenticated application
	 * session.
	 *
	 * <p>
	 * Connection between classes in the flow:
	 *
	 * <ol>
	 * <li>{@link SSOUtil} is used to ensure Fedlet/OpenAM metadata is loaded.</li>
	 * <li>OpenAM APIs parse and verify the incoming SAML response.</li>
	 * <li>This servlet stores the resulting user/token in session and redirects to
	 * the original URL.</li>
	 * </ol>
	 */
	public void verifySamlOutput(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		classLogger.info("Connected to IDP. Starting validation of the assertions and other details.");
		// We define a map which will carry all the output from the saml verifications.
		Map map;

		// We need to pass a writer to the SAMl API to write Federation related
		// logs. This location is specified in the RDF.props file. Below is a
		// sample file path that we actually need to give. Make sure the Federation
		// file is there inside the debug folder.
		// "C:\\workspace\\Semoss_Dev\\saml\\mesoc\\conf\\debug\\Federation"
		String federationLogPath = Utility.getDIHelperProperty(Constants.SAML_FEDERATION_LOG_PATH).trim();
		SSOUtil util = SSOUtil.getInstance();
		// Ensure SAML runtime configuration is ready before processing the callback.
		util.configureSSO(request, response);
		try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(federationLogPath), true))) {
			// invoke the SAML processing logic. this will do all the
			// necessary processing conforming to SAMLv2 specifications,
			// such as XML signature validation, Audience and Recipient
			// validation etc.
			// OpenAM does XML signature, audience, recipient and protocol validation here.
			map = SPACSUtils.processResponseForFedlet(request, response, new PrintWriter(out, true));

			// Check for relay URL in case IDP needs to redirect to specific page in the
			// APP.
			String relayUrl = (String) map.get(SAML2Constants.RELAY_STATE);
			if ((relayUrl != null) && (relayUrl.length() != 0)) {
				// something special for validation to send redirect
				int stringPos = relayUrl.indexOf("sendRedirectForValidationNow=true");
				if (stringPos != -1) {
					response.sendRedirect(relayUrl);
					return;
				}
			}

			// Get all the details from the SAML verification.
			// We just use the assertions, but we keep the code
			// below in case the other details might get used in
			// the near/distant future.
			Response samlResp = (Response) map.get(SAML2Constants.RESPONSE);
			Assertion assertion = (Assertion) map.get(SAML2Constants.ASSERTION);
			Subject subject = (Subject) map.get(SAML2Constants.SUBJECT);
			String entityID = (String) map.get(SAML2Constants.IDPENTITYID);
			String spEntityID = (String) map.get(SAML2Constants.SPENTITYID);
			NameID nameId = (NameID) map.get(SAML2Constants.NAMEID);
			String value = nameId.getValue();
			String format = nameId.getFormat();

			// Get the field mappings from the properties file in the map. The
			// SamlAttributeMapperObject holds all the metadata related to the
			// saml fields.
			Map<String, String[]> attrMap = SocialPropertiesUtil.getInstance().getSamlAttributeNames();
			// The SamlDataObject holds the actual data received from the saml
			// like the userId, email, user name. Additional fields can be added
			// if required.
			SamlDataObject sdo = new SamlDataObject();

			// Lets create the SamlDataObjects for only those fields which are present in
			// our props.
			// IDP can send tons of attributes, no need to get and check all of them.
			classLogger.info("Checking if all mandatory fields is present in the SAML...");
			List<AttributeStatement> attrlist = assertion.getAttributeStatements();
			for (AttributeStatement stmt : attrlist) {
				List<Attribute> attributeList = stmt.getAttribute();
				for (Attribute attr : attributeList) {
					Object[] valXmls = attr.getAttributeValue().toArray();
					String[] samlValStrings = new String[valXmls.length];
					for (int i = 0; i < samlValStrings.length; i++) {
						samlValStrings[i] = StringUtils.substringBetween((String) valXmls[i], ">", "<");
					}
					sdo.addAttribute(attr.getName(), samlValStrings);
				}
			}

			SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo, attrMap);
			mapper.setNameId(value);
			mapper.setIssuer(entityID);
			String groupType = mapper.getGroupType();
			if (groupType == null) {
				groupType = entityID;
			}

			Set<String> validUserGroups = getValidUserGroups(mapper, groupType);
			if (validUserGroups != null && validUserGroups.isEmpty()) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, " User lacks permissions for this resource ");
				return;
			}
			mapper.setValidUserGroups(validUserGroups);

			Map<String, Collection<String>> extendedUserAttributes = getExtendedUserAttributes(mapper);
			mapper.setExtendedUserAttributes(extendedUserAttributes);

			classLogger.info("User details looks good. Creating User/Token and setting it to session.");
			HttpSession session = request.getSession(true);
			// Get all details from SamlDataObject and populate into user and token object.
			establishUserInSession(mapper, request, session);
			classLogger.info("Session is created and user all set to get in. Hold on, redirecting... ");
			String originalRedirect = null;
			if (session != null && session.getAttribute(SSOUtil.SAML_REDIRECT_KEY) != null) {
				originalRedirect = session.getAttribute(SSOUtil.SAML_REDIRECT_KEY) + "";
			} else {
				classLogger.info("No redirect url was found...");
				classLogger.info("Redirect to social.properties value");
				originalRedirect = SocialPropertiesUtil.getInstance().getLoginRedirect();
			}

			// Complete the SSO transaction by returning the user to their original target.
			String encodedRedirectUrl = Encode.forHtml(originalRedirect);
			AdminStartupFilter.setSuccessfulRedirectUrl(encodedRedirectUrl);
			response.setHeader("redirect", encodedRedirectUrl);
			response.sendRedirect(encodedRedirectUrl);

		} catch (SAML2Exception | IOException | SessionException | ServletException sme) {
			classLogger.error("Failed to process SAML verification response.", sme);
			SAMLUtils.sendError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failedToProcessSSOResponse", sme.getMessage());
		}
	}

	/**
	 * Checks if we are configured to use groups, and returns the valid group list,
	 * if so
	 * 
	 * @param mapper
	 * @param groupType
	 * @return Set of valid groups, or <code>null</code> if whitelist isn't enabled
	 */
	private Set<String> getValidUserGroups(SamlDataObjectMapper mapper, String groupType) {
		if (Boolean.parseBoolean(getInitParameter("useSAMLGroupWhitelist"))) {
			if (!mapper.getUserGroups().isEmpty() && groupType != null) {
				try {
					return AdminSecurityGroupUtils.getMatchingGroupsByType(mapper.getUserGroups(), groupType);
				} catch (Exception e) {
					classLogger.error("Failed to retrieve valid SAML groups for user.", e);
					throw new IllegalArgumentException("Error occurred to retrieve the valid groups for SAML login", e);
				}
			} else {
				return new HashSet<>();
			}
		}
		return null;
	}

	/**
	 * Checks if we are configured to load additional attributes, and returns the
	 * attribute-value mappings, if so
	 * 
	 * @param mapper
	 * @return Map of the valid attributes to their values in the SAML response, or
	 *         <code>null</code> if whitelist isn't enabled
	 */
	private Map<String, Collection<String>> getExtendedUserAttributes(SamlDataObjectMapper mapper) {
		if (Boolean.parseBoolean(getInitParameter("useSAMLAttributeWhitelist"))) {
			Map<String, Boolean> args = new HashMap<>();
			List<Map<String, Object>> possibleAttributes = SecurityUserUtils.getMetakeyOptions(null);
			for (Map<String, Object> possibleAttribute : possibleAttributes) {
				args.put(possibleAttribute.get("metakey") + "",
						"multi".equals(possibleAttribute.get("single_multi") + ""));
			}
			return mapper.getValuesForAttributes(args);
		}
		return null;
	}

	/**
	 * Converts verified SAML attributes into the app's User/AccessToken model and
	 * stores them in HTTP session.
	 *
	 * <p>
	 * This is where identity data from the IdP becomes local authenticated state
	 * used by downstream filters/resources.
	 */
	private void establishUserInSession(SamlDataObjectMapper mapper, HttpServletRequest request, HttpSession session) {
		AccessToken token = new AccessToken();
		if (mapper.getId() == null) {
			token.setId(mapper.getNameId());
		} else {
			token.setId(mapper.getId());
		}
		token.setUsername(mapper.getUsername());
		token.setEmail(mapper.getEmail());
		token.setName(mapper.getName());

		// set groups on the token if they are valid
		String groupType = mapper.getGroupType();
		if (groupType == null) {
			groupType = mapper.getIssuer();
		}
		Set<String> groups = mapper.getValidUserGroups();
		if (groupType != null && groups != null && !groups.isEmpty()) {
			token.setUserGroupType(groupType);
			token.setUserGroups(mapper.getValidUserGroups());
		}

		// set other valid attributes
		Map<String, Collection<String>> attribs = mapper.getExtendedUserAttributes();
		if (attribs != null && !attribs.isEmpty()) {
			token.setMeta(attribs);
		}

		// Set SAML provider type in token.
		token.setProvider(AuthProvider.SAML);
		// store in session, log in user tracking db, and add the user to security db
		UserResource.addAccessToken(token, request, true);
	}

}
