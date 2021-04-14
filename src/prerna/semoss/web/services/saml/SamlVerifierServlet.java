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

			List<AttributeStatement> attrlist = assertion.getAttributeStatements();
			String userId = null;
			for (AttributeStatement stmt : attrlist) {
				List<Attribute> attributeList = stmt.getAttribute();
				for (Attribute attr : attributeList) {
					if (attr.getName().equalsIgnoreCase("mail")) {
						logger.debug(" Attrubute name - %s, Attribute value - %s\n", attr.getName(),
								attr.getAttributeValue());
						String valXml = (String) attr.getAttributeValue().get(0);
						userId = StringUtils.substringBetween(valXml, ">", "<");
					}
				}
			}

			logger.info("Checking if user id is present in the SAML...");

			if (userId == null || userId.isEmpty()) {
				throw new IllegalArgumentException("Did not receive valid userID from IDP. "
						+ "Possible cause - user does not have access to IDP and you need to provide access to the user on the IDP side.");				
			}
			
			logger.info("User id is good. Creating User/Token and setting it to session.");
			HttpSession session = request.getSession(true);
			AccessToken token = null;
			User user = new User();
			token = new AccessToken();
			token.setId(userId);
			token.setUsername(userId);
			token.setProvider(AuthProvider.SAML);
			user.setAccessToken(token);
			SecurityUpdateUtils.addOAuthUser(token);
			session.setAttribute(Constants.SESSION_USER, user);

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

}
