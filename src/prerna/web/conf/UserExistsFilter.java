package prerna.web.conf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
import org.owasp.encoder.Encode;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;

public class UserExistsFilter extends NoUserInSessionFilter {
	
	private static final Logger logger = LogManager.getLogger(UserExistsFilter.class);

	// these login types
	// create a user through SSO
	// so if we have a user object
	private static List<AuthProvider> checkLogins = new ArrayList<>();
	static { 
		checkLogins.add(AuthProvider.CAC);
		checkLogins.add(AuthProvider.WINDOWS_USER);
		checkLogins.add(AuthProvider.SAML);
	}
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = ((HttpServletRequest) arg0).getSession(false);
			User user = null;
			if(session != null) {
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}

			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();

			// REALLY DISLIKE THIS CHECK!!!
			if (!ResourceUtility.isIgnored(ignoreDueToFE, fullUrl)) {
				// how you got here without a user, i am unsure given the other filters
				// but just in case
				// i will redirect you to login
				if (user == null || user.getLogins().isEmpty()) {
					((HttpServletResponse) arg1).setStatus(302);

					String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
					redirectUrl = redirectUrl + "#!/login";
					String encodedRedirectUrl = Encode.forHtml(redirectUrl);
					((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
					return;
				} else {
					// okay, need to make sure the user is a valid one
					AccessToken token = user.getAccessToken(user.getLogins().get(0));
					boolean userExists = userExists(token);
					if (!userExists) {
						session.removeAttribute(Constants.SESSION_USER);
						((HttpServletResponse) arg1).setStatus(302);
						String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
						redirectUrl = redirectUrl + "#!/login";
						String encodedRedirectUrl = Encode.forHtml(redirectUrl);
						((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
						
						// log the user login
						logger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is trying to login BUT doesn't have access with provider " +  token.getProvider()));

						return;
					}
				}
			} else {
				// for some providers
				// we need to check if they are valid since its SSO
				if(user != null && user.getLogins().size() == 1) {
					AccessToken token = user.getAccessToken(user.getLogins().get(0));
					if(checkLogins.contains(token.getProvider())) { 
						boolean userExists = userExists(token);
						if(!userExists) {
							session.removeAttribute(Constants.SESSION_USER);
						}
					}
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	private boolean userExists(AccessToken token) {
		IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
		String q = "SELECT * FROM SMSS_USER WHERE ID='" + token.getId() + "'";
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
			return wrapper.hasNext();
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if (wrapper != null) {
				wrapper.cleanUp();
			}
		}
		
		return false;
	}
	
	@Override
	public void destroy() {
		// destroy

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
	}
}
