package prerna.semoss.web.services.saml;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
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
import prerna.auth.User;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.conf.util.SSOUtil;

public class SamlVerifierServlet extends HttpServlet {

	private static final long serialVersionUID = -3853767230988751741L;
	private static final Logger classLogger = LogManager.getLogger(SamlVerifierServlet.class);
	
	public SamlVerifierServlet() {
		super();
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		classLogger.info("Starting saml verification doPost.");
		verifySamlOutput(request, response);
		classLogger.info("Ending saml verification doPost.");
	}

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
        if(!util.isConfigured()) {
                    util.setSSODeployURI((request).getRequestURI());
                    util.configureSSO(request, response);
        }
		try( BufferedWriter out = new BufferedWriter(
				new FileWriter(new File(federationLogPath), true)) ) {
			// invoke the SAML processing logic. this will do all the
			// necessary processing conforming to SAMLv2 specifications,
			// such as XML signature validation, Audience and Recipient
			// validation etc.
			map = SPACSUtils.processResponseForFedlet(request, response, new PrintWriter(out, true));
			
			// Check for relay URL in case IDP needs to redirect to specific page in the APP.
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
			
			// Lets create the SamlDataObjects for only those fields which are present in our props.
			// IDP can send tons of attributes, no need to get and check all of them.
			classLogger.info("Checking if all mandatory fields is present in the SAML...");
			List<AttributeStatement> attrlist = assertion.getAttributeStatements();
			for (AttributeStatement stmt : attrlist) {
				List<Attribute> attributeList = stmt.getAttribute();
				for (Attribute attr : attributeList) {
					Object[] valXmls = attr.getAttributeValue().toArray();
					String[] samlValStrings = new String[valXmls.length];
					for(int i=0; i<samlValStrings.length; i++) {
						samlValStrings[i] = StringUtils.substringBetween((String) valXmls[i], ">", "<");
					}
					sdo.addAttribute(attr.getName(), samlValStrings);
				}
			}
			
			SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo, attrMap);
			mapper.setNameId(value);
			mapper.setIssuer(entityID);
			String groupType = mapper.getGroupType();
			if(groupType == null) {
				groupType = entityID;
			}
			
			Set<String> validUserGroups = getValidUserGroups(mapper, groupType);
			if(validUserGroups != null && validUserGroups.isEmpty()) {
				((HttpServletResponse)response).sendError(HttpServletResponse.SC_FORBIDDEN, " User lacks permissions for this resource " );
				return;
			}
			mapper.setValidUserGroups(validUserGroups);
			
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

			String encodedRedirectUrl = Encode.forHtml(originalRedirect);
			AdminStartupFilter.setSuccessfulRedirectUrl(encodedRedirectUrl);
			response.setHeader("redirect", encodedRedirectUrl);
			response.sendRedirect(encodedRedirectUrl);
			
		} catch (SAML2Exception | IOException | SessionException | ServletException sme) {
			classLogger.error(Constants.STACKTRACE, sme);
			SAMLUtils.sendError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failedToProcessSSOResponse", sme.getMessage());
		}
	}
	
	/**
	 * Checks if we are configured to use groups, and returns the valid group list, if so
	 * 
	 * @param mapper
	 * @param groupType
	 * @return Set of valid groups, or <code>null</code> if whitelist isn't enabled
	 */
	private Set<String> getValidUserGroups(SamlDataObjectMapper mapper, String groupType) {
		if(Boolean.parseBoolean(getInitParameter("useSAMLGroupWhitelist"))) {
			if(!mapper.getUserGroups().isEmpty() && groupType != null) {
				try {
					return AdminSecurityGroupUtils.getMatchingGroupsByType(mapper.getUserGroups(), groupType);
				} catch (Exception e) {
					classLogger.error(Constants.STACKTRACE, e);
					throw new IllegalArgumentException("Error occurred to retrieve the valid groups for SAML login");
				}
			} else {
				return new HashSet<>();
			}
		}
		return null;
	}
	
	/**
	 * Creates the user/token based on the application requirements from the SamlDataObject.
	 * Puts the user object in the session.
	 * 
	 * @param SamlDataObject sdo
	 * @param HttpSession session
	 */
	private void establishUserInSession(SamlDataObjectMapper mapper, HttpServletRequest request, HttpSession session) {
		AccessToken token = null;
		User user = new User();
		token = new AccessToken();
		
		if(mapper.getId() == null) {
			token.setId(mapper.getNameId());
		} else {
			token.setId(mapper.getId());
		}
		token.setUsername(mapper.getUsername());
		token.setEmail(mapper.getEmail());
		token.setName(mapper.getName());
		
		// set groups on the token if they are valid
		String groupType = mapper.getGroupType();
		if(groupType == null) {
			groupType = mapper.getIssuer();
		}
		Set<String> groups = mapper.getValidUserGroups();
		if(groupType != null && groups != null && !groups.isEmpty()) {
			token.setUserGroupType(groupType);
			token.setUserGroups(mapper.getValidUserGroups());
		}
		
		// Set SAML provider type in token.
		token.setProvider(AuthProvider.SAML);
		// Set token in user object
		user.setAccessToken(token);
		// Add user to security database and session. Call it a day, phew!
		SecurityUpdateUtils.addOAuthUser(token);
		session.setAttribute(Constants.SESSION_USER, user);
		session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
		
		// log the user login
		classLogger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider()));

		// store if db tracking
		UserResource.userTrackingLogin(request, user, token.getProvider());
	}

}
