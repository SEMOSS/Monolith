package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.util.Constants;

public class SetAdminSessionTimeoutFilter implements Filter {
	
	private static final Logger logger = LogManager.getLogger(SetAdminSessionTimeoutFilter.class); 

	private static FilterConfig filterConfig = null;
	
	// filter init params
	private static final String TIMEOUT = "timeout";
	private static Integer sessionTimeout = null;

	private static final String SESSIOIN_ATTRIBUTE_CHECK = "adminSessionTimeout";

	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);

		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		if(sessionTimeout != null && session != null) {
			User user = (User) session.getAttribute(Constants.SESSION_USER);

			if (user != null && session.getAttribute(SESSIOIN_ATTRIBUTE_CHECK) == null ) {
				// we have a user to compare
				if(SecurityAdminUtils.userIsAdmin(user)) {
					// need to update the session for the admin user
					// the input is in minutes, so we need to turn that to seconds
					int interval = sessionTimeout * 60;
					session.setMaxInactiveInterval(interval);
					
					// store in session so we do not redo the check
					session.setAttribute(SESSIOIN_ATTRIBUTE_CHECK, true);
					logger.info("Setting the admin timeout to " + interval + " seconds");
				} else {
					
					// also still store in the session so we do not redo the check
					session.setAttribute(SESSIOIN_ATTRIBUTE_CHECK, false);
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
		SetAdminSessionTimeoutFilter.filterConfig = arg0;
	}
	
	private void setInitParams(ServletRequest arg0) {
		if(SetAdminSessionTimeoutFilter.sessionTimeout == null) {
			String timeoutStr = SetAdminSessionTimeoutFilter.filterConfig.getInitParameter(TIMEOUT);
			try {
				int timeoutValue = Integer.parseInt(timeoutStr);
				SetAdminSessionTimeoutFilter.sessionTimeout = timeoutValue;
			} catch(Exception e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
	}
	
}
