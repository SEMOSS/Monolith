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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.SessionCookieConfig;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IEngine;
import prerna.engine.impl.r.RserveUtil;
import prerna.forms.AbstractFormBuilder;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.reactor.frame.py.PySingleton;
import prerna.sablecc2.reactor.frame.r.util.RJavaTranslatorFactory;
import prerna.sablecc2.reactor.scheduler.SchedulerDatabaseUtility;
import prerna.sablecc2.reactor.scheduler.SchedulerFactorySingleton;
import prerna.sablecc2.reactor.scheduler.legacy.JsonConversionToQuartzJob;
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
	private static List<AbstractFileWatcher> watcherList = new Vector<>();

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		ServletContext context = arg0.getServletContext();
		String contextPath = context.getContextPath();

		String rdfPropFile = context.getInitParameter(RDFMAP);
		// see if security is enabled
		String securityEnabled = context.getInitParameter(Constants.SECURITY_ENABLED);

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
		DIHelper.getInstance().setLocalProperty(Constants.SECURITY_ENABLED, securityEnabled);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLIC, adminSetPublicOnly);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLISHER, adminSetPublisher);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_ALLOWED, anonymousUsersEnabled);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_UPLOAD_DATA, anonymousUsersUploadData);
		DIHelper.getInstance().setLocalProperty(Constants.USE_LOGOUT_PAGE, useLogoutPage);
		DIHelper.getInstance().setLocalProperty(Constants.SESSION_ID_KEY, SESSION_ID_KEY);

		// Load empty engine list into DIHelper, then load engines from db folder
		logger.log(STARTUP, "Loading engines...");
		String engines = "";
		DIHelper.getInstance().setLocalProperty(Constants.ENGINES, engines);
		loadEngines();

//		//Just load R right away to avoid synchronization issues
//		try {
//			RJavaTranslatorFactory.initRConnection();
//		} catch(Exception e) {
//			e.printStackTrace();
//		}

		// need to set the path
		// important for taking the image with security
		ChromeDriverUtility.setContextPath(contextPath);

		// if there was an issue starting up the server
		// we should do it here so that we can redirect the user
		{
			IEngine localmaster = (IEngine) DIHelper.getInstance().getLocalProp(Constants.LOCAL_MASTER_DB_NAME);
			IEngine security = (IEngine) DIHelper.getInstance().getLocalProp(Constants.SECURITY_DB);
			// TODO: will make scheduler a requirement
			IEngine scheduler = (IEngine) DIHelper.getInstance().getLocalProp(Constants.SCHEDULER_DB);
//			if (localmaster == null || security == null || scheduler == null || !localmaster.isConnected() || !security.isConnected() || !scheduler.isConnected()) {
			if (localmaster == null || security == null || !localmaster.isConnected() || !security.isConnected() ) {
				// you have messed up!!!
				StartUpSuccessFilter.setStartUpSuccess(false);
			}
			
			// Load and run triggerOnLoad jobs
			if(scheduler != null) {
				try {
					SchedulerDatabaseUtility.executeAllTriggerOnLoads();
					// also add legacy json files
					JsonConversionToQuartzJob.runUpdateFromLegacyFormat();
				} catch(Exception e) {
					// ignore
				}
			}
		}
	}

	public void loadEngines() {
		StringTokenizer watchers = new StringTokenizer(DIHelper.getInstance().getProperty(Constants.ENGINE_WEB_WATCHER), ";");
		try {
			while (watchers.hasMoreElements()) {
				Object monitor = new Object();
				String watcher = watchers.nextToken();
				String watcherClass = DIHelper.getInstance().getProperty(watcher);
				String folder = DIHelper.getInstance().getProperty(watcher + "_DIR");
				String ext = DIHelper.getInstance().getProperty(watcher + "_EXT");
				AbstractFileWatcher watcherInstance = (AbstractFileWatcher) Class.forName(watcherClass).getConstructor(null).newInstance(null);
				watcherInstance.setMonitor(monitor);
				watcherInstance.setFolderToWatch(folder);
				watcherInstance.setExtension(ext);
				watcherInstance.init();
				synchronized (monitor) {
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
		List<String> eIds = MasterDatabaseUtility.getAllEngineIds();
		for (String id : eIds) {
			// grab only loaded engines
			IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(id);
			if (engine != null) {
				// if it is loaded, close it
				logger.log(SHUTDOWN, "Closing database " + id);
				engine.closeDB();
			}
		}

		// these are not loaded in the normal fashion
		// so specifically pull them to close
		IEngine engine = Utility.getEngine(AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
		if (engine != null) {
			logger.log(SHUTDOWN, "Closing database " + AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
			engine.closeDB();
		} else {
			logger.log(SHUTDOWN, "Couldn't find database " + AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
		}

		engine = Utility.getEngine(Constants.SECURITY_DB);
		if (engine != null) {
			logger.log(SHUTDOWN, "Closing database " + Constants.SECURITY_DB);
			engine.closeDB();
		} else {
			logger.log(SHUTDOWN, "Couldn't find database " + Constants.SECURITY_DB);
		}

		engine = Utility.getEngine(Constants.LOCAL_MASTER_DB_NAME);
		if (engine != null) {
			logger.log(SHUTDOWN, "Closing database " + Constants.LOCAL_MASTER_DB_NAME);
			engine.closeDB();
		} else {
			logger.log(SHUTDOWN, "Couldn't find database " + Constants.LOCAL_MASTER_DB_NAME);
		}

		logger.log(SHUTDOWN, "Closing scheduler");
		SchedulerFactorySingleton.getInstance().shutdownScheduler(true);

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
