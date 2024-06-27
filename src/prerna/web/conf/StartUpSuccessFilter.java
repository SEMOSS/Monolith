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

import prerna.web.services.util.WebUtility;

public class StartUpSuccessFilter implements Filter {

	private static boolean startUpSuccess = true;
	private static final String FAIL_HTML = "/startUpFail/";
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		
		if(!startUpSuccess) {
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
				return;
			}
		}
		
		arg2.doFilter(arg0, arg1);
	}
	
	static void setStartUpSuccess(boolean startUpSuccess) {
		StartUpSuccessFilter.startUpSuccess = startUpSuccess;
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
