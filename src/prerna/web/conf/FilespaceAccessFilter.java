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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.util.Constants;

public class FilespaceAccessFilter implements Filter {
	
	private static final Logger classLogger = LogManager.getLogger(FilespaceAccessFilter.class);

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		try {
			// get the space it is trying to access
			// get the user from the session
			// see if this user can access this space
			// allow / redirect
			HttpServletRequest hsr = (HttpServletRequest)request;
			String url = hsr.getRequestURI();
			// this is typically of the pattern
			// http://blah blah/Monolith/public_home/app__app_id/something
			String publicHome = "/" + Constants.PUBLIC_HOME + "/";
			int publicHomeIndex = url.indexOf(publicHome);
			if(publicHomeIndex >= 0) {
				String appHome = url.substring(publicHomeIndex + publicHome.length());
				int appRootIndex = appHome.indexOf("/");
				if(appRootIndex >= 0) {
					String appRoot = appHome.substring(0, appRootIndex);
					String [] appRootElements = appRoot.split("__");
					User user = (User) hsr.getSession().getAttribute(Constants.SESSION_USER);
					if(SecurityProjectUtils.userCanViewProject(user, appRootElements[1])) {
						chain.doFilter(request, response);
					} else {
						((HttpServletResponse)response).sendError(HttpServletResponse.SC_FORBIDDEN, " You are not allowed to access that resource ");;
					}
				}
			}
		} catch(Exception ex) {
			classLogger.error(Constants.STACKTRACE, ex);
		}
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// TODO Auto-generated method stub

	}


}
