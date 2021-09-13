package prerna.web.conf;

import java.io.IOException;

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

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = ((HttpServletRequest) arg0).getSession(true);
			User user = (User) session.getAttribute(Constants.SESSION_USER);

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
					IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
					String q = "SELECT * FROM SMSS_USER WHERE ID='" + user.getAccessToken(user.getLogins().get(0)).getId()
							+ "'";
					IRawSelectWrapper wrapper = null;
					try {
						wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
						boolean hasUser = wrapper.hasNext();
						// this user is not registered
						// just take them to the login page again
						if (!hasUser) {
							((HttpServletResponse) arg1).setStatus(302);
							String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
							redirectUrl = redirectUrl + "#!/login";
							String encodedRedirectUrl = Encode.forHtml(redirectUrl);
							((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
							((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
						}
					} catch (Exception e) {
						logger.error(Constants.STACKTRACE, e);
					} finally {
						if (wrapper != null) {
							wrapper.cleanUp();
						}
					}
				}
			}
		}

		arg2.doFilter(arg0, arg1);
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
