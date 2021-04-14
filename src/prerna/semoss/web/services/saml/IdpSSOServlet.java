//package prerna.semoss.web.services.saml;
//
//import java.io.IOException;
//import java.util.Map;
//
//import javax.servlet.ServletException;
//import javax.servlet.annotation.WebServlet;
//import javax.servlet.http.HttpServlet;
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//
//import prerna.web.conf.util.SSOUtil;
//
///**
// * Servlet implementation class for IDP Initiated SAML
// */
//@WebServlet("/IdpSSOServlet")
//public class IdpSSOServlet extends HttpServlet {
//	
//	private static final long serialVersionUID = 1L;
//	private static final Logger logger = LogManager.getLogger(IdpSSOServlet.class);
//    
//    public IdpSSOServlet() {
//        super();
//    }
//
//	/**
//	 * The below doGet is called from the SSOFilter via the samlLogin/index.html page. 
//	 * Once called, it gets all the required openAM params from the SSOUtil class and 
//	 * does a redirect to the IDP.
//	 */
//	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
//		logger.info("Starting IDP initiated SSO.");
//		SSOUtil util = SSOUtil.getInstance();
//		util.setSSODeployURI((request).getRequestURI());
//		util.configureSSO(request, response);
//		Map<String, String> map = util.getSSOMap();
//		String idpBaseUrl = map.get("idpBaseUrl");
//		String idpMetaAlias = map.get("idpMetaAlias");
//		String spEntityID = map.get("spEntityID");
//		String idpRequest = new StringBuilder(idpBaseUrl).
//				append("/idpssoinit?NameIDFormat=urn:oasis:names:tc:SAML:2.0:nameid-format:transient&metaAlias=").
//				append(idpMetaAlias).
//				append("&spEntityID=").
//				append(spEntityID).
//				append("&binding=urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST").
//				toString();
//		logger.info("Redirect request created and redirecting to IDP now. IDPRequest - " + idpRequest);
//		response.sendRedirect(idpRequest);
//	}
//
//}
