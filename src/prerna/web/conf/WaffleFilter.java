package prerna.web.conf;

import java.io.IOException;
import java.security.Principal;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.util.Constants;

public class WaffleFilter implements Filter {

	private static final String AUTO_ADD = "autoAdd";
	private static Boolean autoAdd = null;

	private FilterConfig filterConfig;
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);
		
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);
		Principal principal = ((HttpServletRequest) arg0).getUserPrincipal();
		String id = principal.getName();
		String name = id.substring(id.lastIndexOf('\\')+1);
		
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			user = new User();

			AccessToken token = new AccessToken();
			token.setProvider(AuthProvider.WINDOWS_USER);
			token.setId(id);
			token.setName(name);
			// add the user if they do not exist
			if(WaffleFilter.autoAdd) {
				SecurityUpdateUtils.addOAuthUser(token);
			}
			
			user.setAccessToken(token);
			session.setAttribute(Constants.SESSION_USER, user);
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		this.filterConfig = arg0;
	}
	
	private void setInitParams(ServletRequest arg0) {
		if(WaffleFilter.autoAdd == null) {
			String autoAddStr = this.filterConfig.getInitParameter(AUTO_ADD);
			if(autoAddStr != null) {
				WaffleFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				WaffleFilter.autoAdd = true;
			}
		}
	}

}
