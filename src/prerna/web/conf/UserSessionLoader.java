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
import prerna.ds.py.PyTranslator;
import prerna.ds.py.PyUtils;
import prerna.engine.impl.r.IRUserConnection;
import prerna.om.ClientProcessWrapper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.Constants;
import prerna.util.MountHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

@WebListener
public class UserSessionLoader implements HttpSessionListener {

	public static final String IS_USER_LOGOUT = "IS_USER_LOGOUT";
	
	private static final Logger classLogger = LogManager.getLogger(UserSessionLoader.class);
	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public void sessionCreated(HttpSessionEvent sessionEvent) {

	}

	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		HttpSession session = sessionEvent.getSession();
		String sessionId = session.getId();
		
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if(thisUser == null) {
			// no need to log a new session that is auto dropped
			// this just keeps writing to the log
			if(!session.isNew()) {
				classLogger.info(sessionId + " >>> Unknown user ending session");
			}
		} else {
			boolean isUserLogout = Boolean.parseBoolean(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT)+"");
			if(isUserLogout) {
				classLogger.info(sessionId + " >>> User " + User.getSingleLogginName(thisUser) + " has logged out to end session");
			} else {
				classLogger.info(sessionId + " >>> User " + User.getSingleLogginName(thisUser) + " is ending session from non-logout event");
			}
			// remove the user memory
			thisUser.removeUserMemory();
		}
		// back up the workspace and asset apps
		try {
			SyncUserAppsThread.execute(session);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}

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
				classLogger.info(sessionId + " >>> Trying to drop insight " + insightId);
				try {
					InsightUtility.dropInsight(insight);
					classLogger.info(sessionId + " >>> Dropped insight " + insightId);
				} catch(Exception e) {
					classLogger.error(Constants.STACKTRACE, e);
				}
			}
			classLogger.info(sessionId + " >>> Successfully removed insight information from session");

			// clear the current session store
			insightIDs.removeAll(copy);
		}

		try {
			String sessionStorage = Utility.getInsightCacheDir() + DIR_SEPARATOR + sessionId;
			ICache.deleteFolder(sessionStorage);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		
		// drop the r thread if not netty
		try {
			if (thisUser != null) {
				IRUserConnection rserve = thisUser.getRcon();
				if (rserve != null && !rserve.isStopped()) {
					classLogger.info(sessionId + " >>> Dropping user r serve");
					ExecutorService executor = Executors.newSingleThreadExecutor();
					try {
						executor.submit(new Callable<Void>() {
							@Override
							public Void call() throws Exception {
								try {
									rserve.stopR();
									classLogger.info(sessionId + " >>> Successfully dropped user r serve");
								} catch (Exception e) {
									classLogger.warn(sessionId + " >>> Unable to drop user r serve");
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
			classLogger.error(Constants.STACKTRACE, e);
		}

		try {
			// stop python if not netty
			if (PyUtils.pyEnabled()) {
				if(thisUser != null) {
					PyTranslator pyt = thisUser.getPyTranslator(false);
					if (pyt instanceof prerna.ds.py.PyTranslator) {
						PyUtils.getInstance().killPyThread(pyt.getPy());
					}
				}
			}
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		
		try {
			// stop the netty thread if used for either r or python
			if(thisUser != null) {
				ClientProcessWrapper cpw = thisUser.getClientProcessWrapper();
				if(cpw != null) {
					cpw.shutdown(true);
				}
			}
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		
		//remove the mounts if chroot enabled
		try {
			if (Boolean.parseBoolean(Utility.getDIHelperProperty(Constants.CHROOT_ENABLE))) {
				if(thisUser != null) {
					MountHelper mh = thisUser.getUserMountHelper();
					if(mh != null) {
						mh.unmountTargetProc();
					}
				}
			}
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		
		// register the successful logout
		UserTrackingUtils.registerLogout(sessionId);
	}

}