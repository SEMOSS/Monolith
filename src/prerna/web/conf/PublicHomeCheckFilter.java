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
		
		String public_home = "/public_home";
		if(DIHelper.getInstance().getProperty(Settings.PUBLIC_HOME) != null) {
			public_home = DIHelper.getInstance().getProperty(Settings.PUBLIC_HOME);
		}
		
		// this will be the deployment name of the app
		//  Context Path  - this is already / Monolith
		String contextPath = context.getContextPath() + public_home ;
		String realPath = context.getRealPath(File.separator);
		
		// try to get the project id
		String projectId = fullUrl.substring(fullUrl.indexOf(contextPath) + contextPath.length() + 1);
		projectId = projectId.substring(0, projectId.indexOf("/"));
		
	
		if(!Strings.isNullOrEmpty(projectId)) {
			// check if the alias exists, if it does the project probably exists
			String alias = SecurityProjectUtils.getProjectAliasForId(projectId);
			
			if(!Strings.isNullOrEmpty(alias)) {
				// check if the project is global				
				boolean allowed = SecurityProjectUtils.projectIsGlobal(projectId);
				
				// check if there is a user and the user can access the project
				if (!allowed && session != null) {
					User user = (User) session.getAttribute(Constants.SESSION_USER);
					
					allowed = user.checkProjectAccess(alias, projectId);
					
				}
				

				if(allowed) {
					// try to create the public home from scratch
					File phomeFile = new File(realPath + public_home); 
					
					// make the directory if it doesn't exist
					if(!phomeFile.exists()) {
						phomeFile.mkdir();
					}

						boolean mapComplete = Utility.getProject(projectId).publish(realPath + public_home);
						if(mapComplete) {
							arg2.doFilter(arg0, arg1);
							return;
						} else {
							arg1.getWriter().write("Publish is not enabled on this project or there was an error publishing this project" );
//							String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + APP_NOT_PUBLISHED;
//							((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
//							((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
							return;
						}	
				} else {
					arg1.getWriter().write("You do not have access to this project" );
					return;
				}
			}
		}

		arg1.getWriter().write("Cannot find project");
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
