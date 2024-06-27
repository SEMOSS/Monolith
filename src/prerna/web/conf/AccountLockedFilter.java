package prerna.web.conf;

import java.io.IOException;

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
import prerna.auth.User;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class AccountLockedFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(AccountLockedFilter.class);

	private static final String SET_ACCOUNT_LOCKED_HTML = "/accountLocked/";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		if(user != null && !user.isAnonymous()) {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			boolean isLocked = token.isLocked();
			if(isLocked) {
				logger.info("User " + token.getId() + " is locked and being redirected");
				// this will be the deployment name of the app
				String contextPath = arg0.getServletContext().getContextPath();
				String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());

				// we redirect to the index.html page where we have pushed the admin page
				String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + SET_ACCOUNT_LOCKED_HTML;
				((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
				((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
				return;
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}
}
