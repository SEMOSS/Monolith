package prerna.web.conf;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

public class NoUserInSessionTrustedTokenListener implements HttpSessionListener {

	@Override
	public void sessionCreated(HttpSessionEvent se) {
		// do nothing
		
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent se) {
		// clean up the session map
		String sessionId = se.getSession().getId();
		NoUserInSessionTrustedTokenFilter.removeSession(sessionId);
	}

}
