package prerna.web.conf;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.SyncUserAppsThread;
import prerna.cache.ICache;
import prerna.ds.py.PyExecutorThread;
import prerna.ds.py.PyUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.insight.InsightUtility;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	private static final Logger LOGGER = LogManager.getLogger(UserSessionLoader.class.getName());
	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public void sessionCreated(HttpSessionEvent sessionEvent) {

	}
	
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		HttpSession session = sessionEvent.getSession();
		String sessionId = session.getId();
		
		// back up the workspace and asset apps
		SyncUserAppsThread.execute(session);
		
		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if(insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for(String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if(insight == null) {
					continue;
				}
				LOGGER.info("Trying to drop insight " + insightId);
				InsightUtility.dropInsight(insight);
				LOGGER.info("Dropped insight " + insightId);
			}
			LOGGER.info("successfully removed insight information from session");
			
			// clear the current session store
			insightIDs.removeAll(copy);
		}
		
		String sessionStorage = DIHelper.getInstance().getProperty(Constants.INSIGHT_CACHE_DIR) + DIR_SEPARATOR + sessionId;
		ICache.deleteFolder(sessionStorage);
		
		// now drop the thread
		if(PyUtils.pyEnabled()) {
			PyExecutorThread pyThread = (PyExecutorThread) session.getAttribute(Constants.PYTHON);
			PyUtils.getInstance().killPyThread(pyThread);
		}
	}
	
}