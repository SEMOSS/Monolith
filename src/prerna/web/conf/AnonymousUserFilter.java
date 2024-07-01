package prerna.web.conf;

import java.io.IOException;
import java.io.Serializable;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class AnonymousUserFilter implements Filter, Serializable {

	private static final Logger logger = LogManager.getLogger(AnonymousUserFilter.class); 
	private static final long serialVersionUID = -4657347128078864456L;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		if(AbstractSecurityUtils.anonymousUsersEnabled()) {
			HttpSession session = ((HttpServletRequest)arg0).getSession(true);
	
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				user = new User();
				user.setAnonymous(true);
				
				String cookieToFind = DBLoader.getSessionIdKey() + "_unk";
				Cookie[] cookies = ((HttpServletRequest) arg0).getCookies();
				if(cookies != null) {
					// loop through and see if we have a cookie for this user
					for(Cookie c : cookies) {
						if(c.getName().equals(cookieToFind)) {
							String uId = WebUtility.cleanHttpResponse(c.getValue());
							user.setAnonymousId(uId);
							// found the cookie
							// no need to continue loop
							break;
						}
					}
				}
				
				boolean foundPrevoiusCookie = user.getAnonymousId() != null;
				if(!foundPrevoiusCookie) {
					// set a new id + add a cookie
					String uId = "UNK_" + UUID.randomUUID().toString();
					user.setAnonymousId(uId);
					
					Cookie c = new Cookie(cookieToFind, uId);
					// max age of 10years...
					c.setMaxAge(60 * 60 * 24 * 365 * 10);
					c.setPath(((HttpServletRequest) arg0).getContextPath());
					c.setHttpOnly(true);
					c.setSecure(((HttpServletRequest)arg0).isSecure());
					((HttpServletResponse)arg1).addCookie(c);
				}
				// add to session
				session.setAttribute(Constants.SESSION_USER, user);
				session.setAttribute(Constants.SESSION_USER_ID_LOG, user.getAnonymousId());

				// log the user login
				if(foundPrevoiusCookie) {
					logger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is logging in anonymously"));
				} else {
					logger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is logging in anonymously for first time"));
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}
	
	@Override
	public void destroy() {
		// destroy
		
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
		
	}

}
