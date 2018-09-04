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

import prerna.auth.User;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;

public class NoUserFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		boolean security = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		if(security) {
			IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
			String q = "SELECT * FROM USER LIMIT 1";
			IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
			try {
				boolean hasUser = wrapper.hasNext();
				
				// no users at all registered, we need to send to the admin page
				if(!hasUser) {
					// this will be the full path of the request
					// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
					String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
					// this will be the deployment name of the app
					String contextPath = context.getContextPath();
					
					// we redirect to the index.html page where we have pushed the admin page
					String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length());
					redirectUrl = redirectUrl + "/index.html";
					((HttpServletResponse) arg1).setStatus(302);
					((HttpServletResponse) arg1).sendRedirect(redirectUrl);
					return;
				}
				
				// users are registered but dont know who this specific user is
				// take them to the login page
				else if( ((HttpServletRequest) arg0).getSession(true).getAttribute("user") == null) {
					((HttpServletRequest) arg0).getSession(true).setAttribute("user", true);
					((HttpServletResponse) arg1).setStatus(302);
					((HttpServletResponse) arg1).sendRedirect("http://localhost:8080/SemossWeb_AppUi/#!/");
					return;
				} 
				
				// have the user + 
				else {
					// make sure someone didn't hack this
					HttpSession session = ((HttpServletRequest) arg0).getSession(false);
					User user = (User) session.getAttribute(Constants.SESSION_USER);
					if(user == null || user.getLogins().isEmpty()) {
						((HttpServletRequest) arg0).getSession(true).setAttribute("user", false);
						((HttpServletResponse) arg1).setStatus(302);
						((HttpServletResponse) arg1).sendRedirect("http://localhost:8080/SemossWeb_AppUi/#!/");
						return;
					}
					
					// now, also adding a check for an aliased endpoint...
					// if we have aliased on the tomcat server itself
					// as a redirect URL
					// we should account for it
					
					// this will be the full path of the request
					// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
					String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
					// this will be the deployment name of the app
					String contextPath = context.getContextPath();
					// we redirect to the index.html page where we have pushed the admin page
					String defaultUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + "/";
					
					// person is running host:port/Monolith_Dev
					// this just means we should redirect
					if(fullUrl.equals(defaultUrl)) {
						((HttpServletRequest) arg0).getSession(true).setAttribute("user", true);
						((HttpServletResponse) arg1).setStatus(302);
						((HttpServletResponse) arg1).sendRedirect("http://localhost:8080/SemossWeb_AppUi/#!/");
					}
				}
			} finally {
				wrapper.cleanUp();
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
