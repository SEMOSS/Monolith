package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import prerna.auth.utils.AbstractSecurityUtils;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;

public class AdminStartupFilter implements Filter {

	private static String initialRedirect;
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		if(AbstractSecurityUtils.securityEnabled()) {
			IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
			String q = "SELECT * FROM USER LIMIT 1";
			IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
			try {
				boolean hasUser = wrapper.hasNext();
				// if there are users, redirect to the main semoss page
				// we do not want to allow the person to make any admin requests
				if(hasUser) {
					if(initialRedirect != null) {
						((HttpServletResponse) arg1).setHeader("redirect", initialRedirect);
						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + initialRedirect);
					} else {
						((HttpServletResponse) arg1).sendError(404, "Page Not Found");
					}
					return;
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
	
	public static void setSuccessfulRedirectUrl(String initialRedirect) {
		AdminStartupFilter.initialRedirect = initialRedirect;
	}

}
