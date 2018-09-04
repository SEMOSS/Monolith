package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;

public class AdminStartupFilter implements Filter {

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
				// if there are users, redirect to the main semoss page
				// we do not want to allow the person to make any admin requests
				if(hasUser) {
					((HttpServletResponse) arg1).setStatus(302);
					((HttpServletResponse) arg1).sendRedirect("http://localhost:8080/SemossWeb_AppUi/#!/");
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

}
