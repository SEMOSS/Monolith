package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.ibm.icu.util.StringTokenizer;

import prerna.util.Constants;
import prerna.util.DIHelper;

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
		System.out.println("This would add the user DBs to that user");
		// loads the user specific databases and adds database to the users session
		// try to see if this guys session is already loaded
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		boolean dbInitialized = session != null && session.getAttribute("DB") != null;
		System.out.println("Getting into session being null");
		if(!dbInitialized) // this is our new friend
		{
			session = ((HttpServletRequest)arg0).getSession(true);
			// get all the engines and add the top engines
			String engineNames = (String)DIHelper.getInstance().getLocalProp(Constants.ENGINES);
			StringTokenizer tokens = new StringTokenizer(engineNames, ";");
			String engines = "";
			while(tokens.hasMoreTokens())
			{
				// this would do some check to see
				String engineName = tokens.nextToken();
				System.out.println(" >>> " + engineName);
				if(engines.length() == 0)
					engines = engineName;
				else
					engines = engines + ":" + engineName;
				// set this guy into the session of our user
				session.setAttribute(engineName, DIHelper.getInstance().getLocalProp(engineName));
				// and over
			}			
			session.setAttribute("ENGINES", engines);
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
