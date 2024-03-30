/*******************************************************************************
' * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.web.conf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.SessionCookieConfig;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IEngine;
import prerna.engine.impl.r.RserveUtil;
import prerna.forms.AbstractFormBuilder;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.frame.py.PySingleton;
import prerna.reactor.frame.r.util.RJavaTranslatorFactory;
import prerna.reactor.scheduler.SchedulerDatabaseUtility;
import prerna.reactor.scheduler.SchedulerFactorySingleton;
import prerna.util.AbstractFileWatcher;
import prerna.util.ChromeDriverUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

public class DBLoader implements ServletContextListener {

	private final Level STARTUP = Level.forName("STARTUP", 0);
	private final Level SHUTDOWN = Level.forName("SHUTDOWN", 0);
	
	private static final Logger logger = LogManager.getLogger(DBLoader.class);
	private static final String RDFMAP = "RDF-MAP";
	private static String SESSION_ID_KEY = "JSESSIONID";
	private static boolean useLogoutPage = false;
	private static String customLogoutUrl = null;

	// keep track of all the watcher threads to kill
	private static List<AbstractFileWatcher> watcherList = new ArrayList<>();

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		ServletContext context = arg0.getServletContext();
		String contextPath = context.getContextPath();
		{
			//TODO: now putting this in RDFMap.prop
			//TODO: now putting this in RDFMap.prop
			// remove below and update code to pull there 
			// using Constants.CONTEXT_PATH_KEY
			
			// need to set the path
			// important for taking the image with security
			ChromeDriverUtility.setContextPath(contextPath);
		}
		
		String rdfPropFile = context.getInitParameter(RDFMAP);

		// see if only admins can set an engine as public
		String adminSetPublicOnly = context.getInitParameter(Constants.ADMIN_SET_PUBLIC);
		if (adminSetPublicOnly == null) {
			adminSetPublicOnly = "false";
		}
		context.setInitParameter(Constants.ADMIN_SET_PUBLIC, adminSetPublicOnly);

		// see if admin can determine who can publish
		String adminSetPublisher = context.getInitParameter(Constants.ADMIN_SET_PUBLISHER);
		if (adminSetPublisher == null) {
			adminSetPublisher = "false";
		}
		context.setInitParameter(Constants.ADMIN_SET_PUBLISHER, adminSetPublisher);
		
		// see if admin can determine who can export
		String adminSetExporter = context.getInitParameter(Constants.ADMIN_SET_EXPORTER);
		if (adminSetExporter == null) {
			adminSetExporter = "false";
		}
		context.setInitParameter(Constants.ADMIN_SET_EXPORTER, adminSetExporter);

		// see if we allow anonymous users
		String anonymousUsersEnabled = context.getInitParameter(Constants.ANONYMOUS_USER_ALLOWED);
		if (anonymousUsersEnabled == null) {
			anonymousUsersEnabled = "false";
		}
		context.setInitParameter(Constants.ANONYMOUS_USER_ALLOWED, anonymousUsersEnabled);
		// see if anonymous users can upload data
		String anonymousUsersUploadData = context.getInitParameter(Constants.ANONYMOUS_USER_UPLOAD_DATA);
		if (anonymousUsersUploadData == null) {
			anonymousUsersUploadData = "false";
		}
		context.setInitParameter(Constants.ANONYMOUS_USER_UPLOAD_DATA, anonymousUsersUploadData);

		// see if we redirect to logout page or back to login screen
		String useLogoutPage = context.getInitParameter(Constants.USE_LOGOUT_PAGE);
		if (useLogoutPage == null) {
			useLogoutPage = "false";
		}
		context.setInitParameter(Constants.USE_LOGOUT_PAGE, useLogoutPage);
		DBLoader.useLogoutPage = Boolean.parseBoolean(useLogoutPage);

		// see if we redirect to logout page or back to login screen
		String customLogoutUrl = context.getInitParameter(Constants.CUSTOM_LOGOUT_URL);
		if (customLogoutUrl != null && !customLogoutUrl.trim().isEmpty()) {
			String trimmedUrl = customLogoutUrl.trim();
			context.setInitParameter(Constants.CUSTOM_LOGOUT_URL, trimmedUrl);
			DBLoader.customLogoutUrl = trimmedUrl;
		}
		
		// get the session id key
		if (context.getSessionCookieConfig() != null) {
			SessionCookieConfig cookieConfig = context.getSessionCookieConfig();
			if (cookieConfig != null && cookieConfig.getName() != null) {
				DBLoader.SESSION_ID_KEY = cookieConfig.getName();
				ChromeDriverUtility.setSessionCookie(cookieConfig.getName());
			}
		}

		logger.log(STARTUP, "Initializing application context..." + Utility.cleanLogString(contextPath));

		// Set default file separator system variable
		logger.log(STARTUP, "Changing file separator value to: '/'");
		System.setProperty("file.separator", "/");

		// Load RDF_Map.prop file
		logger.log(STARTUP, "Loading RDF_Map.prop: " + Utility.cleanLogString(rdfPropFile));
		DIHelper.getInstance().loadCoreProp(rdfPropFile);

		if(RserveUtil.R_KILL_ON_STARTUP) {
			logger.log(STARTUP, "Killing existing RServes running on the machine");
			try {
				RserveUtil.endR();
			} catch (Exception e) {
				logger.log(STARTUP, "Unable to kill existing RServes running on the machine");
			}
		}
		
		// set security enabled within DIHelper first
		// this is because security database, on init, will
		// load it as a boolean instead of us searching within DIHelper
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLIC, adminSetPublicOnly);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLISHER, adminSetPublisher);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_EXPORTER, adminSetExporter);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_ALLOWED, anonymousUsersEnabled);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_UPLOAD_DATA, anonymousUsersUploadData);
		DIHelper.getInstance().setLocalProperty(Constants.USE_LOGOUT_PAGE, useLogoutPage);
		DIHelper.getInstance().setLocalProperty(Constants.SESSION_ID_KEY, SESSION_ID_KEY);
		DIHelper.getInstance().setLocalProperty(Constants.CONTEXT_PATH_KEY, contextPath);

		// Load empty engine list into DIHelper, then load engines from db folder
		logger.log(STARTUP, "Loading engines...");
		String engines = "";
		DIHelper.getInstance().setEngineProperty(Constants.ENGINES, engines);
		loadSmss(Constants.ENGINE_WEB_WATCHER);
		String projects = "";
		DIHelper.getInstance().setProjectProperty(Constants.PROJECTS, projects);
		loadSmss(Constants.PROJECT_WATCHER);
		
		// if there was an issue starting up the server
		// we should do it here so that we can redirect the user
		{
			IDatabaseEngine localmaster = (IDatabaseEngine) DIHelper.getInstance().getEngineProperty(Constants.LOCAL_MASTER_DB);
			IDatabaseEngine security = (IDatabaseEngine) DIHelper.getInstance().getEngineProperty(Constants.SECURITY_DB);
			IDatabaseEngine scheduler = (IDatabaseEngine) DIHelper.getInstance().getEngineProperty(Constants.SCHEDULER_DB);
			IDatabaseEngine userTracking = (IDatabaseEngine) DIHelper.getInstance().getEngineProperty(Constants.USER_TRACKING_DB);
			if (localmaster == null || security == null || !localmaster.isConnected() || !security.isConnected()
					|| (!Utility.schedulerForceDisable() && scheduler != null && !scheduler.isConnected())
					|| (Utility.isUserTrackingEnabled() && (userTracking == null || !userTracking.isConnected() ))
					) {
				// you have messed up!!!
				StartUpSuccessFilter.setStartUpSuccess(false);
			}
			
			// Load and run triggerOnLoad jobs
			if(!Utility.schedulerForceDisable() && scheduler != null) {
				try {
					SchedulerDatabaseUtility.executeAllTriggerOnLoads();
				} catch(Exception e) {
					// ignore
				}
			}
		}
	}

	private void loadSmss(String pathKey) {
		String pathValue = DIHelper.getInstance().getProperty(pathKey);
		if(pathValue == null || pathValue.trim().isEmpty()) {
			throw new NullPointerException("Error occurred - could not find " + pathKey + " in RDF_Map.prop which is required for starting the application");
		}
		
		StringTokenizer watchers = new StringTokenizer(pathValue, ";");
		try {
			while (watchers.hasMoreElements()) {
				String watcher = watchers.nextToken();
				if(watcher != null && !(watcher=watcher.trim()).isEmpty()) {
					String watcherClass = DIHelper.getInstance().getProperty(watcher);
					String folder = DIHelper.getInstance().getProperty(watcher + "_DIR");
					String ext = DIHelper.getInstance().getProperty(watcher + "_EXT");
					String engineType = DIHelper.getInstance().getProperty(watcher + "_ETYPE");
					AbstractFileWatcher watcherInstance = (AbstractFileWatcher) Class.forName(watcherClass).getConstructor(null).newInstance(null);
					watcherInstance.setFolderToWatch(folder);
					watcherInstance.setExtension(ext);
					if( engineType != null && !(engineType=engineType.trim()).isEmpty() ) {
						watcherInstance.setEngineType(IEngine.CATALOG_TYPE.valueOf(engineType));
					}
					watcherInstance.init();
					Thread thread = new Thread(watcherInstance);
					thread.start();
					watcherList.add(watcherInstance);
				}
			}
		} catch (Exception ex) {
			logger.log(STARTUP, Constants.STACKTRACE, ex);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		logger.log(SHUTDOWN, "Start shutdown");

		Set<String> insights = new HashSet<>(InsightStore.getInstance().getAllInsights());
		for (String id : insights) {
			Insight in = InsightStore.getInstance().get(id);
			logger.log(SHUTDOWN, "Closing insight " + id);
			InsightUtility.dropInsight(in);
		}

		// close watchers
		for (AbstractFileWatcher watcher : watcherList) {
			watcher.shutdown();
		}

		// we need to close all the engine ids
		List<String> eIds = MasterDatabaseUtility.getAllDatabaseIds();
		for (String id : eIds) {
			// grab only loaded engines
			IDatabaseEngine engine = (IDatabaseEngine) DIHelper.getInstance().getEngineProperty(id);
			if (engine != null) {
				// if it is loaded, close it
				logger.log(SHUTDOWN, "Closing database " + id);
				try {
					engine.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}

		String[] autoLoadedDbs = new String[] {
				AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME,
				Constants.SECURITY_DB,
				Constants.USER_TRACKING_DB,
				Constants.LOCAL_MASTER_DB,
		};
		
		for(String db : autoLoadedDbs) {
			IDatabaseEngine engine = Utility.getDatabase(db, false);
			if (engine != null) {
				logger.log(SHUTDOWN, "Closing database " + db);
				try {
					engine.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			} else {
				logger.log(SHUTDOWN, "Couldn't find database " + db);
			}
		}

		if(SchedulerFactorySingleton.isInit()) {
			logger.log(SHUTDOWN, "Closing scheduler");
			SchedulerFactorySingleton.getInstance().shutdownScheduler(true);
			IDatabaseEngine engine = Utility.getDatabase(Constants.SCHEDULER_DB, false);
			if (engine != null) {
				logger.log(SHUTDOWN, "Closing database " + Constants.SCHEDULER_DB);
				try {
					engine.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			} else {
				logger.log(SHUTDOWN, "Couldn't find database " + Constants.SCHEDULER_DB);
			}
		}
		
		// close r
		try {
			RJavaTranslatorFactory.stopRConnection();
			PySingleton.stopPy();
		} catch (Exception e) {
			logger.log(SHUTDOWN, Constants.STACKTRACE, e);
		}

		logger.log(SHUTDOWN, "Finished shutdown");
	}

	/**
	 * Get the user defined session id key
	 * 
	 * @return
	 */
	public static String getSessionIdKey() {
		return DBLoader.SESSION_ID_KEY;
	}

	/**
	 * Get if we should redirect to a dedicated logout page Or back to the login
	 * page
	 * 
	 * @return
	 */
	public static boolean useLogoutPage() {
		return DBLoader.useLogoutPage;
	}

	public static String getCustomLogoutUrl() {
		return DBLoader.customLogoutUrl;
	}
}
