package prerna.web.conf;

import java.io.File;
import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.google.common.base.Strings;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Settings;
import prerna.util.Utility;

public class PublicHomeCheckFilter implements Filter {
	
	public static final String NO_SUCH_APP = "/noSuchApp/";
	public static final String APP_NOT_PUBLISHED = "/appPublishFail/";
	
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		
		// check to see if the user is logged in
		// if so pick the user object from session
		// isolate the index of public_home/ to the next /
		// see if this user has permission by using the engine map
		// if yes allow
		
		ServletContext context = arg0.getServletContext();
		
		
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		User user = null;
		if (session != null) {
			// System.out.println("Session ID >> " + session.getId());
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if(user != null)
		{
			String public_home = "/public_home";
			if(DIHelper.getInstance().getProperty(Settings.PUBLIC_HOME) != null)
				public_home = DIHelper.getInstance().getProperty(Settings.PUBLIC_HOME);


			//public_home = "";
			// this will be the deployment name of the app
			//  Context Path  - this is already / Monolith
			String contextPath = context.getContextPath() + public_home ;
			String realPath = context.getRealPath(File.separator);
			
			// https://server/Monolith/public_home/engineid/resource
			// plus 1 is the / on monolith
			
			String possibleEngineId = fullUrl.substring(fullUrl.indexOf(contextPath) + contextPath.length() + 1);
			possibleEngineId = possibleEngineId.substring(0, possibleEngineId.indexOf("/"));
			
			// this is engine id ?! why are we splitting!
			//String [] engTokens = possibleEngineId.split("__");
			
			String alias = SecurityProjectUtils.getProjectAliasForId(possibleEngineId);
			if(!Strings.isNullOrEmpty(alias) && !Strings.isNullOrEmpty(possibleEngineId))
			{
				boolean appAllowed = user.checkAppAccess(alias, possibleEngineId);
			
				File phomeFile = new File(realPath + public_home); // try to create the public home from scratch
				
				if(!phomeFile.exists())
					phomeFile.mkdir();

				if(appAllowed)
				{
					boolean mapComplete = Utility.getProject(possibleEngineId).publish(realPath + public_home, possibleEngineId);
					if(mapComplete)
					{
						arg2.doFilter(arg0, arg1);
						return;
					}
					else
					{
						arg1.getWriter().write("Publish is not enabled on this application or there was an error publishing this application" );
//						String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + APP_NOT_PUBLISHED;
//						((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
//						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
						return;
					}
				}
				else
				{
					// send the user to some kind of page to say the user doesnt have access to this app
					arg1.getWriter().write("App does not exist or You do not have acess to this application" );
//					String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_SUCH_APP;
//					((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
//					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
					return;

				}
			}
		}

		arg1.getWriter().write("You are trying to access an asset without being logged in, please log in first and then try again");
		return;
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
