package prerna.web.conf;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.SyncUserAppsThread;
import prerna.auth.User;
import prerna.cache.ICache;
import prerna.ds.py.FilePyTranslator;
import prerna.ds.py.PyTranslator;
import prerna.ds.py.PyUtils;
import prerna.engine.impl.r.IRUserConnection;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.insight.InsightUtility;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	private static final Logger logger = LogManager.getLogger(UserSessionLoader.class.getName());
	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public void sessionCreated(HttpSessionEvent sessionEvent) {

	}
	
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		HttpSession session = sessionEvent.getSession();
		String sessionId = session.getId();
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if(thisUser == null) {
			logger.info(sessionId + " >>> Unknown user ending session");
		} else {
			logger.info(sessionId + " >>> User " + User.getSingleLogginName(thisUser) + " ending session");
		}
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
				logger.info(sessionId + " >>> Trying to drop insight " + insightId);
				InsightUtility.dropInsight(insight);
				logger.info(sessionId + " >>> Dropped insight " + insightId);
			}
			logger.info(sessionId + " >>> Successfully removed insight information from session");
			
			// clear the current session store
			insightIDs.removeAll(copy);
		}
		
		String sessionStorage = DIHelper.getInstance().getProperty(Constants.INSIGHT_CACHE_DIR) + DIR_SEPARATOR + sessionId;
		ICache.deleteFolder(sessionStorage);
		
		// drop the r thread
		try {
			if (thisUser != null) {
				IRUserConnection rserve = thisUser.getRcon();
				if (rserve != null) {
					logger.info(sessionId + " >>> Dropping user r serve");
					ExecutorService executor = Executors.newSingleThreadExecutor();
					try {
						executor.submit(new Callable<Void>() {
							@Override
							public Void call() throws Exception {
								try {
									rserve.stopR();
									logger.info(sessionId + " >>> Successfully dropped user r serve");
								} catch (Exception e) {
									logger.warn(sessionId + " >>> Unable to drop user r serve");
								}
								return null;
							}
						});
					} finally {
						executor.shutdown();
					}
				}
			}
		} catch(Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}
		
		// now drop the py thread
		if(PyUtils.pyEnabled()) {
			try {
				PyTranslator pyThread = (PyTranslator) session.getAttribute(Constants.PYTHON);
				if(pyThread != null ) {
					logger.info(sessionId + " >>> Found python thread to drop");
					if(!(pyThread instanceof FilePyTranslator)) {
						if(pyThread.getPy() != null) {
							PyUtils.getInstance().killPyThread(pyThread.getPy());
						}
					} else {
						User user = (User)session.getAttribute(Constants.SESSION_USER);
						if(user != null) {
							PyUtils.getInstance().killTempTupleSpace(user);
						}
					}
					logger.info(sessionId + " >>> Successfully dropped python thread");
				}
			} catch(Exception e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
	}
	
}