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

import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.config.AdminConfigService;
import prerna.util.Constants;
import prerna.util.Utility;

public class NoUserExistsFilter implements Filter {

	private static final String SET_ADMIN_HTML = "/setAdmin/";
	private static boolean userDefined = false;
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		
		boolean security = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		if(security) {
			
			// i do not want to run this query for every single call
			// just gets annoying 
			if(!NoUserExistsFilter.userDefined) {
				
				IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
				String q = "SELECT * FROM USER LIMIT 1";
				IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
				try {
					boolean hasUser = wrapper.hasNext();
					
					// no users at all registered, we need to send to the admin page
					if(!hasUser) {
						
						// we need to store information in the session
						// so that we can properly come back to the referer once an admin has been added
						String referer = ((HttpServletRequest) arg0).getHeader("referer");
						referer = referer + "#!/login";
						((HttpServletRequest) arg0).getSession(true).setAttribute(AdminConfigService.ADMIN_REDIRECT_KEY, referer);
	
						// this will be the deployment name of the app
						String contextPath = context.getContextPath();
						
						// this will be the full path of the request
						// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
						String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
		
						// we redirect to the index.html page where we have pushed the admin page
						String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + SET_ADMIN_HTML;
						((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
						return;
					} else {
						// set boolean so we dont keep querying all the time
						NoUserExistsFilter.userDefined = true;
					}
				} finally {
					wrapper.cleanUp();
				}
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
