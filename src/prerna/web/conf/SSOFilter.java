//package prerna.web.conf;
//
//import java.io.IOException;
//
//import javax.servlet.Filter;
//import javax.servlet.FilterChain;
//import javax.servlet.FilterConfig;
//import javax.servlet.ServletException;
//import javax.servlet.ServletRequest;
//import javax.servlet.ServletResponse;
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//import javax.servlet.http.HttpSession;
//
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//
//import prerna.auth.User;
//import prerna.semoss.web.services.config.AdminConfigService;
//import prerna.util.Constants;
//import prerna.util.Utility;
//import prerna.web.conf.util.SSOUtil;
//
///**
// * Servlet Filter implementation class SamlFilter
// */
//public class SSOFilter implements Filter {
//
//	private static final Logger logger = LogManager.getLogger(SSOFilter.class);
//
//	private static final String SAML_LOGIN_HTML = "/samlLogin/";
//
//	/**
//	 * @see Filter#doFilter(ServletRequest, ServletResponse, FilterChain)
//	 */
//	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
//		
//		HttpSession session = ((HttpServletRequest) request).getSession(false);
//		User user = null;
//		
//		// Check if user is already there in the session. If there,
//		// then get the existing user and travel down the chain.
//		if (session != null) {
//			user = (User) session.getAttribute(Constants.SESSION_USER);
//		}
//		
//		// User has not logged in - redirect to the base page to start the
//		// SAML workflow
//		if(user == null) {
//			logger.info("Starting saml transaction.");
//
//			// we need to store information in the session
//			// so that we can properly come back to the referer once an admin has been added
//			String referer = ((HttpServletRequest) request).getHeader("referer");
//			referer = referer + "#!/login";
//			((HttpServletRequest) request).getSession(true).setAttribute(SSOUtil.SAML_REDIRECT_KEY, referer);
//
//			// this will be the deployment name of the app
//			String contextPath = request.getServletContext().getContextPath();
//
//			// this will be the full path of the request
//			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
//			String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());
//
//			// we redirect to the index.html page specifically created for the SAML call.
//			String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + SAML_LOGIN_HTML;
//			((HttpServletResponse) response).setHeader("redirect", redirectUrl);
//			((HttpServletResponse) response).sendError(302, "Need to redirect to " + redirectUrl);
//			return;
//		}
//		
//		chain.doFilter(request, response);
//	}
//	
//	@Override
//	public void destroy() {
//		// TODO Auto-generated method stub
//
//	}
//
//	@Override
//	public void init(FilterConfig arg0) throws ServletException {
//		// TODO Auto-generated method stub
//
//	}
//
//}
