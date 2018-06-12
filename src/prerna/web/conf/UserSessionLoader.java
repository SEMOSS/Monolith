package prerna.web.conf;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

//import prerna.auth.User;
//import prerna.auth.User.LOGIN_TYPES;
import prerna.auth.UserPermissionsMasterDB;
import prerna.ds.h2.H2Frame;
import prerna.ds.r.RDataTable;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc.PKQLRunner;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.util.Constants;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	public void sessionCreated(HttpSessionEvent sessionEvent) {
//		sessionEvent.getSession().setAttribute(Constants.SESSION_USER, new User(Constants.ANONYMOUS_USER_ID, "Anonymous", LOGIN_TYPES.ANONYMOUS, "Anonymous"));
	}
	 
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		String sessionID = sessionEvent.getSession().getId();
		
		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionID);
		if(insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for(String id : copy) {
				Insight in = inStore.get(id);
				if(in == null) {
					continue;
				}
				// adding logic
				// if insight is read only for user -1
				// we will not remove it from memory
				// and just keep it for other people to look at
				// the output call will recognize these insights
				// and just set those for the user on output call
				
				boolean isReadOnlyInsight = false;
				String inEngine = in.getEngineName();
				String inRdbmsId = in.getRdbmsId();
				
//				if(inEngine != null && inRdbmsId != null) {
//					HttpSession session = sessionEvent.getSession();
//					User user = ((User) session.getAttribute(Constants.SESSION_USER));
//					String userId = "";
//					if(user!= null) {
//						userId = user.getId();
//					}
//					
//					UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
//					isReadOnlyInsight = permissions.isUserReadOnlyInsights(userId, inEngine, inRdbmsId);
//				}
//				
//				if(!isReadOnlyInsight) {
					IDataMaker dm = in.getDataMaker();
					if(dm instanceof H2Frame) {
						H2Frame frame = (H2Frame)dm;
						frame.dropTable();
						if(!frame.isInMem()) {
							frame.dropOnDiskTemporalSchema();
						}
					} else if(dm instanceof RDataTable) {
						RDataTable frame = (RDataTable)dm;
						frame.closeConnection();
					} 
//					else if(dm instanceof Dashboard) {
//						Dashboard dashboard = (Dashboard)dm;
//						dashboard.dropDashboard();
//					}
					// also see if other variables in runner that need to be dropped
					PKQLRunner runner = in.getPkqlRunner();
					runner.cleanUp();
					
					inStore.remove(id);
					inStore.removeFromSessionHash(sessionID, id);
//				}
			}
			System.out.println("successfully removed qStore information for session");
		}
	}
}