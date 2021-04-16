package prerna.semoss.web.services.saml;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
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
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.conf.util.SSOUtil;

@WebServlet("/saml/config/fedletapplication")
public class SamlVerifierServlet extends HttpServlet {

	private static final long serialVersionUID = -3853767230988751741L;
	private static final Logger logger = LogManager.getLogger(SamlVerifierServlet.class);
	
	public SamlVerifierServlet() {
		super();
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		logger.info("Starting saml verification doPost.");
		verifySamlOutput(request, response);
		logger.info("Ending saml verification doPost.");
	}

	public void verifySamlOutput(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		logger.info("Connected to IDP. Starting validation of the assertions and other details.");
		// We define a map which will carry all the output from the saml verifications.
		Map map;
		
		// We need to pass a writer to the SAMl API to write Federation related
		// logs. This location is specified in the RDF.props file. Below is a 
		// sample file path that we actually need to give. Make sure the Federation
		// file is there inside the debug folder.
		// "C:\\workspace\\Semoss_Dev\\saml\\mesoc\\conf\\debug\\Federation"
		String federationLogPath = ((String) DIHelper.getInstance().getCoreProp().get(Constants.SAML_FEDERATION_LOG_PATH)).trim();
		
		try( BufferedWriter out = new BufferedWriter(
				new FileWriter(new File(federationLogPath))) ) {
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
			Map<String, SamlAttributeMapperObject> attrMap = UserResource.getSamlAttributeNames();
			// The SamlDataObject holds the actual data received from the saml
			// like the userId, email, user name. Additional fields can be added
			// if required.
			SamlDataObject sdo = new SamlDataObject(); 
			
			// Lets create the SamlDataObjects for only those fields which are present in our props.
			// IDP can send tons of attributes, no need to get and check all of them.
			logger.info("Checking if all mandatory fields is present in the SAML...");
			List<AttributeStatement> attrlist = assertion.getAttributeStatements();
			for (AttributeStatement stmt : attrlist) {
				List<Attribute> attributeList = stmt.getAttribute();
				for (Attribute attr : attributeList) {
					String samlVal = null;
					SamlAttributeMapperObject mapperRow = attrMap.getOrDefault(attr.getName().toLowerCase(), null);
					if (mapperRow != null) {
						logger.info("Attrubute name - %s, Attribute value - %s\n", attr.getName(),
								attr.getAttributeValue());
						String valXml = (String) attr.getAttributeValue().get(0);
						if (attr.getName().equalsIgnoreCase(mapperRow.getAssertionKey())) {
							samlVal = StringUtils.substringBetween(valXml, ">", "<");
						} 
						// Check if mandatory fields are present. If not then fail fast.
						if(mapperRow.isMandatory() && samlVal == null) {
							throw new IllegalArgumentException("Attribute value is mandatory. Please check if the value of this key is being sent from the IDP - " + mapperRow.getAssertionKey());
						}
						// Set the samlVal in the SamlDataObject
						setAuxillaryFields(mapperRow, sdo, samlVal);
					}
				}
			}
			
			logger.info("User details looks good. Creating User/Token and setting it to session.");
			HttpSession session = request.getSession(true);
			// Get all details from SamlDataObject and populate into user and token object.
			establishUserInSession(sdo, session);
			logger.info("Session is created and user all set to get in. Hold on, redirecting... ");
			if (session != null && session.getAttribute(SSOUtil.SAML_REDIRECT_KEY) != null) {
				String originalRedirect = session.getAttribute(SSOUtil.SAML_REDIRECT_KEY) + "";
				String encodedRedirectUrl = Encode.forHtml(originalRedirect);
				AdminStartupFilter.setSuccessfulRedirectUrl(encodedRedirectUrl);
				response.setHeader("redirect", encodedRedirectUrl);
				response.sendRedirect(encodedRedirectUrl);
			}

		} catch (SAML2Exception | IOException | SessionException | ServletException sme) {
			SAMLUtils.sendError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failedToProcessSSOResponse", sme.getMessage());
		}

	}
	
	/**
	 * This method maps the values from the saml to the SamlDataObject.
	 * 
	 * @param SamlAttributeMapperObject mapperField
	 * @param SamlDataObject sdo
	 * @param String samlVal
	 */
	private void setAuxillaryFields(SamlAttributeMapperObject mapperField, SamlDataObject sdo,String samlVal) {
		if(mapperField.getApplicationKey().equals(SamlAttributeMapperObject.SAML_APPLICATION_KEYS.firstName.toString())) {
			sdo.setFirstName(samlVal);
		}else if(mapperField.getApplicationKey().equals(SamlAttributeMapperObject.SAML_APPLICATION_KEYS.lastName.toString())) {
			sdo.setLastName(samlVal);
		}else if(mapperField.getApplicationKey().equals(SamlAttributeMapperObject.SAML_APPLICATION_KEYS.middleName.toString())) {
			sdo.setMiddleName(samlVal);
		}else if(mapperField.getApplicationKey().equals(SamlAttributeMapperObject.SAML_APPLICATION_KEYS.uid.toString())) {
			sdo.setUid(samlVal);
		}else if(mapperField.getApplicationKey().equals(SamlAttributeMapperObject.SAML_APPLICATION_KEYS.mail.toString())) {
			sdo.setMail(samlVal);
		}
	}
	
	/**
	 * Creates the user/token based on the application requirements from the SamlDataObject.
	 * Puts the user object in the session.
	 * 
	 * @param SamlDataObject sdo
	 * @param HttpSession session
	 */
	private void establishUserInSession(SamlDataObject sdo, HttpSession session) {
		AccessToken token = null;
		User user = new User();
		token = new AccessToken();
		// Set user id and email in token
		token.setId(sdo.getUid());
		token.setUsername(sdo.getUid());
		token.setEmail(sdo.getMail());
		// Set name of the user in token
		StringBuilder sb = new StringBuilder();
		String fullName = sdo.getMiddleName() == null ? 
				sb.append(sdo.getFirstName()).append(" ").append(sdo.getLastName()).toString() : 
				sb.append(sdo.getFirstName()).append(" ").append(sdo.getMiddleName()).append(" ").append(sdo.getLastName()).toString();
		token.setName(fullName);
		// Set SAML provider type in token.
		token.setProvider(AuthProvider.SAML);
		// Set token in user object
		user.setAccessToken(token);
		// Add user to security database and session. Call it a day, phew!
		SecurityUpdateUtils.addOAuthUser(token);
		session.setAttribute(Constants.SESSION_USER, user);
	}

}