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
import java.util.Hashtable;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.auth.User.LOGIN_TYPES;
import prerna.engine.api.IEngine;
import prerna.util.Constants;
import prerna.util.DIHelper;

import com.ibm.icu.util.StringTokenizer;

public class UserDBFilter implements Filter {
	FilterConfig config;
	UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
	User user = new User(Constants.ANONYMOUS_USER_ID, "Anonymous", LOGIN_TYPES.anonymous, "Anonymous");
	
	@Override
	public void destroy() {
		// TODO Auto-generated method stub
	}

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1,
			FilterChain arg2) throws IOException, ServletException {
		// assign specific DBs to a given user based on what has already been loaded
		// loads the user specific databases and adds database to the users session
		// try to see if this guys session is already loaded
		ServletContext context = getFilterConfig().getServletContext();
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		if(session != null) {
			if(session.getAttribute(Constants.SESSION_USER) == null) {
				session.setAttribute(Constants.SESSION_USER, user);
			} else {
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}
		}
		boolean dbInitialized = session != null && session.getAttribute(Constants.ENGINES+"unused") != null;
		if(!dbInitialized) // this is our new friend
		{
			ArrayList<String> userEngines = new ArrayList<String>();
			session = ((HttpServletRequest)arg0).getSession(true);
			if(securityEnabled) {
				userEngines = permissions.getUserAccessibleEngines(user.getId());
			}
			// get all the engines and add the top engines
			String engineNames = (String)DIHelper.getInstance().getLocalProp(Constants.ENGINES);
			StringTokenizer tokens = new StringTokenizer(engineNames, ";");
			ArrayList<Hashtable<String, String>> engines = new ArrayList<Hashtable<String, String>>();
			while(tokens.hasMoreTokens())
			{
				// this would do some check to see
				String engineName = tokens.nextToken();
				IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(engineName);
				boolean hidden = (engine.getProperty(Constants.HIDDEN_DATABASE) != null && Boolean.parseBoolean(engine.getProperty(Constants.HIDDEN_DATABASE)));
				if(!hidden) {
					if(!securityEnabled || (securityEnabled && userEngines.contains(engineName))) {
						Hashtable<String, String> engineHash = new Hashtable<String, String>();
						engineHash.put("name", engineName);
						engineHash.put("type", engine.getEngineType() + "");
						engines.add(engineHash);
					}
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
	public void init(FilterConfig config) throws ServletException {
		setFilterConfig(config);
	}
	
	public void setFilterConfig(FilterConfig config) {
		this.config = config;
	}

	public FilterConfig getFilterConfig() {
		return config;
	}

}
