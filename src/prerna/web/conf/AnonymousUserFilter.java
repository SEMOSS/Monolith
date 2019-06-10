package prerna.web.conf;

import java.io.IOException;
import java.util.UUID;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
				
				String cookieToFind = DBLoader.getSessionIdKey() + "_unk";
				Cookie[] cookies = ((HttpServletRequest) arg0).getCookies();
				if(cookies != null) {
					// loop through and see if we have a cookie for this user
					for(Cookie c : cookies) {
						if(c.getName().equals(cookieToFind)) {
							String uId = c.getValue();
							semossUser.setAnonymousId(uId);
							// found the cookie
							// no need to continue loop
							break;
						}
					}
				}
				
				if(semossUser.getAnonymousId() == null) {
					// set a new id + add a cookie
					String uId = "UNK_" + UUID.randomUUID().toString();
					semossUser.setAnonymousId(uId);
					
					Cookie c = new Cookie(cookieToFind, uId);
					// max age of 10years...
					c.setMaxAge(60 * 60 * 24 * 365 * 10);
					c.setPath(((HttpServletRequest) arg0).getContextPath());
					c.setHttpOnly(true);
					c.setSecure(true);
					((HttpServletResponse)arg1).addCookie(c);
				}
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
