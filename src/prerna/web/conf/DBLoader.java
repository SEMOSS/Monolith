package prerna.web.conf;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import com.ibm.icu.util.StringTokenizer;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.SesameJenaSelectStatement;
import prerna.rdf.engine.impl.SesameJenaSelectWrapper;
import prerna.util.AbstractFileWatcher;
import prerna.util.Constants;
import prerna.util.DIHelper;

public class DBLoader implements ServletContextListener {

	public static final String RDFMAP = "RDF-MAP";
	String dbFolder = null;
	String rdfPropFile = null;
	public Object monitor = new Object();
	
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		

	}

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		// this would load the DBs from the folders
		// get the RDF Map prop
		String workingDir = System.getProperty("user.dir");

		rdfPropFile = arg0.getServletContext().getInitParameter(RDFMAP);
		DIHelper.getInstance().loadCoreProp(rdfPropFile);
		
		Logger logger = Logger.getLogger(prerna.web.conf.DBLoader.class);
		//Object monitor = new Object(); // stupid object for being a monitor		
		//logger.setLevel(Level.INFO);
		PropertyConfigurator.configure(DIHelper.getInstance().getProperty("LOG4J"));
		dbFolder = DIHelper.getInstance().getProperty("BaseFolder");
		
		// get the engine name
		//String engines = DIHelper.getInstance().getProperty(Constants.ENGINES);
		String engines = "";
		
		DIHelper.getInstance().setLocalProperty(Constants.ENGINES, engines);

		loadEngines();
		
		
		System.out.println("Initializing the context ");
	}
	
	public void loadEngines()
	{
		String watcherStr = DIHelper.getInstance().getProperty(Constants.ENGINE_WEB_WATCHER);
		StringTokenizer watchers = new StringTokenizer(watcherStr, ";");
		try
		{		
			while(watchers.hasMoreElements())
			{
				String watcher = watchers.nextToken();
				String watcherClass = DIHelper.getInstance().getProperty(watcher);
				String folder = DIHelper.getInstance().getProperty(watcher + "_DIR");
				String ext = DIHelper.getInstance().getProperty(watcher + "_EXT");
				AbstractFileWatcher watcherInstance = (AbstractFileWatcher)Class.forName(watcherClass).getConstructor(null).newInstance(null);
				watcherInstance.setMonitor(monitor);
				watcherInstance.setFolderToWatch(folder);
				watcherInstance.setExtension(ext);
				synchronized(monitor)
				{
					watcherInstance.loadFirst();
					// test this engine
//					IEngine engine = (IEngine)DIHelper.getInstance().getLocalProp("NOAA3");
//					System.err.println("Engine is " + engine);
//					testQuery(engine);
					Thread thread = new Thread(watcherInstance);
					thread.start();
				}
			}
		}catch (Exception ex)
		{
			ex.printStackTrace();
		}
		// get this into a synchronized block
		// so this guy will wait
		// I do this so that I can get reference to the engine when I need it
		/*synchronized(monitor)
		{
			watcherStr = DIHelper.getInstance().getProperty(Constants.ENGINE_WEB_WATCHER);
			if(watcherStr != null )
			{
				watchers = new StringTokenizer(watcherStr, ";");
				while(watchers.hasMoreElements())
				{
					String watcher = watchers.nextToken();
					String watcherClass = DIHelper.getInstance().getProperty(watcher);
					String folder = DIHelper.getInstance().getProperty(watcher + "_DIR");
					String ext = DIHelper.getInstance().getProperty(watcher + "_EXT");
					String engineName = DIHelper.getInstance().getProperty(watcher+"_ENGINE");
					try
					{
						AbstractFileWatcher watcherInstance = (AbstractFileWatcher)Class.forName(watcherClass).getConstructor(null).newInstance(null);
						// engines should be loaded by now
						// hopefully :D
						if(engineName != null && DIHelper.getInstance().getLocalProp(engineName) != null)
						{
							IEngine engine = (IEngine)DIHelper.getInstance().getLocalProp(engineName);
							watcherInstance.setEngine(engine);
						}
						watcherInstance.setMonitor(monitor);
						watcherInstance.setFolderToWatch(folder);
						watcherInstance.setExtension(ext);
						watcherInstance.loadFirst();
						Thread thread = new Thread(watcherInstance);
						thread.start();
					}catch(Exception ex)
					{
						// ok dont do anything the file was not there
					}
				}
			}
		}*/

	}
	
	public void testQuery(IEngine engine)
	{
		SesameJenaSelectWrapper sjsw = new SesameJenaSelectWrapper();
		sjsw.setEngine(engine);
		sjsw.setQuery("Select ?a ?b ?c where {?a ?b ?c} limit 2");
		sjsw.executeQuery();
		sjsw.getVariables();
		
		while(sjsw.hasNext())
		{
			SesameJenaSelectStatement stmt = sjsw.next();
			System.out.println(stmt.getPropHash());
		}
	}

}
