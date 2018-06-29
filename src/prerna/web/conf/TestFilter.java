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
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;

public class TestFilter implements Filter {

	/*
	 * THIS IS JUST A TEST CLASS
	 * DO NOT ACTUALLY USE THIS!
	 */
	
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);

		User semossUser = new User();
		Object user = session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			semossUser = new User();
			AccessToken token = new AccessToken();
			token.setProvider(AuthProvider.CAC);
			token.setEmail("admin@health.mil");
			token.setName("CAC_ADMIN_USER");
			token.setId("12345");
			
//			AccessToken token = new AccessToken();
//			token.setProvider(AuthProvider.CAC);
//			token.setEmail("readonly@health.mil");
//			token.setName("CAC_READ_ONLY");
//			token.setId("54321");
			
			semossUser.setAccessToken(token);
			session.setAttribute(Constants.SESSION_USER, semossUser);
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
