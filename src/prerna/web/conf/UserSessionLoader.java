package prerna.web.conf;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import prerna.auth.User;
import prerna.auth.User.LOGIN_TYPES;
import prerna.util.Constants;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	public void sessionCreated(HttpSessionEvent sessionEvent) {
//		System.out.println("Session Created: ID="+sessionEvent.getSession().getId());
		sessionEvent.getSession().setAttribute(Constants.SESSION_USER, new User(Constants.ANONYMOUS_USER_ID, "Anonymous", LOGIN_TYPES.anonymous, "Anonymous"));
	}
	 
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
//		System.out.println("Session Destroyed: ID="+sessionEvent.getSession().getId());
//		sessionEvent.getSession().setAttribute(Constants.SESSION_USER, new User("1", "Anonymous", LOGIN_TYPES.anonymous, "Anonymous"));
	}
}