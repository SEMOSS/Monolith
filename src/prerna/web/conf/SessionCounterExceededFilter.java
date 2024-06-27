package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.catalina.session.StandardManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.semoss.web.services.local.SessionResource;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class SessionCounterExceededFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(SessionCounterExceededFilter.class); 

	private static final String FAIL_HTML = "/sessionCounterFail/";

	private static final String SESSION_LIMIT = "sessionLimit";
	private static Integer sessionLimit = null;

	private static FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);

		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		User user = null;
		if(session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		if(user == null && sessionLimit != null && sessionLimit > 0) {
			int valid = performCheck(session, arg0, arg1);
			if(valid != 0) {
				return;
			}
		}

		arg2.doFilter(arg0, arg1);
	}
	
	private static synchronized int performCheck(HttpSession session, ServletRequest arg0, ServletResponse arg1) throws IOException {
		ServletContext context = arg0.getServletContext();
		if(session == null) {
			session = ((HttpServletRequest)arg0).getSession();
		}
		
		StandardManager manager = SessionResource.getManager(session);
		if(manager != null) {
			// note this includes the new session that was just created here
			int currentSessions = manager.getActiveSessions();
			if(currentSessions > sessionLimit) {
				logger.info("New user exceeds the # of allowed sessions = " + sessionLimit);

				// invalidate the session that was created for the manager
				session.invalidate();
				// too many users
				// this will be the deployment name of the app
				String contextPath = context.getContextPath();

				// this will be the full path of the request
				// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
				String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());

				if(!fullUrl.endsWith(FAIL_HTML)) {
					// we redirect to the index.html page where we have pushed the admin page
					String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + FAIL_HTML;
					((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
					return -1;
				}
			} else {
				logger.info("New user login makes session #" + currentSessions);
			}
		}
		
		return 0;
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		SessionCounterExceededFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if(SessionCounterExceededFilter.sessionLimit == null) {
			String sessionLimitString = SessionCounterExceededFilter.filterConfig.getInitParameter(SESSION_LIMIT);
			if(sessionLimitString != null) {
				try {
					SessionCounterExceededFilter.sessionLimit = (int) Double.parseDouble(sessionLimitString);
				} catch(Exception e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
	}
	
}
