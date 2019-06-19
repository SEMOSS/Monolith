package prerna.web.conf;

import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

public class SessionCounter implements HttpSessionListener {

	private static AtomicInteger activeSessions = new AtomicInteger(0);

	/* 
	 * Simple listener to count the number of active sessions
	 */
	
	public void sessionCreated(HttpSessionEvent se) {
		activeSessions.incrementAndGet();
	}

	public void sessionDestroyed(HttpSessionEvent se) {
		activeSessions.decrementAndGet();
	}

	public static int getActiveSessions() {
		return activeSessions.get();
	}
}