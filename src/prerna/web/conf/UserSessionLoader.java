package prerna.web.conf;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import prerna.auth.User;
import prerna.auth.User.LOGIN_TYPES;
import prerna.ds.TableDataFrameStore;
import prerna.util.Constants;
import prerna.util.QuestionPlaySheetStore;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	public void sessionCreated(HttpSessionEvent sessionEvent) {
//		System.out.println("Session Created: ID="+sessionEvent.getSession().getId());
		sessionEvent.getSession().setAttribute(Constants.SESSION_USER, new User(Constants.ANONYMOUS_USER_ID, "Anonymous", LOGIN_TYPES.anonymous, "Anonymous"));
	}
	 
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
//		System.out.println("Session Destroyed: ID="+sessionEvent.getSession().getId());
//		sessionEvent.getSession().setAttribute(Constants.SESSION_USER, new User("1", "Anonymous", LOGIN_TYPES.anonymous, "Anonymous"));
		String sessionID = sessionEvent.getSession().getId();
		
		// clear up question store
		QuestionPlaySheetStore qStore = QuestionPlaySheetStore.getInstance();
		Set<String> quesitonIDsToDelete = qStore.getPlaySheetIDsForSession(sessionID);
		if(quesitonIDsToDelete != null) {
			Set<String> copy = new HashSet<String>(quesitonIDsToDelete);
			for(String questionID : copy) {
				qStore.remove(questionID);
				qStore.removeFromSessionHash(sessionID, questionID);
			}
			System.out.println("successfully removed qStore information for session");
		}
		
		// clear up ITable store
		TableDataFrameStore tableStore = TableDataFrameStore.getInstance();
		Set<String> tableIDsToDelete = tableStore.getTableIDsForSession(sessionID);
		if(tableIDsToDelete != null) {
			Set<String> copy = new HashSet<String>(tableIDsToDelete);
			for(String tableID : copy) {
				tableStore.remove(tableID);
				tableStore.removeFromSessionHash(sessionID, tableID);
			}
			System.out.println("successfully removed tableStore information for session");
		}
	}
}