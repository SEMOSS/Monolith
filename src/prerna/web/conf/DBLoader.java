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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.PropertyConfigurator;
import org.quartz.SchedulerException;

import com.ibm.icu.util.StringTokenizer;

import prerna.engine.api.IEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.rpa.quartz.SchedulerUtil;
import prerna.sablecc2.reactor.frame.r.util.RJavaTranslatorFactory;
import prerna.sablecc2.reactor.utils.ImageCaptureReactor;
import prerna.util.AbstractFileWatcher;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

public class DBLoader implements ServletContextListener {

	private static final String RDFMAP = "RDF-MAP";
	
	// keep track of all the watcher threads to kill 
	private static List<AbstractFileWatcher> watcherList = new Vector<AbstractFileWatcher>();
	
	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		ServletContext context = arg0.getServletContext();
		String contextPath = context.getContextPath();
		
		String rdfPropFile = context.getInitParameter(RDFMAP);
		String securityEnabled = context.getInitParameter(Constants.SECURITY_ENABLED);
		String adminSetPublicOnly = context.getInitParameter(Constants.ADMIN_SET_PUBLIC);
		if(adminSetPublicOnly == null) {
			adminSetPublicOnly = "false";
		}
		context.setInitParameter(Constants.ADMIN_SET_PUBLIC, adminSetPublicOnly);

		System.out.println("Initializing application context..." + contextPath);
		
		//Set default file separator system variable
		System.out.println("Changing file separator value to: '/'");
		System.setProperty("file.separator", "/");
		
		//Load RDF_Map.prop file
		System.out.println("Loading RDF_Map.prop: " + rdfPropFile);
		DIHelper.getInstance().loadCoreProp(rdfPropFile);
		
		//Set log4j prop
		String log4jConfig = DIHelper.getInstance().getProperty("LOG4J");
		System.out.println("Setting log4j property: " + log4jConfig);
		PropertyConfigurator.configure(log4jConfig);

		// set security enabled within DIHelper first
		// this is because security database, on init, will
		// load it as a boolean instead of us searching within DIHelper
		DIHelper.getInstance().setLocalProperty(Constants.SECURITY_ENABLED, securityEnabled);
		DIHelper.getInstance().setLocalProperty(Constants.ADMIN_SET_PUBLIC, adminSetPublicOnly);

		//Load empty engine list into DIHelper, then load engines from db folder
		System.out.println("Loading engines...");
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
		ImageCaptureReactor.setContextPath(contextPath);
		
		// if there was an issue starting up the server
		// we should do it here so that we can redirect the user
		{
			IEngine localmaster = (IEngine) DIHelper.getInstance().getLocalProp(Constants.LOCAL_MASTER_DB_NAME);
			IEngine security = (IEngine) DIHelper.getInstance().getLocalProp(Constants.SECURITY_DB);
			if(localmaster == null || security == null || !localmaster.isConnected() || !security.isConnected()) {
				// you have messed up!!!
				StartUpSuccessFilter.setStartUpSuccess(false);
			}
		}
	}
		
	public void loadEngines() {
		String watcherStr = DIHelper.getInstance().getProperty(Constants.ENGINE_WEB_WATCHER);
		StringTokenizer watchers = new StringTokenizer(watcherStr, ";");
		try {		
			while(watchers.hasMoreElements()) {
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
				synchronized(monitor)
				{
					Thread thread = new Thread(watcherInstance);
					thread.start();
					watcherList.add(watcherInstance);
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		System.out.println("Start shutdown");

		Set<String> insights = new HashSet<String>(InsightStore.getInstance().getAllInsights());
		for(String id : insights) {
			Insight in = InsightStore.getInstance().get(id);
			System.out.println("Closing insight " + id);
			InsightUtility.dropInsight(in);
		}
		
		// close watchers
		for(AbstractFileWatcher watcher : watcherList) {
			watcher.shutdown();
		}
		
		// we need to close all the engine ids
		List<String> eIds = MasterDatabaseUtility.getAllEngineIds();
		for(String id : eIds) {
			// grab only loaded engines
			IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(id);
			if(engine != null) {
				// if it is loaded, close it
				System.out.println("Closing database " + id);
				engine.closeDB();
			}
		}
		
		// these are not loaded in the normal fashion
		// so specifically pull them to close
		IEngine engine = Utility.getEngine(AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
		if(engine != null) {
			System.out.println("Closing database " + AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
			engine.closeDB();
		} else {
			System.out.println("Couldn't find database " + AbstractFormBuilder.FORM_BUILDER_ENGINE_NAME);
		}
		
		engine = Utility.getEngine(Constants.SECURITY_DB);
		if(engine != null) {
			System.out.println("Closing database " + Constants.SECURITY_DB);
			engine.closeDB();
		} else {
			System.out.println("Couldn't find database " + Constants.SECURITY_DB);
		}
		
		engine = Utility.getEngine(Constants.LOCAL_MASTER_DB_NAME);
		if(engine != null) {
			System.out.println("Closing database " + Constants.LOCAL_MASTER_DB_NAME);
			engine.closeDB();
		} else {
			System.out.println("Couldn't find database " + Constants.LOCAL_MASTER_DB_NAME);
		}
		
		// close scheduler
		try {
			System.out.println("Closing scheduler");
			SchedulerUtil.shutdownScheduler(true);
		} catch (SchedulerException e) {
			e.printStackTrace();
		}
		
		// close r
		try {
			RJavaTranslatorFactory.stopRConnection();
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		System.out.println("Finished shutdown");
	}
}
