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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.project.api.IProject;
import prerna.util.Constants;
import prerna.util.Utility;

public class PublicHomeCheckFilter implements Filter {

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

		// default is public_home
		String publicHomeFolder = Utility.getPublicHomeFolder();

		// this will be the deployment name of the app
		//  Context Path  - this is already / Monolith
		String contextPath = context.getContextPath();
		String contextPathPublicHome = contextPath + "/" + publicHomeFolder ;
		String realPath = context.getRealPath(File.separator);

		// try to get the project id
		String projectId = fullUrl.substring(fullUrl.indexOf(contextPathPublicHome) + contextPathPublicHome.length() + 1);
		projectId = projectId.substring(0, projectId.indexOf("/"));

		if(!Strings.isNullOrEmpty(projectId)) {
			// check if user can access
			// first, need security on
			// second, if public, allow them to go - rest runPixel endpoint still have security enabled 
			// so this is the case when we want user to go to portal and have a login page there
			// third, if not public - you must have access
			
			if(AbstractSecurityUtils.securityEnabled()) {
				if(!SecurityProjectUtils.projectIsGlobal(projectId)) {
					if(session != null) {
						User user = (User) session.getAttribute(Constants.SESSION_USER);
						if(!SecurityProjectUtils.userCanViewProject(user, projectId)) {
							arg1.getWriter().write("User does not have access to this project" );
							return;	
						}
					} else {
						arg1.getWriter().write("User must be logged in to access this project");
						return;
					}
				}
			}

			IProject project = Utility.getProject(projectId);
			if(project == null) {
				arg1.getWriter().write("Unable to load project with id='" + projectId + "'");
				return;	
			}
			
			// try to create the public home from scratch
			File publicHomeDir = new File(realPath+"/"+publicHomeFolder); 
			// make the directory if it doesn't exist
			if(!publicHomeDir.exists()) {
				publicHomeDir.mkdir();
			}
			
			boolean mapComplete = project.publish(realPath+"/"+publicHomeFolder);
			if(mapComplete) {
				arg2.doFilter(arg0, arg1);
				return;
			} else {
				arg1.getWriter().write("Publish is not enabled on this project or there was an error publishing this project" );
				return;
			}
		}

		arg1.getWriter().write("Improper portal URL - unable to find project ID for the portal");
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
