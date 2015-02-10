package prerna.web.conf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import prerna.rdf.engine.api.IEngine;
import prerna.util.Constants;
import prerna.util.DIHelper;

import com.ibm.icu.util.StringTokenizer;

public class UserDBFilter implements Filter {

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1,
			FilterChain arg2) throws IOException, ServletException {
		// TODO Auto-generated method stub
		// assign specific DBs to a given user based on what has already been loaded
		System.out.println("This would add the user DBs to that user ");
		// loads the user specific databases and adds database to the users session
		// try to see if this guys session is already loaded
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		boolean dbInitialized = session != null && session.getAttribute(Constants.ENGINES+"unused") != null;
		System.out.println("Getting into session being null");
		if(!dbInitialized) // this is our new friend
		{
			session = ((HttpServletRequest)arg0).getSession(true);
			// get all the engines and add the top engines
			String engineNames = (String)DIHelper.getInstance().getLocalProp(Constants.ENGINES);
			StringTokenizer tokens = new StringTokenizer(engineNames, ";");
			ArrayList<Hashtable<String, String>> engines = new ArrayList<Hashtable<String, String>>();
			while(tokens.hasMoreTokens())
			{
				// this would do some check to see
				String engineName = tokens.nextToken();
				System.out.println(" >>> " + engineName);
				IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(engineName);
				boolean hidden = (engine.getProperty(Constants.HIDDEN_DATABASE) != null && Boolean.parseBoolean(engine.getProperty(Constants.HIDDEN_DATABASE)));
				if(!hidden) {
					Hashtable<String, String> engineHash = new Hashtable<String, String> ();
					engineHash.put("name", engineName);
					engineHash.put("type", engine.getEngineType() + "");
					engines.add(engineHash);
				}
				// set this guy into the session of our user
				session.setAttribute(engineName, engine);
				// and over
			}			
			session.setAttribute(Constants.ENGINES, engines);
		}
		arg2.doFilter(arg0, arg1);

	}
	
	private void loadUserDB()
	{
		
	}
	
	private boolean checkValidEngine(String engineName)
	{
		// this will do the check
		return true;
		
		
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}

}
