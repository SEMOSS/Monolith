package prerna.web.conf;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.SSOUtil;
import prerna.web.conf.util.UserFileLogUtil;

/**
 * Servlet Filter implementation class SamlFilter
 */
public class SSOFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(SSOFilter.class);

	private static final String SAML_LOGIN_HTML = "/samlLogin/";

	
	// filter init params
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";
	private static final String LOG_USER_INFO = "logUserInfo";
	private static final String LOG_USER_INFO_PATH = "logUserInfoPath";
	private static final String LOG_USER_INFO_SEP = "logUserInfoSep";
	
	// realization of init params
	private static boolean init = false;
	private static CACTrackingUtil tracker = null;
	private static UserFileLogUtil userLogger = null;

	private static final String LOG_USER = "LOG_USER";
	
	private static FilterConfig filterConfig;

	/**
	 * @see Filter#doFilter(ServletRequest, ServletResponse, FilterChain)
	 */
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		setInitParams(request);

		HttpSession session = ((HttpServletRequest) request).getSession(false);
		User user = null;
		
		// Check if user is already there in the session. If there,
		// then get the existing user and travel down the chain.
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		// User has not logged in - redirect to the base page to start the
		// SAML workflow
		if(user == null) {
			logger.info("Starting saml transaction.");

			// we need to store information in the session
			// so that we can properly come back to the referer once an admin has been added
			String referer = ((HttpServletRequest) request).getHeader("referer");
			referer = referer + "#!/login";
			((HttpServletRequest) request).getSession(true).setAttribute(SSOUtil.SAML_REDIRECT_KEY, referer);

			// this will be the deployment name of the app
			String contextPath = request.getServletContext().getContextPath();

			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());

			// we redirect to the index.html page specifically created for the SAML call.
			String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + SAML_LOGIN_HTML;
			((HttpServletResponse) response).setHeader("redirect", redirectUrl);
			((HttpServletResponse) response).sendError(302, "Need to redirect to " + redirectUrl);
			
			if(tracker != null) {
				((HttpServletRequest) request).getSession().setAttribute(LOG_USER, "true");
			}
			
			return;
		}
		
		if(session != null && session.getAttribute(LOG_USER) != null) {
			session.removeAttribute(LOG_USER);
			
			AccessToken token = user.getAccessToken(AuthProvider.SAML);
			// new user has entered!
			// do we need to count?
			if(tracker != null && !token.getName().equals("TOPAZ")) {
				tracker.addToQueue(LocalDate.now());
			}

			// are we logging their information?
			if(userLogger != null && !token.getName().equals("TOPAZ")) {
				// grab the ip address
				userLogger.addToQueue(new String[] {token.getId(), token.getName(), 
						LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), ResourceUtility.getClientIp((HttpServletRequest)request)});
			}
		}
		
		chain.doFilter(request, response);
	}
	
	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		SSOFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if(!init) {
			boolean logUsers = false;
			String logUserInfoStr = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if(logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if(logUsers) {
				String logInfoPath = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
				if(logInfoPath == null) {
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
				}
				try {
					userLogger = UserFileLogUtil.getInstance(logInfoPath, logInfoSep);
				} catch(Exception e) {
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
				}
			}

			boolean countUsers = false;
			String countUsersStr = SSOFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if(countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}

			if(countUsers) {
				String countDatabaseId = SSOFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if(countDatabaseId == null) {
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch(Exception e) {
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
				}
			}
			
			// change init to true
			init = true;
		}
	}

}
