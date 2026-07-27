/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
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
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.SessionCookieConfig;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import com.github.f4b6a3.uuid.alt.GUID;

import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IEngine;
import prerna.engine.api.IEngine.CATALOG_TYPE;
import prerna.engine.impl.r.RserveUtil;
import prerna.logging.SemossLogUtils;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.ReactorFactory;
import prerna.reactor.frame.r.util.RJavaTranslatorFactory;
import prerna.reactor.scheduler.SchedulerDatabaseUtility;
import prerna.reactor.scheduler.SchedulerFactorySingleton;
import prerna.util.AbstractFileWatcher;
import prerna.util.ChromeDriverUtility;
import prerna.util.ChrootTemplate;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.SystemEngineRegistry;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

public class DBLoader implements ServletContextListener {

	static {
		// Set headless mode explicitly
		// otherwise TextToGraphic breaks in a container
		System.setProperty("java.awt.headless", "true");

		// Set this for log4j async loggers
		System.setProperty("log4j2.isThreadContextMapInheritable", "true");
	}

	private final Level STARTUP = Level.forName("STARTUP", 0);
	private final Level SHUTDOWN = Level.forName("SHUTDOWN", 0);

	private static final Logger classLogger = LogManager.getLogger(DBLoader.class);
	private static final String RDFMAP = "RDF-MAP";
	private static String SESSION_ID_KEY = "JSESSIONID";
	private static boolean useLogoutPage = false;
	private static String customLogoutUrl = null;

	// keep track of all the watcher threads to kill
	private static List<Thread> watcherList = new ArrayList<>();

	/**
	 * Flipped to true only once {@link #contextInitialized} has run all the way
	 * through a successful startup. The readiness health probe (see
	 * {@link prerna.semoss.web.services.config.HealthResource}) reads this to
	 * report whether the application has finished booting.
	 */
	private static volatile boolean startupComplete = false;

	/**
	 * Flipped to false only when a required-resource check fails during startup.
	 * {@link StartUpSuccessFilter} reads this to redirect users to the failure
	 * page, and the health probe surfaces it. Note this stays true while still
	 * booting, so it is distinct from {@link #startupComplete} (booting = false,
	 * success = true).
	 */
	private static volatile boolean startupSuccess = true;

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		ThreadContext.put(SemossLogUtils.REQUEST_ID, GUID.v7().toUUID().toString());
		ThreadContext.put(SemossLogUtils.USER_ID, "STARTUP");
		ThreadContext.put(SemossLogUtils.USER_TYPE, "SYSTEM");
		ThreadContext.put(SemossLogUtils.SESSION_ID, "STARTUP");
		ThreadContext.put(SemossLogUtils.CLIENT_IP, "STARTUP");

		ServletContext context = arg0.getServletContext();
		String contextPath = context.getContextPath();
		{
			// TODO: now putting this in RDFMap.prop
			// TODO: now putting this in RDFMap.prop
			// remove below and update code to pull there
			// using Constants.CONTEXT_PATH_KEY

			// need to set the path
			// important for taking the image with security
			ChromeDriverUtility.setContextPath(contextPath);
		}

		String rdfPropFile = context.getInitParameter(RDFMAP);

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

		classLogger.log(STARTUP, "Initializing application context... {}", Utility.cleanLogString(contextPath));

		// Set default file separator system variable
		classLogger.log(STARTUP, "Changing file separator value to: '/'");
		System.setProperty("file.separator", "/");

		// Load RDF_Map.prop file
		classLogger.log(STARTUP, "Loading RDF_Map.prop: {}", Utility.cleanLogString(rdfPropFile));
		DIHelper.getInstance().loadCoreProp(rdfPropFile);

		if (RserveUtil.R_KILL_ON_STARTUP) {
			classLogger.log(STARTUP, "Killing existing RServes running on the machine");
			try {
				RserveUtil.endR();
			} catch (Exception e) {
				classLogger.log(STARTUP, "Unable to kill existing RServes running on the machine", e);
			}
		}

		// set security enabled within DIHelper first
		// this is because security database, on init, will
		// load it as a boolean instead of us searching within DIHelper
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLISHER, adminSetPublisher);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_EXPORTER, adminSetExporter);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_ALLOWED, anonymousUsersEnabled);
		DIHelper.getInstance().setLocalProperty(Constants.ANONYMOUS_USER_UPLOAD_DATA, anonymousUsersUploadData);
		DIHelper.getInstance().setLocalProperty(Constants.USE_LOGOUT_PAGE, useLogoutPage);
		DIHelper.getInstance().setLocalProperty(Constants.SESSION_ID_KEY, SESSION_ID_KEY);
		DIHelper.getInstance().setLocalProperty(Constants.CONTEXT_PATH_KEY, contextPath);

		// Load empty engine list into DIHelper, then load engines from db folder
		classLogger.log(STARTUP, "Loading engines...");
		DIHelper.getInstance().setEngineProperty(Constants.ENGINES, "");
		loadSmss(Constants.ENGINE_WEB_WATCHER);
		// if there was an issue starting up the server
		// we should do it here so that we can redirect the user
		{
			// Check localmaster
			if (!SystemEngineRegistry.isLocalMasterDbLoaded()) {
				classLogger.error("STARTUP CHECK FAILED: localmaster is not loaded");
				startupSuccess = false;
			} else {
				classLogger.info("STARTUP CHECK PASSED: localmaster is connected");
			}

			// Check security
			if (!SystemEngineRegistry.isSecurityDbLoaded()) {
				classLogger.error("STARTUP CHECK FAILED: security is not loaded");
				startupSuccess = false;
			} else {
				classLogger.info("STARTUP CHECK PASSED: security is connected");
			}

			// Check scheduler (conditional)
			if (!Utility.schedulerForceDisable()) {
				if (!SystemEngineRegistry.isSchedulerDbLoaded()) {
					classLogger.error("STARTUP CHECK FAILED: scheduler is not loaded (SCHEDULER_FORCE_DISABLE=false)");
					startupSuccess = false;
				} else {
					classLogger.info("STARTUP CHECK PASSED: scheduler is connected");
				}
			} else {
				classLogger.info("STARTUP CHECK SKIPPED: scheduler (SCHEDULER_FORCE_DISABLE=true)");
			}

			// Check userTracking (conditional)
			if (Utility.isUserTrackingEnabled()) {
				if (!SystemEngineRegistry.isUserTrackingDbLoaded()) {
					classLogger.error("STARTUP CHECK FAILED: userTracking is not loaded (USER_TRACKING_ENABLED=true)");
					startupSuccess = false;
				} else {
					classLogger.info("STARTUP CHECK PASSED: userTracking is connected");
				}
			} else {
				classLogger.info("STARTUP CHECK SKIPPED: userTracking (USER_TRACKING_ENABLED=false)");
			}

			// Check auditDb (conditional)
			if (Utility.isAuditLogsDatabaseEnabled()) {
				if (!SystemEngineRegistry.isAuditLogsDbLoaded()) {
					classLogger.error("STARTUP CHECK FAILED: auditDb is not loaded (AUDIT_LOGS_DATABASE_ENABLED=true)");
					startupSuccess = false;
				} else {
					classLogger.info("STARTUP CHECK PASSED: auditDb is connected");
				}
			} else {
				classLogger.info("STARTUP CHECK SKIPPED: auditDb (AUDIT_LOGS_DATABASE_ENABLED=false)");
			}

			// Check auditDb (conditional)
			if (Utility.isModelInferenceLogsEnabled()) {
				if (!SystemEngineRegistry.isModelInferenceLogsDbLoaded()) {
					classLogger.error(
							"STARTUP CHECK FAILED: modelInferenceLogsDb is not loaded (MODEL_INFERENCE_LOGS_ENABLED=true)");
					startupSuccess = false;
				} else {
					classLogger.info("STARTUP CHECK PASSED: modelInferenceLogsDb is connected");
				}
			} else {
				classLogger.info("STARTUP CHECK SKIPPED: modelInferenceLogsDb (MODEL_INFERENCE_LOGS_ENABLED=false)");
			}

			// Check notificationDb (conditional)
			if (Utility.isNotificationDatabaseEnabled()) {
				if (!SystemEngineRegistry.isNotificationDbLoaded()) {
					classLogger.error(
							"STARTUP CHECK FAILED: notificationDb is not loaded (NOTIFICATION_DATABASE_ENABLED=true)");
					startupSuccess = false;
				} else {
					classLogger.info("STARTUP CHECK PASSED: notificationDb is connected");
				}
			} else {
				classLogger.info("STARTUP CHECK SKIPPED: notificationDb (NOTIFICATION_DATABASE_ENABLED=false)");
			}

			if (!startupSuccess) {
				classLogger.error("STARTUP FAILED - See detailed errors above");
				// dont continue trying to load / init
				return;
			}

			classLogger.info("STARTUP SUCCESS - All required components are connected");
		}

		classLogger.log(STARTUP, "Loading projects...");
		DIHelper.getInstance().setProjectProperty(Constants.PROJECTS, "");
		loadSmss(Constants.PROJECT_WATCHER);

		// Load and run triggerOnLoad jobs
		if (!Utility.schedulerForceDisable() && SystemEngineRegistry.isSchedulerDbLoaded()) {
			try {
				SchedulerDatabaseUtility.executeAllTriggerOnLoads();
			} catch (Exception e) {
				classLogger.warn("Failed to execute triggerOnLoad scheduler jobs", e);
			}
		}

		// this will likely need to be broken out into another service in the future
		// but for now start one time thread to pull all the images for the engines
		CATALOG_TYPE[] types = IEngine.CATALOG_TYPE.values();
		for (CATALOG_TYPE eType : types) {
			new Thread() {
				@Override
				public void run() {
					ClusterUtil.pullEngineAndProjectImageFolder(eType);
				}
			}.start();
		}

		// create and wait for chroot template before allowing users to access
		if (Boolean.parseBoolean(Utility.getDIHelperProperty(Constants.CHROOT_ENABLE))) {
			ChrootTemplate.warmAsync();
			ChrootTemplate.awaitReady();
		}

		// warm up the reactors
		ReactorFactory.load();

		// startup ran all the way through - the app is booted and ready to serve.
		// note: a required-resource failure returns early above without setting this,
		// so readiness stays false until (and unless) a full successful startup.
		startupComplete = true;
	}

	private void loadSmss(String pathKey) {
		Map<String, String> parentMDC = ThreadContext.getImmutableContext();

		String pathValue = DIHelper.getInstance().getProperty(pathKey);
		if (pathValue == null || pathValue.trim().isEmpty()) {
			throw new NullPointerException("Error occurred - could not find " + pathKey
					+ " in RDF_Map.prop which is required for starting the application");
		}

		StringTokenizer watchers = new StringTokenizer(pathValue, ";");
		try {
			while (watchers.hasMoreElements()) {
				String watcher = watchers.nextToken();
				if (watcher != null && !(watcher = watcher.trim()).isEmpty()) {
					String watcherClass = Utility.getDIHelperProperty(watcher);
					String folder = Utility.getDIHelperProperty(watcher + "_DIR");
					String ext = Utility.getDIHelperProperty(watcher + "_EXT");
					String engineType = Utility.getDIHelperProperty(watcher + "_ETYPE");
					AbstractFileWatcher watcherInstance = (AbstractFileWatcher) Class.forName(watcherClass)
							.getConstructor().newInstance();
					watcherInstance.setFolderToWatch(folder);
					watcherInstance.setExtension(ext);
					if (engineType != null && !(engineType = engineType.trim()).isEmpty()) {
						watcherInstance.setEngineType(IEngine.CATALOG_TYPE.valueOf(engineType));
					}
					watcherInstance.init();
					// start the watcher thread with MDC
					Thread watcherThread = Thread.ofPlatform().daemon().start(() -> {
						try (var ctx = org.apache.logging.log4j.CloseableThreadContext.putAll(parentMDC)) {
							watcherInstance.run();
						}
					});
					watcherList.add(watcherThread);
				}
			}
		} catch (Exception ex) {
			classLogger.log(STARTUP, "Failed to init and start thread for file watchers", ex);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		ThreadContext.put(SemossLogUtils.REQUEST_ID, GUID.v7().toUUID().toString());
		ThreadContext.put(SemossLogUtils.USER_ID, "SHUTDOWN");
		ThreadContext.put(SemossLogUtils.USER_TYPE, "SYSTEM");
		ThreadContext.put(SemossLogUtils.SESSION_ID, "SHUTDOWN");
		ThreadContext.put(SemossLogUtils.CLIENT_IP, "SHUTDOWN");

		classLogger.log(SHUTDOWN, "Starting application shutdown");

		Set<String> insights = new HashSet<>(InsightStore.getInstance().getAllInsights());
		for (String id : insights) {
			Insight in = InsightStore.getInstance().get(id);
			classLogger.log(SHUTDOWN, "Closing insight {}", id);
			InsightUtility.dropInsight(in);
		}

		// close watchers
		for (Thread watcherThread : watcherList) {
			watcherThread.interrupt();
		}
		for (Thread watcherThread : watcherList) {
			try {
				watcherThread.join(2000); // Wait up to 2 seconds per thread
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		watcherList.clear();

		// we need to close all the engine ids
		List<String> eIds = MasterDatabaseUtility.getAllDatabaseIds();
		for (String id : eIds) {
			// grab only loaded engines
			IEngine engine = (IEngine) DIHelper.getInstance().getEngineProperty(id);
			if (engine != null) {
				// if it is loaded, close it
				classLogger.log(SHUTDOWN, "Closing engine {}", id);
				try {
					engine.close();
				} catch (IOException e) {
					classLogger.error("Unable to close engine {}", engine.getEngineId(), e);
				}
			}
		}

		IDatabaseEngine[] autoLoadedDbs = new IDatabaseEngine[] { SystemEngineRegistry.getSecurityDb(),
				SystemEngineRegistry.getLocalMasterDb(), SystemEngineRegistry.getLocalMasterDb() };
		for (IDatabaseEngine engine : autoLoadedDbs) {
			if (engine != null) {
				classLogger.log(SHUTDOWN, "Closing database {}", engine.getEngineId());
				try {
					engine.close();
				} catch (IOException e) {
					classLogger.error("Unable to close engine {}", engine.getEngineId(), e);
				}
			}
		}

		if (SchedulerFactorySingleton.isInit()) {
			classLogger.log(SHUTDOWN, "Closing scheduler");
			SchedulerFactorySingleton.getInstance().shutdownScheduler(true);
			IDatabaseEngine engine = SystemEngineRegistry.getSchedulerDb();
			if (engine != null) {
				classLogger.log(SHUTDOWN, "Closing database {}", Constants.SCHEDULER_DB);
				try {
					engine.close();
				} catch (IOException e) {
					classLogger.error("Unable to close engine {}", Constants.SCHEDULER_DB, e);
				}
			} else {
				classLogger.warn("Couldn't find database {} during shutdown", Constants.SCHEDULER_DB);
			}
		}

		// close r
		try {
			RJavaTranslatorFactory.stopRConnection();
		} catch (Exception e) {
			classLogger.log(SHUTDOWN, "Error occurred closing R connections", e);
		}

		classLogger.log(SHUTDOWN, "Application shutdown complete");
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

	/**
	 * Get a custom logout url
	 *
	 * @return
	 */
	public static String getCustomLogoutUrl() {
		return DBLoader.customLogoutUrl;
	}

	/**
	 * Whether the startup routine ({@link #contextInitialized}) has run all the way
	 * through a successful boot. Used by the readiness health probe. Returns false
	 * while the application is still starting up, or if startup failed and returned
	 * early on a required-resource error.
	 *
	 * @return true once the application has finished booting successfully
	 */
	public static boolean isStartupComplete() {
		return DBLoader.startupComplete;
	}

	/**
	 * Whether startup completed without a required-resource failure. Read by
	 * {@link StartUpSuccessFilter} to gate the failure redirect, and surfaced by
	 * the health probe. Stays true while the application is still starting up; it
	 * only flips to false once a required-resource check has failed.
	 *
	 * @return false once startup has been marked as failed
	 */
	public static boolean isStartupSuccess() {
		return DBLoader.startupSuccess;
	}
}
