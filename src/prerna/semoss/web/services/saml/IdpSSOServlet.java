package prerna.semoss.web.services.saml;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.SSOUtil;
import prerna.web.services.util.WebUtility;

/**
 * Servlet implementation class for IDP Initiated SAML
 */
@WebServlet("/IdpSSOServlet")
public class IdpSSOServlet extends HttpServlet {
	
	private static final long serialVersionUID = 1L;
	private static final Logger logger = LogManager.getLogger(IdpSSOServlet.class);
    
    public IdpSSOServlet() {
        super();
    }

	/**
	 * The below doGet is called from the SSOFilter via the samlLogin/index.html page. 
	 * Once called, it gets all the required openAM params from the SSOUtil class and 
	 * does a redirect to the IDP.
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// if a redirect is given, validate it
		String redirect = request.getParameter("redirect");
		boolean hasRedirect = (redirect != null && !(redirect = redirect.trim()).isEmpty());
		if(hasRedirect) {
			try {
				WebUtility.checkIfValidDomain(redirect);
			} catch (IllegalArgumentException | IllegalStateException e) {
				((HttpServletResponse)response).sendError(HttpServletResponse.SC_FORBIDDEN, " Provided redirect is unauthorized");
				return;
			}
		}
		
		// if already logged in
		// then nothing to do, redirect 
		if(request.getSession(false) != null) {
			HttpSession session = request.getSession();
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if(user != null && user.getAccessToken(AuthProvider.SAML) != null && hasRedirect) {
				response.setStatus(302);
				response.sendRedirect(redirect);
				return;
			}
		}
		
		// not already logged in
		// if we are specifying the redirect
		// set it here so after the login we redirect to the correct page
		if(hasRedirect) {
			HttpSession session = request.getSession();
			logger.info(Utility.cleanLogString("Setting new redirect value to " + redirect));
			session.setAttribute(SSOUtil.SAML_REDIRECT_KEY, redirect);
		}
		
		logger.info("Starting IDP initiated SSO.");
		SSOUtil util = SSOUtil.getInstance();
		util.setSSODeployURI((request).getRequestURI());
		util.configureSSO(request, response);
		Map<String, String> map = util.getSSOMap();
		String idpBaseUrl = map.get("idpBaseUrl");
		String idpMetaAlias = map.get("idpMetaAlias");
		String spEntityID = map.get("spEntityID");
		String idpRequest = new StringBuilder(idpBaseUrl).
				append("/idpssoinit?NameIDFormat=urn:oasis:names:tc:SAML:2.0:nameid-format:transient&metaAlias=").
				append(idpMetaAlias).
				append("&spEntityID=").
				append(spEntityID).
				append("&binding=urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST").
				toString();
		logger.info("Redirect request created and redirecting to IDP now. IDPRequest - " + idpRequest);
		response.sendRedirect(idpRequest);
	}

}
