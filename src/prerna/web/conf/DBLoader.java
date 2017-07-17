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

import java.io.File;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.PropertyConfigurator;

import prerna.util.AbstractFileWatcher;
import prerna.util.Constants;
import prerna.util.DIHelper;

import com.ibm.icu.util.StringTokenizer;

public class DBLoader implements ServletContextListener {

	public static final String RDFMAP = "RDF-MAP";
	public Object monitor = new Object();

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		System.out.println("Initializing application context..." + arg0.getServletContext().getContextPath());
		
		//Set default file separator system variable
		System.out.println("Changing file separator value to: '/'");
		System.setProperty("file.separator", "/");
		
		//Load RDF_Map.prop file
		String rdfPropFile = arg0.getServletContext().getInitParameter(RDFMAP);
		System.out.println("Loading RDF_Map.prop: " + rdfPropFile);
		DIHelper.getInstance().loadCoreProp(rdfPropFile);
		
		//Set log4j prop
		String log4jConfig = DIHelper.getInstance().getProperty("LOG4J");
		System.out.println("Setting log4j property: " + log4jConfig);
		PropertyConfigurator.configure(log4jConfig);
		
		//Set Solr home variable (~SEMOSS_PROJ_HOME/Solr)
		String solrHome = DIHelper.getInstance().getProperty("BaseFolder") + "/" + Constants.SOLR_HOME_DIR;
		if((new File(solrHome)).exists()) {
			System.out.println("Setting Solr home: " + solrHome);
			System.setProperty("solr.solr.home", solrHome);
		}
		
		//Load empty engine list into DIHelper, then load engines from db folder
		System.out.println("Loading engines...");
		String engines = "";
		DIHelper.getInstance().setLocalProperty(Constants.ENGINES, engines);
		loadEngines();
		
		//Set whether or not security is enabled in DIHelper to be used in PKQL processing
		DIHelper.getInstance().setLocalProperty(Constants.SECURITY_ENABLED, arg0.getServletContext().getInitParameter(Constants.SECURITY_ENABLED));
	}
	
	public void loadEngines() {
		String watcherStr = DIHelper.getInstance().getProperty(Constants.ENGINE_WEB_WATCHER);
		StringTokenizer watchers = new StringTokenizer(watcherStr, ";");
		try {		
			while(watchers.hasMoreElements()) {
				String watcher = watchers.nextToken();
				String watcherClass = DIHelper.getInstance().getProperty(watcher);
				String folder = DIHelper.getInstance().getProperty(watcher + "_DIR");
				String ext = DIHelper.getInstance().getProperty(watcher + "_EXT");
				AbstractFileWatcher watcherInstance = (AbstractFileWatcher) Class.forName(watcherClass).getConstructor(null).newInstance(null);
				watcherInstance.setMonitor(monitor);
				watcherInstance.setFolderToWatch(folder);
				watcherInstance.setExtension(ext);
				synchronized(monitor)
				{
					Thread thread = new Thread(watcherInstance);
					thread.start();
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		
	}
}
