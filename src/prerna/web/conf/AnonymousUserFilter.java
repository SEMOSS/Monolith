package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.util.Constants;

public class AnonymousUserFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		if(AbstractSecurityUtils.securityEnabled() && AbstractSecurityUtils.anonymousUsersEnabled()) {
			HttpSession session = ((HttpServletRequest)arg0).getSession(true);
	
			User semossUser = new User();
			Object user = session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				semossUser = new User();
				semossUser.setAnonymous(true);
	
				// add to session
				session.setAttribute(Constants.SESSION_USER, semossUser);
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
