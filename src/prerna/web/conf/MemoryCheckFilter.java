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
import prerna.sablecc2.reactor.mgmt.MgmtUtil;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Settings;
import prerna.util.Utility;

public class MemoryCheckFilter implements Filter {

	private static final String NO_MORE_MEMORY = "/sessionCounterFail/";
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		
		//check to see if the user is logged in
		// if yes.. nothing to do
		// if not then try to see if we have memory
		// if not move the uset to oom page
		
		ServletContext context = arg0.getServletContext();
		
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		User user = null;
		if (session != null) {
			// System.out.println("Session ID >> " + session.getId());
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if(user == null && !fullUrl.endsWith(NO_MORE_MEMORY) && !canLoadUser() )
		{
			// this will be the deployment name of the app
			String contextPath = context.getContextPath();
			
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
				// we redirect to the index.html page where we have pushed the admin page
				String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_MORE_MEMORY;
				((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
				((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
				return;
		}
		
		arg2.doFilter(arg0, arg1);
	}
	
	public boolean canLoadUser()
	{
		boolean canLoad = true;
		
		String checkMemSettings = DIHelper.getInstance().getProperty(Settings.CHECK_MEM);
		
		boolean checkMem = checkMemSettings != null && checkMemSettings.equalsIgnoreCase("true"); 
		if(checkMem)
		{
			long freeMem = MgmtUtil.getFreeMemory();
			String memProfileSettings = DIHelper.getInstance().getProperty(Settings.MEM_PROFILE_SETTINGS);
			
			if(memProfileSettings.equalsIgnoreCase(Settings.CONSTANT_MEM))
			{
				String memLimitSettings = DIHelper.getInstance().getProperty(Settings.USER_MEM_LIMIT);
				int limit = Integer.parseInt(memLimitSettings);
				canLoad = limit < freeMem;
			}
		}
		
		return canLoad;
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
