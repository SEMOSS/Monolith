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

import prerna.auth.AccessToken;
import prerna.auth.SecurityQueryUtils;
import prerna.auth.User;
import prerna.util.Constants;

public class UserRestrictionFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);

		if(session == null) {
			//TODO: need to redirect!
			return;
		}
		
		Object user = session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			//TODO: need to redirect!
			return;
		}
		
		User semossUser = (User) user;
		if(!semossUser.isLoggedIn()) {
			//TODO: need to redirect!
			return;
		}
		
		AccessToken token = semossUser.getAccessToken(semossUser.getLogins().get(0));
		String userId = token.getId();
		if(!SecurityQueryUtils.userExists(userId)) {
			//TODO: need to redirect!
			return;
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
