package prerna.web.conf;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import prerna.auth.User;
import prerna.util.Constants;

public class SemossNegotiateSecurityFilter extends waffle.servlet.NegotiateSecurityFilter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {

		HttpSession session = ((HttpServletRequest)arg0).getSession(true);
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			super.doFilter(arg0, arg1, arg2);
		} else {
			arg2.doFilter(arg0, arg1);
		}
	}
	
}
