package prerna.web.conf;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

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

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;

public class NoUserInSessionFilter implements Filter {

	private static List<String> ignoreDueToFE = new Vector<String>();
	static {
		ignoreDueToFE.add("authorization/securityEnabled");
		ignoreDueToFE.add("auth/isUserRegistrationOn");
		ignoreDueToFE.add("auth/logins");
		ignoreDueToFE.add("auth/loginProperties");
		ignoreDueToFE.add("auth/login");
		ignoreDueToFE.add("auth/createUser");
		for(AuthProvider v : AuthProvider.values()) {
			ignoreDueToFE.add("auth/userinfo/" +  v.toString().toLowerCase());
			ignoreDueToFE.add("auth/login/" +  v.toString().toLowerCase());
		}
	}

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
		String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();

		boolean security = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		if(security) {
			// REALLY DISLIKE THIS CHECK!!!
			if(!isIgnored(fullUrl)) {
				// due to FE being annoying
				// we need to push a response for this one end point
				// since security is embedded w/ normal semoss and not standalone

				HttpSession session = ((HttpServletRequest) arg0).getSession(true);
				// users are registered but dont know who this specific user is
				// take them to the login page
				if(session.getAttribute("user") == null) {
					session.setAttribute("user", true);
					((HttpServletResponse) arg1).setStatus(302);

					String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
					redirectUrl = redirectUrl + "#!/login";
					((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
					return;
				}

				// have the user redirect value
				// need to make sure no one did any funny business and doesn't have an actual user object
				else {
					User user = (User) session.getAttribute(Constants.SESSION_USER);
					if(user == null || user.getLogins().isEmpty()) {
						((HttpServletResponse) arg1).setStatus(302);

						String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
						redirectUrl = redirectUrl + "#!/login";
						((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
						return;
					}
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

	/**
	 * Due to how the FE security is set up
	 * Need to ignore some URLs :(
	 * I REALLY DISLIKE THIS!!!
	 * @param fullUrl
	 * @return
	 */
	private static boolean isIgnored(String fullUrl) {
		for(String ignore : ignoreDueToFE) {
			if(fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

}
